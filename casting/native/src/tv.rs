use crate::{
    devices::{Device, Devices},
    problem::{Problem, Result},
};
use futures_util::{StreamExt, stream};
use reqwest::{Client, Method};
use serde_json::{Value, json};
use std::{
    collections::{HashMap, HashSet},
    error::Error,
    net::{IpAddr, Ipv4Addr, SocketAddr},
    sync::Arc,
    time::{Duration, Instant},
};
use tokio::{net::UdpSocket, sync::Mutex};

pub const PORT: u16 = 8765;
const MAX_RESPONSE: usize = 4 * 1024 * 1024;
#[derive(Clone)]
pub struct TvClient {
    http: Client,
    pub devices: Devices,
    searched: Arc<Mutex<HashMap<String, Instant>>>,
}
impl TvClient {
    pub fn new(devices: Devices) -> Result<Self> {
        let http = Client::builder()
            .no_proxy()
            .redirect(reqwest::redirect::Policy::none())
            .connect_timeout(Duration::from_secs(3))
            .timeout(Duration::from_secs(8))
            .pool_idle_timeout(Duration::from_secs(60))
            .pool_max_idle_per_host(4)
            .tcp_nodelay(true)
            .build()
            .map_err(|_| Problem::helper("Couldn’t start the TV connection"))?;
        Ok(Self {
            http,
            devices,
            searched: Arc::default(),
        })
    }
    pub async fn request(
        &self,
        endpoint: SocketAddr,
        method: Method,
        path: &str,
        body: Option<&Value>,
        token: Option<&str>,
        timeout: Duration,
    ) -> Result<Value> {
        let started = Instant::now();
        let url = format!("http://{endpoint}{path}");
        let mut call = self
            .http
            .request(method, url)
            .timeout(timeout)
            .header("Content-Type", "application/json");
        if let Some(token) = token {
            call = call.bearer_auth(token);
        }
        if let Some(body) = body {
            call = call.json(body);
        }
        let mut response = call
            .send()
            .await
            .map_err(|error| network_problem(&error, started.elapsed()))?;
        let status = response.status();
        let mut bytes = Vec::new();
        while let Some(chunk) = response
            .chunk()
            .await
            .map_err(|error| network_problem(&error, started.elapsed()))?
        {
            if bytes.len() + chunk.len() > MAX_RESPONSE {
                return Err(Problem::new(
                    "offline",
                    "The TV answered with something unexpected",
                ));
            }
            bytes.extend_from_slice(&chunk);
        }
        let value: Value = if bytes.is_empty() {
            json!({})
        } else {
            serde_json::from_slice(&bytes).unwrap_or(Value::Null)
        };
        if !status.is_success() {
            let code = value["error"]["code"].as_str().unwrap_or("http");
            let message = value["error"]["message"]
                .as_str()
                .map(str::to_owned)
                .unwrap_or_else(|| format!("The TV answered HTTP {}", status.as_u16()));
            return Err(tv_problem(code, &message).detail(format!("HTTP {}", status.as_u16())));
        }
        if !value.is_object() {
            return Err(Problem::new(
                "offline",
                "The TV answered with something unexpected",
            ));
        }
        Ok(value)
    }
    pub async fn call(
        &self,
        device: &mut Device,
        method: Method,
        path: &str,
        body: Option<&Value>,
        timeout: Duration,
    ) -> Result<Value> {
        let address = device
            .address
            .parse()
            .map_err(|_| Problem::helper("The saved TV address is invalid"))?;
        let result = self
            .request(
                SocketAddr::new(address, device.port),
                method.clone(),
                path,
                body,
                Some(&device.token),
                timeout,
            )
            .await;
        if result.as_ref().is_err_and(|error| error.code == "offline")
            && self.find_again(device).await?
        {
            return self
                .request(
                    SocketAddr::new(
                        device
                            .address
                            .parse()
                            .map_err(|_| Problem::helper("Invalid TV address"))?,
                        device.port,
                    ),
                    method,
                    path,
                    body,
                    Some(&device.token),
                    timeout,
                )
                .await;
        }
        result
    }
    async fn find_again(&self, device: &mut Device) -> Result<bool> {
        {
            let mut searched = self.searched.lock().await;
            if searched
                .get(&device.id)
                .is_some_and(|time| time.elapsed() < Duration::from_secs(30))
            {
                return Ok(false);
            }
            searched.insert(device.id.clone(), Instant::now());
        }
        for info in self.discover(Duration::from_secs(1), false).await {
            if info["id"] == device.id {
                let address = info["address"].as_str().unwrap_or_default();
                let port = info["port"].as_u64().unwrap_or(PORT as u64) as u16;
                if (address, port) != (device.address.as_str(), device.port) {
                    self.devices.move_to(&device.id, address, port)?;
                    device.address = address.into();
                    device.port = port;
                    return Ok(true);
                }
            }
        }
        Ok(false)
    }
    pub async fn info(&self, device: &mut Device) -> Result<Value> {
        self.call(
            device,
            Method::GET,
            "/v1/info",
            None,
            Duration::from_secs(3),
        )
        .await
    }
    pub async fn status(&self, device: &mut Device) -> Result<Value> {
        self.call(
            device,
            Method::GET,
            "/v1/status",
            None,
            Duration::from_secs(3),
        )
        .await
    }
    pub async fn command(&self, device: &mut Device, body: &Value) -> Result<Value> {
        self.call(
            device,
            Method::POST,
            "/v1/command",
            Some(body),
            Duration::from_secs(8),
        )
        .await
    }
    pub async fn discover(&self, duration: Duration, scan: bool) -> Vec<Value> {
        // Probe UDP and the optional local emulator together, rather than paying both timeouts in sequence.
        let networks = networks();
        let udp = async {
            let mut found = Vec::new();
            if let Ok(socket) = UdpSocket::bind("0.0.0.0:0").await {
                let _ = socket.set_broadcast(true);
                let targets: HashSet<_> = networks
                    .iter()
                    .map(|n| n.2)
                    .chain([Ipv4Addr::BROADCAST])
                    .collect();
                for target in targets {
                    let _ = socket
                        .send_to(b"DANIILCAST_DISCOVER/1", (target, PORT))
                        .await;
                }
                let deadline = tokio::time::Instant::now() + duration;
                let mut bytes = [0; 8192];
                while let Ok(Ok((length, address))) =
                    tokio::time::timeout_at(deadline, socket.recv_from(&mut bytes)).await
                {
                    if let Ok(info) = serde_json::from_slice(&bytes[..length]) {
                        if let Some(info) = discovered(info, address.ip()) {
                            found.push(info);
                        }
                    }
                }
            }
            found
        };
        let local = self.request(
            SocketAddr::new(Ipv4Addr::LOCALHOST.into(), PORT),
            Method::GET,
            "/v1/info",
            None,
            None,
            Duration::from_millis(600),
        );
        let (mut found, local) = tokio::join!(udp, local);
        if let Ok(info) = local {
            if let Some(info) = discovered(info, Ipv4Addr::LOCALHOST.into()) {
                found.push(info);
            }
        }
        if scan
            && !found
                .iter()
                .any(|v| v["address"].as_str().is_some_and(|a| a != "127.0.0.1"))
        {
            let candidates = candidates(&networks);
            let replies = stream::iter(candidates)
                .map(|address| async move {
                    self.request(
                        SocketAddr::new(address.into(), PORT),
                        Method::GET,
                        "/v1/info",
                        None,
                        None,
                        Duration::from_millis(500),
                    )
                    .await
                    .ok()
                    .and_then(|info| discovered(info, address.into()))
                })
                .buffer_unordered(64)
                .collect::<Vec<_>>()
                .await;
            found.extend(replies.into_iter().flatten());
        }
        let mut unique = HashMap::new();
        for info in found {
            if let Some(id) = info["id"].as_str() {
                unique.insert(id.to_owned(), info);
            }
        }
        let mut found: Vec<_> = unique.into_values().collect();
        found.sort_by(|a, b| a["name"].as_str().cmp(&b["name"].as_str()));
        found
    }
}
fn discovered(mut info: Value, address: IpAddr) -> Option<Value> {
    if info["app"] != "Daniil Cast" || info["id"].as_str().is_none_or(str::is_empty) {
        return None;
    }
    let port = info["port"]
        .as_u64()
        .filter(|p| *p > 0 && *p <= u16::MAX as u64)
        .unwrap_or(PORT as u64);
    info["address"] = json!(address.to_string());
    info["port"] = json!(port);
    Some(info)
}
fn networks() -> Vec<(Ipv4Addr, Ipv4Addr, Ipv4Addr)> {
    if_addrs::get_if_addrs()
        .unwrap_or_default()
        .into_iter()
        .filter_map(|interface| {
            if let if_addrs::IfAddr::V4(v4) = interface.addr {
                if !v4.ip.is_loopback() && !v4.ip.is_link_local() {
                    return Some((
                        v4.ip,
                        v4.netmask,
                        v4.broadcast.unwrap_or(Ipv4Addr::BROADCAST),
                    ));
                }
            }
            None
        })
        .collect()
}
fn candidates(networks: &[(Ipv4Addr, Ipv4Addr, Ipv4Addr)]) -> Vec<Ipv4Addr> {
    let mut all = HashSet::new();
    for (address, mask, _) in networks {
        let address = u32::from(*address);
        let mut mask = u32::from(*mask);
        if (!mask as u64) + 1 > 1024 {
            mask = 0xffffff00;
        }
        let start = address & mask;
        let end = start | !mask;
        for value in (start as u64 + 1)..(end as u64) {
            if all.len() >= 1024 {
                break;
            }
            if value as u32 != address {
                all.insert(Ipv4Addr::from(value as u32));
            }
        }
    }
    all.into_iter().collect()
}
fn network_problem(error: &reqwest::Error, elapsed: Duration) -> Problem {
    let mut source: Option<&(dyn Error + 'static)> = Some(error);
    let mut kind = None;
    let mut number = None;
    while let Some(error) = source {
        if let Some(error) = error.downcast_ref::<std::io::Error>() {
            kind = Some(error.kind());
            number = error.raw_os_error();
        }
        source = error.source();
    }
    let code = if cfg!(target_os = "macos")
        && number == Some(65)
        && elapsed < Duration::from_millis(500)
    {
        "local-network"
    } else if kind == Some(std::io::ErrorKind::ConnectionRefused) {
        "not-running"
    } else {
        "offline"
    };
    tv_problem(code, "").detail(format!(
        "Connection failed{} after {:.2} s",
        number.map(|n| format!(" · errno {n}")).unwrap_or_default(),
        elapsed.as_secs_f64()
    ))
}
pub fn tv_problem(code: &str, message: &str) -> Problem {
    match code {
        "local-network" => Problem::new(code, "macOS is keeping the helper off your network").message("Allow Daniil Cast in System Settings → Privacy & Security → Local Network, then try again."),
        "not-running" => Problem::new(code, "Daniil Cast isn't running on the TV").message("Open Daniil Cast on the TV once. After that it starts with the TV."),
        "offline" => Problem::new(code, "The TV isn't answering").message("Turn on the TV and open Daniil Cast, and check it’s on the same network as this computer."),
        "unauthorized" => Problem::new("unpaired", "This computer is no longer paired").message("The TV removed this pairing. Pair again in the TV tab."),
        "not-playing" => Problem::new(code, "Nothing is playing on the TV"),
        _ => Problem::new(code, "The TV couldn’t do that").message(message),
    }
}
pub fn computer_name() -> String {
    hostname::get()
        .ok()
        .and_then(|name| name.into_string().ok())
        .map(|name| name.trim_end_matches(".local").to_owned())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "Computer".into())
}
