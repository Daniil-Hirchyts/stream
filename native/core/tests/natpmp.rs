//! NAT-PMP port mapping against a fake gateway on loopback: the session maps its listen port for TCP and uTP,
//! announces the external port it was granted, renews the lease, and deletes both mappings when it is dropped.

use std::net::{SocketAddrV4, UdpSocket};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use stream_core::bt::session::{Session, SessionConfig};

/// One request the gateway received: opcode, internal port, lifetime.
type Request = (u8, u16, u32);

struct Gateway {
    address: SocketAddrV4,
    requests: Arc<Mutex<Vec<Request>>>,
}

impl Gateway {
    /// Grants `lifetime` seconds (whatever was asked, other than a deletion) at `internal + offset`, or refuses with
    /// `refuse` as the result code.
    fn start(lifetime: u32, offset: u16, refuse: u16) -> Gateway {
        let socket = UdpSocket::bind("127.0.0.1:0").unwrap();
        socket.set_read_timeout(Some(Duration::from_millis(100))).unwrap();
        let address = match socket.local_addr().unwrap() {
            std::net::SocketAddr::V4(a) => a,
            _ => unreachable!(),
        };
        let requests = Arc::new(Mutex::new(Vec::new()));
        let seen = Arc::downgrade(&requests);
        std::thread::spawn(move || {
            let mut buf = [0u8; 64];
            while let Some(seen) = seen.upgrade() {
                let Ok((n, from)) = socket.recv_from(&mut buf) else { continue };
                if n != 12 || buf[0] != 0 {
                    continue;
                }
                let op = buf[1];
                let internal = u16::from_be_bytes([buf[4], buf[5]]);
                let asked = u32::from_be_bytes([buf[8], buf[9], buf[10], buf[11]]);
                seen.lock().unwrap().push((op, internal, asked));
                let mut reply = [0u8; 16];
                reply[1] = 128 + op;
                reply[2..4].copy_from_slice(&refuse.to_be_bytes());
                reply[8..10].copy_from_slice(&internal.to_be_bytes());
                let (external, granted) = if asked == 0 { (0, 0) } else { (internal + offset, lifetime) };
                reply[10..12].copy_from_slice(&external.to_be_bytes());
                reply[12..16].copy_from_slice(&granted.to_be_bytes());
                let _ = socket.send_to(&reply, from);
            }
        });
        Gateway { address, requests }
    }

    fn count(&self, matches: impl Fn(&Request) -> bool) -> usize {
        self.requests.lock().unwrap().iter().filter(|r| matches(r)).count()
    }
}

fn session(gateway: Option<SocketAddrV4>) -> Session {
    Session::new(
        SessionConfig { listen: "127.0.0.1:0".into(), natpmp_gateway: gateway, log: true, ..SessionConfig::default() },
        None,
    )
    .unwrap()
}

fn wait(what: &str, timeout: Duration, ready: impl Fn() -> bool) {
    let deadline = Instant::now() + timeout;
    while !ready() {
        assert!(Instant::now() < deadline, "timed out: {what}");
        std::thread::sleep(Duration::from_millis(20));
    }
}

#[test]
fn maps_renews_announces_and_deletes() {
    // A four-second lease is renewed after two.
    let gateway = Gateway::start(4, 1000, 0);
    let s = session(Some(gateway.address));
    let port = s.port();
    assert_ne!(port, 0);
    wait("TCP and UDP mapped", Duration::from_secs(3), || {
        gateway.count(|r| r.0 == 2 && r.1 == port && r.2 > 0) >= 1 && gateway.count(|r| r.0 == 1 && r.1 == port && r.2 > 0) >= 1
    });
    wait("external port announced", Duration::from_secs(1), || s.announce_port() == port + 1000);
    assert_eq!(s.port(), port, "the listen port itself is unchanged");
    wait("lease renewed", Duration::from_secs(5), || gateway.count(|r| r.0 == 2 && r.2 > 0) >= 2);
    assert_eq!(gateway.count(|r| r.2 == 0), 0, "nothing deleted while running");
    drop(s);
    assert_eq!(gateway.count(|r| r.0 == 2 && r.2 == 0), 1, "TCP mapping deleted on shutdown");
    assert_eq!(gateway.count(|r| r.0 == 1 && r.2 == 0), 1, "UDP mapping deleted on shutdown");
}

#[test]
fn a_refusing_gateway_maps_nothing_and_deletes_nothing() {
    let gateway = Gateway::start(600, 0, 2);
    let s = session(Some(gateway.address));
    let port = s.port();
    wait("both requests answered", Duration::from_secs(3), || gateway.count(|r| r.2 > 0) >= 2);
    assert_eq!(s.announce_port(), port);
    let before = gateway.requests.lock().unwrap().len();
    drop(s);
    assert_eq!(gateway.requests.lock().unwrap().len(), before, "no deletion for a mapping never made");
}

#[test]
fn no_gateway_no_requests() {
    let gateway = Gateway::start(600, 0, 0);
    let s = session(None);
    std::thread::sleep(Duration::from_millis(500));
    drop(s);
    assert_eq!(gateway.count(|_| true), 0);
}
