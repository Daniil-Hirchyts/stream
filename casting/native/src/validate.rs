use crate::problem::{Problem, Result};
use regex::Regex;
use serde_json::{Value, json};
use std::{net::IpAddr, sync::LazyLock};

static LANGUAGE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$").unwrap());
static TRACK: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^(off|\d{1,3}\.\d{1,3})$").unwrap());
static ID: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^[\w:.-]{1,40}$").unwrap());
pub fn text(value: &Value, limit: usize) -> Result<String> {
    value
        .as_str()
        .filter(|s| !s.contains('\0') && s.chars().count() <= limit)
        .map(str::to_owned)
        .ok_or_else(|| Problem::invalid("Invalid text field"))
}
pub fn field(value: &Value, key: &str, default: &str, limit: usize) -> Result<String> {
    match value.get(key) {
        Some(value) => text(value, limit),
        None => Ok(default.into()),
    }
}
pub fn number(value: &Value, low: f64, high: f64, name: &str) -> Result<f64> {
    value
        .as_f64()
        .filter(|n| n.is_finite() && *n >= low && *n <= high)
        .ok_or_else(|| Problem::invalid(format!("Invalid {name}")))
}
pub fn url(value: &Value, limit: usize) -> Result<String> {
    let value = text(value, limit)?;
    let parsed = reqwest::Url::parse(&value).ok();
    if !parsed.is_some_and(|url| {
        matches!(url.scheme(), "http" | "https")
            && url.host_str().is_some()
            && url.username().is_empty()
            && url.password().is_none()
    }) {
        return Err(Problem::invalid(
            "Choose a direct web address for the video; blob addresses only exist in the browser",
        ));
    }
    Ok(value)
}
pub fn language(value: &Value) -> Result<String> {
    let value = text(value, 35)?;
    if !value.is_empty() && !LANGUAGE.is_match(&value) {
        return Err(Problem::invalid("Invalid subtitle language"));
    }
    Ok(value)
}
pub fn headers(value: &Value) -> Result<Value> {
    let values = value
        .as_object()
        .ok_or_else(|| Problem::invalid("Invalid request headers"))?;
    let mut output = json!({});
    for (key, value) in values {
        if matches!(
            key.to_lowercase().as_str(),
            "referer" | "origin" | "user-agent"
        ) {
            let value = text(value, 4000)?;
            if value.contains(['\r', '\n']) {
                return Err(Problem::invalid("Invalid request header"));
            }
            output[key] = json!(value);
        }
    }
    Ok(output)
}
fn subtitles(value: &Value, limit: usize) -> Result<Value> {
    let values = value
        .as_array()
        .filter(|a| a.len() <= limit)
        .ok_or_else(|| Problem::invalid("Invalid subtitle list"))?;
    values.iter().map(|item| {
        if !item.is_object() { return Err(Problem::invalid("Invalid subtitle")); }
        let mime = field(item, "mime", "", 40)?;
        if !matches!(mime.as_str(), "" | "text/vtt" | "application/x-subrip" | "text/x-ssa" | "application/ttml+xml") { return Err(Problem::invalid("Unsupported subtitle format")); }
        Ok(json!({"url": url(&item["url"], 8000)?, "mime": mime, "language": language(item.get("language").unwrap_or(&json!("")))?, "label": field(item, "label", "", 100)?}))
    }).collect::<Result<Vec<_>>>().map(Value::Array)
}
fn names(value: &Value) -> Result<Value> {
    value
        .as_array()
        .filter(|a| a.len() <= 24)
        .ok_or_else(|| Problem::invalid("Invalid audio track names"))?
        .iter()
        .map(|name| text(name, 80).map(Value::String))
        .collect::<Result<Vec<_>>>()
        .map(Value::Array)
}
fn codec(value: &Value) -> &str {
    value
        .as_str()
        .filter(|c| matches!(*c, "H.264" | "HEVC" | "VP9" | "AV1"))
        .unwrap_or_default()
}
fn identifier(value: &Value) -> Value {
    if let Some(s) = value.as_str() {
        json!(s)
    } else if value.is_null() {
        json!("")
    } else {
        json!(value.to_string())
    }
}
fn array<'a>(value: &'a Value, key: &str) -> Result<&'a [Value]> {
    match value.get(key) {
        None => Ok(&[]),
        Some(Value::Array(a)) => Ok(a),
        _ => Err(Problem::invalid(format!("Invalid {key}"))),
    }
}
pub fn catalog(value: &Value) -> Result<Value> {
    if !value.is_object() {
        return Err(Problem::invalid("Invalid episode list"));
    }
    let mut seasons = Vec::new();
    let mut total = 0;
    for season in array(value, "seasons")?.iter().take(60) {
        let mut episodes = Vec::new();
        for episode in array(season, "episodes")?.iter().take(400) {
            total += 1;
            if total > 1000 {
                return Err(Problem::invalid("Too many episodes"));
            }
            let versions = array(episode, "versions")?.iter().take(6).map(|v| Ok(json!({
                "kind": field(v, "kind", "", 20)?, "url": url(&v["url"], 24000)?, "mime": field(v, "mime", "", 60)?, "codec": codec(&v["codec"])
            }))).collect::<Result<Vec<_>>>()?;
            if versions.is_empty() {
                continue;
            }
            episodes.push(json!({"id": field(episode, "id", "", 40)?, "number": text(&identifier(&episode["number"]), 20)?, "title": field(episode, "title", "", 200)?,
                "duration": number(episode.get("duration").unwrap_or(&json!(0)), 0., 86400., "duration")?, "versions": versions,
                "audioNames": names(episode.get("audioNames").unwrap_or(&json!([])))?, "subtitles": subtitles(episode.get("subtitles").unwrap_or(&json!([])), 12)?}));
        }
        if !episodes.is_empty() {
            seasons.push(json!({"id": text(&identifier(&season["id"]), 40)?, "number": text(&identifier(&season["number"]), 20)?, "episodes": episodes}));
        }
    }
    Ok(
        json!({"title": field(value, "title", "", 250)?, "headers": headers(value.get("headers").unwrap_or(&json!({})))?, "seasons": seasons}),
    )
}
pub fn load(message: &Value) -> Result<Value> {
    let mut command = json!({"action": "load", "url": url(&message["url"], 24000)?, "title": field(message, "title", "Video from Chrome", 250)?,
        "mime": field(message, "mime", "", 100)?, "headers": headers(message.get("headers").unwrap_or(&json!({})))?,
        "position": number(message.get("position").unwrap_or(&json!(0)), 0., 31536000000., "playback position")? as u64,
        "kind": field(message, "kind", "", 20)?, "codec": codec(&message["codec"])});
    let subs = subtitles(message.get("subtitles").unwrap_or(&json!([])), 12)?;
    if !subs.as_array().unwrap().is_empty() {
        command["subtitles"] = subs.clone();
    }
    if let Some(choice) = message.get("subtitle").filter(|v| !v.is_null()) {
        let valid = choice.as_str().is_some_and(|s| {
            s == "off"
                || (!s.is_empty()
                    && s.bytes().all(|b| b.is_ascii_digit())
                    && s.parse::<usize>()
                        .is_ok_and(|n| n < subs.as_array().unwrap().len()))
        });
        if !valid {
            return Err(Problem::invalid("Invalid subtitle choice"));
        }
        command["subtitle"] = choice.clone();
    }
    if let Some(audio) = message.get("audio").filter(|v| !v.is_null()) {
        command["audio"] = json!(number(audio, 0., 63., "audio track")? as u64);
    }
    let audio_names = names(message.get("audioNames").unwrap_or(&json!([])))?;
    if !audio_names.as_array().unwrap().is_empty() {
        command["audioNames"] = audio_names;
    }
    if message
        .get("textLanguage")
        .is_some_and(|v| v != "" && !v.is_null())
    {
        command["textLanguage"] = json!(language(&message["textLanguage"])?);
    }
    if message
        .get("textLabel")
        .is_some_and(|v| v != "" && !v.is_null())
    {
        command["textLabel"] = json!(text(&message["textLabel"], 100)?);
    }
    if let Some(value) = message.get("catalog").filter(|v| !v.is_null()) {
        command["catalog"] = catalog(value)?;
        command["episode"] = json!(field(message, "episode", "", 40)?);
    }
    Ok(command)
}
pub const PASSTHROUGH: &[&str] = &[
    "play",
    "pause",
    "stop",
    "seek",
    "audio",
    "subtitles",
    "quality",
    "speed",
    "episode",
    "next",
    "previous",
    "retry",
];
pub fn passthrough(message: &Value, action: &str) -> Result<Value> {
    let mut command = json!({"action": action});
    match action {
        "seek" => {
            command["position"] =
                json!(number(&message["position"], 0., 31536000000., "playback position")? as u64)
        }
        "audio" => command["track"] = json!(text(&message["track"], 8)?),
        "quality" => command["id"] = json!(text(&message["id"], 10)?),
        "speed" => command["value"] = json!(number(&message["value"], 0.25, 4., "speed")?),
        "episode" => {
            let id = text(&message["id"], 40)?;
            if !ID.is_match(&id) {
                return Err(Problem::invalid("Invalid episode"));
            }
            command["id"] = json!(id);
        }
        "subtitles" => {
            if let Some(track) = message.get("track").filter(|v| !v.is_null()) {
                let track = text(track, 8)?;
                if !TRACK.is_match(&track) {
                    return Err(Problem::invalid("Invalid subtitle track"));
                }
                command["track"] = json!(track);
            }
            for (key, allowed) in [
                ("size", &["small", "medium", "large", "xlarge"][..]),
                ("color", &["white", "yellow", "cyan"][..]),
                ("edge", &["outline", "shadow", "box"][..]),
                ("position", &["low", "standard", "raised"][..]),
            ] {
                if let Some(value) = message.get(key).filter(|v| !v.is_null()) {
                    if !value.as_str().is_some_and(|s| allowed.contains(&s)) {
                        return Err(Problem::invalid(format!("Invalid subtitle {key}")));
                    }
                    command[key] = value.clone();
                }
            }
        }
        _ => {}
    }
    Ok(command)
}
pub fn address(message: &Value) -> Result<(IpAddr, u16)> {
    let address = field(message, "address", "", 60)?
        .trim()
        .parse()
        .map_err(|_| Problem::invalid("Enter the TV’s IP address, such as 192.168.1.20"))?;
    let port = number(
        message.get("port").unwrap_or(&json!(8765)),
        1.,
        65535.,
        "port",
    )?;
    if port.fract() != 0. {
        return Err(Problem::invalid("Invalid port"));
    }
    Ok((address, port as u16))
}
