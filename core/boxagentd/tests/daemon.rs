//! Host integration test: spawn the real daemon binary and drive it over
//! loopback TCP exactly like the app's DaemonClient does (plus the legacy
//! abstract socket older app builds used).

use base64::Engine;
use boxagent_proto::{read_frame, write_frame, Request, Response};
use std::os::linux::net::SocketAddrExt;
use std::process::{Child, Command};
use std::time::{Duration, Instant};
use tokio::io::{AsyncRead, AsyncWrite};
use tokio::net::{TcpStream, UnixStream};

const TOKEN: &str = "test-token";

struct Daemon {
    child: Child,
    name: String,
    port: u16,
}

impl Drop for Daemon {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

/// A port the kernel just handed out — free unless something races us.
fn free_port() -> u16 {
    std::net::TcpListener::bind("127.0.0.1:0")
        .unwrap()
        .local_addr()
        .unwrap()
        .port()
}

fn spawn() -> Daemon {
    let name = format!("boxagentd.test.{}.{}", std::process::id(), rand_suffix());
    let port = free_port();
    let child = Command::new(env!("CARGO_BIN_EXE_boxagentd"))
        .args([
            "--port",
            &port.to_string(),
            "--socket",
            &name,
            "--token",
            TOKEN,
        ])
        .spawn()
        .expect("spawn daemon");
    Daemon { child, name, port }
}

fn rand_suffix() -> u128 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_nanos()
}

async fn retry<T>(mut f: impl FnMut() -> std::io::Result<T>) -> T {
    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        match f() {
            Ok(s) => return s,
            Err(e) if Instant::now() < deadline => {
                let _ = e;
                tokio::time::sleep(Duration::from_millis(50)).await;
            }
            Err(e) => panic!("connect: {e}"),
        }
    }
}

async fn tcp(d: &Daemon) -> TcpStream {
    let port = d.port;
    let s = retry(|| std::net::TcpStream::connect(("127.0.0.1", port))).await;
    s.set_nonblocking(true).unwrap();
    TcpStream::from_std(s).unwrap()
}

async fn abstract_sock(d: &Daemon) -> UnixStream {
    let addr = std::os::unix::net::SocketAddr::from_abstract_name(d.name.as_bytes()).unwrap();
    let s = retry(|| std::os::unix::net::UnixStream::connect_addr(&addr)).await;
    s.set_nonblocking(true).unwrap();
    UnixStream::from_std(s).unwrap()
}

async fn auth<S: AsyncRead + AsyncWrite + Unpin>(s: &mut S, token: &str) -> Response {
    write_frame(
        s,
        &Request::Auth {
            token: token.into(),
        },
    )
    .await
    .unwrap();
    read_frame(s).await.unwrap()
}

async fn authed(d: &Daemon) -> TcpStream {
    let mut s = tcp(d).await;
    let r = auth(&mut s, TOKEN).await;
    assert!(matches!(r, Response::AuthOk { .. }), "{r:?}");
    s
}

struct ExecOut {
    stdout: Vec<u8>,
    stderr: Vec<u8>,
    exit: i32,
    timed_out: bool,
}

async fn exec<S: AsyncRead + AsyncWrite + Unpin>(
    s: &mut S,
    id: u64,
    cmd: &str,
    timeout_ms: u64,
) -> ExecOut {
    write_frame(
        s,
        &Request::Exec {
            id,
            cmd: cmd.into(),
            timeout_ms,
        },
    )
    .await
    .unwrap();
    let b64 = base64::engine::general_purpose::STANDARD;
    let mut out = ExecOut {
        stdout: vec![],
        stderr: vec![],
        exit: 0,
        timed_out: false,
    };
    loop {
        match read_frame::<_, Response>(s).await.unwrap() {
            Response::Chunk {
                id: cid,
                stream,
                data_b64,
            } => {
                assert_eq!(cid, id);
                let d = b64.decode(data_b64).unwrap();
                if stream == "stderr" {
                    out.stderr.extend(d)
                } else {
                    out.stdout.extend(d)
                }
            }
            Response::ExecDone {
                id: cid,
                exit,
                timed_out,
                ..
            } => {
                assert_eq!(cid, id);
                out.exit = exit;
                out.timed_out = timed_out;
                return out;
            }
            other => panic!("unexpected {other:?}"),
        }
    }
}

#[tokio::test]
async fn rejects_bad_token() {
    let d = spawn();
    let mut s = tcp(&d).await;
    let r = auth(&mut s, "wrong").await;
    assert!(matches!(r, Response::Err { .. }), "{r:?}");
}

#[tokio::test]
async fn legacy_abstract_socket_still_serves() {
    let d = spawn();
    let mut s = abstract_sock(&d).await;
    let r = auth(&mut s, TOKEN).await;
    assert!(matches!(r, Response::AuthOk { .. }), "{r:?}");
    let o = exec(&mut s, 1, "echo hi", 5000).await;
    assert_eq!(String::from_utf8_lossy(&o.stdout), "hi\n");
}

#[tokio::test]
async fn silent_peer_is_dropped_after_auth_timeout() {
    use tokio::io::AsyncReadExt;
    let d = spawn();
    let mut s = tcp(&d).await;
    // Never send the token: the daemon must hang up on its own.
    let mut buf = [0u8; 1];
    let r = tokio::time::timeout(Duration::from_secs(8), s.read(&mut buf)).await;
    assert!(matches!(r, Ok(Ok(0)) | Ok(Err(_))), "{r:?}");
}

#[tokio::test]
async fn loopback_only() {
    let d = spawn();
    let _ = authed(&d).await; // up
                              // Any non-loopback local address must refuse.
    let ext = std::net::UdpSocket::bind("0.0.0.0:0").and_then(|u| {
        u.connect("192.0.2.1:9")?;
        u.local_addr()
    });
    if let Ok(a) = ext {
        if !a.ip().is_loopback() && !a.ip().is_unspecified() {
            let r = std::net::TcpStream::connect_timeout(
                &std::net::SocketAddr::new(a.ip(), d.port),
                Duration::from_secs(2),
            );
            assert!(r.is_err(), "daemon reachable on {}", a.ip());
        }
    }
}

#[tokio::test]
async fn ping_and_exec() {
    let d = spawn();
    let mut s = authed(&d).await;
    write_frame(&mut s, &Request::Ping).await.unwrap();
    assert!(matches!(
        read_frame::<_, Response>(&mut s).await.unwrap(),
        Response::Pong
    ));

    let o = exec(&mut s, 1, "echo out; echo err >&2; exit 3", 5000).await;
    assert_eq!(String::from_utf8_lossy(&o.stdout), "out\n");
    assert_eq!(String::from_utf8_lossy(&o.stderr), "err\n");
    assert_eq!(o.exit, 3);
    assert!(!o.timed_out);

    // Large output streams across many chunks intact.
    let o = exec(&mut s, 2, "head -c 300000 /dev/zero | tr '\\0' x", 10_000).await;
    assert_eq!(o.stdout.len(), 300_000);
}

#[tokio::test]
async fn timeout_kills_whole_process_group() {
    let d = spawn();
    let mut s = authed(&d).await;
    let t0 = Instant::now();
    // The backgrounded sleep inherits the pipes; killing only `sh` would
    // leave it holding them until the drain timeout.
    let o = exec(&mut s, 7, "sleep 30 & sleep 30", 600).await;
    assert!(o.timed_out);
    assert!(
        t0.elapsed() < Duration::from_millis(2500),
        "took {:?}",
        t0.elapsed()
    );
    // Connection still usable afterwards.
    let o = exec(&mut s, 8, "echo ok", 5000).await;
    assert_eq!(String::from_utf8_lossy(&o.stdout), "ok\n");
}

#[tokio::test]
async fn file_ops_roundtrip_and_bounded_reads() {
    let d = spawn();
    let mut s = authed(&d).await;
    let dir = std::env::temp_dir().join(format!("boxagentd-test-{}", rand_suffix()));
    let path = dir.join("sub/f.txt");
    let b64 = base64::engine::general_purpose::STANDARD;

    write_frame(
        &mut s,
        &Request::FileWrite {
            id: 1,
            path: path.to_string_lossy().into(),
            data_b64: b64.encode("héllo"),
        },
    )
    .await
    .unwrap();
    match read_frame::<_, Response>(&mut s).await.unwrap() {
        Response::FileWritten { bytes, .. } => assert_eq!(bytes, 6),
        other => panic!("{other:?}"),
    }

    write_frame(
        &mut s,
        &Request::FileRead {
            id: 2,
            path: path.to_string_lossy().into(),
        },
    )
    .await
    .unwrap();
    match read_frame::<_, Response>(&mut s).await.unwrap() {
        Response::FileData { data_b64, .. } => {
            assert_eq!(b64.decode(data_b64).unwrap(), "héllo".as_bytes())
        }
        other => panic!("{other:?}"),
    }

    write_frame(
        &mut s,
        &Request::FileList {
            id: 3,
            path: dir.join("sub").to_string_lossy().into(),
        },
    )
    .await
    .unwrap();
    match read_frame::<_, Response>(&mut s).await.unwrap() {
        Response::FileList { entries, .. } => {
            assert_eq!(entries.len(), 1);
            assert_eq!(entries[0].name, "f.txt");
        }
        other => panic!("{other:?}"),
    }

    // An endless char device must error out, not OOM the daemon or send
    // a frame the client can't accept.
    write_frame(
        &mut s,
        &Request::FileRead {
            id: 4,
            path: "/dev/zero".into(),
        },
    )
    .await
    .unwrap();
    match read_frame::<_, Response>(&mut s).await.unwrap() {
        Response::Err { id, message } => {
            assert_eq!(id, Some(4));
            assert!(message.contains("too large"), "{message}");
        }
        other => panic!("{other:?}"),
    }

    // Connection survives the error.
    write_frame(&mut s, &Request::Ping).await.unwrap();
    assert!(matches!(
        read_frame::<_, Response>(&mut s).await.unwrap(),
        Response::Pong
    ));
    let _ = std::fs::remove_dir_all(dir);
}
