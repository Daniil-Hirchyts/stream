use directories::BaseDirs;
use std::path::PathBuf;

fn base() -> BaseDirs {
    BaseDirs::new().expect("The current user must have a home directory")
}
pub fn home() -> PathBuf {
    base().home_dir().to_path_buf()
}
pub fn data() -> PathBuf {
    std::env::var_os("DANIILCAST_HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            if cfg!(target_os = "macos") {
                home().join("Library/Application Support/DaniilCast")
            } else {
                base().data_local_dir().join("DaniilCast")
            }
        })
}
pub fn log() -> PathBuf {
    std::env::var_os("DANIILCAST_LOG")
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            if cfg!(target_os = "macos") {
                home().join("Library/Logs/DaniilCast/host.log")
            } else {
                data().join("logs/host.log")
            }
        })
}
pub fn cache() -> PathBuf {
    std::env::var_os("DANIILCAST_CACHE")
        .map(PathBuf::from)
        .unwrap_or_else(|| base().cache_dir().join("DaniilCast/native-v2"))
}
pub fn private_dir(path: &std::path::Path) -> std::io::Result<()> {
    std::fs::create_dir_all(path)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o700))?;
    }
    Ok(())
}
pub fn private_file(path: &std::path::Path) -> std::io::Result<()> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600))?;
    }
    #[cfg(not(unix))]
    let _ = path;
    Ok(())
}
pub fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}
