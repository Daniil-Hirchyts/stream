use clap::{Parser, Subcommand};
use daniil_cast::{bridge::Bridge, install, native, relay};
use serde_json::{Value, json};
use std::{io::Write, path::PathBuf, sync::Arc, time::Duration};

#[derive(Parser)]
#[command(version, about = "Daniil Cast desktop helper and TV controls")]
struct Cli {
    #[command(subcommand)]
    command: Option<Action>,
}
#[derive(Subcommand)]
enum Action {
    /// Install the helper and register it with Chrome. Users do not need Rust.
    Install {
        #[arg(long)]
        destination: Option<PathBuf>,
        #[arg(long)]
        registry_dir: Option<PathBuf>,
        #[arg(long, default_value = install::EXTENSION_ID)]
        extension_id: String,
        #[arg(long)]
        tools: Option<PathBuf>,
    },
    /// Assemble a distributable app; --tools includes FFmpeg and ffprobe.
    Package {
        #[arg(long, default_value = "build")]
        output: PathBuf,
        #[arg(long)]
        tools: Option<PathBuf>,
    },
    Devices,
    Discover,
    Pair {
        address: String,
        #[arg(long, default_value_t = 8765)]
        port: u16,
    },
    Status {
        #[arg(long)]
        watch: bool,
    },
    Load {
        url: String,
        #[arg(long, default_value = "Video from Terminal")]
        title: String,
        #[arg(long)]
        referer: Option<String>,
        #[arg(long, default_value = "")]
        mime: String,
        #[arg(long, default_value = "")]
        codec: String,
        #[arg(long)]
        convert: bool,
    },
    Seek {
        seconds: f64,
    },
    Send {
        message: String,
    },
    Log {
        #[arg(short = 'n', default_value_t = 30)]
        limit: usize,
    },
    Play,
    Pause,
    Stop,
    Next,
    Previous,
    /// Speak Chrome's binary-framed native messaging protocol on stdin/stdout.
    Native,
}
fn show(value: &Value) -> anyhow::Result<()> {
    println!("{}", serde_json::to_string_pretty(value)?);
    Ok(())
}
#[tokio::main(worker_threads = 2)]
async fn main() -> anyhow::Result<()> {
    let args: Vec<_> = std::env::args().collect();
    if args.get(1).is_some_and(|arg| arg == "--relay") {
        return relay::serve().await;
    }
    if install::is_app_bundle()
        && (args.len() == 1 || args.get(1).is_some_and(|arg| arg.starts_with("-psn_")))
    {
        install::install(None, None, install::EXTENSION_ID, None)?;
        install::show_installed();
        return Ok(());
    }
    if args.len() == 1
        || args
            .get(1)
            .is_some_and(|arg| arg.starts_with("chrome-extension://"))
    {
        return native::serve(Arc::new(Bridge::local()?)).await;
    }
    let action = Cli::parse().command.unwrap_or(Action::Native);
    match action {
        Action::Install {
            destination,
            registry_dir,
            extension_id,
            tools,
        } => {
            let binary =
                install::install(destination, registry_dir, &extension_id, tools.as_deref())?;
            println!(
                "Daniil Cast installed: {}\nChrome native host registered. Reopen the side panel.",
                binary.display()
            );
            return Ok(());
        }
        Action::Package { output, tools } => {
            println!("{}", install::package(&output, tools.as_deref())?.display());
            return Ok(());
        }
        Action::Native => return native::serve(Arc::new(Bridge::local()?)).await,
        _ => {}
    }
    let bridge = Bridge::local()?;
    let message = match action {
        Action::Devices => json!({"action": "hello"}),
        Action::Discover => json!({"action": "discover"}),
        Action::Pair { address, port } => {
            let result = bridge
                .handle(json!({"action": "pairRequest", "address": address, "port": port}))
                .await;
            if result["ok"] == false {
                show(&result)?;
                std::process::exit(1);
            }
            print!(
                "Code shown on {}: ",
                result["tv"]["name"].as_str().unwrap_or("the TV")
            );
            std::io::stdout().flush()?;
            let mut code = String::new();
            std::io::stdin().read_line(&mut code)?;
            json!({"action": "pair", "address": address, "port": port, "code": code.trim()})
        }
        Action::Status { watch: true } => {
            loop {
                show(&bridge.handle(json!({"action": "status"})).await)?;
                tokio::select! { _ = tokio::signal::ctrl_c() => break, _ = tokio::time::sleep(Duration::from_secs(1)) => {} }
            }
            return Ok(());
        }
        Action::Status { watch: false } => json!({"action": "status"}),
        Action::Load {
            url,
            title,
            referer,
            mime,
            codec,
            convert,
        } => {
            json!({"action": "load", "url": url, "title": title, "headers": referer.map(|r| json!({"Referer": r})).unwrap_or(json!({})), "mime": mime, "codec": codec, "convert": convert})
        }
        Action::Seek { seconds } => json!({"action": "seek", "position": seconds * 1000.}),
        Action::Send { message } => serde_json::from_str(&message)?,
        Action::Log { limit } => json!({"action": "log", "limit": limit}),
        Action::Play => json!({"action": "play"}),
        Action::Pause => json!({"action": "pause"}),
        Action::Stop => json!({"action": "stop"}),
        Action::Next => json!({"action": "next"}),
        Action::Previous => json!({"action": "previous"}),
        _ => unreachable!(),
    };
    let result = bridge.handle(message).await;
    show(&result)?;
    if result["ok"] == false {
        std::process::exit(1);
    }
    Ok(())
}
