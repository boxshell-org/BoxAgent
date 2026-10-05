//! boxagentd — privileged execution daemon.
//!
//! Spawned via `adb shell` (runs as uid 2000, `shell`). Listens on a
//! loopback TCP port and/or an abstract-namespace Unix socket,
//! authenticates the first frame with the one-time token passed on argv,
//! then serves exec / file / screencap requests framed as length-prefixed
//! JSON (see boxagent-proto).
//!
//! The app connects over TCP: SELinux on production builds denies
//! `untrusted_app → shell unix_stream_socket connectto`, so an abstract
//! socket only works on permissive builds (emulators, redroid). Loopback
//! TCP is policy-neutral; the token is what keeps other apps out.

mod exec;
mod fsops;
mod sock;

use anyhow::{Context, Result};
use boxagent_proto::{read_frame, write_frame, Request, Response};
use std::sync::Arc;
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncWrite};
use tracing::{error, info, warn};

/// An unauthenticated peer gets this long to present the token — any local
/// app can reach a loopback port, and a silent one must not pin a task.
const AUTH_TIMEOUT: Duration = Duration::from_secs(5);

struct Args {
    socket_name: Option<String>,
    port: Option<u16>,
    token: String,
}

fn parse_args() -> Result<Args> {
    let mut socket_name = None;
    let mut port = None;
    let mut token = None;
    let mut it = std::env::args().skip(1);
    while let Some(a) = it.next() {
        match a.as_str() {
            "--socket" => socket_name = it.next(),
            "--port" => {
                port = Some(
                    it.next()
                        .context("--port needs a value")?
                        .parse::<u16>()
                        .context("--port")?,
                )
            }
            "--token" => token = it.next(),
            other => anyhow::bail!("unknown arg: {other}"),
        }
    }
    if socket_name.is_none() && port.is_none() {
        anyhow::bail!("--port or --socket required");
    }
    Ok(Args {
        socket_name,
        port,
        token: token.context("--token required")?,
    })
}

#[tokio::main(flavor = "multi_thread", worker_threads = 4)]
async fn main() -> Result<()> {
    // Detach from the launcher's session/process group: launchers that let
    // us start without `setsid` (direct `sh`, some adbd paths) would
    // otherwise leave us killable by the group's SIGHUP on exit. Fails
    // harmlessly when already detached.
    unsafe {
        libc::setsid();
    }
    tracing_subscriber::fmt()
        .with_writer(std::io::stderr)
        .with_ansi(false)
        .init();

    let args = Arc::new(parse_args()?);
    let uid = unsafe { libc::getuid() };
    let mut tasks = Vec::new();

    if let Some(port) = args.port {
        // Loopback only — never reachable from the network.
        let listener = tokio::net::TcpListener::bind(("127.0.0.1", port))
            .await
            .with_context(|| format!("bind 127.0.0.1:{port}"))?;
        info!("boxagentd up: tcp 127.0.0.1:{port} uid={uid}");
        let args = args.clone();
        tasks.push(tokio::spawn(async move {
            loop {
                match listener.accept().await {
                    Ok((stream, _)) => {
                        let _ = stream.set_nodelay(true);
                        serve(stream, args.clone());
                    }
                    Err(e) => accept_failed(e).await,
                }
            }
        }));
    }
    if let Some(name) = args.socket_name.clone() {
        let listener = sock::bind_abstract(&name).with_context(|| format!("bind @{name}"))?;
        info!("boxagentd up: @{name} uid={uid}");
        let args = args.clone();
        tasks.push(tokio::spawn(async move {
            loop {
                match listener.accept().await {
                    Ok((stream, _)) => serve(stream, args.clone()),
                    Err(e) => accept_failed(e).await,
                }
            }
        }));
    }
    for t in tasks {
        let _ = t.await;
    }
    Ok(())
}

/// EMFILE & co. would otherwise spin the accept loop at 100% CPU.
async fn accept_failed(e: std::io::Error) {
    error!("accept: {e}");
    tokio::time::sleep(Duration::from_millis(100)).await;
}

fn serve<S>(stream: S, args: Arc<Args>)
where
    S: AsyncRead + AsyncWrite + Send + 'static,
{
    tokio::spawn(async move {
        if let Err(e) = handle_conn(stream, args).await {
            warn!("conn ended: {e:#}");
        }
    });
}

async fn handle_conn<S>(stream: S, args: Arc<Args>) -> Result<()>
where
    S: AsyncRead + AsyncWrite + Send + 'static,
{
    let (mut rd, mut wr) = tokio::io::split(stream);

    // First frame must be Auth with the one-time token.
    let first: Request = tokio::time::timeout(AUTH_TIMEOUT, read_frame(&mut rd))
        .await
        .map_err(|_| anyhow::anyhow!("auth timed out"))??;
    match &first {
        Request::Auth { token } if token == &args.token => {
            let uid = unsafe { libc::getuid() } as u32;
            write_frame(
                &mut wr,
                &Response::AuthOk {
                    uid,
                    version: env!("BOXAGENT_APP_VERSION").to_string(),
                },
            )
            .await?;
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
        dispatch(req, &mut wr).await?;
    }
}

async fn dispatch<W: AsyncWrite + Unpin>(req: Request, wr: &mut W) -> Result<()> {
    match req {
        Request::Ping => write_frame(wr, &Response::Pong).await?,
        Request::Exec {
            id,
            cmd,
            timeout_ms,
        } => exec::run(id, &cmd, timeout_ms, wr).await?,
        Request::FileRead { id, path } => match fsops::read(&path) {
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
        },
        Request::FileWrite { id, path, data_b64 } => {
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
            Ok(entries) => write_frame(wr, &Response::FileList { id, entries }).await?,
            Err(e) => send_err(wr, id, e).await?,
        },
        Request::Screencap { id } => {
            exec::screencap(id, wr).await?;
        }
        Request::Shutdown => {
            info!("shutdown requested");
            std::process::exit(0);
        }
        Request::Auth { .. } => {} // already authed; ignore repeats
    }
    Ok(())
}

async fn send_err<W: AsyncWrite + Unpin>(wr: &mut W, id: u64, e: anyhow::Error) -> Result<()> {
    Ok(write_frame(
        wr,
        &Response::Err {
            id: Some(id),
            message: format!("{e:#}"),
        },
    )
    .await?)
}
