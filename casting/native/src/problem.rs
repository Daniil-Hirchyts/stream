use serde::Serialize;
use serde_json::{Value, json};

#[derive(Debug, Clone, Serialize)]
pub struct Problem {
    pub code: String,
    pub title: String,
    pub message: String,
    pub detail: String,
}

pub type Result<T> = std::result::Result<T, Problem>;

impl Problem {
    pub fn new(code: &str, title: impl Into<String>) -> Self {
        Self {
            code: code.into(),
            title: title.into(),
            message: String::new(),
            detail: String::new(),
        }
    }
    pub fn message(mut self, text: impl Into<String>) -> Self {
        self.message = text.into();
        self
    }
    pub fn detail(mut self, text: impl Into<String>) -> Self {
        self.detail = text.into();
        self
    }
    pub fn invalid(text: impl Into<String>) -> Self {
        Self::new("invalid", text)
    }
    pub fn helper(text: impl Into<String>) -> Self {
        Self::new("helper", text)
    }
    pub fn payload(&self) -> Value {
        json!({"ok": false, "error": self})
    }
}
impl std::fmt::Display for Problem {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.title)
    }
}
impl std::error::Error for Problem {}
impl From<std::io::Error> for Problem {
    fn from(_: std::io::Error) -> Self {
        Self::helper("Couldn’t read or write the helper’s local files")
    }
}
