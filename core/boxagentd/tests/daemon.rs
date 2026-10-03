//! Host integration test: spawn the real daemon binary and drive it over its
//! abstract socket exactly like the app's DaemonClient does.

use base64::Engine;
use boxagent_proto::{read_frame, write_frame, Request, Response};
use std::os::linux::net::SocketAddrExt;
use std::process::{Child, Command};
use std::time::{Duration, Instant};
use tokio::net::UnixStream;

const TOKEN: &str = "test-token";

struct Daemon {
    child: Child,
    name: String,
}

impl Drop for Daemon {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

fn spawn() -> Daemon {
    let name = format!("boxagentd.test.{}.{}", std::process::id(), rand_suffix());
    let child = Command::new(env!("CARGO_BIN_EXE_boxagentd"))
        .args(["--socket", &name, "--token", TOKEN])
        .spawn()
        .expect("spawn daemon");
    Daemon { child, name }
}

fn rand_suffix() -> u128 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_nanos()
}

async fn connect(d: &Daemon, token: &str) -> (UnixStream, Response) {
    let addr = std::os::unix::net::SocketAddr::from_abstract_name(d.name.as_bytes()).unwrap();
    let deadline = Instant::now() + Duration::from_secs(5);
    let std_stream = loop {
        match std::os::unix::net::UnixStream::connect_addr(&addr) {
            Ok(s) => break s,
            Err(e) if Instant::now() < deadline => {
                let _ = e;
                tokio::time::sleep(Duration::from_millis(50)).await;
            }
            Err(e) => panic!("connect: {e}"),
        }
    };
    std_stream.set_nonblocking(true).unwrap();
    let mut s = UnixStream::from_std(std_stream).unwrap();
    write_frame(
        &mut s,
        &Request::Auth {
            token: token.into(),
        },
    )
    .await
    .unwrap();
    let r: Response = read_frame(&mut s).await.unwrap();
    (s, r)
}

async fn authed(d: &Daemon) -> UnixStream {
    let (s, r) = connect(d, TOKEN).await;
    assert!(matches!(r, Response::AuthOk { .. }), "{r:?}");
    s
}

struct ExecOut {
    stdout: Vec<u8>,
    stderr: Vec<u8>,
    exit: i32,
    timed_out: bool,
}

async fn exec(s: &mut UnixStream, id: u64, cmd: &str, timeout_ms: u64) -> ExecOut {
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
    let (_s, r) = connect(&d, "wrong").await;
    assert!(matches!(r, Response::Err { .. }), "{r:?}");
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
