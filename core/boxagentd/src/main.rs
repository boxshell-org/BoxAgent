//! boxagentd — privileged execution daemon.
//!
//! Spawned via `adb shell` (runs as uid 2000, `shell`). Listens on an
//! abstract-namespace Unix socket, authenticates the first frame with the
//! one-time token passed on argv, then serves exec / file / screencap
//! requests framed as length-prefixed JSON (see boxagent-proto).

mod exec;
mod fsops;
mod sock;

use anyhow::{Context, Result};
use boxagent_proto::{read_frame, write_frame, Request, Response};
use std::sync::Arc;
use tokio::io::{ReadHalf, WriteHalf};
use tokio::net::UnixStream;
use tracing::{error, info, warn};

struct Args {
    socket_name: String,
    token: String,
}

fn parse_args() -> Result<Args> {
    let mut socket_name = None;
    let mut token = None;
    let mut it = std::env::args().skip(1);
    while let Some(a) = it.next() {
        match a.as_str() {
            "--socket" => socket_name = it.next(),
            "--token" => token = it.next(),
            other => anyhow::bail!("unknown arg: {other}"),
        }
    }
    Ok(Args {
        socket_name: socket_name.context("--socket required")?,
        token: token.context("--token required")?,
    })
}

#[tokio::main(flavor = "multi_thread", worker_threads = 4)]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_writer(std::io::stderr)
        .with_ansi(false)
        .init();

    let args = Arc::new(parse_args()?);
    let listener = sock::bind_abstract(&args.socket_name)
        .with_context(|| format!("bind @{}", args.socket_name))?;
    info!("boxagentd up: @{} uid={}", args.socket_name, unsafe {
        libc::getuid()
    });

    loop {
        match listener.accept().await {
            Ok((stream, _)) => {
                let args = args.clone();
                tokio::spawn(async move {
                    if let Err(e) = handle_conn(stream, args).await {
                        warn!("conn ended: {e:#}");
                    }
                });
            }
            Err(e) => error!("accept: {e}"),
        }
    }
}

async fn handle_conn(stream: UnixStream, args: Arc<Args>) -> Result<()> {
    let (mut rd, mut wr) = tokio::io::split(stream);

    // First frame must be Auth with the one-time token.
    let first: Request = read_frame(&mut rd).await?;
    match &first {
        Request::Auth { token } if token == &args.token => {
            let uid = unsafe { libc::getuid() } as u32;
            write_frame(&mut wr, &Response::AuthOk { uid }).await?;
        }
        _ => {
            let _ = write_frame(
                &mut wr,
                &Response::Err {
                    id: None,
                    message: "auth failed".into(),
                },
            )
            .await;
            anyhow::bail!("auth failed");
        }
    }

    loop {
        let req: Request = match read_frame(&mut rd).await {
            Ok(r) => r,
            Err(boxagent_proto::ProtoError::Closed) => return Ok(()),
            Err(e) => return Err(e.into()),
        };
        dispatch(req, &mut rd, &mut wr).await?;
    }
}

async fn dispatch(
    req: Request,
    rd: &mut ReadHalf<UnixStream>,
    wr: &mut WriteHalf<UnixStream>,
) -> Result<()> {
    match req {
        Request::Ping => write_frame(wr, &Response::Pong).await?,
        Request::Exec {
            id,
            cmd,
            timeout_ms,
        } => exec::run(id, &cmd, timeout_ms, wr).await?,
        Request::FileRead { id, path } => {
            match fsops::read(&path) {
                Ok(data) => {
                    write_frame(
                        wr,
                        &Response::FileData {
                            id,
                            data_b64: base64::Engine::encode(
                                &base64::engine::general_purpose::STANDARD,
                                data,
                            ),
                        },
                    )
                    .await?
                }
                Err(e) => send_err(wr, id, e).await?,
            }
        }
        Request::FileWrite {
            id,
            path,
            data_b64,
        } => {
            use base64::Engine;
            match base64::engine::general_purpose::STANDARD
                .decode(&data_b64)
                .map_err(|e| anyhow::anyhow!("b64: {e}"))
                .and_then(|d| fsops::write(&path, &d).map(|_| d.len() as u64))
            {
                Ok(bytes) => write_frame(wr, &Response::FileWritten { id, bytes }).await?,
                Err(e) => send_err(wr, id, e).await?,
            }
        }
        Request::FileList { id, path } => match fsops::list(&path) {
            Ok(entries) => {
                write_frame(wr, &Response::FileList { id, entries }).await?
            }
            Err(e) => send_err(wr, id, e).await?,
        },
        Request::Screencap { id } => {
            exec::screencap(id, wr).await?;
        }
        Request::Auth { .. } => {
            let _ = rd; // already authed; ignore repeats
        }
    }
    Ok(())
}

async fn send_err(
    wr: &mut WriteHalf<UnixStream>,
    id: u64,
    e: anyhow::Error,
) -> Result<()> {
    write_frame(
        wr,
        &Response::Err {
            id: Some(id),
            message: format!("{e:#}"),
        },
    )
    .await
}
