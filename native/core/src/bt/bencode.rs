//! Bencoding (BEP 3) with bounded decoding: nesting, item count and integer size all have limits, so a hostile
//! torrent or peer message can't exhaust memory or the stack.

use std::collections::BTreeMap;
use std::fmt;
use std::ops::Range;

#[derive(Clone, PartialEq, Eq)]
pub enum Value {
    Int(i64),
    Bytes(Vec<u8>),
    List(Vec<Value>),
    Dict(BTreeMap<Vec<u8>, Value>),
}

impl fmt::Debug for Value {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Value::Int(i) => write!(f, "{i}"),
            Value::Bytes(b) => match std::str::from_utf8(b) {
                Ok(s) if b.len() <= 64 => write!(f, "{s:?}"),
                _ => write!(f, "<{} bytes>", b.len()),
            },
            Value::List(l) => f.debug_list().entries(l).finish(),
            Value::Dict(d) => f.debug_map().entries(d.iter().map(|(k, v)| (String::from_utf8_lossy(k), v))).finish(),
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Error(pub String);

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for Error {}

pub type Result<T> = std::result::Result<T, Error>;

fn err<T>(message: &str) -> Result<T> {
    Err(Error(message.into()))
}

const MAX_DEPTH: usize = 100;
const MAX_ITEMS: usize = 3_000_000;

struct Decoder<'a> {
    data: &'a [u8],
    pos: usize,
    items: usize,
}

impl<'a> Decoder<'a> {
    fn peek(&self) -> Result<u8> {
        self.data.get(self.pos).copied().ok_or_else(|| Error("Truncated bencoding".into()))
    }

    fn number_until(&mut self, end: u8) -> Result<i64> {
        let start = self.pos;
        let stop = self.data[start..]
            .iter()
            .position(|&b| b == end)
            .ok_or_else(|| Error("Unterminated number".into()))?;
        let text = std::str::from_utf8(&self.data[start..start + stop]).map_err(|_| Error("Invalid number".into()))?;
        if text.is_empty() || text.len() > 20 || text == "-0" || (text.len() > 1 && text.starts_with('0')) {
            return err("Invalid number");
        }
        let n: i64 = text.parse().map_err(|_| Error("Invalid number".into()))?;
        self.pos = start + stop + 1;
        Ok(n)
    }

    fn bytes(&mut self) -> Result<&'a [u8]> {
        let len = self.number_until(b':')?;
        let len = usize::try_from(len).map_err(|_| Error("Negative length".into()))?;
        let end = self.pos.checked_add(len).filter(|&e| e <= self.data.len()).ok_or_else(|| Error("Truncated string".into()))?;
        let out = &self.data[self.pos..end];
        self.pos = end;
        Ok(out)
    }

    fn value(&mut self, depth: usize) -> Result<Value> {
        if depth > MAX_DEPTH {
            return err("Bencoding nested too deeply");
        }
        self.items += 1;
        if self.items > MAX_ITEMS {
            return err("Bencoding has too many items");
        }
        match self.peek()? {
            b'i' => {
                self.pos += 1;
                Ok(Value::Int(self.number_until(b'e')?))
            }
            b'l' => {
                self.pos += 1;
                let mut list = Vec::new();
                while self.peek()? != b'e' {
                    list.push(self.value(depth + 1)?);
                }
                self.pos += 1;
                Ok(Value::List(list))
            }
            b'd' => {
                self.pos += 1;
                let mut dict = BTreeMap::new();
                while self.peek()? != b'e' {
                    let key = self.bytes()?.to_vec();
                    let value = self.value(depth + 1)?;
                    dict.insert(key, value);
                }
                self.pos += 1;
                Ok(Value::Dict(dict))
            }
            b'0'..=b'9' => Ok(Value::Bytes(self.bytes()?.to_vec())),
            _ => err("Invalid bencoding"),
        }
    }

    /// Skips one value, returning its byte range.
    fn skip(&mut self, depth: usize) -> Result<Range<usize>> {
        let start = self.pos;
        self.value(depth)?;
        Ok(start..self.pos)
    }
}

/// Decodes a buffer holding exactly one value.
pub fn decode(data: &[u8]) -> Result<Value> {
    let (value, used) = decode_prefix(data)?;
    if used != data.len() {
        return err("Data after the bencoded value");
    }
    Ok(value)
}

/// Decodes the value at the start of a buffer, returning it and its length (ut_metadata pieces follow their header).
pub fn decode_prefix(data: &[u8]) -> Result<(Value, usize)> {
    let mut d = Decoder { data, pos: 0, items: 0 };
    let value = d.value(0)?;
    Ok((value, d.pos))
}

/// The exact bytes of a top-level dictionary entry, for hashing the info dictionary as it was written.
pub fn raw_entry(data: &[u8], key: &[u8]) -> Result<Option<Range<usize>>> {
    let mut d = Decoder { data, pos: 0, items: 0 };
    if d.peek()? != b'd' {
        return err("Not a dictionary");
    }
    d.pos += 1;
    while d.peek()? != b'e' {
        let k = d.bytes()?;
        let range = d.skip(1)?;
        if k == key {
            return Ok(Some(range));
        }
    }
    Ok(None)
}

pub fn encode(value: &Value) -> Vec<u8> {
    let mut out = Vec::new();
    encode_into(value, &mut out);
    out
}

pub fn encode_into(value: &Value, out: &mut Vec<u8>) {
    match value {
        Value::Int(i) => {
            out.push(b'i');
            out.extend_from_slice(i.to_string().as_bytes());
            out.push(b'e');
        }
        Value::Bytes(b) => {
            out.extend_from_slice(b.len().to_string().as_bytes());
            out.push(b':');
            out.extend_from_slice(b);
        }
        Value::List(l) => {
            out.push(b'l');
            for v in l {
                encode_into(v, out);
            }
            out.push(b'e');
        }
        Value::Dict(d) => {
            out.push(b'd');
            for (k, v) in d {
                out.extend_from_slice(k.len().to_string().as_bytes());
                out.push(b':');
                out.extend_from_slice(k);
                encode_into(v, out);
            }
            out.push(b'e');
        }
    }
}

impl Value {
    pub fn get(&self, key: &str) -> Option<&Value> {
        match self {
            Value::Dict(d) => d.get(key.as_bytes()),
            _ => None,
        }
    }
    pub fn int(&self) -> Option<i64> {
        match self {
            Value::Int(i) => Some(*i),
            _ => None,
        }
    }
    pub fn bytes(&self) -> Option<&[u8]> {
        match self {
            Value::Bytes(b) => Some(b),
            _ => None,
        }
    }
    pub fn str(&self) -> Option<&str> {
        self.bytes().and_then(|b| std::str::from_utf8(b).ok())
    }
    pub fn list(&self) -> Option<&[Value]> {
        match self {
            Value::List(l) => Some(l),
            _ => None,
        }
    }
    pub fn dict(&self) -> Option<&BTreeMap<Vec<u8>, Value>> {
        match self {
            Value::Dict(d) => Some(d),
            _ => None,
        }
    }
    pub fn get_int(&self, key: &str) -> Option<i64> {
        self.get(key).and_then(Value::int)
    }
    pub fn get_bytes(&self, key: &str) -> Option<&[u8]> {
        self.get(key).and_then(Value::bytes)
    }
    pub fn get_str(&self, key: &str) -> Option<&str> {
        self.get(key).and_then(Value::str)
    }
}

/// Builds dictionaries tersely: `dict([("a", Value::Int(1))])`.
pub fn dict<const N: usize>(entries: [(&str, Value); N]) -> Value {
    Value::Dict(entries.into_iter().map(|(k, v)| (k.as_bytes().to_vec(), v)).collect())
}

pub fn bytes(b: impl Into<Vec<u8>>) -> Value {
    Value::Bytes(b.into())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trips_and_finds_raw_entries() {
        let v = dict([("info", dict([("name", bytes("a")), ("length", Value::Int(-3))])), ("z", Value::List(vec![]))]);
        let encoded = encode(&v);
        assert_eq!(encoded, b"d4:infod6:lengthi-3e4:name1:ae1:zlee");
        assert_eq!(decode(&encoded).unwrap(), v);
        let range = raw_entry(&encoded, b"info").unwrap().unwrap();
        assert_eq!(&encoded[range], b"d6:lengthi-3e4:name1:ae");
    }

    #[test]
    fn rejects_malformed_input() {
        for bad in [&b"i-0e"[..], b"i03e", b"5:abc", b"l", b"d1:a", b"x", b"i1ei2e", b"4294967296:"] {
            assert!(decode(bad).is_err(), "{:?}", String::from_utf8_lossy(bad));
        }
        let deep = "l".repeat(200) + &"e".repeat(200);
        assert!(decode(deep.as_bytes()).is_err());
    }

    #[test]
    fn prefixes_report_their_length() {
        let (v, used) = decode_prefix(b"d8:msg_typei1eeXYZ").unwrap();
        assert_eq!(v.get_int("msg_type"), Some(1));
        assert_eq!(used, 15);
    }
}
