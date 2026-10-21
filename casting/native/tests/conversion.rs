use axum::{
    Router,
    body::Body,
    extract::State,
    http::{HeaderMap, StatusCode, header},
    response::Response,
    routing::get,
};
use daniil_cast::relay;
use serde_json::{Value, json};
use std::{process::Stdio, sync::Arc, time::Duration};
use tokio::process::Command;

async fn source(State(bytes): State<Arc<Vec<u8>>>, headers: HeaderMap) -> Response {
    let range = headers
        .get(header::RANGE)
        .and_then(|h| h.to_str().ok())
        .and_then(|h| h.strip_prefix("bytes="));
    let (start, end, partial) = match range {
        Some(range) => {
            let (start, end) = range.split_once('-').unwrap();
            (
                start.parse::<usize>().unwrap_or(0),
                end.parse::<usize>()
                    .unwrap_or(bytes.len() - 1)
                    .min(bytes.len() - 1),
                true,
            )
        }
        None => (0, bytes.len() - 1, false),
    };
    if start > end {
        return Response::builder().status(416).body(Body::empty()).unwrap();
    }
    let mut builder = Response::builder()
        .status(if partial {
            StatusCode::PARTIAL_CONTENT
        } else {
            StatusCode::OK
        })
        .header(header::CONTENT_TYPE, "video/x-matroska")
        .header(header::ACCEPT_RANGES, "bytes")
        .header(header::CONTENT_LENGTH, end - start + 1);
    if partial {
        builder = builder.header(
            header::CONTENT_RANGE,
            format!("bytes {start}-{end}/{}", bytes.len()),
        );
    }
    builder
        .body(Body::from(bytes[start..=end].to_vec()))
        .unwrap()
}
#[tokio::test]
#[ignore = "requires FFmpeg with AV1 decoding, libaom-av1, libopus and libx264"]
async fn real_av1_conversion_seeking_authentication_and_cleanup() {
    let temp = tempfile::tempdir().unwrap();
    let input = temp.path().join("source.mkv");
    let mut generator = Command::new(relay::executable("ffmpeg").unwrap());
    generator
        .args([
            "-hide_banner",
            "-loglevel",
            "error",
            "-f",
            "lavfi",
            "-i",
            "color=c=blue:s=160x90:r=2:d=88",
            "-f",
            "lavfi",
            "-i",
            "sine=frequency=440:duration=88",
            "-f",
            "lavfi",
            "-i",
            "sine=frequency=880:duration=88",
            "-map",
            "0:v",
            "-map",
            "1:a",
            "-map",
            "2:a",
            "-c:v",
            "libaom-av1",
            "-cpu-used",
            "8",
            "-crf",
            "50",
            "-b:v",
            "0",
            "-threads",
            "2",
            "-c:a",
            "libopus",
            "-metadata:s:a:0",
            "language=rus0",
            "-metadata:s:a:1",
            "language=eng2",
        ])
        .arg(&input)
        .kill_on_drop(true);
    let output = tokio::time::timeout(Duration::from_secs(30), generator.output())
        .await
        .expect("FFmpeg fixture generator did not start or finish")
        .unwrap();
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
    let bytes = Arc::new(std::fs::read(input).unwrap());
    let socket = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = socket.local_addr().unwrap();
    let source_server = tokio::spawn(async move {
        axum::serve(
            socket,
            Router::new()
                .route("/source", get(source))
                .with_state(bytes),
        )
        .await
        .unwrap();
    });
    let cache = temp.path().join("cache");
    let mut daemon = Command::new(env!("CARGO_BIN_EXE_daniil-cast"))
        .arg("--relay")
        .env("DANIILCAST_CACHE", &cache)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::piped())
        .kill_on_drop(true)
        .spawn()
        .unwrap();
    let deadline = std::time::Instant::now() + Duration::from_secs(5);
    let endpoint: Value = loop {
        if let Ok(bytes) = tokio::fs::read(cache.join("control.json")).await {
            if let Ok(value) = serde_json::from_slice(&bytes) {
                break value;
            }
        }
        assert!(std::time::Instant::now() < deadline, "relay did not start");
        tokio::time::sleep(Duration::from_millis(20)).await;
    };
    let control = format!("http://127.0.0.1:{}/command", endpoint["port"]);
    let token = endpoint["token"].as_str().unwrap();
    let client = reqwest::Client::builder()
        .no_proxy()
        .timeout(Duration::from_secs(60))
        .build()
        .unwrap();
    assert_eq!(
        client
            .post(&control)
            .json(&json!({"action": "status"}))
            .send()
            .await
            .unwrap()
            .status(),
        403
    );
    let start: Value = client.post(&control).bearer_auth(token).json(&json!({"action": "start", "url": format!("http://{address}/source"), "tv": "127.0.0.1", "encoder": "x264", "headers": {"User-Agent": "Chrome Fixture"}, "audio": 2})).send().await.unwrap().json().await.unwrap();
    assert_eq!(start["ok"], true, "{start}");
    assert_eq!(start["sourceCodec"], "av1");
    let url = start["url"].as_str().unwrap();
    let base = url.strip_suffix("video.m3u8").unwrap();
    let playlist = client.get(url).send().await.unwrap().text().await.unwrap();
    assert!(playlist.contains("#EXT-X-ENDLIST"));
    assert!(playlist.contains("21.ts"));
    assert_eq!(
        client
            .get(url.replace(base.split('/').nth(3).unwrap(), "wrong-token"))
            .send()
            .await
            .unwrap()
            .status(),
        404
    );
    let first = client.get(format!("{base}0.ts")).send().await.unwrap();
    assert_eq!(first.status(), 200);
    let bytes = first.bytes().await.unwrap();
    assert!(!bytes.is_empty());
    let segment = temp.path().join("converted.ts");
    std::fs::write(&segment, &bytes).unwrap();
    let metadata = Command::new(relay::executable("ffprobe").unwrap())
        .args([
            "-v",
            "error",
            "-show_entries",
            "stream=codec_name,channels,width,height",
            "-of",
            "json",
        ])
        .arg(segment)
        .output()
        .await
        .unwrap();
    let metadata: Value = serde_json::from_slice(&metadata.stdout).unwrap();
    assert!(
        metadata["streams"]
            .as_array()
            .unwrap()
            .iter()
            .any(|s| s["codec_name"] == "h264" && s["width"] == 160 && s["height"] == 90),
        "conversion must preserve small source dimensions: {metadata}"
    );
    assert!(
        metadata["streams"]
            .as_array()
            .unwrap()
            .iter()
            .any(|s| s["codec_name"] == "aac" && s["channels"] == 2)
    );
    let seek = client.get(format!("{base}21.ts")).send().await.unwrap();
    assert_eq!(seek.status(), 200);
    assert!(!seek.bytes().await.unwrap().is_empty());
    let head = client.head(url).send().await.unwrap();
    assert_eq!(head.status(), 200);
    assert!(head.bytes().await.unwrap().is_empty());
    let stop: Value = client
        .post(&control)
        .bearer_auth(token)
        .json(&json!({"action": "stop"}))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(stop["active"], false);
    assert!(!std::fs::read_dir(&cache).unwrap().any(|e| {
        e.unwrap()
            .file_name()
            .to_string_lossy()
            .starts_with("session-")
    }));
    assert!(client.get(url).send().await.is_err());
    daemon.kill().await.unwrap();
    source_server.abort();
}
