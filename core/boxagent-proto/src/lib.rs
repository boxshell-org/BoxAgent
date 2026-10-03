//! Wire protocol shared between the BoxAgent app and the `boxagentd` daemon.
//!
//! Framing: 4-byte big-endian length prefix + UTF-8 JSON payload.
//! The daemon listens on an abstract-namespace Unix socket; the first frame
//! after connect must be `Request::Auth` carrying the one-time spawn token.

use serde::{Deserialize, Serialize};
use thiserror::Error;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

pub const MAX_FRAME: u32 = 16 * 1024 * 1024;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Request {
    /// First frame after connect. `token` must match the daemon spawn arg.
    Auth {
        token: String,
    },
    Ping,
    /// Execute `cmd` via `sh -c`. Response is a stream of `Chunk` frames
    /// followed by a single `ExecDone`.
    Exec {
        id: u64,
        cmd: String,
        timeout_ms: u64,
    },
    FileRead {
        id: u64,
        path: String,
    },
    FileWrite {
        id: u64,
        path: String,
        data_b64: String,
    },
    FileList {
        id: u64,
        path: String,
    },
    /// `screencap -p` executed in the shell context; returns PNG bytes.
    Screencap {
        id: u64,
    },
    /// Ask the daemon to exit — used when the app was overwrite-installed
    /// with a newer build and the running daemon is stale.
    Shutdown,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Response {
    AuthOk {
        uid: u32,
        /// Daemon build version (app versionName it shipped with). Empty
        /// on daemons that predate the version handshake.
        #[serde(default)]
        version: String,
    },
    Pong,
    /// Streaming exec output. `stream`: "stdout" | "stderr".
    Chunk {
        id: u64,
        stream: String,
        data_b64: String,
    },
    ExecDone {
        id: u64,
        exit: i32,
        duration_ms: u64,
        timed_out: bool,
    },
    FileData {
        id: u64,
        data_b64: String,
    },
    FileWritten {
        id: u64,
        bytes: u64,
    },
    FileList {
        id: u64,
        entries: Vec<FileEntry>,
    },
    Err {
        id: Option<u64>,
        message: String,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FileEntry {
    pub name: String,
    pub is_dir: bool,
    pub size: u64,
    pub mode: u32,
    pub mtime: u64,
}

#[derive(Debug, Error)]
pub enum ProtoError {
    #[error("io: {0}")]
    Io(#[from] std::io::Error),
    #[error("json: {0}")]
    Json(#[from] serde_json::Error),
    #[error("frame too large: {0} bytes")]
    TooLarge(u32),
    #[error("connection closed")]
    Closed,
}

/// Read one length-prefixed JSON frame.
pub async fn read_frame<R, T>(r: &mut R) -> Result<T, ProtoError>
where
    R: AsyncRead + Unpin,
    T: for<'de> Deserialize<'de>,
{
    let len = match r.read_u32().await {
        Ok(v) => v,
        Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => return Err(ProtoError::Closed),
        Err(e) => return Err(e.into()),
    };
    if len > MAX_FRAME {
        return Err(ProtoError::TooLarge(len));
    }
    let mut buf = vec![0u8; len as usize];
    r.read_exact(&mut buf).await?;
    Ok(serde_json::from_slice(&buf)?)
}

/// Write one length-prefixed JSON frame. Oversized frames are refused
/// before anything hits the wire — the peer would reject the length and
/// lose framing sync otherwise.
pub async fn write_frame<W, T: Serialize>(w: &mut W, v: &T) -> Result<(), ProtoError>
where
    W: AsyncWrite + Unpin,
{
    let payload = serde_json::to_vec(v)?;
    if payload.len() > MAX_FRAME as usize {
        return Err(ProtoError::TooLarge(
            payload.len().min(u32::MAX as usize) as u32
        ));
    }
    w.write_u32(payload.len() as u32).await?;
    w.write_all(&payload).await?;
    w.flush().await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn frame_roundtrip() {
        let (mut a, mut b) = tokio::io::duplex(4096);
        let req = Request::Exec {
            id: 42,
            cmd: "id".into(),
            timeout_ms: 1000,
        };
        write_frame(&mut a, &req).await.unwrap();
        let got: Request = read_frame(&mut b).await.unwrap();
        match got {
            Request::Exec {
                id,
                cmd,
                timeout_ms,
            } => {
                assert_eq!(id, 42);
                assert_eq!(cmd, "id");
                assert_eq!(timeout_ms, 1000);
            }
            other => panic!("unexpected: {other:?}"),
        }
    }

    #[tokio::test]
    async fn oversized_frame_is_refused_without_writing() {
        let (mut a, mut b) = tokio::io::duplex(64);
        let big = Response::FileData {
            id: 1,
            data_b64: "A".repeat(MAX_FRAME as usize),
        };
        let err = write_frame(&mut a, &big).await.unwrap_err();
        assert!(matches!(err, ProtoError::TooLarge(_)));
        // Stream still in sync: the next frame reads cleanly.
        write_frame(&mut a, &Response::Pong).await.unwrap();
        let got: Response = read_frame(&mut b).await.unwrap();
        assert!(matches!(got, Response::Pong));
    }

    #[tokio::test]
    async fn closed_is_not_io_error() {
        let (a, mut b) = tokio::io::duplex(16);
        drop(a);
        let err = read_frame::<_, Request>(&mut b).await.unwrap_err();
        assert!(matches!(err, ProtoError::Closed));
    }
}
