use crate::{bridge::Bridge, problem::Problem};
use serde_json::Value;
use std::{
    io::{Read, Write},
    sync::Arc,
};
use tokio::{sync::mpsc, task::JoinSet};

pub const MAX_INPUT: usize = 4 * 1024 * 1024;
pub const MAX_OUTPUT: usize = 1024 * 1024;
pub fn read_frame(reader: &mut impl Read) -> std::io::Result<Option<Value>> {
    let mut prefix = [0; 4];
    // Distinguish a clean disconnect from a truncated frame.
    match reader.read(&mut prefix[..1])? {
        0 => return Ok(None),
        _ => reader.read_exact(&mut prefix[1..])?,
    }
    let size = u32::from_ne_bytes(prefix) as usize;
    if size == 0 || size > MAX_INPUT {
        return Err(std::io::Error::other("Invalid native message size"));
    }
    let mut body = vec![0; size];
    reader.read_exact(&mut body)?;
    serde_json::from_slice(&body)
        .map(Some)
        .map_err(|_| std::io::Error::other("Invalid native JSON"))
}
pub fn write_frame(writer: &mut impl Write, value: &Value) -> std::io::Result<()> {
    let mut bytes = serde_json::to_vec(value)?;
    if bytes.len() > MAX_OUTPUT {
        let mut error = Problem::new("too-large", "The helper’s response was too large").payload();
        if let Some(request) = value.get("request") {
            error["request"] = request.clone();
        }
        bytes = serde_json::to_vec(&error)?;
    }
    writer.write_all(&(bytes.len() as u32).to_ne_bytes())?;
    writer.write_all(&bytes)?;
    writer.flush()
}
pub async fn serve(bridge: Arc<Bridge>) -> anyhow::Result<()> {
    let (input_tx, mut input_rx) = mpsc::channel(8);
    std::thread::spawn(move || {
        let mut input = std::io::stdin().lock();
        loop {
            match read_frame(&mut input) {
                Ok(Some(value)) => {
                    if input_tx.blocking_send(Ok(value)).is_err() {
                        return;
                    }
                }
                Ok(None) => return,
                Err(error) => {
                    let _ = input_tx.blocking_send(Err(error));
                    return;
                }
            }
        }
    });
    let (output_tx, mut output_rx) = mpsc::channel::<Value>(16);
    let writer = std::thread::spawn(move || {
        let mut output = std::io::stdout().lock();
        while let Some(value) = output_rx.blocking_recv() {
            if write_frame(&mut output, &value).is_err() {
                return;
            }
        }
    });
    let mut jobs = JoinSet::new();
    while let Some(message) = input_rx.recv().await {
        if jobs.len() >= 16 {
            let _ = jobs.join_next().await;
        }
        let mut message = match message {
            Ok(value) => value,
            Err(_) => {
                let _ = output_tx
                    .send(Problem::invalid("Invalid native message").payload())
                    .await;
                break;
            }
        };
        let bridge = bridge.clone();
        let output = output_tx.clone();
        jobs.spawn(async move {
            let request = message.as_object_mut().and_then(|m| m.remove("request"));
            let mut result = bridge.handle(message).await;
            if let Some(request) = request {
                result["request"] = request;
            }
            let _ = output.send(result).await;
        });
        while jobs.try_join_next().is_some() {}
    }
    while jobs.join_next().await.is_some() {}
    drop(output_tx);
    let _ = writer.join();
    Ok(())
}
