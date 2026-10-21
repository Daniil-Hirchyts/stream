//! Measures helper overhead through the same framed protocol Chrome uses; does not contact a TV.
use daniil_cast::native::{read_frame, write_frame};
use serde_json::json;
use std::{
    process::{Command, Stdio},
    time::Instant,
};

fn main() -> anyhow::Result<()> {
    let args: Vec<_> = std::env::args().skip(1).collect();
    anyhow::ensure!(!args.is_empty(), "Usage: benchmark PROGRAM [ARGS ...]");
    let temp = tempfile::tempdir()?;
    let spawn = || {
        Command::new(&args[0])
            .args(&args[1..])
            .env("DANIILCAST_HOME", temp.path().join("data"))
            .env("DANIILCAST_LOG", temp.path().join("logs/host.log"))
            .env("DANIILCAST_CACHE", temp.path().join("cache"))
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .spawn()
    };
    let mut startup = Vec::new();
    for _ in 0..30 {
        let start = Instant::now();
        let mut child = spawn()?;
        let mut input = child.stdin.take().unwrap();
        let mut output = child.stdout.take().unwrap();
        write_frame(&mut input, &json!({"action": "hello", "request": 1}))?;
        let response = read_frame(&mut output)?.unwrap();
        anyhow::ensure!(response["ok"] == true, "hello failed");
        startup.push(start.elapsed().as_secs_f64() * 1000.);
        drop(input);
        anyhow::ensure!(child.wait()?.success(), "host failed");
    }
    startup.sort_by(f64::total_cmp);
    let mut child = spawn()?;
    let mut input = child.stdin.take().unwrap();
    let mut output = child.stdout.take().unwrap();
    write_frame(&mut input, &json!({"action": "hello"}))?;
    read_frame(&mut output)?;
    let count = 2000;
    let start = Instant::now();
    for request in 0..count {
        write_frame(&mut input, &json!({"action": "hello", "request": request}))?;
        let value = read_frame(&mut output)?.unwrap();
        anyhow::ensure!(value["request"] == request, "request correlation failed");
    }
    let elapsed = start.elapsed().as_secs_f64();
    drop(input);
    anyhow::ensure!(child.wait()?.success(), "host failed");
    println!(
        "{}",
        serde_json::to_string_pretty(
            &json!({"startup_samples": 30, "startup_median_ms": startup[15], "startup_p95_ms": startup[28], "warm_requests": count, "warm_average_ms": elapsed * 1000. / count as f64, "warm_requests_per_second": count as f64 / elapsed})
        )?
    );
    Ok(())
}
