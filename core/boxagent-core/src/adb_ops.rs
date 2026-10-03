//! Device bootstrap over the ADB session: push the daemon, spawn it, and
//! generic shell access. Backed by `adb_client`'s message transport
//! (TLS-capable `TcpTransport`).

use adb_client::{tcp::ADBTcpDevice, ADBDeviceExt};
use std::io::{Cursor, Write};
use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use thiserror::Error;

#[derive(Debug, Error)]
pub enum AdbOpsError {
    #[error("io: {0}")]
    Io(#[from] std::io::Error),
    #[error("adb: {0}")]
    Adb(String),
}

/// `adb_client` only takes the private key as a file path. Materialize it
/// per operation — unique name (concurrent ops can't race on one file),
/// 0600, removed on drop so the PEM doesn't linger on disk. The app points
/// TMPDIR at its private cache dir (Android's default, /data/local/tmp, is
/// not writable by apps).
struct KeyFile(PathBuf);

impl KeyFile {
    fn new(pem: &str) -> Result<Self, AdbOpsError> {
        use std::os::unix::fs::OpenOptionsExt;
        static SEQ: AtomicU64 = AtomicU64::new(0);
        let path = std::env::temp_dir().join(format!(
            "boxagent_adbkey.{}.{}",
            std::process::id(),
            SEQ.fetch_add(1, Ordering::Relaxed)
        ));
        let mut f = std::fs::OpenOptions::new()
            .write(true)
            .create(true)
            .truncate(true)
            .mode(0o600)
            .open(&path)
            .map_err(|e| AdbOpsError::Adb(format!("key file {}: {e}", path.display())))?;
        let key = KeyFile(path);
        f.write_all(pem.as_bytes())?;
        Ok(key)
    }
}

impl Drop for KeyFile {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
    }
}

fn device(pem: &str, addr: SocketAddr) -> Result<(ADBTcpDevice, KeyFile), AdbOpsError> {
    let key = KeyFile::new(pem)?;
    let dev = ADBTcpDevice::new_with_custom_private_key(addr, &key.0)
        .map_err(|e| AdbOpsError::Adb(format!("{e}")))?;
    Ok((dev, key))
}

/// Shell single-quote `s` for `sh -c`.
fn sq(s: &str) -> String {
    format!("'{}'", s.replace('\'', "'\\''"))
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
    let (mut dev, _key) = device(pem, addr)?;

    let mut cur = Cursor::new(daemon_bytes);
    dev.push(&mut cur, &remote_path)
        .map_err(|e| AdbOpsError::Adb(format!("push: {e}")))?;

    // One daemon at a time: a previous instance whose socket/token the app
    // lost (data cleared, crash before persisting) would otherwise run
    // forever. `-x` matches the process name only, never this `sh -c`.
    let spawn = format!(
        "pkill -x boxagentd; chmod 755 {p} && (setsid {p} --socket {s} --token {t} \
         </dev/null >/data/local/tmp/boxagentd.log 2>&1 &)",
        p = sq(remote_path),
        s = sq(socket_name),
        t = sq(token)
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
    let (mut dev, _key) = device(pem, addr)?;
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn key_file_is_private_and_removed() {
        use std::os::unix::fs::PermissionsExt;
        let k = KeyFile::new("PEM").unwrap();
        let p = k.0.clone();
        assert_eq!(std::fs::read_to_string(&p).unwrap(), "PEM");
        assert_eq!(
            std::fs::metadata(&p).unwrap().permissions().mode() & 0o777,
            0o600
        );
        let k2 = KeyFile::new("PEM").unwrap();
        assert_ne!(k2.0, p, "concurrent ops need distinct files");
        drop(k);
        assert!(!p.exists());
    }

    #[test]
    fn sq_quotes_for_sh() {
        assert_eq!(sq("a b"), "'a b'");
        assert_eq!(sq("it's"), "'it'\\''s'");
    }
}
