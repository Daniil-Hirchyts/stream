use crate::paths;
use fs2::FileExt;
use regex::Regex;
use serde_json::{Value, json};
use std::{
    fs::OpenOptions,
    io::{Read, Write},
    path::PathBuf,
    sync::LazyLock,
};

static URL: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r#"\b(https?)://([^/\s\"'?#]+)[^\s\"']*"#).unwrap());
static SECRET: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"[A-Za-z0-9_-]{40,}").unwrap());
pub fn redact(value: &Value) -> Value {
    match value {
        Value::String(text) => Value::String(
            SECRET
                .replace_all(&URL.replace_all(text, "$1://$2/…"), "…")
                .chars()
                .take(600)
                .collect(),
        ),
        Value::Array(items) => Value::Array(items.iter().take(20).map(redact).collect()),
        Value::Object(items) => Value::Object(
            items
                .iter()
                .filter(|(key, _)| {
                    !matches!(
                        key.to_lowercase().as_str(),
                        "token" | "authorization" | "headers"
                    )
                })
                .map(|(key, value)| (key.clone(), redact(value)))
                .collect(),
        ),
        value => value.clone(),
    }
}
#[derive(Clone)]
pub struct Journal {
    pub path: PathBuf,
}
impl Default for Journal {
    fn default() -> Self {
        Self { path: paths::log() }
    }
}
impl Journal {
    fn lock(&self) -> std::io::Result<std::fs::File> {
        let parent = self
            .path
            .parent()
            .ok_or_else(|| std::io::Error::other("Missing log directory"))?;
        paths::private_dir(parent)?;
        let file = OpenOptions::new()
            .create(true)
            .truncate(false)
            .read(true)
            .write(true)
            .open(self.path.with_extension("lock"))?;
        file.lock_exclusive()?;
        Ok(file)
    }
    pub fn record(&self, level: &str, message: &str, detail: Value) {
        let mut entry =
            json!({"at": paths::now_ms(), "level": level, "message": redact(&json!(message))});
        if detail.as_object().is_some_and(|map| !map.is_empty()) {
            entry["detail"] = redact(&detail);
        }
        let save = || -> std::io::Result<()> {
            let _lock = self.lock()?;
            if std::fs::metadata(&self.path).is_ok_and(|meta| meta.len() > 512 * 1024) {
                let rotated = self.path.with_extension("log.1");
                if rotated.exists() {
                    std::fs::remove_file(&rotated)?;
                }
                std::fs::rename(&self.path, rotated)?;
            }
            let mut file = OpenOptions::new()
                .create(true)
                .append(true)
                .open(&self.path)?;
            paths::private_file(&self.path)?;
            serde_json::to_writer(&mut file, &entry)?;
            file.write_all(b"\n")
        };
        let _ = save(); // A logging failure must never stop playback.
    }
    pub fn recent(&self, count: usize) -> Vec<Value> {
        let read = || -> std::io::Result<String> {
            let _lock = self.lock()?;
            let mut text = String::new();
            std::fs::File::open(&self.path)?
                .take(1024 * 1024)
                .read_to_string(&mut text)?;
            Ok(text)
        };
        read()
            .unwrap_or_default()
            .lines()
            .rev()
            .filter_map(|line| serde_json::from_str(line).ok())
            .take(count)
            .collect()
    }
    pub fn clear(&self) {
        if let Ok(_lock) = self.lock() {
            let _ = std::fs::remove_file(&self.path);
            let _ = std::fs::remove_file(self.path.with_extension("log.1"));
        }
    }
}
