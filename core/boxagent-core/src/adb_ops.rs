//! Device bootstrap over the ADB session: push the daemon, spawn it, and
//! generic shell access. Backed by `adb_client`'s message transport
//! (TLS-capable `TcpTransport`).

use adb_client::{tcp::ADBTcpDevice, ADBDeviceExt};
use std::io::{Cursor, Write};
use std::net::{SocketAddr, TcpStream};
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc;
use std::thread;
use std::time::Duration;
use thiserror::Error;

/// `adb_client`'s transport reads with an effectively unbounded timeout —
/// a live non-adb port (or a middlebox that accepts then drops) hangs the
/// JNI call and the Kotlin mutex serializing daemon ops with it. Every op
/// runs on a worker with a hard deadline; on timeout the worker is left
/// detached holding a dead socket while the caller fails fast.
const SPAWN_DEADLINE: Duration = Duration::from_secs(25);
const SHELL_DEADLINE: Duration = Duration::from_secs(12);
/// `TcpStream::connect` inside `adb_client` is unbounded too (kernel SYN
/// retries ~2min for a filtered host) — probe reachability ourselves so a
/// dead endpoint fails in milliseconds and a blackholed one in seconds.
const CONNECT_TIMEOUT: Duration = Duration::from_secs(8);

fn run_deadlined<T, F>(deadline: Duration, what: &'static str, f: F) -> Result<T, AdbOpsError>
where
    F: FnOnce() -> Result<T, AdbOpsError> + Send + 'static,
    T: Send + 'static,
{
    let (tx, rx) = mpsc::channel();
    thread::spawn(move || {
        let _ = tx.send(f());
    });
    rx.recv_timeout(deadline)
        .unwrap_or_else(|_| Err(AdbOpsError::Adb(format!("{what} timed out"))))
}

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
    TcpStream::connect_timeout(&addr, CONNECT_TIMEOUT)?;
    let key = KeyFile::new(pem)?;
    let dev = ADBTcpDevice::new_with_custom_private_key(addr, &key.0)
        .map_err(|e| AdbOpsError::Adb(format!("{e}")))?;
    Ok((dev, key))
}

/// Shell single-quote `s` for `sh -c`.
fn sq(s: &str) -> String {
    format!("'{}'", s.replace('\'', "'\\''"))
}

/// Daemon listen flag for an app-side endpoint: `tcp:<port>` → loopback
/// TCP (what the app uses — SELinux lets apps reach it), anything else is
/// a legacy abstract socket name.
fn listen_flag(listen: &str) -> String {
    match listen.strip_prefix("tcp:") {
        Some(port) => format!("--port {}", sq(port)),
        None => format!("--socket {}", sq(listen)),
    }
}

/// Push `daemon` bytes to the device and spawn it detached, listening on
/// `listen` (see [`listen_flag`]). Returns the shell output of the spawn
/// command for diagnostics.
pub fn spawn_daemon(
    pem: &str,
    addr: SocketAddr,
    daemon_bytes: &[u8],
    remote_path: &str,
    listen: &str,
    token: &str,
) -> Result<String, AdbOpsError> {
    let pem = pem.to_owned();
    let bytes = daemon_bytes.to_vec();
    let remote_path = remote_path.to_owned();
    let listen = listen.to_owned();
    let token = token.to_owned();
    run_deadlined(SPAWN_DEADLINE, "spawn daemon", move || {
        spawn_daemon_inner(&pem, addr, &bytes, &remote_path, &listen, &token)
    })
}

fn spawn_daemon_inner(
    pem: &str,
    addr: SocketAddr,
    daemon_bytes: &[u8],
    remote_path: &str,
    listen: &str,
    token: &str,
) -> Result<String, AdbOpsError> {
    let (mut dev, _key) = device(pem, addr)?;

    let mut cur = Cursor::new(daemon_bytes);
    dev.push(&mut cur, &remote_path)
        .map_err(|e| AdbOpsError::Adb(format!("push: {e}")))?;

    // One daemon at a time: a previous instance whose socket/token the app
    // lost (data cleared, crash before persisting) would otherwise run
    // forever. `-x` matches the process name only, never this `sh -c`.
    //
    // `trap '' HUP` before anything else: when this `sh` exits, the kernel
    // SIGHUPs the orphaned process group — and the `setsid` binary is still
    // being linked (~10ms) so it dies before it can detach. An ignored
    // disposition survives fork+exec, so the daemon is immune from birth.
    let spawn = format!(
        "trap '' HUP; pkill -x boxagentd; chmod 755 {p} && \
         (setsid {p} {l} --token {t} \
         </dev/null >/data/local/tmp/boxagentd.log 2>&1 &)",
        p = sq(remote_path),
        l = listen_flag(listen),
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
    let pem = pem.to_owned();
    let cmd = cmd.to_owned();
    run_deadlined(SHELL_DEADLINE, "adb shell", move || {
        adb_shell_inner(&pem, addr, &cmd)
    })
}

fn adb_shell_inner(pem: &str, addr: SocketAddr, cmd: &str) -> Result<String, AdbOpsError> {
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
    fn listen_flag_picks_tcp_or_abstract() {
        assert_eq!(listen_flag("tcp:41234"), "--port '41234'");
        assert_eq!(listen_flag("boxagentd.1f"), "--socket 'boxagentd.1f'");
    }

    #[test]
    fn sq_quotes_for_sh() {
        assert_eq!(sq("a b"), "'a b'");
        assert_eq!(sq("it's"), "'it'\\''s'");
    }
}
