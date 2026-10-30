//! Tracker announces over HTTP, HTTPS (BEP 3/23, certificates always verified) and UDP (BEP 15).

use super::bencode;
use super::metainfo::Id20;
use super::net::Policy;
use super::wire::{parse_compact_v4, parse_compact_v6};
use rustls::pki_types::{pem::PemObject, CertificateDer, ServerName};
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::{TcpStream, UdpSocket};
use tokio::time::timeout;

const TIMEOUT: Duration = Duration::from_secs(15);
const MAX_RESPONSE: usize = 1 << 20;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Event {
    None,
    Started,
    Stopped,
    Completed,
}

#[derive(Clone, Debug)]
pub struct Announce {
    pub info_hash: Id20,
    pub peer_id: Id20,
    pub port: u16,
    pub uploaded: i64,
    pub downloaded: i64,
    pub left: i64,
    pub event: Event,
    pub numwant: i32,
    pub key: u32,
}

#[derive(Clone, Debug, Default)]
pub struct Response {
    pub peers: Vec<SocketAddr>,
    pub interval: Option<Duration>,
}

/// Trust roots for HTTPS trackers: the app's PEM file, or the bundled Mozilla roots when none is given.
pub fn tls_config(ca_file: &str) -> Result<Arc<rustls::ClientConfig>, String> {
    let mut roots = rustls::RootCertStore::empty();
    if ca_file.is_empty() {
        roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    } else {
        let certs = CertificateDer::pem_file_iter(ca_file).map_err(|e| format!("Trust roots {ca_file}: {e}"))?;
        for cert in certs {
            let cert = cert.map_err(|e| format!("Trust roots {ca_file}: {e}"))?;
            // A root the TLS library can't parse is skipped, as OpenSSL does.
            let _ = roots.add(cert);
        }
        if roots.is_empty() {
            return Err(format!("No usable trust roots in {ca_file}"));
        }
    }
    let config = rustls::ClientConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
        .with_safe_default_protocol_versions()
        .map_err(|e| e.to_string())?
        .with_root_certificates(roots)
        .with_no_client_auth();
    Ok(Arc::new(config))
}

pub struct Client {
    pub tls: Arc<rustls::ClientConfig>,
    pub user_agent: String,
    pub policy: Policy,
}

fn url_encode(bytes: &[u8]) -> String {
    let mut out = String::new();
    for &b in bytes {
        if b.is_ascii_alphanumeric() || b"-_.~".contains(&b) {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

struct Url<'a> {
    scheme: &'a str,
    host: &'a str,
    port: u16,
    path: &'a str,
}

fn parse_url(url: &str) -> Result<Url<'_>, String> {
    let (scheme, rest) = url.split_once("://").ok_or("Invalid tracker address")?;
    let (authority, path) = match rest.find('/') {
        Some(i) => (&rest[..i], &rest[i..]),
        None => (rest, "/"),
    };
    let default_port = match scheme {
        "http" => 80,
        "https" => 443,
        "udp" => 0,
        _ => return Err(format!("Unsupported tracker scheme {scheme}")),
    };
    let (host, port) = match authority.rsplit_once(':') {
        Some((h, p)) if !p.contains(']') => (h, p.parse().map_err(|_| "Invalid tracker port")?),
        _ => (authority, default_port),
    };
    let host = host.trim_start_matches('[').trim_end_matches(']');
    if host.is_empty() || port == 0 {
        return Err("Invalid tracker address".into());
    }
    Ok(Url { scheme, host, port, path })
}

impl Client {
    async fn resolve(&self, host: &str, port: u16) -> Result<Vec<SocketAddr>, String> {
        let found: Vec<SocketAddr> = timeout(TIMEOUT, tokio::net::lookup_host((host, port)))
            .await
            .map_err(|_| format!("Resolving {host} timed out"))?
            .map_err(|e| format!("Resolving {host}: {e}"))?
            .collect();
        let allowed: Vec<SocketAddr> = found.into_iter().filter(|a| self.policy.allows(a)).collect();
        if allowed.is_empty() {
            return Err(format!("{host} has no allowed address"));
        }
        Ok(allowed)
    }

    pub async fn announce(&self, url: &str, a: &Announce) -> Result<Response, String> {
        let u = parse_url(url)?;
        if u.scheme == "udp" {
            return self.announce_udp(&u, a).await;
        }
        let event = match a.event {
            Event::Started => "&event=started",
            Event::Stopped => "&event=stopped",
            Event::Completed => "&event=completed",
            Event::None => "",
        };
        let query = format!(
            "info_hash={}&peer_id={}&port={}&uploaded={}&downloaded={}&left={}&compact=1&numwant={}&key={:08x}{}",
            url_encode(&a.info_hash),
            url_encode(&a.peer_id),
            a.port,
            a.uploaded,
            a.downloaded,
            a.left,
            a.numwant,
            a.key,
            event
        );
        let mut target = format!("{url}{}{query}", if u.path.contains('?') { '&' } else { '?' });
        for _ in 0..4 {
            match self.get(&target).await? {
                Fetched::Body(body) => return parse_http_response(&body),
                Fetched::Redirect(location) => target = location,
            }
        }
        Err("Too many tracker redirects".into())
    }

    async fn get(&self, url: &str) -> Result<Fetched, String> {
        let u = parse_url(url)?;
        let addresses = self.resolve(u.host, u.port).await?;
        let mut last = String::new();
        for address in addresses {
            let stream = match timeout(TIMEOUT, TcpStream::connect(address)).await {
                Ok(Ok(s)) => s,
                Ok(Err(e)) => {
                    last = format!("{}: {e}", u.host);
                    continue;
                }
                Err(_) => {
                    last = format!("{}: connection timed out", u.host);
                    continue;
                }
            };
            let request = format!(
                "GET {} HTTP/1.1\r\nHost: {}\r\nUser-Agent: {}\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n",
                u.path, u.host, self.user_agent
            );
            let response = if u.scheme == "https" {
                let name = ServerName::try_from(u.host.to_owned()).map_err(|_| format!("Invalid host {}", u.host))?;
                let connector = tokio_rustls::TlsConnector::from(self.tls.clone());
                let tls = timeout(TIMEOUT, connector.connect(name, stream))
                    .await
                    .map_err(|_| format!("{}: TLS handshake timed out", u.host))?
                    .map_err(|e| format!("{}: {e}", u.host))?;
                exchange(tls, request.as_bytes()).await
            } else {
                exchange(stream, request.as_bytes()).await
            };
            return parse_http(&response.map_err(|e| format!("{}: {e}", u.host))?, url);
        }
        Err(last)
    }

    async fn announce_udp(&self, u: &Url<'_>, a: &Announce) -> Result<Response, String> {
        let addresses = self.resolve(u.host, u.port).await?;
        let address = addresses[0];
        let bind: SocketAddr = if address.is_ipv4() { "0.0.0.0:0".parse().unwrap() } else { "[::]:0".parse().unwrap() };
        let socket = UdpSocket::bind(bind).await.map_err(|e| e.to_string())?;
        socket.connect(address).await.map_err(|e| e.to_string())?;
        let mut random = [0u8; 8];
        let _ = getrandom::fill(&mut random);
        let mut txid = u32::from_be_bytes(random[..4].try_into().unwrap());
        let mut buf = vec![0u8; 2048];

        let mut connect = Vec::with_capacity(16);
        connect.extend_from_slice(&0x417_2710_1980u64.to_be_bytes());
        connect.extend_from_slice(&0u32.to_be_bytes());
        connect.extend_from_slice(&txid.to_be_bytes());
        let reply = udp_exchange(&socket, &connect, &mut buf, txid, address).await?;
        if reply.len() < 16 || reply[..4] != 0u32.to_be_bytes() {
            return Err("Invalid UDP tracker reply".into());
        }
        let connection: [u8; 8] = reply[8..16].try_into().unwrap();
        txid = txid.wrapping_add(1);
        let event = match a.event {
            Event::None => 0u32,
            Event::Completed => 1,
            Event::Started => 2,
            Event::Stopped => 3,
        };
        let mut announce = Vec::with_capacity(98);
        announce.extend_from_slice(&connection);
        announce.extend_from_slice(&1u32.to_be_bytes());
        announce.extend_from_slice(&txid.to_be_bytes());
        announce.extend_from_slice(&a.info_hash);
        announce.extend_from_slice(&a.peer_id);
        announce.extend_from_slice(&a.downloaded.to_be_bytes());
        announce.extend_from_slice(&a.left.to_be_bytes());
        announce.extend_from_slice(&a.uploaded.to_be_bytes());
        announce.extend_from_slice(&event.to_be_bytes());
        announce.extend_from_slice(&0u32.to_be_bytes());
        announce.extend_from_slice(&a.key.to_be_bytes());
        announce.extend_from_slice(&a.numwant.to_be_bytes());
        announce.extend_from_slice(&a.port.to_be_bytes());
        let reply = udp_exchange(&socket, &announce, &mut buf, txid, address).await?;
        if reply.len() < 20 || reply[..4] != 1u32.to_be_bytes() {
            return Err("Invalid UDP tracker announce reply".into());
        }
        let interval = u32::from_be_bytes(reply[8..12].try_into().unwrap());
        let peers = if address.is_ipv4() { parse_compact_v4(&reply[20..]) } else { parse_compact_v6(&reply[20..]) };
        Ok(Response {
            peers: peers.into_iter().filter(|p| self.policy.allows(p)).collect(),
            interval: Some(Duration::from_secs(u64::from(interval.clamp(60, 3600)))),
        })
    }
}

async fn udp_exchange(socket: &UdpSocket, payload: &[u8], buf: &mut [u8], txid: u32, address: SocketAddr) -> Result<Vec<u8>, String> {
    for attempt in 0..3u32 {
        socket.send(payload).await.map_err(|e| e.to_string())?;
        let deadline = tokio::time::Instant::now() + Duration::from_secs(3 << attempt);
        loop {
            match tokio::time::timeout_at(deadline, socket.recv(buf)).await {
                Err(_) => break,
                Ok(Err(e)) => return Err(e.to_string()),
                Ok(Ok(n)) if n >= 8 && buf[4..8] == txid.to_be_bytes() => {
                    if buf[..4] == 3u32.to_be_bytes() {
                        return Err(format!("Tracker: {}", String::from_utf8_lossy(&buf[8..n])));
                    }
                    return Ok(buf[..n].to_vec());
                }
                Ok(Ok(_)) => {}
            }
        }
    }
    Err(format!("{address}: no response"))
}

enum Fetched {
    Body(Vec<u8>),
    Redirect(String),
}

async fn exchange<S: AsyncRead + AsyncWrite + Unpin>(mut stream: S, request: &[u8]) -> std::io::Result<Vec<u8>> {
    timeout(TIMEOUT, async {
        stream.write_all(request).await?;
        let mut out = Vec::new();
        let mut chunk = [0u8; 8192];
        loop {
            let n = match stream.read(&mut chunk).await {
                Ok(n) => n,
                // Servers often close without a TLS close_notify; the body length still decides completeness.
                Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof && !out.is_empty() => 0,
                Err(e) => return Err(e),
            };
            if n == 0 {
                return Ok(out);
            }
            out.extend_from_slice(&chunk[..n]);
            if out.len() > MAX_RESPONSE {
                return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "Tracker response too large"));
            }
        }
    })
    .await
    .map_err(|_| std::io::Error::new(std::io::ErrorKind::TimedOut, "Tracker timed out"))?
}

fn parse_http(response: &[u8], url: &str) -> Result<Fetched, String> {
    let end = response.windows(4).position(|w| w == b"\r\n\r\n").ok_or("Invalid HTTP response")?;
    let head = std::str::from_utf8(&response[..end]).map_err(|_| "Invalid HTTP response")?;
    let mut lines = head.split("\r\n");
    let status: u16 = lines
        .next()
        .and_then(|l| l.split_whitespace().nth(1))
        .and_then(|s| s.parse().ok())
        .ok_or("Invalid HTTP status")?;
    let headers: Vec<(String, &str)> =
        lines.filter_map(|l| l.split_once(':')).map(|(k, v)| (k.trim().to_ascii_lowercase(), v.trim())).collect();
    let header = |name: &str| headers.iter().find(|(k, _)| k == name).map(|(_, v)| *v);
    if (300..400).contains(&status) {
        let location = header("location").ok_or("Redirect without a location")?;
        let target = if location.contains("://") {
            location.to_owned()
        } else {
            let u = parse_url(url)?;
            format!("{}://{}:{}{}", u.scheme, u.host, u.port, location)
        };
        return Ok(Fetched::Redirect(target));
    }
    if status != 200 {
        return Err(format!("Tracker HTTP status {status}"));
    }
    let mut body = response[end + 4..].to_vec();
    if header("transfer-encoding").is_some_and(|v| v.eq_ignore_ascii_case("chunked")) {
        body = dechunk(&body).ok_or("Invalid chunked response")?;
    } else if let Some(len) = header("content-length").and_then(|v| v.parse::<usize>().ok()) {
        if body.len() < len {
            return Err("Truncated tracker response".into());
        }
        body.truncate(len);
    }
    Ok(Fetched::Body(body))
}

fn dechunk(mut data: &[u8]) -> Option<Vec<u8>> {
    let mut out = Vec::new();
    loop {
        let line_end = data.windows(2).position(|w| w == b"\r\n")?;
        let size_text = std::str::from_utf8(&data[..line_end]).ok()?;
        let size = usize::from_str_radix(size_text.split(';').next()?.trim(), 16).ok()?;
        data = &data[line_end + 2..];
        if size == 0 {
            return Some(out);
        }
        out.extend_from_slice(data.get(..size)?);
        data = data.get(size + 2..)?;
    }
}

fn parse_http_response(body: &[u8]) -> Result<Response, String> {
    let v = bencode::decode(body).map_err(|e| format!("Invalid tracker response: {e}"))?;
    if let Some(reason) = v.get_str("failure reason") {
        return Err(format!("Tracker: {reason}"));
    }
    let mut peers = Vec::new();
    match v.get("peers") {
        Some(bencode::Value::Bytes(b)) => peers.extend(parse_compact_v4(b)),
        Some(bencode::Value::List(list)) => {
            for p in list {
                let (Some(ip), Some(port)) = (p.get_str("ip"), p.get_int("port")) else { continue };
                if let (Ok(ip), Ok(port)) = (ip.parse(), u16::try_from(port)) {
                    peers.push(SocketAddr::new(ip, port));
                }
            }
        }
        _ => {}
    }
    if let Some(b) = v.get_bytes("peers6") {
        peers.extend(parse_compact_v6(b));
    }
    let interval = v.get_int("interval").map(|s| Duration::from_secs(s.clamp(60, 3600) as u64));
    Ok(Response { peers, interval })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn urls_and_http_responses_parse() {
        let u = parse_url("https://t.example:8443/announce?x=1").unwrap();
        assert_eq!((u.scheme, u.host, u.port, u.path), ("https", "t.example", 8443, "/announce?x=1"));
        assert_eq!(parse_url("udp://[::1]:6969").unwrap().host, "::1");
        assert!(parse_url("udp://t.example/announce").is_err());
        assert_eq!(url_encode(&[0x12, b'a', 0xff]), "%12a%FF");

        let body = b"d8:intervali900e5:peers6:\x7f\x00\x00\x01\x1a\xe1e";
        let raw = [b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n".as_slice(), b"10\r\n", &body[..16], b"\r\n", b"10\r\n", &body[16..], b"\r\n0\r\n\r\n"].concat();
        let Fetched::Body(b) = parse_http(&raw, "http://t/a").unwrap() else { panic!() };
        let r = parse_http_response(&b).unwrap();
        assert_eq!(r.peers, vec!["127.0.0.1:6881".parse().unwrap()]);
        assert_eq!(r.interval, Some(Duration::from_secs(900)));
        assert_eq!(parse_http_response(b"d14:failure reason4:nopee").unwrap_err(), "Tracker: nope");
    }

    #[tokio::test]
    async fn https_trackers_refuse_untrusted_certificates() {
        // A loopback listener that answers TLS with an unknown certificate is refused before any request is sent;
        // here nothing answers TLS at all, so the handshake fails rather than falling back to plain HTTP.
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        tokio::spawn(async move {
            while let Ok((mut s, _)) = listener.accept().await {
                let _ = s.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n").await;
            }
        });
        let client = Client { tls: tls_config("").unwrap(), user_agent: "Stream".into(), policy: Policy::default() };
        let a = Announce {
            info_hash: [1; 20],
            peer_id: [2; 20],
            port: 1,
            uploaded: 0,
            downloaded: 0,
            left: 0,
            event: Event::Started,
            numwant: 10,
            key: 0,
        };
        assert!(client.announce(&format!("https://localhost:{port}/announce"), &a).await.is_err());
    }
}
