use daniil_cast::{
    devices::Devices,
    journal::{Journal, redact},
    native::{self, MAX_INPUT, MAX_OUTPUT},
    relay, validate,
};
use serde_json::{Value, json};
use std::{io::Cursor, sync::Arc};

#[test]
fn native_framing_handles_fragmentation_and_rejects_bad_lengths() {
    let message = json!({"action": "hello", "request": 7, "title": "Українська"});
    let mut bytes = Vec::new();
    native::write_frame(&mut bytes, &message).unwrap();
    assert_eq!(
        native::read_frame(&mut Cursor::new(&bytes)).unwrap(),
        Some(message)
    );
    assert!(native::read_frame(&mut Cursor::new([])).unwrap().is_none());
    for bytes in [
        vec![1],
        vec![0, 0, 0, 0],
        ((MAX_INPUT + 1) as u32).to_ne_bytes().to_vec(),
        vec![8, 0, 0, 0, b'{'],
    ] {
        assert!(native::read_frame(&mut Cursor::new(bytes)).is_err());
    }
    let mut output = Vec::new();
    native::write_frame(
        &mut output,
        &json!({"request": 11, "value": "a".repeat(MAX_OUTPUT)}),
    )
    .unwrap();
    let error = native::read_frame(&mut Cursor::new(output))
        .unwrap()
        .unwrap();
    assert_eq!(error["request"], 11);
    assert_eq!(error["error"]["code"], "too-large");
}
#[test]
fn validates_real_cast_payloads_and_rejects_injection() {
    let payload = json!({"url": "https://example.org/video", "headers": {"User-Agent": "Chrome", "Cookie": "secret"}, "position": 754000,
        "audio": 2, "audioNames": ["HDRezka", "Original"], "subtitles": [{"url": "https://example.org/en.vtt", "mime": "text/vtt", "language": "en", "label": "English"}], "subtitle": "0",
        "catalog": {"title": "Show", "seasons": [{"id": 1, "number": 1, "episodes": [{"id": "s1e1", "number": 1, "versions": [{"kind": "hls", "url": "https://example.org/hls", "codec": "H.264"}]}]}]}, "episode": "s1e1"});
    let output = validate::load(&payload).unwrap();
    assert_eq!(output["headers"], json!({"User-Agent": "Chrome"}));
    assert_eq!(output["catalog"]["seasons"][0]["number"], "1");
    assert_eq!(output["position"], 754000);
    assert_eq!(output["subtitle"], "0");
    for value in [
        json!({"url": "file:///etc/passwd"}),
        json!({"url": "blob:abc"}),
        json!({"url": "https://example.org", "position": true}),
        json!({"url": "https://example.org", "headers": {"Origin": "x\r\nInjected: yes"}}),
        json!({"url": "https://example.org", "subtitles": [], "subtitle": "0"}),
        json!({"url": "https://example.org", "catalog": {"seasons": "invalid"}}),
    ] {
        assert!(validate::load(&value).is_err(), "accepted {value}");
    }
    assert!(validate::passthrough(&json!({"value": 9}), "speed").is_err());
    assert!(validate::passthrough(&json!({"track": "../../secret"}), "subtitles").is_err());
    assert!(validate::address(&json!({"address": "localhost"})).is_err());
    assert!(validate::address(&json!({"address": "127.0.0.1", "port": 12.5})).is_err());
}
#[test]
fn pairings_survive_concurrent_writers_and_keep_tokens_private() {
    let temp = tempfile::tempdir().unwrap();
    let devices = Arc::new(Devices {
        path: temp.path().join("devices.json"),
    });
    let handles: Vec<_> = (0..8)
        .map(|worker| {
            let devices = devices.clone();
            std::thread::spawn(move || {
                for i in 0..10 {
                    devices
                        .add(
                            &json!({"id": format!("tv-{worker}-{i}"), "name": "Test"}),
                            "127.0.0.1".into(),
                            8765,
                            "private-token".into(),
                        )
                        .unwrap();
                }
            })
        })
        .collect();
    for handle in handles {
        handle.join().unwrap();
    }
    assert_eq!(devices.all().unwrap().devices.len(), 80);
    assert!(
        !devices
            .public()
            .unwrap()
            .to_string()
            .contains("private-token")
    );
    devices.select("tv-0-0").unwrap();
    assert_eq!(devices.selected().unwrap().id, "tv-0-0");
    devices.move_to("tv-0-0", "127.0.0.2", 9999).unwrap();
    assert_eq!(devices.selected().unwrap().port, 9999);
    devices.remove("tv-0-0").unwrap();
    assert_eq!(devices.all().unwrap().devices.len(), 79);
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        assert_eq!(
            std::fs::metadata(&devices.path)
                .unwrap()
                .permissions()
                .mode()
                & 0o777,
            0o600
        );
    }
    let saved = std::fs::read(&devices.path).unwrap();
    std::fs::write(&devices.path, b"broken store").unwrap();
    assert!(devices.select("tv-0-1").is_err());
    assert_eq!(std::fs::read(&devices.path).unwrap(), b"broken store");
    std::fs::write(&devices.path, saved).unwrap();
}
#[test]
fn activity_redacts_secrets_and_rotates_without_breaking_json() {
    let secret = "a".repeat(64);
    let result = redact(
        &json!({"token": secret, "headers": {"Authorization": "secret"}, "url": "https://cdn.example.org/video?token=private", "message": format!("failed {secret}"), "children": [{"authorization": "secret", "reason": "okay"}]}),
    );
    assert_eq!(result["url"], "https://cdn.example.org/…");
    assert!(!result.to_string().contains("private"));
    assert!(!result.to_string().contains(&secret));
    let temp = tempfile::tempdir().unwrap();
    let log = Journal {
        path: temp.path().join("host.log"),
    };
    for _ in 0..1000 {
        log.record("info", &"small words ".repeat(60), json!({}));
    }
    assert!(log.path.with_extension("log.1").exists());
    assert_eq!(log.recent(10).len(), 10);
    log.clear();
    assert!(log.recent(10).is_empty());
}
#[test]
fn conversion_preserves_browser_headers_and_named_audio() {
    let options = relay::input_options(&json!({"User-Agent": "Chrome Test", "Referer": "https://example.org/", "Cookie": "secret"}), "application/dash+xml").unwrap();
    assert!(
        options
            .windows(2)
            .any(|w| w == ["-user_agent", "Chrome Test"])
    );
    assert!(!options.join(" ").contains("secret"));
    assert!(options.contains(&"dash".into()));
    let audio: Vec<Value> = vec![
        json!({"index": 4, "tags": {"language": "rus0"}}),
        json!({"index": 5, "tags": {"language": "eng2"}}),
    ];
    assert_eq!(relay::pick_audio(&audio, Some(2)), Some(5));
    assert_eq!(relay::pick_audio(&audio, Some(1)), Some(5));
    assert_eq!(relay::pick_audio(&audio, Some(99)), Some(4));
}
