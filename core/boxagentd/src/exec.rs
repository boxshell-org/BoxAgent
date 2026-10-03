//! Command execution with streamed stdout/stderr and timeout enforcement.

use anyhow::Result;
use base64::Engine;
use boxagent_proto::{write_frame, Response};
use std::process::Stdio;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::io::{AsyncReadExt, WriteHalf};
use tokio::net::UnixStream;
use tokio::process::{Child, Command};
use tokio::sync::Mutex;

const CHUNK: usize = 32 * 1024;
const SCREENCAP_TIMEOUT: Duration = Duration::from_secs(15);

fn sh(cmd: &str) -> Command {
    let mut c = Command::new("/system/bin/sh");
    c.arg("-c").arg(cmd);
    c.stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        // Detach into its own process group so a timeout kill doesn't leave
        // grandchildren holding pipes open.
        .process_group(0);
    c
}

async fn stream_pipe<R: AsyncReadExt + Unpin>(
    id: u64,
    name: &'static str,
    mut pipe: R,
    wr: Arc<Mutex<WriteHalf<UnixStream>>>,
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
                if wr.lock().await.write_all_frame(&msg).await.is_err() {
                    break;
                }
            }
        }
    }
}

trait FrameSink {
    async fn write_all_frame(&mut self, v: &Response) -> Result<()>;
}
impl FrameSink for WriteHalf<UnixStream> {
    async fn write_all_frame(&mut self, v: &Response) -> Result<()> {
        Ok(write_frame(self, v).await?)
    }
}

async fn wait_child(child: &mut Child, timeout: Duration) -> (Option<i32>, bool) {
    match tokio::time::timeout(timeout, child.wait()).await {
        Ok(Ok(status)) => (status.code(), false),
        Ok(Err(_)) => (None, false),
        Err(_) => {
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
    let wr = Arc::new(Mutex::new(wr));

    let mut child = match sh(cmd).spawn() {
        Ok(c) => c,
        Err(e) => {
            wr.lock()
                .await
                .write_all_frame(&Response::Err {
                    id: Some(id),
                    message: format!("spawn: {e}"),
                })
                .await?;
            return Ok(());
        }
    };

    let stdout = child.stdout.take();
    let stderr = child.stderr.take();
    let mut handles = Vec::new();
    if let Some(p) = stdout {
        let w = wr.clone();
        handles.push(tokio::spawn(stream_pipe(id, "stdout", p, w)));
    }
    if let Some(p) = stderr {
        let w = wr.clone();
        handles.push(tokio::spawn(stream_pipe(id, "stderr", p, w)));
    }

    let (exit, timed_out) = wait_child(&mut child, timeout).await;
    for h in handles {
        let _ = h.await;
    }

    wr.lock()
        .await
        .write_all_frame(&Response::ExecDone {
            id,
            exit: exit.unwrap_or(-1),
            duration_ms: started.elapsed().as_millis() as u64,
            timed_out,
        })
        .await?;
    Ok(())
}

/// `screencap -p` under shell uid captures the primary display.
pub async fn screencap(id: u64, wr: &mut WriteHalf<UnixStream>) -> Result<()> {
    use base64::Engine;
    let out = tokio::time::timeout(
        SCREENCAP_TIMEOUT,
        sh("screencap -p").output(),
    )
    .await;

    match out {
        Ok(Ok(o)) if o.status.success() && !o.stdout.is_empty() => {
            write_frame(
                wr,
                &Response::FileData {
                    id,
                    data_b64: base64::engine::general_purpose::STANDARD
                        .encode(o.stdout),
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
