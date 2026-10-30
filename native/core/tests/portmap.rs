//! Router port mapping against fake gateways on loopback: PCP, a NAT-PMP-only gateway, a refusing gateway, and UPnP
//! IGD by SSDP and SOAP. The session maps its listen port for TCP and uTP, announces the external port it was
//! granted, renews leases, and deletes its mappings when dropped. Nothing leaves 127.0.0.1.

use std::io::{BufRead, BufReader, Read, Write};
use std::net::{SocketAddrV4, TcpListener, UdpSocket};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use stream_core::bt::portmap::Options;
use stream_core::bt::session::{Session, SessionConfig};

fn local(socket: &UdpSocket) -> SocketAddrV4 {
    match socket.local_addr().unwrap() {
        std::net::SocketAddr::V4(a) => a,
        _ => unreachable!(),
    }
}

/// A request the PCP/NAT-PMP gateway received: version, TCP?, internal port, lifetime, nonce (PCP only).
#[derive(Clone, Debug)]
struct Request {
    version: u8,
    tcp: bool,
    internal: u16,
    lifetime: u32,
    nonce: [u8; 12],
}

#[derive(Clone, Copy, PartialEq)]
enum Kind {
    /// Speaks PCP.
    Pcp,
    /// Answers version 2 with NAT-PMP's "unsupported version", as RFC 6887 Appendix A describes.
    NatPmpOnly,
    /// NAT-PMP with mapping disabled: "not authorized".
    Refusing,
}

struct Gateway {
    address: SocketAddrV4,
    requests: Arc<Mutex<Vec<Request>>>,
}

impl Gateway {
    /// Grants `lifetime` (or deletes, for a zero lifetime) at `internal + offset`.
    fn start(kind: Kind, lifetime: u32, offset: u16) -> Gateway {
        let socket = UdpSocket::bind("127.0.0.1:0").unwrap();
        socket.set_read_timeout(Some(Duration::from_millis(100))).unwrap();
        let address = local(&socket);
        let requests = Arc::new(Mutex::new(Vec::new()));
        let seen = Arc::downgrade(&requests);
        std::thread::spawn(move || {
            let mut buf = [0u8; 1100];
            while let Some(seen) = seen.upgrade() {
                let Ok((n, from)) = socket.recv_from(&mut buf) else { continue };
                let reply: Vec<u8> = match (buf[0], n) {
                    (2, 60) => {
                        let mut nonce = [0u8; 12];
                        nonce.copy_from_slice(&buf[24..36]);
                        let internal = u16::from_be_bytes([buf[40], buf[41]]);
                        let asked = u32::from_be_bytes([buf[4], buf[5], buf[6], buf[7]]);
                        seen.lock().unwrap().push(Request { version: 2, tcp: buf[36] == 6, internal, lifetime: asked, nonce });
                        if kind != Kind::Pcp {
                            vec![0, 129, 0, 1, 0, 0, 0, 0]
                        } else {
                            let mut r = buf[..60].to_vec();
                            r[1] = 0x81;
                            r[2] = 0;
                            r[3] = 0;
                            let granted = if asked == 0 { 0 } else { lifetime };
                            r[4..8].copy_from_slice(&granted.to_be_bytes());
                            r[8..24].fill(0);
                            let external = if asked == 0 { 0 } else { internal + offset };
                            r[42..44].copy_from_slice(&external.to_be_bytes());
                            r
                        }
                    }
                    (0, 12) => {
                        let internal = u16::from_be_bytes([buf[4], buf[5]]);
                        let asked = u32::from_be_bytes([buf[8], buf[9], buf[10], buf[11]]);
                        seen.lock().unwrap().push(Request { version: 0, tcp: buf[1] == 2, internal, lifetime: asked, nonce: [0; 12] });
                        let mut r = vec![0u8; 16];
                        r[1] = 128 + buf[1];
                        if kind == Kind::Refusing {
                            r[3] = 2;
                        }
                        r[8..10].copy_from_slice(&internal.to_be_bytes());
                        let (external, granted) = if asked == 0 { (0, 0) } else { (internal + offset, lifetime) };
                        r[10..12].copy_from_slice(&external.to_be_bytes());
                        r[12..16].copy_from_slice(&granted.to_be_bytes());
                        r
                    }
                    _ => continue,
                };
                let _ = socket.send_to(&reply, from);
            }
        });
        Gateway { address, requests }
    }

    fn count(&self, matches: impl Fn(&Request) -> bool) -> usize {
        self.requests.lock().unwrap().iter().filter(|r| matches(r)).count()
    }
}

/// A UPnP IGD: an SSDP responder and an HTTP server for its description and WANIPConnection:1 control URL.
struct Igd {
    ssdp: SocketAddrV4,
    /// Each SOAP call: action, protocol, lease.
    calls: Arc<Mutex<Vec<(String, String, String)>>>,
    /// Requests for anything other than the description and control URL.
    strays: Arc<Mutex<Vec<String>>>,
}

fn tag(body: &str, name: &str) -> String {
    body.split(&format!("<{name}>")).nth(1).and_then(|r| r.split(&format!("</{name}>")).next()).unwrap_or("").to_string()
}

impl Igd {
    /// `permanent`: refuse a non-zero lease with error 725. `control` overrides the control URL.
    fn start(permanent: bool, control: Option<&str>) -> Igd {
        let http = TcpListener::bind("127.0.0.1:0").unwrap();
        let http_port = http.local_addr().unwrap().port();
        let control = control.map(str::to_string).unwrap_or_else(|| "/ctl/IPConn".into());
        let calls = Arc::new(Mutex::new(Vec::new()));
        let strays = Arc::new(Mutex::new(Vec::new()));
        let (c, s) = (calls.clone(), strays.clone());
        std::thread::spawn(move || {
            for stream in http.incoming() {
                let Ok(mut stream) = stream else { continue };
                let mut reader = BufReader::new(stream.try_clone().unwrap());
                let mut first = String::new();
                if reader.read_line(&mut first).is_err() {
                    continue;
                }
                let mut length = 0;
                let mut action = String::new();
                loop {
                    let mut line = String::new();
                    if reader.read_line(&mut line).is_err() || line == "\r\n" || line.is_empty() {
                        break;
                    }
                    let lower = line.to_ascii_lowercase();
                    if let Some(v) = lower.strip_prefix("content-length:") {
                        length = v.trim().parse().unwrap_or(0);
                    }
                    if lower.starts_with("soapaction:") {
                        action = line.rsplit('#').next().unwrap_or("").trim().trim_end_matches('"').to_string();
                    }
                }
                let mut body = vec![0u8; length];
                let _ = reader.read_exact(&mut body);
                let body = String::from_utf8_lossy(&body).to_string();
                let (status, reply) = if first.starts_with("GET /desc.xml ") {
                    ("200 OK", format!("<?xml version=\"1.0\"?><root><device><serviceList><service>\
                        <serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType>\
                        <controlURL>{control}</controlURL></service></serviceList></device></root>"))
                } else if first.starts_with("POST /ctl/IPConn ") {
                    let lease = tag(&body, "NewLeaseDuration");
                    c.lock().unwrap().push((action.clone(), tag(&body, "NewProtocol"), lease.clone()));
                    if action == "AddPortMapping" && permanent && lease != "0" {
                        ("500 Internal Server Error", "<s:Envelope><s:Body><s:Fault><detail><UPnPError><errorCode>725</errorCode>\
                            </UPnPError></detail></s:Fault></s:Body></s:Envelope>".to_string())
                    } else {
                        ("200 OK", format!("<s:Envelope><s:Body><u:{action}Response/></s:Body></s:Envelope>"))
                    }
                } else {
                    s.lock().unwrap().push(first.trim().to_string());
                    ("404 Not Found", String::new())
                };
                let _ = write!(stream, "HTTP/1.1 {status}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{reply}", reply.len());
            }
        });
        let ssdp = UdpSocket::bind("127.0.0.1:0").unwrap();
        ssdp.set_read_timeout(Some(Duration::from_millis(100))).unwrap();
        let address = local(&ssdp);
        let weak = Arc::downgrade(&calls);
        std::thread::spawn(move || {
            let mut buf = [0u8; 2048];
            while weak.upgrade().is_some() {
                let Ok((n, from)) = ssdp.recv_from(&mut buf) else { continue };
                let search = String::from_utf8_lossy(&buf[..n]);
                if !search.starts_with("M-SEARCH * HTTP/1.1") || !search.contains("ssdp:discover") {
                    continue;
                }
                let answer = format!("HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=120\r\nST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\
                    USN: uuid:fake::urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\nLOCATION: http://127.0.0.1:{http_port}/desc.xml\r\n\r\n");
                let _ = ssdp.send_to(answer.as_bytes(), from);
            }
        });
        Igd { ssdp: address, calls, strays }
    }

    fn count(&self, action: &str, protocol: &str) -> usize {
        self.calls.lock().unwrap().iter().filter(|c| c.0 == action && c.1 == protocol).count()
    }
}

/// A loopback UDP port with nothing listening: PCP/NAT-PMP requests there fail at once (ICMP port unreachable).
fn closed_port() -> SocketAddrV4 {
    let socket = UdpSocket::bind("127.0.0.1:0").unwrap();
    local(&socket)
}

fn session(options: Option<Options>) -> Session {
    Session::new(
        SessionConfig { listen: "127.0.0.1:0".into(), port_mapping: options, log: true, ..SessionConfig::default() },
        None,
    )
    .unwrap()
}

fn options(gateway: SocketAddrV4, upnp: Option<SocketAddrV4>) -> Option<Options> {
    Some(Options { gateway, pcp: true, upnp: upnp.is_some(), ssdp: upnp.unwrap_or(gateway), multicast: false })
}

fn wait(what: &str, timeout: Duration, ready: impl Fn() -> bool) {
    let deadline = Instant::now() + timeout;
    while !ready() {
        assert!(Instant::now() < deadline, "timed out: {what}");
        std::thread::sleep(Duration::from_millis(20));
    }
}

#[test]
fn pcp_maps_renews_announces_and_deletes_by_nonce() {
    // A four-second lease is renewed after two.
    let gateway = Gateway::start(Kind::Pcp, 4, 1000);
    let s = session(options(gateway.address, None));
    let port = s.port();
    wait("TCP and UDP mapped by PCP", Duration::from_secs(3), || {
        gateway.count(|r| r.version == 2 && r.tcp && r.internal == port && r.lifetime > 0) >= 1
            && gateway.count(|r| r.version == 2 && !r.tcp && r.internal == port && r.lifetime > 0) >= 1
    });
    wait("external port announced", Duration::from_secs(1), || s.announce_port() == port + 1000);
    wait("lease renewed", Duration::from_secs(5), || gateway.count(|r| r.tcp && r.lifetime > 0) >= 2);
    assert_eq!(gateway.count(|r| r.version == 0), 0, "a PCP gateway is never asked by NAT-PMP");
    let tcp_nonce = gateway.requests.lock().unwrap().iter().find(|r| r.tcp).unwrap().nonce;
    assert!(gateway.requests.lock().unwrap().iter().filter(|r| r.tcp).all(|r| r.nonce == tcp_nonce), "renewals keep the nonce");
    drop(s);
    let deleted: Vec<Request> = gateway.requests.lock().unwrap().iter().filter(|r| r.lifetime == 0).cloned().collect();
    assert_eq!(deleted.len(), 2, "both mappings deleted on shutdown");
    assert!(deleted.iter().any(|r| r.tcp && r.nonce == tcp_nonce), "the TCP deletion names its mapping's nonce");
}

#[test]
fn a_natpmp_only_gateway_is_asked_by_natpmp_after_pcp() {
    let gateway = Gateway::start(Kind::NatPmpOnly, 600, 0);
    let s = session(options(gateway.address, None));
    let port = s.port();
    wait("mapped by NAT-PMP", Duration::from_secs(5), || {
        gateway.count(|r| r.version == 0 && r.tcp && r.lifetime > 0) >= 1 && gateway.count(|r| r.version == 0 && !r.tcp && r.lifetime > 0) >= 1
    });
    assert!(gateway.count(|r| r.version == 2) >= 1, "PCP was tried first");
    assert_eq!(s.announce_port(), port, "the same external port needs no different announcement");
    drop(s);
    assert_eq!(gateway.count(|r| r.version == 0 && r.lifetime == 0), 2, "NAT-PMP mappings deleted by NAT-PMP");
}

#[test]
fn a_refusing_gateway_maps_nothing_and_deletes_nothing() {
    let gateway = Gateway::start(Kind::Refusing, 600, 0);
    let s = session(options(gateway.address, None));
    let port = s.port();
    wait("NAT-PMP asked and refused", Duration::from_secs(5), || gateway.count(|r| r.version == 0) >= 1);
    assert_eq!(s.announce_port(), port);
    std::thread::sleep(Duration::from_millis(300));
    let before = gateway.requests.lock().unwrap().len();
    drop(s);
    assert_eq!(gateway.requests.lock().unwrap().len(), before, "no deletion for a mapping never made");
}

#[test]
fn upnp_maps_when_pcp_and_natpmp_do_not_answer() {
    let igd = Igd::start(false, None);
    let s = session(options(closed_port(), Some(igd.ssdp)));
    wait("TCP and UDP mapped by UPnP", Duration::from_secs(8), || igd.count("AddPortMapping", "TCP") >= 1 && igd.count("AddPortMapping", "UDP") >= 1);
    assert!(igd.calls.lock().unwrap().iter().all(|c| c.2 == "600" || c.0 != "AddPortMapping"), "a ten-minute lease");
    drop(s);
    assert_eq!(igd.count("DeletePortMapping", "TCP"), 1);
    assert_eq!(igd.count("DeletePortMapping", "UDP"), 1);
    assert!(igd.strays.lock().unwrap().is_empty(), "only the description and control URL: {:?}", igd.strays.lock().unwrap());
}

#[test]
fn a_permanent_only_upnp_gateway_gets_a_permanent_mapping_that_is_deleted() {
    let igd = Igd::start(true, None);
    let s = session(options(closed_port(), Some(igd.ssdp)));
    wait("mapped permanently", Duration::from_secs(8), || {
        igd.calls.lock().unwrap().iter().any(|c| c.0 == "AddPortMapping" && c.1 == "TCP" && c.2 == "0")
    });
    drop(s);
    assert_eq!(igd.count("DeletePortMapping", "TCP"), 1, "a permanent mapping must be deleted on shutdown");
}

#[test]
fn a_control_url_off_the_gateway_is_never_used() {
    // The description points the control URL at another host; nothing may be sent there, and nothing is mapped.
    let igd = Igd::start(false, Some("http://127.0.0.2:5000/ctl/IPConn"));
    let s = session(options(closed_port(), Some(igd.ssdp)));
    std::thread::sleep(Duration::from_secs(3));
    assert_eq!(igd.calls.lock().unwrap().len(), 0);
    let port = s.port();
    assert_eq!(s.announce_port(), port);
    drop(s);
}

#[test]
fn no_options_no_requests() {
    let gateway = Gateway::start(Kind::Pcp, 600, 0);
    let s = session(None);
    std::thread::sleep(Duration::from_millis(500));
    drop(s);
    assert_eq!(gateway.count(|_| true), 0);
}
