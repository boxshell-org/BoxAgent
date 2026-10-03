//! Command execution with streamed stdout/stderr and timeout enforcement.

use anyhow::Result;
use base64::Engine;
use boxagent_proto::{write_frame, Response};
use std::process::Stdio;
use std::time::{Duration, Instant};
use tokio::io::{AsyncReadExt, WriteHalf};
use tokio::net::UnixStream;
use tokio::process::{Child, Command};
use tokio::sync::mpsc;

const CHUNK: usize = 32 * 1024;
const SCREENCAP_TIMEOUT: Duration = Duration::from_secs(15);
const DRAIN_TIMEOUT: Duration = Duration::from_secs(3);

/// Android's shell, or the host's when the daemon runs in host tests.
fn shell_path() -> &'static str {
    if std::path::Path::new("/system/bin/sh").exists() {
        "/system/bin/sh"
    } else {
        "/bin/sh"
    }
}

fn sh(cmd: &str) -> Command {
    let mut c = Command::new(shell_path());
    c.arg("-c").arg(cmd);
    c.stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        // Detach into its own process group so a timeout kill can take
        // the whole tree down (see `PgKill`), not just the `sh` leader.
        .process_group(0)
        .kill_on_drop(true);
    c
}

/// SIGKILLs the child's process group on drop unless disarmed — covers the
/// timeout path and early returns when the client disconnects mid-stream.
struct PgKill(Option<i32>);

impl PgKill {
    fn kill_now(&mut self) {
        if let Some(pgid) = self.0.take() {
            // Negative pid = the whole group; leader pid == pgid here.
            unsafe { libc::kill(-pgid, libc::SIGKILL) };
        }
    }
}

impl Drop for PgKill {
    fn drop(&mut self) {
        self.kill_now();
    }
}

/// Read `pipe` to EOF, forwarding each chunk over `tx`. Lives in its own
/// task so stdout and stderr interleave; owns nothing but the sender.
async fn stream_pipe<R: AsyncReadExt + Unpin>(
    id: u64,
    name: &'static str,
    mut pipe: R,
    tx: mpsc::Sender<Response>,
) {
    let b64 = base64::engine::general_purpose::STANDARD;
    let mut buf = vec![0u8; CHUNK];
    loop {
        match pipe.read(&mut buf).await {
            Ok(0) | Err(_) => break,
            Ok(n) => {
                let msg = Response::Chunk {
                    id,
                    stream: name.into(),
                    data_b64: b64.encode(&buf[..n]),
                };
                if tx.send(msg).await.is_err() {
                    break;
                }
            }
        }
    }
}

async fn wait_child(child: &mut Child, pg: &mut PgKill, timeout: Duration) -> (Option<i32>, bool) {
    match tokio::time::timeout(timeout, child.wait()).await {
        Ok(Ok(status)) => (status.code(), false),
        Ok(Err(_)) => (None, false),
        Err(_) => {
            pg.kill_now();
            let _ = child.kill().await;
            let _ = child.wait().await;
            (None, true)
        }
    }
}

pub async fn run(
    id: u64,
    cmd: &str,
    timeout_ms: u64,
    wr: &mut WriteHalf<UnixStream>,
) -> Result<()> {
    let started = Instant::now();
    let timeout = Duration::from_millis(timeout_ms.max(500));

    let mut child = match sh(cmd).spawn() {
        Ok(c) => c,
        Err(e) => {
            write_frame(
                wr,
                &Response::Err {
                    id: Some(id),
                    message: format!("spawn: {e}"),
                },
            )
            .await?;
            return Ok(());
        }
    };

    let mut pg = PgKill(child.id().map(|p| p as i32));

    let (tx, mut rx) = mpsc::channel::<Response>(64);
    let mut handles = Vec::new();
    if let Some(p) = child.stdout.take() {
        handles.push(tokio::spawn(stream_pipe(id, "stdout", p, tx.clone())));
    }
    if let Some(p) = child.stderr.take() {
        handles.push(tokio::spawn(stream_pipe(id, "stderr", p, tx.clone())));
    }
    drop(tx);

    // Interleave streamed chunks with the wait so output flows live and a
    // chatty command can't deadlock on a full channel.
    let mut wait = Box::pin(wait_child(&mut child, &mut pg, timeout));
    let mut rx_open = true;
    let (exit, timed_out) = loop {
        tokio::select! {
            msg = rx.recv(), if rx_open => match msg {
                Some(m) => write_frame(wr, &m).await?,
                None => rx_open = false,
            },
            r = &mut wait => break r,
        }
    };
    drop(wait);
    // Normal exit: leave intentional background jobs (`cmd &`) alone —
    // only timeouts and aborted requests take the group down.
    if !timed_out {
        pg.0 = None;
    }

    // Bounded drain: grandchildren may hold pipes open after the leader dies.
    let _ = tokio::time::timeout(DRAIN_TIMEOUT, async {
        while let Some(m) = rx.recv().await {
            let _ = write_frame(wr, &m).await;
        }
    })
    .await;
    for h in handles {
        h.abort();
    }

    write_frame(
        wr,
        &Response::ExecDone {
            id,
            exit: exit.unwrap_or(-1),
            duration_ms: started.elapsed().as_millis() as u64,
            timed_out,
        },
    )
    .await?;
    Ok(())
}

/// `screencap -p` under shell uid captures the primary display.
pub async fn screencap(id: u64, wr: &mut WriteHalf<UnixStream>) -> Result<()> {
    use base64::Engine;
    let out = tokio::time::timeout(SCREENCAP_TIMEOUT, sh("screencap -p").output()).await;

    match out {
        Ok(Ok(o)) if o.stdout.len() as u64 > crate::fsops::MAX_READ => {
            write_frame(
                wr,
                &Response::Err {
                    id: Some(id),
                    message: format!("screencap too large: {} bytes", o.stdout.len()),
                },
            )
            .await?;
        }
        Ok(Ok(o)) if o.status.success() && !o.stdout.is_empty() => {
            write_frame(
                wr,
                &Response::FileData {
                    id,
                    data_b64: base64::engine::general_purpose::STANDARD.encode(o.stdout),
                },
            )
            .await?;
        }
        Ok(Ok(o)) => {
            write_frame(
                wr,
                &Response::Err {
                    id: Some(id),
                    message: format!(
                        "screencap exit {:?}: {}",
                        o.status.code(),
                        String::from_utf8_lossy(&o.stderr)
                    ),
                },
            )
            .await?;
        }
        Ok(Err(e)) => {
            write_frame(
                wr,
                &Response::Err {
                    id: Some(id),
                    message: format!("screencap: {e}"),
                },
            )
            .await?;
        }
        Err(_) => {
            write_frame(
                wr,
                &Response::Err {
                    id: Some(id),
                    message: "screencap timed out".into(),
                },
            )
            .await?;
        }
    }
    Ok(())
}
