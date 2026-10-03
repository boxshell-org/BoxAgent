//! Device bootstrap over the ADB session: push the daemon, spawn it, and
//! generic shell access. Backed by `adb_client`'s message transport
//! (TLS-capable `TcpTransport`).

use adb_client::{ADBDeviceExt, ADBTcpDevice};
use std::io::Cursor;
use std::net::SocketAddr;
use std::path::PathBuf;
use thiserror::Error;

#[derive(Debug, Error)]
pub enum AdbOpsError {
    #[error("io: {0}")]
    Io(#[from] std::io::Error),
    #[error("adb: {0}")]
    Adb(String),
}

fn key_file(pem: &str) -> Result<PathBuf, AdbOpsError> {
    let dir = std::env::temp_dir();
    let path = dir.join("boxagent_adbkey");
    if std::fs::read_to_string(&path).ok().as_deref() != Some(pem) {
        std::fs::write(&path, pem)?;
    }
    Ok(path)
}

fn device(pem: &str, addr: SocketAddr) -> Result<ADBTcpDevice, AdbOpsError> {
    let pk = key_file(pem)?;
    ADBTcpDevice::new_with_custom_private_key(addr, pk)
        .map_err(|e| AdbOpsError::Adb(format!("{e}")))
}

/// Push `daemon` bytes to the device and spawn it detached.
/// Returns the shell output of the spawn command for diagnostics.
pub fn spawn_daemon(
    pem: &str,
    addr: SocketAddr,
    daemon_bytes: &[u8],
    remote_path: &str,
    socket_name: &str,
    token: &str,
) -> Result<String, AdbOpsError> {
    let mut dev = device(pem, addr)?;

    let mut cur = Cursor::new(daemon_bytes);
    dev.push(&mut cur, &remote_path)
        .map_err(|e| AdbOpsError::Adb(format!("push: {e}")))?;

    let spawn = format!(
        "chmod 755 {p} && (setsid {p} --socket {s} --token {t} \
         </dev/null >/data/local/tmp/boxagentd.log 2>&1 &)",
        p = remote_path,
        s = socket_name,
        t = token
    );
    let mut out = Vec::new();
    let mut err = Vec::new();
    dev.shell_command(&spawn, Some(&mut out), Some(&mut err))
        .map_err(|e| AdbOpsError::Adb(format!("spawn: {e}")))?;

    Ok(format!(
        "{}{}",
        String::from_utf8_lossy(&out),
        String::from_utf8_lossy(&err)
    ))
}

/// One-off diagnostic shell command over the ADB session.
pub fn adb_shell(pem: &str, addr: SocketAddr, cmd: &str) -> Result<String, AdbOpsError> {
    let mut dev = device(pem, addr)?;
    let mut out = Vec::new();
    let mut err = Vec::new();
    dev.shell_command(&cmd, Some(&mut out), Some(&mut err))
        .map_err(|e| AdbOpsError::Adb(format!("{e}")))?;
    Ok(format!(
        "{}{}",
        String::from_utf8_lossy(&out),
        String::from_utf8_lossy(&err)
    ))
}
