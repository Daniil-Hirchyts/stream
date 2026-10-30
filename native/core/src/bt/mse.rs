//! Message stream encryption (MSE/PE): the obfuscated handshake many BitTorrent clients offer and some require.
//! A 768-bit Diffie-Hellman exchange derives RC4 keys; the torrent's info hash is only ever sent hashed. After the
//! handshake the connection is RC4-encrypted or, when both sides allow it and the responder picks it, plaintext.
//!
//! This obfuscates traffic; it is not a security layer. Piece hashes still verify every byte.

use super::metainfo::{sha1, Id20};
use num_bigint::BigUint;
use std::io;
use std::pin::Pin;
use std::sync::OnceLock;
use std::task::{Context, Poll};
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, ReadBuf};

const PRIME_HEX: &str = "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A63A36210000000000090563";
const KEY_BYTES: usize = 96;
const MAX_PAD: usize = 512;
const VC: [u8; 8] = [0; 8];
pub const CRYPTO_PLAINTEXT: u32 = 1;
pub const CRYPTO_RC4: u32 = 2;
const TIMEOUT: Duration = Duration::from_secs(10);

/// Whether connections use MSE: never, when the peer allows it (outgoing tries it first, incoming accepts both),
/// or always (incoming plaintext is refused).
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum Policy {
    Disabled,
    #[default]
    Enabled,
    Forced,
}

fn prime() -> &'static BigUint {
    static P: OnceLock<BigUint> = OnceLock::new();
    P.get_or_init(|| BigUint::parse_bytes(PRIME_HEX.as_bytes(), 16).unwrap())
}

fn invalid(message: &str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, message.to_owned())
}

fn random_bytes(n: usize) -> Vec<u8> {
    let mut b = vec![0u8; n];
    let _ = getrandom::fill(&mut b);
    b
}

fn padded(n: &BigUint) -> [u8; KEY_BYTES] {
    let bytes = n.to_bytes_be();
    let mut out = [0u8; KEY_BYTES];
    out[KEY_BYTES - bytes.len()..].copy_from_slice(&bytes);
    out
}

struct KeyPair {
    private: BigUint,
    public: [u8; KEY_BYTES],
}

impl KeyPair {
    fn new() -> Self {
        let private = BigUint::from_bytes_be(&random_bytes(20));
        let public = padded(&BigUint::from(2u32).modpow(&private, prime()));
        KeyPair { private, public }
    }
    /// The shared secret S, or an error for a degenerate public key.
    fn secret(&self, remote: &[u8]) -> io::Result<[u8; KEY_BYTES]> {
        let y = BigUint::from_bytes_be(remote);
        if y <= BigUint::from(1u32) || &y >= prime() {
            return Err(invalid("Invalid MSE public key"));
        }
        Ok(padded(&y.modpow(&self.private, prime())))
    }
}

fn hash(parts: &[&[u8]]) -> Id20 {
    sha1(&parts.concat())
}

/// RC4, with the first 1024 bytes of keystream discarded as MSE requires.
#[derive(Clone)]
pub struct Rc4 {
    s: [u8; 256],
    i: u8,
    j: u8,
}

impl Rc4 {
    pub fn new(key: &[u8]) -> Self {
        let mut s = [0u8; 256];
        for (i, v) in s.iter_mut().enumerate() {
            *v = i as u8;
        }
        let mut j: u8 = 0;
        for i in 0..256 {
            j = j.wrapping_add(s[i]).wrapping_add(key[i % key.len()]);
            s.swap(i, j as usize);
        }
        let mut rc4 = Rc4 { s, i: 0, j: 0 };
        rc4.apply(&mut [0u8; 1024]);
        rc4
    }
    pub fn apply(&mut self, data: &mut [u8]) {
        for b in data {
            self.i = self.i.wrapping_add(1);
            self.j = self.j.wrapping_add(self.s[self.i as usize]);
            self.s.swap(self.i as usize, self.j as usize);
            let k = self.s[(self.s[self.i as usize].wrapping_add(self.s[self.j as usize])) as usize];
            *b ^= k;
        }
    }
}

/// A connection after the MSE handshake: bytes already received and decrypted come first, then the stream, each
/// direction RC4-encrypted when that was selected.
pub struct Stream<S> {
    inner: S,
    prefix: Vec<u8>,
    prefix_at: usize,
    decrypt: Option<Rc4>,
    encrypt: Option<Rc4>,
    pending: Vec<u8>,
}

impl<S> Stream<S> {
    pub fn plain(inner: S, prefix: Vec<u8>) -> Self {
        Stream { inner, prefix, prefix_at: 0, decrypt: None, encrypt: None, pending: Vec::new() }
    }
    pub fn encrypted(&self) -> bool {
        self.encrypt.is_some()
    }
}

impl<S: AsyncRead + Unpin> AsyncRead for Stream<S> {
    fn poll_read(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        let this = &mut *self;
        if this.prefix_at < this.prefix.len() {
            let n = (this.prefix.len() - this.prefix_at).min(buf.remaining());
            buf.put_slice(&this.prefix[this.prefix_at..this.prefix_at + n]);
            this.prefix_at += n;
            if this.prefix_at == this.prefix.len() {
                this.prefix = Vec::new();
                this.prefix_at = 0;
            }
            return Poll::Ready(Ok(()));
        }
        let before = buf.filled().len();
        let r = Pin::new(&mut this.inner).poll_read(cx, buf);
        if let (Poll::Ready(Ok(())), Some(rc4)) = (&r, this.decrypt.as_mut()) {
            rc4.apply(&mut buf.filled_mut()[before..]);
        }
        r
    }
}

impl<S: AsyncWrite + Unpin> Stream<S> {
    fn poll_pending(&mut self, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        while !self.pending.is_empty() {
            match Pin::new(&mut self.inner).poll_write(cx, &self.pending) {
                Poll::Ready(Ok(0)) => return Poll::Ready(Err(io::ErrorKind::WriteZero.into())),
                Poll::Ready(Ok(n)) => {
                    self.pending.drain(..n);
                }
                Poll::Ready(Err(e)) => return Poll::Ready(Err(e)),
                Poll::Pending => return Poll::Pending,
            }
        }
        Poll::Ready(Ok(()))
    }
}

impl<S: AsyncWrite + Unpin> AsyncWrite for Stream<S> {
    fn poll_write(mut self: Pin<&mut Self>, cx: &mut Context<'_>, data: &[u8]) -> Poll<io::Result<usize>> {
        let this = &mut *self;
        if this.encrypt.is_none() {
            return Pin::new(&mut this.inner).poll_write(cx, data);
        }
        // Encrypted bytes must reach the peer in order: earlier ones go out before new data is accepted.
        match this.poll_pending(cx) {
            Poll::Ready(Ok(())) => {}
            Poll::Ready(Err(e)) => return Poll::Ready(Err(e)),
            Poll::Pending => return Poll::Pending,
        }
        let take = data.len().min(64 * 1024);
        this.pending.extend_from_slice(&data[..take]);
        let start = this.pending.len() - take;
        this.encrypt.as_mut().unwrap().apply(&mut this.pending[start..]);
        // Start sending; whatever is left goes out on the next write or flush.
        let _ = this.poll_pending(cx);
        Poll::Ready(Ok(take))
    }
    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        let this = &mut *self;
        match this.poll_pending(cx) {
            Poll::Ready(Ok(())) => Pin::new(&mut this.inner).poll_flush(cx),
            other => other,
        }
    }
    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        let this = &mut *self;
        match this.poll_pending(cx) {
            Poll::Ready(Ok(())) => Pin::new(&mut this.inner).poll_shutdown(cx),
            other => other,
        }
    }
}

/// Reads until `pattern` appears within `limit` bytes; returns the bytes after it.
async fn sync_to<S: AsyncRead + Unpin>(s: &mut S, pattern: &[u8], limit: usize) -> io::Result<Vec<u8>> {
    let mut seen = Vec::with_capacity(limit + pattern.len());
    let mut byte = [0u8; 1];
    while seen.len() < limit + pattern.len() {
        s.read_exact(&mut byte).await?;
        seen.push(byte[0]);
        if seen.ends_with(pattern) {
            return Ok(Vec::new());
        }
    }
    Err(invalid("MSE synchronisation pattern not found"))
}

/// The initiator's side. `skey` is the torrent's 20-byte info hash; `handshake` (the BitTorrent handshake) is sent
/// encrypted as the initial payload. `allow_plain` offers plaintext after the handshake as well as RC4.
pub async fn initiate<S: AsyncRead + AsyncWrite + Unpin>(
    mut s: S,
    skey: &Id20,
    handshake: &[u8],
    allow_plain: bool,
) -> io::Result<Stream<S>> {
    tokio::time::timeout(TIMEOUT, async move {
        let keys = KeyPair::new();
        let pad_a = random_bytes((random_bytes(2)[0] as usize) % 64);
        s.write_all(&[&keys.public[..], &pad_a].concat()).await?;
        let mut remote = [0u8; KEY_BYTES];
        s.read_exact(&mut remote).await?;
        let secret = keys.secret(&remote)?;
        let mut encrypt = Rc4::new(&hash(&[b"keyA", &secret, skey]));
        let mut decrypt = Rc4::new(&hash(&[b"keyB", &secret, skey]));

        let req2 = hash(&[b"req2", skey]);
        let req3 = hash(&[b"req3", &secret]);
        let obfuscated: Vec<u8> = req2.iter().zip(req3.iter()).map(|(a, b)| a ^ b).collect();
        let provide = CRYPTO_RC4 | if allow_plain { CRYPTO_PLAINTEXT } else { 0 };
        let mut body = Vec::new();
        body.extend_from_slice(&VC);
        body.extend_from_slice(&provide.to_be_bytes());
        body.extend_from_slice(&0u16.to_be_bytes());
        body.extend_from_slice(&(handshake.len() as u16).to_be_bytes());
        body.extend_from_slice(handshake);
        encrypt.apply(&mut body);
        s.write_all(&[&hash(&[b"req1", &secret])[..], &obfuscated, &body].concat()).await?;

        // The responder's VC is the first 8 bytes of its keystream, after up to 512 bytes of padding.
        let mut expected_vc = VC;
        decrypt.clone().apply(&mut expected_vc);
        sync_to(&mut s, &expected_vc, MAX_PAD).await?;
        decrypt.apply(&mut [0u8; 8]);
        let mut select = [0u8; 6];
        s.read_exact(&mut select).await?;
        decrypt.apply(&mut select);
        let chosen = u32::from_be_bytes(select[..4].try_into().unwrap());
        let pad = u16::from_be_bytes([select[4], select[5]]) as usize;
        if pad > MAX_PAD {
            return Err(invalid("MSE padding too long"));
        }
        let mut pad_d = vec![0u8; pad];
        s.read_exact(&mut pad_d).await?;
        decrypt.apply(&mut pad_d);
        match chosen {
            CRYPTO_RC4 => Ok(Stream { inner: s, prefix: Vec::new(), prefix_at: 0, decrypt: Some(decrypt), encrypt: Some(encrypt), pending: Vec::new() }),
            CRYPTO_PLAINTEXT if allow_plain => Ok(Stream::plain(s, Vec::new())),
            _ => Err(invalid("The peer selected an unsupported MSE mode")),
        }
    })
    .await
    .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "MSE handshake timed out"))?
}

/// The responder's side, after the first bytes showed the peer isn't sending a plaintext handshake. `first` holds
/// those bytes. `find` maps the hashed info hash (`HASH('req2', SKEY)`) to a torrent's 20-byte hash. Returns the
/// stream, positioned at the peer's BitTorrent handshake, and the torrent's hash.
pub async fn respond<S: AsyncRead + AsyncWrite + Unpin>(
    mut s: S,
    first: &[u8],
    find: impl Fn(&Id20) -> Option<Id20>,
    allow_plain: bool,
) -> io::Result<(Stream<S>, Id20)> {
    tokio::time::timeout(TIMEOUT, async move {
        let mut remote = [0u8; KEY_BYTES];
        let have = first.len().min(KEY_BYTES);
        remote[..have].copy_from_slice(&first[..have]);
        s.read_exact(&mut remote[have..]).await?;
        let keys = KeyPair::new();
        let secret = keys.secret(&remote)?;
        let pad_b = random_bytes((random_bytes(2)[0] as usize) % 64);
        s.write_all(&[&keys.public[..], &pad_b].concat()).await?;

        sync_to(&mut s, &hash(&[b"req1", &secret]), MAX_PAD).await?;
        let mut obfuscated = [0u8; 20];
        s.read_exact(&mut obfuscated).await?;
        let req3 = hash(&[b"req3", &secret]);
        let req2: Id20 = std::array::from_fn(|i| obfuscated[i] ^ req3[i]);
        let skey = find(&req2).ok_or_else(|| invalid("MSE handshake for an unknown torrent"))?;
        let mut decrypt = Rc4::new(&hash(&[b"keyA", &secret, &skey]));
        let mut encrypt = Rc4::new(&hash(&[b"keyB", &secret, &skey]));

        let mut head = [0u8; 14];
        s.read_exact(&mut head).await?;
        decrypt.apply(&mut head);
        if head[..8] != VC {
            return Err(invalid("Invalid MSE verification constant"));
        }
        let provide = u32::from_be_bytes(head[8..12].try_into().unwrap());
        let pad = u16::from_be_bytes([head[12], head[13]]) as usize;
        if pad > MAX_PAD {
            return Err(invalid("MSE padding too long"));
        }
        let mut pad_c = vec![0u8; pad + 2];
        s.read_exact(&mut pad_c).await?;
        decrypt.apply(&mut pad_c);
        let ia_len = u16::from_be_bytes([pad_c[pad], pad_c[pad + 1]]) as usize;
        if ia_len > 68 {
            return Err(invalid("MSE initial payload too long"));
        }
        let mut ia = vec![0u8; ia_len];
        s.read_exact(&mut ia).await?;
        decrypt.apply(&mut ia);

        // Plaintext when both allow it, as libtorrent prefers by default; otherwise RC4.
        let chosen = if allow_plain && provide & CRYPTO_PLAINTEXT != 0 {
            CRYPTO_PLAINTEXT
        } else if provide & CRYPTO_RC4 != 0 {
            CRYPTO_RC4
        } else {
            return Err(invalid("No common MSE mode"));
        };
        let mut reply = Vec::new();
        reply.extend_from_slice(&VC);
        reply.extend_from_slice(&chosen.to_be_bytes());
        reply.extend_from_slice(&0u16.to_be_bytes());
        encrypt.apply(&mut reply);
        s.write_all(&reply).await?;
        let stream = if chosen == CRYPTO_RC4 {
            Stream { inner: s, prefix: ia, prefix_at: 0, decrypt: Some(decrypt), encrypt: Some(encrypt), pending: Vec::new() }
        } else {
            Stream::plain(s, ia)
        };
        Ok((stream, skey))
    })
    .await
    .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "MSE handshake timed out"))?
}

/// `HASH('req2', SKEY)`: how an MSE initiator names a torrent.
pub fn obfuscated_id(id: &Id20) -> Id20 {
    hash(&[b"req2", id])
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rc4_matches_a_known_keystream() {
        // RFC 6229, key 0x0102030405, keystream at offset 1024 (MSE discards the first 1024 bytes).
        let mut rc4 = Rc4::new(&[1, 2, 3, 4, 5]);
        let mut out = [0u8; 16];
        rc4.apply(&mut out);
        assert_eq!(out, [0x30, 0xab, 0xbc, 0xc7, 0xc2, 0x0b, 0x01, 0x60, 0x9f, 0x23, 0xee, 0x2d, 0x5f, 0x6b, 0xb7, 0xdf]);
    }

    async fn pair(initiator_plain: bool, responder_plain: bool) -> (bool, Vec<u8>, Vec<u8>) {
        let (a, b) = tokio::io::duplex(1 << 16);
        let skey = [9u8; 20];
        let handshake = vec![19u8; 68];
        let server = tokio::spawn(async move {
            let mut b = b;
            let mut first = [0u8; 20];
            b.read_exact(&mut first).await.unwrap();
            let (mut stream, found) =
                respond(b, &first, |h| (*h == obfuscated_id(&skey)).then_some(skey), responder_plain).await.unwrap();
            assert_eq!(found, skey);
            let mut hs = vec![0u8; 68];
            stream.read_exact(&mut hs).await.unwrap();
            stream.write_all(b"reply from the responder").await.unwrap();
            stream.flush().await.unwrap();
            let mut more = vec![0u8; 5];
            stream.read_exact(&mut more).await.unwrap();
            (hs, more)
        });
        let mut stream = initiate(a, &skey, &handshake, initiator_plain).await.unwrap();
        let encrypted = stream.encrypted();
        let mut reply = vec![0u8; 24];
        stream.read_exact(&mut reply).await.unwrap();
        stream.write_all(b"hello").await.unwrap();
        stream.flush().await.unwrap();
        let (hs, more) = server.await.unwrap();
        assert_eq!(hs, handshake);
        assert_eq!(&reply, b"reply from the responder");
        (encrypted, hs, more)
    }

    #[tokio::test]
    async fn handshakes_negotiate_rc4_or_plaintext_and_carry_data_both_ways() {
        assert!(!pair(true, true).await.0, "both allow plaintext: the responder picks it");
        assert!(pair(false, true).await.0, "the initiator requires RC4");
        let (encrypted, _, more) = pair(true, false).await;
        assert!(encrypted, "the responder requires RC4");
        assert_eq!(more, b"hello");
    }
}
