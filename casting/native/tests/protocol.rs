use axum::{
    Json, Router,
    body::to_bytes,
    extract::{Request, State},
    http::StatusCode,
    routing::any,
};
use serde_json::{Value, json};
use std::{process::Stdio, sync::Arc, time::Duration};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    process::{Child, ChildStdin, ChildStdout, Command},
    sync::Mutex,
};

#[derive(Clone, Default)]
struct Tv {
    commands: Arc<Mutex<Vec<Value>>>,
    status: Arc<Mutex<Value>>,
}
async fn handler(State(tv): State<Tv>, request: Request) -> (StatusCode, Json<Value>) {
    let path = request.uri().path().to_owned();
    if !matches!(path.as_str(), "/v1/info" | "/v1/pair" | "/v1/pair/request")
        && request
            .headers()
            .get("Authorization")
            .and_then(|h| h.to_str().ok())
            != Some("Bearer mock-token")
    {
        return (
            StatusCode::UNAUTHORIZED,
            Json(json!({"error": {"code": "unauthorized", "message": "Unauthorized"}})),
        );
    }
    let body: Value = serde_json::from_slice(
        &to_bytes(request.into_body(), 4 * 1024 * 1024)
            .await
            .unwrap(),
    )
    .unwrap_or(json!({}));
    let result = match path.as_str() {
        "/v1/info" => {
            json!({"app": "Daniil Cast", "id": "test-tv", "name": "Mock TV", "model": "AFTSS", "version": "0.3.0", "decoders": {"video": ["H.264", "VP9", "HEVC"]}})
        }
        "/v1/pair/request" => json!({"ok": true}),
        "/v1/pair" => {
            if body["code"] != "123456" {
                return (
                    StatusCode::FORBIDDEN,
                    Json(json!({"error": {"code": "wrong-code", "message": "Wrong code"}})),
                );
            }
            json!({"token": "mock-token", "tv": {"id": "test-tv", "name": "Mock TV", "model": "AFTSS"}})
        }
        "/v1/status" => tv.status.lock().await.clone(),
        "/v1/command" => {
            tv.commands.lock().await.push(body.clone());
            if body["action"] == "load" {
                if body["title"] == "Slow" {
                    tokio::time::sleep(Duration::from_millis(800)).await;
                }
                *tv.status.lock().await = json!({"phase": "playing", "renderedVideo": true, "position": body["position"], "title": body["title"], "tracks": [{"type": 2}]});
            }
            json!({"ok": true})
        }
        "/v1/unpair" => json!({"ok": true}),
        _ => return (StatusCode::NOT_FOUND, Json(json!({}))),
    };
    (StatusCode::OK, Json(result))
}
struct Session {
    child: Child,
    input: Option<ChildStdin>,
    output: ChildStdout,
    _home: tempfile::TempDir,
}
impl Session {
    async fn new() -> Self {
        let home = tempfile::tempdir().unwrap();
        let mut child = Command::new(env!("CARGO_BIN_EXE_daniil-cast"))
            .arg("native")
            .env("DANIILCAST_HOME", home.path().join("data"))
            .env("DANIILCAST_LOG", home.path().join("logs/host.log"))
            .env("DANIILCAST_CACHE", home.path().join("cache"))
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true)
            .spawn()
            .unwrap();
        let input = child.stdin.take();
        let output = child.stdout.take().unwrap();
        Self {
            child,
            input,
            output,
            _home: home,
        }
    }
    async fn send(&mut self, value: Value) {
        let bytes = serde_json::to_vec(&value).unwrap();
        let input = self.input.as_mut().unwrap();
        input
            .write_all(&(bytes.len() as u32).to_ne_bytes())
            .await
            .unwrap();
        input.write_all(&bytes).await.unwrap();
        input.flush().await.unwrap();
    }
    async fn receive(&mut self) -> Value {
        tokio::time::timeout(Duration::from_secs(5), async {
            let mut size = [0; 4];
            self.output.read_exact(&mut size).await.unwrap();
            let mut bytes = vec![0; u32::from_ne_bytes(size) as usize];
            self.output.read_exact(&mut bytes).await.unwrap();
            serde_json::from_slice(&bytes).unwrap()
        })
        .await
        .expect("native host response timed out")
    }
    async fn request(&mut self, value: Value) -> Value {
        self.send(value).await;
        self.receive().await
    }
    async fn finish(mut self) {
        drop(self.input.take());
        assert!(
            tokio::time::timeout(Duration::from_secs(3), self.child.wait())
                .await
                .unwrap()
                .unwrap()
                .success()
        );
    }
}
#[tokio::test]
async fn chrome_to_helper_to_tv_preserves_pairing_playback_and_controls() {
    let tv = Tv::default();
    *tv.status.lock().await = json!({"phase": "idle"});
    let socket = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = socket.local_addr().unwrap().port();
    let app = Router::new()
        .route("/{*path}", any(handler))
        .with_state(tv.clone());
    let server = tokio::spawn(async move {
        axum::serve(socket, app).await.unwrap();
    });
    let mut session = Session::new().await;
    assert_eq!(
        session
            .request(json!({"action": "hello", "request": 1}))
            .await["request"],
        1
    );
    assert_eq!(
        session.request(json!({"action": "status"})).await["error"]["code"],
        "no-tv"
    );
    assert_eq!(
        session
            .request(json!({"action": "pairRequest", "address": "127.0.0.1", "port": port}))
            .await["ok"],
        true
    );
    assert_eq!(
        session
            .request(
                json!({"action": "pair", "address": "127.0.0.1", "port": port, "code": "000000"})
            )
            .await["error"]["code"],
        "wrong-code"
    );
    assert_eq!(
        session
            .request(
                json!({"action": "pair", "address": "127.0.0.1", "port": port, "code": "123456"})
            )
            .await["ok"],
        true
    );
    let hello = session.request(json!({"action": "hello"})).await;
    assert_eq!(hello["devices"][0]["selected"], true);
    assert!(!hello.to_string().contains("mock-token"));
    let status = session.request(json!({"action": "status"})).await;
    assert_eq!(status["online"], true);
    let load = session.request(json!({"action": "load", "url": "https://example.org/video", "codec": "H.264", "position": 754000, "title": "Episode 3"})).await;
    assert_eq!(load["state"], "playing");
    assert_eq!(load["route"], "direct");
    for command in [
        json!({"action": "pause"}),
        json!({"action": "play"}),
        json!({"action": "seek", "position": 10000}),
        json!({"action": "audio", "track": "1.2"}),
        json!({"action": "subtitles", "track": "off", "color": "yellow"}),
        json!({"action": "quality", "id": "720"}),
        json!({"action": "speed", "value": 1.5}),
        json!({"action": "episode", "id": "s2e3"}),
        json!({"action": "next"}),
        json!({"action": "previous"}),
        json!({"action": "stop"}),
    ] {
        assert_eq!(session.request(command).await["ok"], true);
    }
    assert_eq!(tv.commands.lock().await.len(), 12);
    let log = session.request(json!({"action": "log"})).await;
    assert!(log["entries"].as_array().unwrap().len() >= 2);
    assert!(!log.to_string().contains("mock-token"));
    assert_eq!(
        session
            .request(json!({"action": "forget", "id": "test-tv"}))
            .await["devices"],
        json!([])
    );
    session.finish().await;
    server.abort();
}
#[tokio::test]
async fn slow_cast_does_not_block_other_native_requests() {
    let tv = Tv::default();
    *tv.status.lock().await = json!({"phase": "idle"});
    let socket = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = socket.local_addr().unwrap().port();
    let server = tokio::spawn(async move {
        axum::serve(
            socket,
            Router::new().route("/{*path}", any(handler)).with_state(tv),
        )
        .await
        .unwrap();
    });
    let mut session = Session::new().await;
    session
        .request(json!({"action": "pair", "address": "127.0.0.1", "port": port, "code": "123456"}))
        .await;
    session.send(json!({"request": 100, "action": "load", "url": "https://example.org/video", "codec": "H.264", "title": "Slow"})).await;
    tokio::time::sleep(Duration::from_millis(50)).await;
    let start = std::time::Instant::now();
    session
        .send(json!({"request": 101, "action": "hello"}))
        .await;
    assert_eq!(session.receive().await["request"], 101);
    assert!(start.elapsed() < Duration::from_millis(500));
    assert_eq!(session.receive().await["request"], 100);
    session.finish().await;
    server.abort();
}
#[tokio::test]
async fn malformed_native_frame_produces_a_framed_error_and_exits() {
    let mut session = Session::new().await;
    session
        .input
        .as_mut()
        .unwrap()
        .write_all(&0u32.to_ne_bytes())
        .await
        .unwrap();
    assert_eq!(session.receive().await["error"]["code"], "invalid");
    session.finish().await;
}
