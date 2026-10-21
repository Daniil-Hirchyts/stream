use crate::{
    paths,
    problem::{Problem, Result},
    validate,
};
use axum::{
    Json, Router,
    body::Body,
    extract::{ConnectInfo, Path, State},
    http::{HeaderMap, StatusCode, header},
    response::Response,
    routing::{get, post},
};
use fs2::FileExt;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::{
    collections::{BTreeMap, VecDeque},
    fs::OpenOptions,
    net::{IpAddr, Ipv4Addr, SocketAddr},
    path::PathBuf,
    process::Stdio,
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    time::{Duration, Instant},
};
use subtle::ConstantTimeEq;
use tokio::{
    io::BufReader,
    net::TcpListener,
    process::{Child, Command},
    sync::Mutex,
};

const SEGMENT: u32 = 4;
const BLOCK: u32 = 20;
const MAX_SEGMENT: u64 = 32 * 1024 * 1024;
#[derive(Serialize, Deserialize)]
struct Endpoint {
    port: u16,
    token: String,
}
fn token() -> String {
    rand::random::<[u8; 32]>()
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect()
}
fn error(message: impl Into<String>) -> Problem {
    Problem::new("conversion", "Couldn’t convert this video").message(message)
}

/// Finds a bundled tool first. End-user installations never need Homebrew or a shell.
pub fn executable(name: &str) -> Result<PathBuf> {
    let executable = if cfg!(windows) {
        format!("{name}.exe")
    } else {
        name.into()
    };
    let mut candidates = Vec::new();
    if let Some(value) = std::env::var_os(format!("DANIILCAST_{}", name.to_uppercase())) {
        candidates.push(PathBuf::from(value));
    }
    if let Ok(current) = std::env::current_exe() {
        if let Some(parent) = current.parent() {
            candidates.push(parent.join(&executable));
            candidates.push(parent.join("../Resources/bin").join(&executable));
        }
    }
    for parent in [
        "/opt/homebrew/opt/ffmpeg@7/bin",
        "/usr/local/opt/ffmpeg@7/bin",
        "/opt/homebrew/bin",
        "/usr/local/bin",
    ] {
        candidates.push(PathBuf::from(parent).join(&executable));
    }
    if let Some(path) = std::env::var_os("PATH") {
        candidates.extend(std::env::split_paths(&path).map(|parent| parent.join(&executable)));
    }
    candidates.into_iter().find(|path| path.is_file()).ok_or_else(|| error("Video conversion needs FFmpeg and ffprobe. Install the helper with its conversion tools."))
}
pub fn available() -> bool {
    static CACHED: std::sync::Mutex<Option<(Instant, bool)>> = std::sync::Mutex::new(None);
    let mut cached = CACHED.lock().unwrap();
    if let Some((time, available)) = *cached {
        if time.elapsed() < Duration::from_secs(1) {
            return available;
        }
    }
    let available = executable("ffmpeg").is_ok() && executable("ffprobe").is_ok();
    *cached = Some((Instant::now(), available));
    available
}

pub fn input_options(headers: &Value, mime: &str) -> Result<Vec<String>> {
    let headers = validate::headers(headers)?;
    let values: BTreeMap<_, _> = headers
        .as_object()
        .unwrap()
        .iter()
        .map(|(key, value)| (key.to_lowercase(), value.as_str().unwrap().to_owned()))
        .collect();
    let mut args: Vec<String> = [
        "-rw_timeout",
        "15000000",
        "-protocol_whitelist",
        "http,https,tcp,tls,crypto",
    ]
    .map(str::to_owned)
    .into();
    if mime.to_lowercase().contains("dash") {
        args.extend(["-f", "dash", "-allowed_extensions", "ALL"].map(str::to_owned));
    }
    if let Some(value) = values.get("user-agent") {
        args.extend(["-user_agent".into(), value.clone()]);
    }
    let mut extra = String::new();
    for name in ["Referer", "Origin"] {
        if let Some(value) = values.get(&name.to_lowercase()) {
            extra.push_str(&format!("{name}: {value}\r\n"));
        }
    }
    if !extra.is_empty() {
        args.extend(["-headers".into(), extra]);
    }
    Ok(args)
}
pub fn pick_audio(streams: &[Value], wanted: Option<u64>) -> Option<u64> {
    if let Some(wanted) = wanted {
        for stream in streams {
            if let Some(language) = stream["tags"]["language"].as_str() {
                let prefix = language.trim_end_matches(|c: char| c.is_ascii_digit());
                let suffix = &language[prefix.len()..];
                if (2..=3).contains(&prefix.len())
                    && prefix.bytes().all(|b| b.is_ascii_alphabetic())
                    && (1..=2).contains(&suffix.len())
                    && suffix.parse::<u64>().ok() == Some(wanted)
                {
                    return stream["index"].as_u64();
                }
            }
        }
        if let Some(stream) = streams.get(wanted as usize) {
            return stream["index"].as_u64();
        }
    }
    streams.first().and_then(|stream| stream["index"].as_u64())
}
fn client() -> &'static reqwest::Client {
    static CLIENT: std::sync::LazyLock<reqwest::Client> = std::sync::LazyLock::new(|| {
        reqwest::Client::builder()
            .no_proxy()
            .redirect(reqwest::redirect::Policy::none())
            .timeout(Duration::from_secs(40))
            .build()
            .expect("Local HTTP client")
    });
    &CLIENT
}
async fn control(value: &Value) -> Option<Result<Value>> {
    let bytes = tokio::fs::read(paths::cache().join("control.json"))
        .await
        .ok()?;
    let endpoint: Endpoint = serde_json::from_slice(&bytes).ok()?;
    if endpoint.token.len() != 64 || endpoint.port == 0 {
        return None;
    }
    let response = client()
        .post(format!("http://127.0.0.1:{}/command", endpoint.port))
        .bearer_auth(&endpoint.token)
        .json(value)
        .send()
        .await
        .ok()?;
    let body: Value = response.json().await.ok()?;
    Some(if body["ok"] == true {
        Ok(body)
    } else {
        Err(error(
            body["error"].as_str().unwrap_or("Video conversion failed"),
        ))
    })
}
pub async fn request(value: Value, start: bool) -> Result<Value> {
    if let Some(result) = control(&value).await {
        return result;
    }
    if !start {
        return Ok(json!({"ok": true, "active": false}));
    }
    let cache = paths::cache();
    paths::private_dir(&cache)?;
    let lock_path = cache.join("launch.lock");
    let _lock = tokio::task::spawn_blocking(move || -> std::io::Result<std::fs::File> {
        let file = OpenOptions::new()
            .create(true)
            .truncate(false)
            .read(true)
            .write(true)
            .open(lock_path)?;
        file.lock_exclusive()?;
        Ok(file)
    })
    .await
    .map_err(|_| error("Couldn’t start the video converter"))??;
    if let Some(result) = control(&value).await {
        return result;
    }
    let executable = std::env::current_exe()?;
    let mut command = std::process::Command::new(executable);
    command
        .arg("--relay")
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    #[cfg(unix)]
    {
        use std::os::unix::process::CommandExt;
        command.process_group(0);
    }
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x08000000);
    }
    let mut child = command.spawn()?;
    std::thread::spawn(move || {
        let _ = child.wait();
    });
    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        if let Some(result) = control(&value).await {
            return result;
        }
        if Instant::now() > deadline {
            return Err(error("The local video converter could not start"));
        }
        tokio::time::sleep(Duration::from_millis(30)).await;
    }
}

struct Encoding {
    process: Child,
    directory: PathBuf,
}
struct Job {
    tv: IpAddr,
    token: String,
    source_codec: String,
    duration: f64,
    url: String,
    input: Vec<String>,
    video: u64,
    audio: Option<u64>,
    encoder: String,
    ffmpeg: PathBuf,
    directory: tempfile::TempDir,
    blocks: Mutex<BTreeMap<u32, Encoding>>,
    closed: AtomicBool,
    last_use: std::sync::Mutex<Instant>,
    failure: std::sync::Mutex<String>,
    diagnostics: Arc<std::sync::Mutex<VecDeque<String>>>,
    server: Mutex<Option<tokio::task::JoinHandle<()>>>,
    sleep_guard: Mutex<Option<Child>>,
}
impl Job {
    async fn new(value: &Value) -> Result<Arc<Self>> {
        let url = validate::url(&value["url"], 24000)?;
        let tv: IpAddr = value["tv"]
            .as_str()
            .unwrap_or_default()
            .parse()
            .map_err(|_| error("Invalid TV address"))?;
        let headers = value.get("headers").cloned().unwrap_or(json!({}));
        let input = input_options(&headers, value["mime"].as_str().unwrap_or_default())?;
        let mut probe = Command::new(executable("ffprobe")?);
        probe.args(["-v", "error"]).args(&input).arg("-i").arg(&url)
            .args(["-show_entries", "stream=index,codec_type,codec_name,width,height:stream_tags=language:format=duration", "-of", "json"])
            .stdin(Stdio::null()).kill_on_drop(true);
        hide_window(&mut probe);
        let output = tokio::time::timeout(Duration::from_secs(28), probe.output())
            .await
            .map_err(|_| {
                error("Reading this video took too long. Reload the video and cast a fresh URL.")
            })?
            .map_err(|_| error("Could not read this stream for conversion"))?;
        if !output.status.success() || output.stdout.len() > 4 * 1024 * 1024 {
            return Err(error(
                "Could not read this stream for conversion. Reload the video and cast a fresh URL.",
            ));
        }
        let metadata: Value = serde_json::from_slice(&output.stdout)
            .map_err(|_| error("Could not read the video’s format"))?;
        let duration = metadata["format"]["duration"]
            .as_str()
            .and_then(|n| n.parse::<f64>().ok())
            .or_else(|| metadata["format"]["duration"].as_f64())
            .unwrap_or(f64::NAN);
        if !duration.is_finite() || duration <= 0. || duration > 86400. {
            return Err(error(
                "Conversion currently supports videos with a known duration up to 24 hours",
            ));
        }
        let streams = metadata["streams"]
            .as_array()
            .ok_or_else(|| error("This source contains no video track to convert"))?;
        let videos: Vec<_> = streams
            .iter()
            .filter(|s| s["codec_type"] == "video")
            .collect();
        let area = |s: &&Value| {
            s["width"]
                .as_u64()
                .unwrap_or(0)
                .saturating_mul(s["height"].as_u64().unwrap_or(0))
        };
        let video = videos
            .iter()
            .copied()
            .filter(|s| s["height"].as_u64().unwrap_or(u64::MAX) <= 720)
            .max_by_key(area)
            .or_else(|| videos.iter().copied().min_by_key(area))
            .ok_or_else(|| error("This source contains no video track to convert"))?;
        let audio = pick_audio(
            &streams
                .iter()
                .filter(|s| s["codec_type"] == "audio")
                .cloned()
                .collect::<Vec<_>>(),
            value["audio"].as_u64(),
        );
        let cache = paths::cache();
        paths::private_dir(&cache)?;
        let directory = tempfile::Builder::new()
            .prefix("session-")
            .tempdir_in(cache)?;
        let encoder = if value["encoder"] == "x264" || !cfg!(target_os = "macos") {
            "libx264"
        } else {
            "h264_videotoolbox"
        }
        .into();
        let sleep_guard = sleep_guard();
        Ok(Arc::new(Self {
            tv,
            token: token(),
            source_codec: video["codec_name"].as_str().unwrap_or("unknown").into(),
            duration,
            url,
            input,
            video: video["index"]
                .as_u64()
                .ok_or_else(|| error("Invalid video track"))?,
            audio,
            encoder,
            ffmpeg: executable("ffmpeg")?,
            directory,
            blocks: Mutex::new(BTreeMap::new()),
            closed: AtomicBool::new(false),
            last_use: std::sync::Mutex::new(Instant::now()),
            failure: std::sync::Mutex::new(String::new()),
            diagnostics: Arc::default(),
            server: Mutex::new(None),
            sleep_guard: Mutex::new(sleep_guard),
        }))
    }
    fn playlist(&self) -> String {
        let mut text = String::from(
            "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-INDEPENDENT-SEGMENTS\n",
        );
        for index in 0..(self.duration / SEGMENT as f64).ceil() as u32 {
            text.push_str(&format!(
                "#EXTINF:{:.6},\n{index}.ts\n",
                (self.duration - (index * SEGMENT) as f64).min(SEGMENT as f64)
            ));
        }
        text.push_str("#EXT-X-ENDLIST\n");
        text
    }
    async fn encode(&self, first: u32) -> Result<Encoding> {
        let directory = self.directory.path().join(first.to_string());
        tokio::fs::create_dir_all(&directory).await?;
        let start = first * SEGMENT;
        let mut command = Command::new(&self.ffmpeg);
        command
            .args(["-hide_banner", "-loglevel", "error", "-nostdin", "-y"])
            .args(&self.input)
            .arg("-ss")
            .arg(start.to_string())
            .arg("-i")
            .arg(&self.url)
            .arg("-t")
            .arg(
                ((BLOCK * SEGMENT) as f64)
                    .min(self.duration - start as f64)
                    .to_string(),
            )
            .arg("-map")
            .arg(format!("0:{}", self.video));
        if let Some(audio) = self.audio {
            command.arg("-map").arg(format!("0:{audio}"));
        }
        // Cap conversion at 720p without enlarging smaller sources. Upscaling adds
        // encoding work and network traffic without recovering any picture detail.
        command.args(["-sn", "-dn", "-vf", "scale=w='min(1280,iw)':h='min(720,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2,fps=30,format=yuv420p", "-c:v", &self.encoder]);
        if self.encoder == "libx264" {
            command.args(["-preset", "veryfast"]);
        }
        command
            .args([
                "-profile:v",
                "main",
                "-level",
                "4.0",
                "-b:v",
                "3000k",
                "-maxrate",
                "4000k",
                "-bufsize",
                "8000k",
                "-g",
                "120",
                "-force_key_frames",
                "expr:gte(t,n_forced*4)",
                "-c:a",
                "aac",
                "-ac",
                "2",
                "-ar",
                "48000",
                "-b:a",
                "128k",
                "-output_ts_offset",
            ])
            .arg(start.to_string())
            .args([
                "-f",
                "hls",
                "-hls_time",
                "4",
                "-hls_list_size",
                "0",
                "-hls_playlist_type",
                "vod",
                "-hls_flags",
                "independent_segments+temp_file",
                "-start_number",
            ])
            .arg(first.to_string())
            .arg("-hls_segment_filename")
            .arg(directory.join("%d.ts"))
            .arg(directory.join("window.m3u8"))
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::piped())
            .kill_on_drop(true);
        hide_window(&mut command);
        let mut process = command
            .spawn()
            .map_err(|_| error("The video encoder could not start"))?;
        if let Some(stderr) = process.stderr.take() {
            let diagnostics = self.diagnostics.clone();
            tokio::spawn(async move {
                // Bounded reads prevent a malicious stream from growing an unlimited diagnostic line.
                use tokio::io::AsyncReadExt;
                let mut stream = BufReader::new(stderr).take(64 * 1024);
                let mut bytes = Vec::new();
                if stream.read_to_end(&mut bytes).await.is_ok() {
                    for line in String::from_utf8_lossy(&bytes).lines() {
                        if !line.to_lowercase().contains("http")
                            && !line
                                .split(|c: char| !c.is_ascii_alphanumeric() && c != '_' && c != '-')
                                .any(|word| word.len() >= 40)
                        {
                            let mut values = diagnostics.lock().unwrap();
                            values.push_back(line.chars().take(300).collect());
                            if values.len() > 6 {
                                values.pop_front();
                            }
                        }
                    }
                }
            });
        }
        Ok(Encoding { process, directory })
    }
    async fn segment(&self, index: u32) -> Result<Vec<u8>> {
        if index as f64 >= (self.duration / SEGMENT as f64).ceil() {
            return Err(error("Invalid video segment"));
        }
        *self.last_use.lock().unwrap() = Instant::now();
        let first = index / BLOCK * BLOCK;
        let target = self
            .directory
            .path()
            .join(first.to_string())
            .join(format!("{index}.ts"));
        let deadline = Instant::now() + Duration::from_secs(55);
        loop {
            if self.closed.load(Ordering::Acquire) {
                return Err(error("Playback has stopped"));
            }
            if let Ok(metadata) = tokio::fs::metadata(&target).await {
                if metadata.len() > MAX_SEGMENT {
                    return Err(error("Converted video segment is too large"));
                }
                return tokio::fs::read(&target).await.map_err(Into::into);
            }
            {
                let mut blocks = self.blocks.lock().await;
                if self.closed.load(Ordering::Acquire) {
                    return Err(error("Playback has stopped"));
                }
                if !blocks.contains_key(&first) {
                    let mut active = Vec::new();
                    for (number, encoding) in blocks.iter_mut() {
                        if encoding.process.try_wait()?.is_none() {
                            active.push(*number);
                        }
                    }
                    while active.len() >= 2 {
                        if let Some(mut old) = blocks.remove(&active.remove(0)) {
                            let _ = old.process.kill().await;
                        }
                    }
                    blocks.insert(first, self.encode(first).await?);
                    let mut completed = Vec::new();
                    for (number, encoding) in blocks.iter_mut() {
                        if *number != first && encoding.process.try_wait()?.is_some() {
                            completed.push(*number);
                        }
                    }
                    while blocks.len() > 6 && !completed.is_empty() {
                        if let Some(old) = blocks.remove(&completed.remove(0)) {
                            let _ = tokio::fs::remove_dir_all(old.directory).await;
                        }
                    }
                }
                if let Some(status) = blocks.get_mut(&first).unwrap().process.try_wait()? {
                    // The final rename may have raced with our metadata check above.
                    if !target.exists() {
                        let message =
                            format!("Could not convert this part of the video (FFmpeg {status})");
                        *self.failure.lock().unwrap() = message.clone();
                        return Err(error(message));
                    }
                }
            }
            if Instant::now() > deadline {
                return Err(error("Converting this part of the video took too long"));
            }
            tokio::time::sleep(Duration::from_millis(30)).await;
        }
    }
    async fn close(&self) {
        self.closed.store(true, Ordering::Release);
        if let Some(server) = self.server.lock().await.take() {
            server.abort();
            let _ = server.await;
        }
        let mut blocks = self.blocks.lock().await;
        for encoding in blocks.values_mut() {
            let _ = encoding.process.kill().await;
        }
        blocks.clear();
        if let Some(mut guard) = self.sleep_guard.lock().await.take() {
            let _ = guard.kill().await;
        }
        let _ = tokio::fs::remove_dir_all(self.directory.path()).await;
    }
}
#[derive(Clone)]
struct Relay {
    token: String,
    job: Arc<Mutex<Option<Arc<Job>>>>,
    lifecycle: Arc<Mutex<()>>,
    last_control: Arc<std::sync::Mutex<Instant>>,
}
impl Relay {
    async fn stop(&self) {
        if let Some(job) = self.job.lock().await.take() {
            job.close().await;
        }
    }
    async fn command(&self, value: Value) -> Result<Value> {
        match value["action"].as_str().unwrap_or_default() {
            "start" => {
                let _guard = self.lifecycle.lock().await;
                self.stop().await;
                let job = Job::new(&value).await?;
                let route = tokio::net::UdpSocket::bind(if job.tv.is_ipv4() {
                    "0.0.0.0:0"
                } else {
                    "[::]:0"
                })
                .await?;
                route.connect(SocketAddr::new(job.tv, 8765)).await?;
                let address = route.local_addr()?.ip();
                let listener = TcpListener::bind(SocketAddr::new(address, 0)).await?;
                let port = listener.local_addr()?.port();
                let advertised = value["advertise"]
                    .as_str()
                    .filter(|s| !s.is_empty())
                    .and_then(|s| s.parse::<IpAddr>().ok())
                    .unwrap_or(address);
                let url = format!(
                    "http://{}/{}/video.m3u8",
                    SocketAddr::new(advertised, port),
                    job.token
                );
                let app = Router::new()
                    .route("/{token}/{file}", get(video))
                    .with_state(job.clone());
                *job.server.lock().await = Some(tokio::spawn(async move {
                    let _ = axum::serve(
                        listener,
                        app.into_make_service_with_connect_info::<SocketAddr>(),
                    )
                    .await;
                }));
                let codec = job.source_codec.clone();
                *self.job.lock().await = Some(job);
                Ok(json!({"ok": true, "url": url, "sourceCodec": codec}))
            }
            "stop" => {
                let _guard = self.lifecycle.lock().await;
                self.stop().await;
                Ok(json!({"ok": true, "active": false}))
            }
            "status" => {
                let job = self.job.lock().await;
                Ok(match &*job {
                    Some(job) => {
                        json!({"ok": true, "active": true, "mode": "mac-h264", "sourceCodec": job.source_codec, "error": *job.failure.lock().unwrap(), "diagnostics": *job.diagnostics.lock().unwrap()})
                    }
                    None => {
                        json!({"ok": true, "active": false, "mode": "direct", "sourceCodec": "", "error": "", "diagnostics": []})
                    }
                })
            }
            _ => Err(error("Unknown conversion command")),
        }
    }
}
async fn control_handler(
    State(relay): State<Relay>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Json(value): Json<Value>,
) -> (StatusCode, Json<Value>) {
    let expected = format!("Bearer {}", relay.token);
    let authorized = headers
        .get(header::AUTHORIZATION)
        .map(|v| v.as_bytes().ct_eq(expected.as_bytes()).into())
        .unwrap_or(false);
    if !peer.ip().is_loopback() || !authorized {
        return (
            StatusCode::FORBIDDEN,
            Json(json!({"ok": false, "error": "Forbidden"})),
        );
    }
    *relay.last_control.lock().unwrap() = Instant::now();
    let value = relay
        .command(value)
        .await
        .unwrap_or_else(|problem| json!({"ok": false, "error": problem.message}));
    (StatusCode::OK, Json(value))
}
async fn video(
    State(job): State<Arc<Job>>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    Path((token, file)): Path<(String, String)>,
    method: axum::http::Method,
) -> Response {
    if peer.ip() != job.tv || job.closed.load(Ordering::Acquire) {
        return response(StatusCode::FORBIDDEN, "text/plain", Vec::new(), false);
    }
    if !bool::from(token.as_bytes().ct_eq(job.token.as_bytes())) {
        return response(StatusCode::NOT_FOUND, "text/plain", Vec::new(), false);
    }
    *job.last_use.lock().unwrap() = Instant::now();
    let (body, mime) = if file == "video.m3u8" {
        (
            Ok(job.playlist().into_bytes()),
            "application/vnd.apple.mpegurl",
        )
    } else if let Some(index) = file
        .strip_suffix(".ts")
        .filter(|n| !n.is_empty() && n.len() <= 6 && n.bytes().all(|b| b.is_ascii_digit()))
        .and_then(|n| n.parse::<u32>().ok())
    {
        (job.segment(index).await, "video/mp2t")
    } else {
        return response(StatusCode::NOT_FOUND, "text/plain", Vec::new(), false);
    };
    match body {
        Ok(body) => response(
            StatusCode::OK,
            mime,
            body,
            method == axum::http::Method::HEAD,
        ),
        Err(_) => response(
            StatusCode::BAD_GATEWAY,
            "text/plain",
            b"Video conversion failed".to_vec(),
            method == axum::http::Method::HEAD,
        ),
    }
}
fn response(status: StatusCode, mime: &str, body: Vec<u8>, head: bool) -> Response {
    Response::builder()
        .status(status)
        .header(header::CONTENT_TYPE, mime)
        .header(header::CONTENT_LENGTH, body.len())
        .header(header::CACHE_CONTROL, "no-store")
        .body(if head {
            Body::empty()
        } else {
            Body::from(body)
        })
        .expect("HTTP response")
}
pub async fn serve() -> anyhow::Result<()> {
    let cache = paths::cache();
    paths::private_dir(&cache)?;
    // A separate lifetime lock prevents two daemons from deleting each other’s descriptors or caches.
    let lifetime = OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .open(cache.join("daemon.lock"))?;
    if lifetime.try_lock_exclusive().is_err() {
        return Ok(());
    }
    for entry in std::fs::read_dir(&cache)? {
        let entry = entry?;
        if entry.file_name().to_string_lossy().starts_with("session-")
            && entry.file_type()?.is_dir()
        {
            let _ = std::fs::remove_dir_all(entry.path());
        }
    }
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await?;
    let endpoint = Endpoint {
        port: listener.local_addr()?.port(),
        token: token(),
    };
    let path = cache.join("control.json");
    let mut temp = tempfile::NamedTempFile::new_in(&cache)?;
    serde_json::to_writer(&mut temp, &endpoint)?;
    paths::private_file(temp.path())?;
    temp.persist(&path)?;
    let relay = Relay {
        token: endpoint.token,
        job: Arc::default(),
        lifecycle: Arc::default(),
        last_control: Arc::new(std::sync::Mutex::new(Instant::now())),
    };
    let app = Router::new()
        .route("/command", post(control_handler))
        .layer(axum::extract::DefaultBodyLimit::max(4 * 1024 * 1024))
        .with_state(relay.clone());
    let server = axum::serve(
        listener,
        app.into_make_service_with_connect_info::<SocketAddr>(),
    );
    let idle = async {
        loop {
            tokio::time::sleep(Duration::from_secs(10)).await;
            let _guard = relay.lifecycle.lock().await;
            let expired = relay.job.lock().await.as_ref().is_some_and(|job| {
                job.last_use.lock().unwrap().elapsed() > Duration::from_secs(1800)
            });
            if expired {
                relay.stop().await;
            }
            if relay.job.lock().await.is_none()
                && relay.last_control.lock().unwrap().elapsed() > Duration::from_secs(60)
            {
                break;
            }
        }
    };
    tokio::select! { _ = server => {}, _ = idle => {}, _ = tokio::signal::ctrl_c() => {} }
    relay.stop().await;
    let _ = std::fs::remove_file(path);
    Ok(())
}
fn hide_window(command: &mut Command) {
    #[cfg(windows)]
    {
        command.creation_flags(0x08000000);
    }
    #[cfg(not(windows))]
    let _ = command;
}
fn sleep_guard() -> Option<Child> {
    #[cfg(target_os = "macos")]
    {
        Command::new("/usr/bin/caffeinate")
            .args(["-i", "-w", &std::process::id().to_string()])
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .kill_on_drop(true)
            .spawn()
            .ok()
    }
    #[cfg(target_os = "linux")]
    {
        Command::new("systemd-inhibit")
            .args([
                "--what=sleep",
                "--mode=block",
                "--who=Daniil Cast",
                "--why=Video playback",
                "sleep",
                "infinity",
            ])
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .kill_on_drop(true)
            .spawn()
            .ok()
    }
    #[cfg(not(any(target_os = "macos", target_os = "linux")))]
    {
        None
    }
}
