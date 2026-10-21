use crate::{VERSION, paths};
use anyhow::{Context, Result, bail};
use serde_json::json;
#[cfg(target_os = "macos")]
use std::collections::{BTreeMap, VecDeque};
use std::path::{Path, PathBuf};
#[cfg(any(target_os = "macos", windows))]
use std::process::Command;

pub const EXTENSION_ID: &str = "mdcjencokcefbcilbgmlpmonlilphdjk";
const BUNDLE_ID: &str = "tv.daniil.cast.helper";
const NAME: &str = "daniil-cast";
pub fn default_destination() -> PathBuf {
    if cfg!(target_os = "macos") {
        paths::home().join("Applications/Daniil Cast.app")
    } else if cfg!(windows) {
        paths::data().join("bin")
    } else {
        paths::home().join(".local/lib/daniil-cast")
    }
}
pub fn registry_directory() -> PathBuf {
    if cfg!(target_os = "macos") {
        paths::home().join("Library/Application Support/Google/Chrome/NativeMessagingHosts")
    } else if cfg!(windows) {
        paths::data().join("NativeMessagingHosts")
    } else {
        std::env::var_os("XDG_CONFIG_HOME")
            .map(PathBuf::from)
            .unwrap_or_else(|| paths::home().join(".config"))
            .join("google-chrome/NativeMessagingHosts")
    }
}
fn binary_path(destination: &Path) -> PathBuf {
    if cfg!(target_os = "macos") {
        destination.join("Contents/MacOS/daniil-cast-helper")
    } else {
        destination.join(if cfg!(windows) {
            "daniil-cast.exe"
        } else {
            NAME
        })
    }
}
fn copy_file(source: &Path, target: &Path) -> Result<()> {
    std::fs::create_dir_all(target.parent().context("Missing installation directory")?)?;
    std::fs::copy(source, target).with_context(|| format!("Couldn’t copy {}", source.display()))?;
    Ok(())
}
fn copy_tree(source: &Path, target: &Path) -> Result<()> {
    std::fs::create_dir_all(target)?;
    for entry in std::fs::read_dir(source)? {
        let entry = entry?;
        if entry.file_type()?.is_dir() {
            copy_tree(&entry.path(), &target.join(entry.file_name()))?;
        } else if entry.file_type()?.is_file() {
            copy_file(&entry.path(), &target.join(entry.file_name()))?;
        } else {
            bail!(
                "Unsupported symbolic link in application bundle: {}",
                entry.path().display()
            );
        }
    }
    Ok(())
}
fn same_file(a: &Path, b: &Path) -> bool {
    std::fs::metadata(a)
        .ok()
        .zip(std::fs::metadata(b).ok())
        .is_some_and(|(a_meta, b_meta)| {
            a_meta.len() == b_meta.len() && std::fs::read(a).ok() == std::fs::read(b).ok()
        })
}
#[cfg(any(target_os = "macos", windows))]
fn checked(command: &mut Command, operation: &str) -> Result<()> {
    let output = command.output().with_context(|| operation.to_owned())?;
    if !output.status.success() {
        bail!(
            "{operation}: {}",
            String::from_utf8_lossy(&output.stderr).trim()
        );
    }
    Ok(())
}

pub fn is_app_bundle() -> bool {
    cfg!(target_os = "macos")
        && std::env::current_exe()
            .ok()
            .and_then(|path| path.parent().and_then(Path::parent).map(Path::to_path_buf))
            .is_some_and(|path| path.file_name().is_some_and(|name| name == "Contents"))
}
pub fn show_installed() {
    #[cfg(target_os = "macos")]
    {
        let _ = Command::new("/usr/bin/osascript").args(["-e", "display dialog \"Daniil Cast is installed. Reopen the Chrome side panel. Allow Local Network access when macOS asks.\" with title \"Daniil Cast\" buttons {\"OK\"} default button \"OK\""]).status();
    }
}
fn make_bundle(destination: &Path, tools: Option<&Path>) -> Result<PathBuf> {
    let current = std::env::current_exe()?;
    let binary = binary_path(destination);
    copy_file(&current, &binary)?;
    if cfg!(target_os = "macos") {
        let info = format!(
            r#"<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleIdentifier</key><string>{BUNDLE_ID}</string>
<key>CFBundleName</key><string>Daniil Cast</string>
<key>CFBundleDisplayName</key><string>Daniil Cast</string>
<key>CFBundleExecutable</key><string>daniil-cast-helper</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleVersion</key><string>2</string>
<key>CFBundleShortVersionString</key><string>{VERSION}</string>
<key>LSUIElement</key><true/>
<key>LSMinimumSystemVersion</key><string>13.0</string>
<key>NSLocalNetworkUsageDescription</key><string>Daniil Cast finds your TV on this network and sends it videos.</string>
</dict></plist>
"#
        );
        std::fs::create_dir_all(destination.join("Contents/Resources"))?;
        std::fs::write(destination.join("Contents/Info.plist"), info)?;
    }
    if let Some(tools) = tools {
        let bin = if cfg!(target_os = "macos") {
            destination.join("Contents/Resources/bin")
        } else {
            destination.to_path_buf()
        };
        for name in ["ffmpeg", "ffprobe"] {
            let name = if cfg!(windows) {
                format!("{name}.exe")
            } else {
                name.to_owned()
            };
            copy_file(&tools.join(&name), &bin.join(name))?;
        }
        if cfg!(windows) {
            for entry in std::fs::read_dir(tools)? {
                let entry = entry?;
                if entry
                    .path()
                    .extension()
                    .is_some_and(|e| e.eq_ignore_ascii_case("dll"))
                {
                    copy_file(&entry.path(), &bin.join(entry.file_name()))?;
                }
            }
        }
        #[cfg(target_os = "macos")]
        {
            let minimum = bundle_dylibs(&bin, tools)?;
            let plist = destination.join("Contents/Info.plist");
            let info = std::fs::read_to_string(&plist)?.replace(
                "<string>13.0</string>",
                &format!("<string>{minimum}</string>"),
            );
            std::fs::write(plist, info)?;
        }
    }
    #[cfg(target_os = "macos")]
    checked(
        Command::new("/usr/bin/codesign")
            .args(["--force", "--sign", "-", "--identifier", BUNDLE_ID])
            .arg(destination),
        "Couldn’t sign Daniil Cast.app",
    )?;
    if tools.is_some() {
        let bin = if cfg!(target_os = "macos") {
            destination.join("Contents/Resources/bin")
        } else {
            destination.to_path_buf()
        };
        for name in ["ffmpeg", "ffprobe"] {
            let binary = bin.join(if cfg!(windows) {
                format!("{name}.exe")
            } else {
                name.to_owned()
            });
            verify_tool(&binary)?;
        }
    }
    Ok(binary)
}

fn verify_tool(binary: &Path) -> Result<()> {
    let mut child = std::process::Command::new(binary)
        .arg("-version")
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn()?;
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(10);
    loop {
        if let Some(status) = child.try_wait()? {
            if !status.success() {
                bail!(
                    "Bundled {} could not start; check its libraries",
                    binary.display()
                );
            }
            return Ok(());
        }
        if std::time::Instant::now() >= deadline {
            let _ = child.kill();
            let _ = child.wait();
            bail!(
                "Bundled {} did not start within ten seconds; check its runtime libraries",
                binary.display()
            );
        }
        std::thread::sleep(std::time::Duration::from_millis(20));
    }
}
/// Package locally built binaries. Release builds may replace the ad-hoc signature with Developer ID signing.
pub fn package(output: &Path, tools: Option<&Path>) -> Result<PathBuf> {
    std::fs::create_dir_all(output)?;
    let destination = output.join(if cfg!(target_os = "macos") {
        "Daniil Cast.app"
    } else {
        "DaniilCast"
    });
    let staging = tempfile::Builder::new()
        .prefix(".daniil-cast-package-")
        .tempdir_in(output)?;
    let staged = staging.path().join(destination.file_name().unwrap());
    make_bundle(&staged, tools)?;
    if destination.exists() {
        std::fs::remove_dir_all(&destination)?;
    }
    std::fs::rename(staged, &destination)?;
    Ok(destination)
}
pub fn install(
    destination: Option<PathBuf>,
    registry: Option<PathBuf>,
    extension_id: &str,
    tools: Option<&Path>,
) -> Result<PathBuf> {
    if extension_id.len() != 32 || !extension_id.bytes().all(|c| (b'a'..=b'p').contains(&c)) {
        bail!("Invalid Chrome extension ID");
    }
    let destination = destination.unwrap_or_else(default_destination);
    let parent = destination
        .parent()
        .context("Invalid application destination")?;
    std::fs::create_dir_all(parent)?;
    let current = std::env::current_exe()?;
    let installed = binary_path(&destination);
    if !same_file(&current, &installed) || tools.is_some() {
        let staging = tempfile::Builder::new()
            .prefix(".daniil-cast-install-")
            .tempdir_in(parent)?;
        let staged = staging.path().join(
            destination
                .file_name()
                .context("Invalid application destination")?,
        );
        let source_bundle = current
            .parent()
            .and_then(Path::parent)
            .filter(|p| p.file_name().is_some_and(|n| n == "Contents"))
            .and_then(Path::parent);
        if let Some(source) = source_bundle.filter(|_| cfg!(target_os = "macos") && tools.is_none())
        {
            copy_tree(source, &staged)?;
        } else {
            let companion_tools = current.parent().filter(|path| {
                !cfg!(target_os = "macos")
                    && path
                        .join(if cfg!(windows) {
                            "ffmpeg.exe"
                        } else {
                            "ffmpeg"
                        })
                        .is_file()
                    && path
                        .join(if cfg!(windows) {
                            "ffprobe.exe"
                        } else {
                            "ffprobe"
                        })
                        .is_file()
            });
            make_bundle(&staged, tools.or(companion_tools))?;
        }
        if destination.exists() {
            if cfg!(target_os = "macos")
                && !std::fs::read_to_string(destination.join("Contents/Info.plist"))
                    .unwrap_or_default()
                    .contains(BUNDLE_ID)
            {
                bail!("The destination is occupied by a different application");
            }
            let old = staging.path().join("previous");
            std::fs::rename(&destination, &old)?;
            if let Err(error) = std::fs::rename(&staged, &destination) {
                let _ = std::fs::rename(old, &destination);
                return Err(error.into());
            }
        } else {
            std::fs::rename(staged, &destination)?;
        }
    }
    let directory = registry.unwrap_or_else(registry_directory);
    paths::private_dir(&directory)?;
    let manifest = directory.join("tv.daniil.cast.json");
    let body = json!({"name": "tv.daniil.cast", "description": "Daniil Cast helper: paired TVs and video conversion", "path": installed,
        "type": "stdio", "allowed_origins": [format!("chrome-extension://{extension_id}/")]});
    let mut temporary = tempfile::NamedTempFile::new_in(directory)?;
    serde_json::to_writer_pretty(&mut temporary, &body)?;
    paths::private_file(temporary.path())?;
    temporary.persist(&manifest)?;
    #[cfg(windows)]
    checked(
        Command::new("reg")
            .args([
                "add",
                r"HKCU\Software\Google\Chrome\NativeMessagingHosts\tv.daniil.cast",
                "/ve",
                "/t",
                "REG_SZ",
                "/d",
            ])
            .arg(&manifest)
            .arg("/f"),
        "Couldn’t register the Chrome native host",
    )?;
    Ok(installed)
}

#[cfg(target_os = "macos")]
fn bundle_dylibs(bin: &Path, tools: &Path) -> Result<String> {
    let lib = bin.parent().unwrap().join("lib");
    std::fs::create_dir_all(&lib)?;
    let mut queue: VecDeque<_> = [
        (tools.join("ffmpeg"), bin.join("ffmpeg")),
        (tools.join("ffprobe"), bin.join("ffprobe")),
    ]
    .into();
    let mut copied = BTreeMap::<String, PathBuf>::new();
    let mut modified = Vec::new();
    let mut minimum = [13_u32, 0, 0];
    while let Some((original, binary)) = queue.pop_front() {
        let commands = Command::new("/usr/bin/otool")
            .arg("-l")
            .arg(&original)
            .output()?;
        let commands = String::from_utf8_lossy(&commands.stdout);
        let mut version_key = None;
        for line in commands.lines().map(str::trim) {
            if line.starts_with("cmd ") {
                version_key = match line {
                    "cmd LC_BUILD_VERSION" => Some("minos "),
                    "cmd LC_VERSION_MIN_MACOSX" => Some("version "),
                    _ => None,
                };
            }
            if let Some(key) = version_key {
                if let Some(value) = line.strip_prefix(key) {
                    let mut version = [0; 3];
                    for (part, value) in version.iter_mut().zip(value.split('.')) {
                        *part = value.parse().unwrap_or(0);
                    }
                    minimum = minimum.max(version);
                }
            }
        }
        let output = Command::new("/usr/bin/otool")
            .arg("-L")
            .arg(&original)
            .output()?;
        if !output.status.success() {
            bail!("Couldn’t inspect bundled FFmpeg libraries");
        }
        // Homebrew's SDL2 compatibility library dlopens SDL3; otool cannot see this dependency.
        if binary
            .file_name()
            .is_some_and(|name| name.to_string_lossy().starts_with("libSDL2"))
            && std::fs::read(&original)?
                .windows(b"sdl2-compat".len())
                .any(|window| window == b"sdl2-compat")
        {
            let name = "libSDL3.dylib";
            if !copied.contains_key(name) {
                let source = resolve_dylib("@rpath/libSDL3.dylib", &original, tools)?;
                copy_file(&source, &lib.join(name))?;
                copied.insert(name.into(), source.clone());
                queue.push_back((source, lib.join(name)));
            }
        }
        for (index, line) in String::from_utf8_lossy(&output.stdout)
            .lines()
            .skip(1)
            .enumerate()
        {
            if index == 0 && binary.parent() == Some(lib.as_path()) {
                continue;
            } // LC_ID_DYLIB, including versioned aliases.
            let Some(dependency) = line.split_whitespace().next() else {
                continue;
            };
            if dependency.starts_with("/usr/lib/") || dependency.starts_with("/System/") {
                continue;
            }
            if binary.parent() == Some(lib.as_path())
                && Path::new(dependency).file_name() == binary.file_name()
            {
                continue;
            }
            let source = resolve_dylib(dependency, &original, tools)?;
            let name = source
                .file_name()
                .context("Invalid FFmpeg library path")?
                .to_string_lossy()
                .into_owned();
            if binary.file_name().is_some_and(|n| n == name.as_str()) {
                continue;
            } // Dylib's own install name.
            if let Some(previous) = copied.get(&name) {
                if previous.canonicalize()? != source.canonicalize()?
                    && !same_file(previous, &source)
                {
                    bail!("Conflicting bundled library {name}");
                }
            } else {
                copy_file(&source, &lib.join(&name))?;
                copied.insert(name.clone(), source.clone());
                queue.push_back((source, lib.join(&name)));
            }
            let relative = if binary.parent() == Some(bin) {
                format!("@loader_path/../lib/{name}")
            } else {
                format!("@loader_path/{name}")
            };
            checked(
                Command::new("/usr/bin/install_name_tool")
                    .arg("-change")
                    .arg(dependency)
                    .arg(relative)
                    .arg(&binary),
                "Couldn’t relocate FFmpeg libraries",
            )?;
        }
        if binary.parent() == Some(lib.as_path()) {
            checked(
                Command::new("/usr/bin/install_name_tool")
                    .arg("-id")
                    .arg(format!(
                        "@rpath/{}",
                        binary.file_name().unwrap().to_string_lossy()
                    ))
                    .arg(&binary),
                "Couldn’t set the bundled library identity",
            )?;
        }
        modified.push(binary);
    }
    for binary in modified {
        checked(
            Command::new("/usr/bin/codesign")
                .args(["--force", "--sign", "-"])
                .arg(binary),
            "Couldn’t sign bundled FFmpeg",
        )?;
    }
    Ok(format!("{}.{}.{}", minimum[0], minimum[1], minimum[2]))
}

#[cfg(target_os = "macos")]
fn resolve_dylib(dependency: &str, original: &Path, tools: &Path) -> Result<PathBuf> {
    let loader = original.parent().context("Missing library directory")?;
    let expand = |path: &str| -> PathBuf {
        if let Some(rest) = path.strip_prefix("@loader_path/") {
            loader.join(rest)
        } else if let Some(rest) = path.strip_prefix("@executable_path/") {
            tools.join(rest)
        } else {
            PathBuf::from(path)
        }
    };
    if let Some(name) = dependency.strip_prefix("@rpath/") {
        let output = Command::new("/usr/bin/otool")
            .arg("-l")
            .arg(original)
            .output()?;
        let output = String::from_utf8_lossy(&output.stdout);
        let mut in_rpath = false;
        let mut candidates = Vec::new();
        for line in output.lines().map(str::trim) {
            if line.starts_with("cmd ") {
                in_rpath = line == "cmd LC_RPATH";
            }
            if in_rpath {
                if let Some(path) = line
                    .strip_prefix("path ")
                    .and_then(|s| s.split(" (offset").next())
                {
                    candidates.push(expand(path).join(name));
                }
            }
        }
        candidates.extend([
            loader.join(name),
            PathBuf::from("/opt/homebrew/lib").join(name),
            PathBuf::from("/usr/local/lib").join(name),
        ]);
        return candidates
            .into_iter()
            .find(|path| path.is_file())
            .with_context(|| format!("Couldn’t resolve FFmpeg dependency {dependency}"));
    }
    let path = expand(dependency);
    if !path.is_file() {
        bail!("Couldn’t resolve FFmpeg dependency {dependency}");
    }
    Ok(path)
}
