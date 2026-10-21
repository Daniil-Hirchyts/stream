use crate::{
    VERSION,
    devices::{Device, Devices},
    journal::Journal,
    problem::{Problem, Result},
    relay,
    tv::{TvClient, computer_name},
    validate,
};
use reqwest::Method;
use serde_json::{Value, json};
use std::{
    collections::HashMap,
    sync::atomic::{AtomicU64, Ordering},
    time::{Duration, Instant},
};
use tokio::sync::Mutex;

pub struct Bridge {
    pub devices: Devices,
    pub journal: Journal,
    pub tv: TvClient,
    info: Mutex<HashMap<String, (Instant, Value)>>,
    load_gate: Mutex<()>,
    generation: AtomicU64,
}
impl Bridge {
    pub fn new(devices: Devices, journal: Journal) -> Result<Self> {
        let tv = TvClient::new(devices.clone())?;
        Ok(Self {
            devices,
            journal,
            tv,
            info: Mutex::new(HashMap::new()),
            load_gate: Mutex::new(()),
            generation: AtomicU64::new(0),
        })
    }
    pub fn local() -> Result<Self> {
        Self::new(Devices::default(), Journal::default())
    }
    pub async fn handle(&self, message: Value) -> Value {
        let action = message["action"].as_str().unwrap_or_default().to_owned();
        match self.handle_inner(message).await {
            Ok(value) => value,
            Err(problem) => {
                if !matches!(
                    action.as_str(),
                    "hello" | "status" | "log" | "journal" | "clearLog"
                ) {
                    self.journal.record("error", &problem.title, json!({"action": action, "code": problem.code, "detail": if problem.detail.is_empty() { &problem.message } else { &problem.detail }}));
                }
                problem.payload()
            }
        }
    }
    async fn handle_inner(&self, message: Value) -> Result<Value> {
        if !message.is_object() {
            return Err(Problem::invalid("Expected a command object"));
        }
        let action = message["action"].as_str().unwrap_or_default();
        match action {
            "hello" => Ok(
                json!({"ok": true, "version": VERSION, "conversion": relay::available(), "devices": self.devices.public()?}),
            ),
            "discover" => {
                let paired = self.devices.all()?;
                let tvs = self
                    .tv
                    .discover(Duration::from_millis(1200), true)
                    .await
                    .into_iter()
                    .map(|info| {
                        let mut output = json!({});
                        for key in ["id", "name", "model", "address", "port", "version"] {
                            output[key] = info[key].clone();
                        }
                        output["paired"] = json!(
                            paired
                                .devices
                                .contains_key(info["id"].as_str().unwrap_or_default())
                        );
                        output
                    })
                    .collect::<Vec<_>>();
                Ok(json!({"ok": true, "tvs": tvs}))
            }
            "pairRequest" => {
                let (address, port) = validate::address(&message)?;
                let info = self
                    .tv
                    .request(
                        std::net::SocketAddr::new(address, port),
                        Method::GET,
                        "/v1/info",
                        None,
                        None,
                        Duration::from_secs(3),
                    )
                    .await?;
                self.tv
                    .request(
                        std::net::SocketAddr::new(address, port),
                        Method::POST,
                        "/v1/pair/request",
                        Some(&json!({})),
                        None,
                        Duration::from_secs(3),
                    )
                    .await?;
                let mut output = json!({});
                for key in ["id", "name", "model", "version"] {
                    output[key] = info[key].clone();
                }
                Ok(json!({"ok": true, "tv": output}))
            }
            "pair" => {
                let (address, port) = validate::address(&message)?;
                let code: String = validate::field(&message, "code", "", 20)?
                    .chars()
                    .filter(char::is_ascii_digit)
                    .collect();
                if code.len() != 6 {
                    return Err(Problem::new(
                        "wrong-code",
                        "Enter the six digits shown on the TV",
                    ));
                }
                let answer = self.tv.request(std::net::SocketAddr::new(address, port), Method::POST, "/v1/pair", Some(&json!({"code": code, "client": format!("Chrome on {}", computer_name())})), None, Duration::from_secs(4)).await.map_err(|problem| match problem.code.as_str() {
                    "wrong-code" => Problem::new("wrong-code", "That code didn’t work").message("Check the code on the TV and try again."),
                    "code-expired" => Problem::new("code-expired", "That code expired").message("Ask the TV for a new code and enter it."), _ => problem,
                })?;
                let token = answer["token"]
                    .as_str()
                    .filter(|token| !token.is_empty() && token.len() <= 1024)
                    .ok_or_else(|| Problem::helper("The TV returned an invalid pairing"))?;
                let device =
                    self.devices
                        .add(&answer["tv"], address.to_string(), port, token.into())?;
                self.journal.record(
                    "success",
                    &format!("Paired with {}", device.name),
                    json!({"address": address.to_string()}),
                );
                Ok(json!({"ok": true, "device": device.public()}))
            }
            "forget" => {
                let id = validate::field(&message, "id", "", 60)?;
                if let Some(mut device) = self.devices.all()?.devices.remove(&id) {
                    let _ = self
                        .tv
                        .call(
                            &mut device,
                            Method::POST,
                            "/v1/unpair",
                            Some(&json!({})),
                            Duration::from_secs(3),
                        )
                        .await;
                    self.devices.remove(&id)?;
                    self.info.lock().await.remove(&id);
                    self.journal
                        .record("info", &format!("Removed {}", device.name), json!({}));
                }
                Ok(json!({"ok": true, "devices": self.devices.public()?}))
            }
            "select" => {
                self.devices
                    .select(&validate::field(&message, "id", "", 60)?)?;
                Ok(json!({"ok": true, "devices": self.devices.public()?}))
            }
            "status" => self.status().await,
            "load" => self.load(&message).await,
            "journal" => {
                let level = message["level"]
                    .as_str()
                    .filter(|s| matches!(*s, "info" | "success" | "warning" | "error"))
                    .unwrap_or("info");
                self.journal.record(
                    level,
                    &validate::field(&message, "message", "", 300)?,
                    message
                        .get("detail")
                        .filter(|v| v.is_object())
                        .cloned()
                        .unwrap_or(json!({})),
                );
                Ok(json!({"ok": true}))
            }
            "log" => {
                let limit = validate::number(
                    message.get("limit").unwrap_or(&json!(100)),
                    1.,
                    500.,
                    "limit",
                )? as usize;
                Ok(json!({"ok": true, "entries": self.journal.recent(limit)}))
            }
            "clearLog" => {
                self.journal.clear();
                Ok(json!({"ok": true}))
            }
            action if validate::PASSTHROUGH.contains(&action) => {
                let command = validate::passthrough(&message, action)?;
                if action == "stop" {
                    self.generation.fetch_add(1, Ordering::SeqCst);
                }
                let mut device = self.devices.selected()?;
                let result = self.tv.command(&mut device, &command).await?;
                if action == "stop" {
                    let _guard = self.load_gate.lock().await;
                    relay::request(json!({"action": "stop"}), false).await?;
                }
                Ok(result)
            }
            _ => Err(Problem::invalid("Unsupported command")),
        }
    }
    async fn tv_info(&self, device: &mut Device) -> Result<Value> {
        if let Some((_, value)) = self
            .info
            .lock()
            .await
            .get(&device.id)
            .filter(|(time, _)| time.elapsed() < Duration::from_secs(60))
        {
            return Ok(value.clone());
        }
        let info = self.tv.info(device).await?;
        self.info
            .lock()
            .await
            .insert(device.id.clone(), (Instant::now(), info.clone()));
        Ok(info)
    }
    async fn status(&self) -> Result<Value> {
        let mut device = self.devices.selected()?;
        let (status, converted) = tokio::join!(
            self.tv.status(&mut device),
            relay::request(json!({"action": "status"}), false)
        );
        let mut conversion = converted.unwrap_or(json!({"active": false}));
        conversion
            .as_object_mut()
            .unwrap()
            .retain(|key, _| matches!(key.as_str(), "active" | "sourceCodec" | "error"));
        match status {
            Ok(status) => match self.tv_info(&mut device).await {
                Ok(info) => Ok(
                    json!({"ok": true, "online": true, "device": device.public(), "status": status, "decoders": info["decoders"], "conversion": conversion}),
                ),
                Err(problem) => Ok(
                    json!({"ok": true, "online": false, "device": device.public(), "problem": problem, "conversion": conversion}),
                ),
            },
            Err(problem) => Ok(
                json!({"ok": true, "online": false, "device": device.public(), "problem": problem, "conversion": conversion}),
            ),
        }
    }
    async fn load(&self, message: &Value) -> Result<Value> {
        let command = validate::load(message)?;
        let generation = self.generation.fetch_add(1, Ordering::SeqCst) + 1;
        let mut device = self.devices.selected()?;
        let info = self.tv_info(&mut device).await?;
        let codec = command["codec"].as_str().unwrap_or_default();
        let needs_conversion = !codec.is_empty()
            && info["decoders"]["video"]
                .as_array()
                .is_some_and(|codecs| !codecs.is_empty() && !codecs.iter().any(|c| c == codec));
        if message["convert"] == true || needs_conversion {
            let format = if codec.is_empty() {
                "this video".to_owned()
            } else {
                codec.to_owned()
            };
            return self.convert(&mut device, command, format, generation).await;
        }
        self.journal.record("info", &format!("Casting “{}”", command["title"].as_str().unwrap_or_default()), json!({"source": reqwest::Url::parse(command["url"].as_str().unwrap_or_default()).ok().and_then(|u| u.host_str().map(str::to_owned)), "codec": codec}));
        {
            let _guard = self.load_gate.lock().await;
            if self.generation.load(Ordering::SeqCst) != generation {
                return Ok(json!({"ok": true, "state": "superseded"}));
            }
            relay::request(json!({"action": "stop"}), false).await?;
            self.tv.command(&mut device, &command).await?;
        }
        let (outcome, status) = self
            .wait(&mut device, Duration::from_secs(25), generation)
            .await;
        if outcome == "needs-conversion" {
            let format = status["unsupportedFormat"]
                .as_str()
                .unwrap_or("this video")
                .to_owned();
            return self.convert(&mut device, command, format, generation).await;
        }
        Ok(self.result(&outcome, status, "direct", ""))
    }
    async fn convert(
        &self,
        device: &mut Device,
        command: Value,
        format: String,
        generation: u64,
    ) -> Result<Value> {
        self.journal.record(
            "info",
            &format!("Converting {format} on this computer for the TV"),
            json!({"title": command["title"]}),
        );
        {
            let _guard = self.load_gate.lock().await;
            if self.generation.load(Ordering::SeqCst) != generation {
                return Ok(json!({"ok": true, "state": "superseded"}));
            }
            self.tv
                .command(
                    device,
                    &json!({"action": "prepare", "title": command["title"], "format": format}),
                )
                .await?;
            let emulator = device
                .address
                .parse::<std::net::IpAddr>()
                .is_ok_and(|ip| ip.is_loopback());
            let conversion = relay::request(json!({"action": "start", "url": command["url"], "mime": command["mime"], "headers": command["headers"], "tv": device.address,
                "advertise": if emulator { "10.0.2.2" } else { "" }, "encoder": if emulator { "x264" } else { "" }, "audio": command.get("audio")}), true).await;
            let conversion = match conversion {
                Ok(value) => value,
                Err(problem) => {
                    self.journal.record(
                        "error",
                        &problem.title,
                        json!({"detail": problem.message}),
                    );
                    let _ = self.tv.command(device, &json!({"action": "fail", "code": "conversion", "title": problem.title, "message": problem.message})).await;
                    return Err(problem);
                }
            };
            if self.generation.load(Ordering::SeqCst) != generation {
                relay::request(json!({"action": "stop"}), false).await?;
                return Ok(json!({"ok": true, "state": "superseded"}));
            }
            let mut load = command;
            load["subtitleHeaders"] = load["headers"].clone();
            load["url"] = conversion["url"].clone();
            load["headers"] = json!({});
            load["mime"] = json!("application/x-mpegURL");
            load["converted"] = json!({"from": format});
            self.tv.command(device, &load).await?;
        }
        let (outcome, status) = self.wait(device, Duration::from_secs(60), generation).await;
        Ok(self.result(&outcome, status, "converted", &format))
    }
    async fn wait(
        &self,
        device: &mut Device,
        duration: Duration,
        generation: u64,
    ) -> (String, Value) {
        let deadline = Instant::now() + duration;
        let mut status = json!({});
        while Instant::now() < deadline {
            if self.generation.load(Ordering::SeqCst) != generation {
                return ("superseded".into(), status);
            }
            if let Ok(value) = self.tv.status(device).await {
                status = value;
                if status["phase"] == "error" {
                    return ("error".into(), status);
                }
                if status["needsConversion"] == true {
                    return ("needs-conversion".into(), status);
                }
                let has_video = status["tracks"]
                    .as_array()
                    .is_some_and(|tracks| tracks.iter().any(|t| t["type"] == 2));
                if matches!(
                    status["phase"].as_str(),
                    Some("playing" | "paused" | "ended" | "buffering")
                ) && (status["renderedVideo"] == true || !has_video)
                {
                    return ("playing".into(), status);
                }
            }
            tokio::time::sleep(Duration::from_millis(200)).await;
        }
        ("pending".into(), status)
    }
    fn result(&self, outcome: &str, status: Value, route: &str, format: &str) -> Value {
        if outcome == "error" {
            let error = &status["error"];
            self.journal.record(
                "error",
                error["title"]
                    .as_str()
                    .unwrap_or("The TV couldn’t play this video"),
                json!({"code": error["code"], "detail": error["detail"]}),
            );
            return json!({"ok": false, "error": error, "status": status});
        }
        if outcome == "playing" {
            self.journal.record(
                "success",
                &format!(
                    "Playing on the TV{}",
                    if format.is_empty() {
                        String::new()
                    } else {
                        format!(" (converted from {format})")
                    }
                ),
                json!({}),
            );
        }
        json!({"ok": true, "route": route, "state": outcome, "format": format, "status": status})
    }
}
