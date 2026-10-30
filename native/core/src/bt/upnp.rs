//! UPnP Internet Gateway Device port mapping (IGD:1/2 WANIPConnection or WANPPPConnection), used only when the
//! gateway answers neither PCP nor NAT-PMP. Discovery asks the gateway the app names by unicast SSDP first (UPnP
//! Device Architecture 1.1 §1.3.2) and then, on a real network, with the standard multicast search. Only answers from
//! the gateway's own address are accepted, and its description and control URLs must name that same address, so the
//! engine never talks to another device on the network, whatever the answers say.

use super::portmap::{Failure, Protocol};
use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4};
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpStream, UdpSocket};

pub const SSDP_PORT: u16 = 1900;
const SSDP_GROUP: SocketAddrV4 = SocketAddrV4::new(Ipv4Addr::new(239, 255, 255, 250), SSDP_PORT);
const SEARCH_TARGET: &str = "urn:schemas-upnp-org:device:InternetGatewayDevice:1";
/// IGD:2 first, then the two IGD:1 connection services.
const SERVICES: [&str; 3] = [
    "urn:schemas-upnp-org:service:WANIPConnection:2",
    "urn:schemas-upnp-org:service:WANIPConnection:1",
    "urn:schemas-upnp-org:service:WANPPPConnection:1",
];
const HTTP_TIMEOUT: Duration = Duration::from_secs(3);
const MAX_RESPONSE: usize = 64 << 10;
/// UPnP error 725, OnlyPermanentLeasesSupported: the gateway accepts only a lease duration of zero.
const ONLY_PERMANENT: &str = "725";

/// The gateway's WAN connection service.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Igd {
    pub host: SocketAddrV4,
    pub control_path: String,
    pub service: String,
}

fn search(host: SocketAddrV4) -> String {
    format!("M-SEARCH * HTTP/1.1\r\nHOST: {host}\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: {SEARCH_TARGET}\r\n\r\n")
}

/// The header `name` of an HTTP-style message, case-insensitively.
fn header<'a>(message: &'a str, name: &str) -> Option<&'a str> {
    message.split("\r\n").skip(1).find_map(|line| {
        let (k, v) = line.split_once(':')?;
        k.trim().eq_ignore_ascii_case(name).then(|| v.trim())
    })
}

/// `http://<gateway>[:port]/path` as the host and path, or None when it names any other host or scheme.
pub fn gateway_url(url: &str, gateway: Ipv4Addr) -> Option<(SocketAddrV4, String)> {
    let rest = url.trim().strip_prefix("http://")?;
    let (authority, path) = match rest.find('/') {
        Some(i) => (&rest[..i], &rest[i..]),
        None => (rest, "/"),
    };
    let (host, port) = match authority.rsplit_once(':') {
        Some((h, p)) => (h, p.parse::<u16>().ok()?),
        None => (authority, 80),
    };
    let ip: Ipv4Addr = host.parse().ok()?;
    (ip == gateway && port != 0).then(|| (SocketAddrV4::new(ip, port), path.to_string()))
}

/// The description's location from an SSDP answer, if the answer is an IGD's.
pub fn parse_search_response(message: &str, gateway: Ipv4Addr) -> Option<(SocketAddrV4, String)> {
    if !message.starts_with("HTTP/1.1 200") && !message.starts_with("HTTP/1.0 200") {
        return None;
    }
    gateway_url(header(message, "LOCATION")?, gateway)
}

/// The best WAN connection service in a device description, with its control URL resolved against `host`.
pub fn parse_description(xml: &str, host: SocketAddrV4) -> Option<Igd> {
    let tag = |block: &str, name: &str| -> Option<String> {
        let open = format!("<{name}>");
        let start = block.find(&open)? + open.len();
        let end = block[start..].find(&format!("</{name}>"))? + start;
        Some(block[start..end].trim().to_string())
    };
    let mut found: Vec<(usize, Igd)> = Vec::new();
    for block in xml.split("<service>").skip(1) {
        let block = block.split("</service>").next().unwrap_or("");
        let (Some(service), Some(control)) = (tag(block, "serviceType"), tag(block, "controlURL")) else { continue };
        let Some(rank) = SERVICES.iter().position(|s| *s == service) else { continue };
        let control_path = if control.starts_with("http://") {
            // An absolute control URL must stay on the gateway too.
            match gateway_url(&control, *host.ip()) {
                Some((h, p)) if h == host => p,
                _ => continue,
            }
        } else if control.starts_with('/') {
            control
        } else {
            format!("/{control}")
        };
        found.push((rank, Igd { host, control_path, service }));
    }
    found.sort_by_key(|(rank, _)| *rank);
    found.into_iter().next().map(|(_, igd)| igd)
}

fn decode_chunked(body: &[u8]) -> Option<Vec<u8>> {
    let mut out = Vec::new();
    let mut rest = body;
    loop {
        let line_end = rest.windows(2).position(|w| w == b"\r\n")?;
        let size = usize::from_str_radix(std::str::from_utf8(&rest[..line_end]).ok()?.split(';').next()?.trim(), 16).ok()?;
        rest = &rest[line_end + 2..];
        if size == 0 {
            return Some(out);
        }
        out.extend_from_slice(rest.get(..size)?);
        rest = rest.get(size + 2..)?;
    }
}

/// One HTTP/1.1 request with `Connection: close`: the status code and body, bounded in size and time.
async fn http(host: SocketAddrV4, request: String) -> Result<(u16, String), Failure> {
    let exchange = async {
        let mut stream = TcpStream::connect(host).await.map_err(|e| Failure::NoAnswer(format!("UPnP {host}: {e}")))?;
        stream.write_all(request.as_bytes()).await.map_err(|e| Failure::NoAnswer(format!("UPnP {host}: {e}")))?;
        let mut data = Vec::new();
        let mut buf = [0u8; 4096];
        loop {
            let n = stream.read(&mut buf).await.map_err(|e| Failure::NoAnswer(format!("UPnP {host}: {e}")))?;
            if n == 0 {
                break;
            }
            data.extend_from_slice(&buf[..n]);
            if data.len() > MAX_RESPONSE {
                return Err(Failure::NoAnswer(format!("UPnP {host}: response too large")));
            }
        }
        Ok(data)
    };
    let data = tokio::time::timeout(HTTP_TIMEOUT, exchange)
        .await
        .map_err(|_| Failure::NoAnswer(format!("UPnP {host}: timed out")))??;
    let split = data.windows(4).position(|w| w == b"\r\n\r\n").ok_or(Failure::NoAnswer(format!("UPnP {host}: malformed response")))?;
    let head = String::from_utf8_lossy(&data[..split]).to_string();
    let status = head.split(' ').nth(1).and_then(|s| s.parse::<u16>().ok()).ok_or(Failure::NoAnswer(format!("UPnP {host}: malformed status")))?;
    let mut body = data[split + 4..].to_vec();
    if header(&head, "Transfer-Encoding").is_some_and(|v| v.eq_ignore_ascii_case("chunked")) {
        body = decode_chunked(&body).ok_or(Failure::NoAnswer(format!("UPnP {host}: malformed chunked body")))?;
    }
    Ok((status, String::from_utf8_lossy(&body).to_string()))
}

/// Finds the gateway's IGD: by unicast to `unicast` (the gateway's SSDP port) and, when `multicast`, the SSDP group.
pub async fn discover(gateway: Ipv4Addr, unicast: SocketAddrV4, multicast: bool) -> Result<Igd, Failure> {
    let socket = UdpSocket::bind(SocketAddrV4::new(Ipv4Addr::UNSPECIFIED, 0))
        .await
        .map_err(|e| Failure::NoAnswer(format!("UPnP discovery: {e}")))?;
    let mut buf = [0u8; 2048];
    for attempt in 0..2 {
        let _ = socket.send_to(search(unicast).as_bytes(), unicast).await;
        if multicast {
            let _ = socket.set_multicast_ttl_v4(2);
            let _ = socket.send_to(search(SSDP_GROUP).as_bytes(), SSDP_GROUP).await;
        }
        let deadline = tokio::time::Instant::now() + Duration::from_millis(1500 * (attempt + 1));
        while let Ok(Ok((n, from))) = tokio::time::timeout_at(deadline, socket.recv_from(&mut buf)).await {
            // Other devices answer the multicast search too; only the gateway's answer counts.
            if from.ip() != std::net::IpAddr::V4(gateway) {
                continue;
            }
            let Some((host, path)) = parse_search_response(&String::from_utf8_lossy(&buf[..n]), gateway) else { continue };
            let (status, xml) = http(host, format!("GET {path} HTTP/1.1\r\nHost: {host}\r\nConnection: close\r\n\r\n")).await?;
            if status != 200 {
                return Err(Failure::Refused(format!("UPnP description {path}: HTTP {status}")));
            }
            return parse_description(&xml, host).ok_or(Failure::Refused("The gateway's UPnP description has no WAN connection service".into()));
        }
    }
    Err(Failure::NoAnswer("no UPnP gateway answered".into()))
}

fn soap(igd: &Igd, action: &str, arguments: &str) -> String {
    let body = format!(
        "<?xml version=\"1.0\"?>\r\n<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" \
         s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:{action} xmlns:u=\"{}\">{arguments}</u:{action}></s:Body></s:Envelope>\r\n",
        igd.service
    );
    format!(
        "POST {} HTTP/1.1\r\nHost: {}\r\nContent-Type: text/xml; charset=\"utf-8\"\r\nSOAPAction: \"{}#{action}\"\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
        igd.control_path, igd.host, igd.service, body.len()
    )
}

fn error_code(body: &str) -> String {
    body.split("<errorCode>").nth(1).and_then(|r| r.split("</errorCode>").next()).unwrap_or("").trim().to_string()
}

fn protocol_name(protocol: Protocol) -> &'static str {
    match protocol {
        Protocol::Tcp => "TCP",
        Protocol::Udp => "UDP",
    }
}

/// Maps `port` to the same external port for `client`, for `lease` seconds; the lease granted (zero when the gateway
/// supports only permanent mappings, which the caller must delete itself).
pub async fn add(igd: &Igd, protocol: Protocol, port: u16, client: Ipv4Addr, lease: u32) -> Result<u32, Failure> {
    let mut lease = lease;
    loop {
        let arguments = format!(
            "<NewRemoteHost></NewRemoteHost><NewExternalPort>{port}</NewExternalPort><NewProtocol>{}</NewProtocol>\
             <NewInternalPort>{port}</NewInternalPort><NewInternalClient>{client}</NewInternalClient><NewEnabled>1</NewEnabled>\
             <NewPortMappingDescription>Stream</NewPortMappingDescription><NewLeaseDuration>{lease}</NewLeaseDuration>",
            protocol_name(protocol)
        );
        let (status, body) = http(igd.host, soap(igd, "AddPortMapping", &arguments)).await?;
        if status == 200 {
            return Ok(lease);
        }
        let code = error_code(&body);
        if code == ONLY_PERMANENT && lease != 0 {
            lease = 0;
            continue;
        }
        return Err(Failure::Refused(format!("UPnP gateway refused the {} mapping: HTTP {status}, error {code}", protocol_name(protocol))));
    }
}

pub async fn delete(igd: &Igd, protocol: Protocol, port: u16) -> Result<(), Failure> {
    let arguments = format!(
        "<NewRemoteHost></NewRemoteHost><NewExternalPort>{port}</NewExternalPort><NewProtocol>{}</NewProtocol>",
        protocol_name(protocol)
    );
    match http(igd.host, soap(igd, "DeletePortMapping", &arguments)).await? {
        (200, _) => Ok(()),
        (status, body) => Err(Failure::Refused(format!("UPnP gateway kept the {} mapping: HTTP {status}, error {}", protocol_name(protocol), error_code(&body)))),
    }
}

/// The local address the gateway sees us at.
pub async fn local_address(gateway: SocketAddrV4) -> Option<Ipv4Addr> {
    let socket = UdpSocket::bind(SocketAddrV4::new(Ipv4Addr::UNSPECIFIED, 0)).await.ok()?;
    socket.connect(gateway).await.ok()?;
    match socket.local_addr().ok()? {
        SocketAddr::V4(a) => Some(*a.ip()),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const GATEWAY: Ipv4Addr = Ipv4Addr::new(192, 168, 1, 1);

    #[test]
    fn only_the_gateway_is_accepted_as_a_location() {
        let ok = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=120\r\nLocation: http://192.168.1.1:5000/rootDesc.xml\r\nST: x\r\n\r\n";
        assert_eq!(parse_search_response(ok, GATEWAY), Some(("192.168.1.1:5000".parse().unwrap(), "/rootDesc.xml".into())));
        let elsewhere = ok.replace("192.168.1.1:5000", "192.168.1.50:5000");
        assert_eq!(parse_search_response(&elsewhere, GATEWAY), None, "another device on the network is ignored");
        assert_eq!(parse_search_response(&ok.replace("http://", "https://"), GATEWAY), None);
        assert_eq!(parse_search_response(&ok.replace("200 OK", "404 Not Found"), GATEWAY), None);
        assert_eq!(gateway_url("http://192.168.1.1/desc", GATEWAY), Some(("192.168.1.1:80".parse().unwrap(), "/desc".into())));
        assert_eq!(gateway_url("http://gateway.local:80/desc", GATEWAY), None, "names are not resolved");
    }

    #[test]
    fn descriptions_pick_the_best_wan_service_on_the_gateway() {
        let host: SocketAddrV4 = "192.168.1.1:5000".parse().unwrap();
        let xml = "<root><device><serviceList>\
            <service><serviceType>urn:schemas-upnp-org:service:Layer3Forwarding:1</serviceType><controlURL>/l3f</controlURL></service>\
            <service><serviceType>urn:schemas-upnp-org:service:WANPPPConnection:1</serviceType><controlURL>ctl/PPP</controlURL></service>\
            <service><serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType><controlURL>/ctl/IPConn</controlURL></service>\
            </serviceList></device></root>";
        assert_eq!(parse_description(xml, host).unwrap(),
            Igd { host, control_path: "/ctl/IPConn".into(), service: "urn:schemas-upnp-org:service:WANIPConnection:1".into() });
        let ppp_only = xml.replace("WANIPConnection:1", "Other:1");
        assert_eq!(parse_description(&ppp_only, host).unwrap().control_path, "/ctl/PPP");
        let off_gateway = xml.replace("/ctl/IPConn", "http://192.168.1.50:5000/ctl/IPConn");
        assert_eq!(parse_description(&off_gateway, host).unwrap().control_path, "/ctl/PPP", "a control URL elsewhere is skipped");
        assert_eq!(parse_description("<root></root>", host), None);
    }

    #[test]
    fn soap_requests_and_errors() {
        let igd = Igd { host: "192.168.1.1:5000".parse().unwrap(), control_path: "/ctl".into(), service: SERVICES[1].into() };
        let r = soap(&igd, "DeletePortMapping", "<NewExternalPort>1</NewExternalPort>");
        assert!(r.starts_with("POST /ctl HTTP/1.1\r\nHost: 192.168.1.1:5000\r\n"));
        assert!(r.contains("SOAPAction: \"urn:schemas-upnp-org:service:WANIPConnection:1#DeletePortMapping\""));
        let body = &r[r.find("\r\n\r\n").unwrap() + 4..];
        assert!(r.contains(&format!("Content-Length: {}\r\n", body.len())));
        assert_eq!(error_code("<UPnPError><errorCode> 725</errorCode></UPnPError>"), "725");
        assert_eq!(decode_chunked(b"5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n").unwrap(), b"hello world");
    }
}
