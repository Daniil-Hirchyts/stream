#![cfg(not(windows))] // Windows registration intentionally writes HKCU; exercise it in an installer-specific job.
use daniil_cast::native::{read_frame, write_frame};
use serde_json::{Value, json};
use std::{
    path::Path,
    process::{Command, Stdio},
};

fn run(executable: &Path, args: &[&std::ffi::OsStr]) {
    let output = Command::new(executable).args(args).output().unwrap();
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
}
#[test]
fn installed_helper_survives_source_removal_and_keeps_unchanged_binary() {
    let temp = tempfile::tempdir().unwrap();
    let package = temp.path().join("package");
    let executable = Path::new(env!("CARGO_BIN_EXE_daniil-cast"));
    run(
        executable,
        &["package".as_ref(), "--output".as_ref(), package.as_os_str()],
    );
    let (source, destination, binary) = if cfg!(target_os = "macos") {
        (
            package.join("Daniil Cast.app/Contents/MacOS/daniil-cast-helper"),
            temp.path().join("installed/Daniil Cast.app"),
            "Contents/MacOS/daniil-cast-helper",
        )
    } else {
        (
            package.join("DaniilCast/daniil-cast"),
            temp.path().join("installed/DaniilCast"),
            "daniil-cast",
        )
    };
    let registry = temp.path().join("registry");
    let args = [
        "install".as_ref(),
        "--destination".as_ref(),
        destination.as_os_str(),
        "--registry-dir".as_ref(),
        registry.as_os_str(),
    ];
    run(&source, &args);
    let installed = destination.join(binary);
    let before = std::fs::metadata(&installed).unwrap().modified().unwrap();
    run(&source, &args);
    assert_eq!(
        before,
        std::fs::metadata(&installed).unwrap().modified().unwrap()
    );
    let manifest: Value =
        serde_json::from_slice(&std::fs::read(registry.join("tv.daniil.cast.json")).unwrap())
            .unwrap();
    assert_eq!(manifest["path"], installed.to_string_lossy().as_ref());
    std::fs::remove_dir_all(package).unwrap();
    let mut child = Command::new(installed)
        .arg("chrome-extension://mdcjencokcefbcilbgmlpmonlilphdjk/")
        .env("DANIILCAST_HOME", temp.path().join("data"))
        .env("DANIILCAST_LOG", temp.path().join("logs/host.log"))
        .env("DANIILCAST_CACHE", temp.path().join("cache"))
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .spawn()
        .unwrap();
    let mut input = child.stdin.take().unwrap();
    let mut output = child.stdout.take().unwrap();
    write_frame(&mut input, &json!({"action": "hello", "request": 123})).unwrap();
    let result = read_frame(&mut output).unwrap().unwrap();
    assert_eq!(result["ok"], true);
    assert_eq!(result["request"], 123);
    drop(input);
    assert!(child.wait().unwrap().success());
}
