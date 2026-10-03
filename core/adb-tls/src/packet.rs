//! Pairing packet framing: 6-byte header {version u8, type u8, len i32 BE}
//! followed by `len` payload bytes, over the TLS channel.

use std::io::{Read, Write};
use thiserror::Error;

pub const VERSION: u8 = 1;
pub const TYPE_SPAKE2_MSG: u8 = 0;
pub const TYPE_PEER_INFO: u8 = 1;
pub const MAX_PEER_INFO: usize = 8192;
pub const MAX_PAYLOAD: usize = MAX_PEER_INFO * 2;
const HEADER: usize = 6;

#[derive(Debug, Error)]
pub enum PacketError {
    #[error("io: {0}")]
    Io(#[from] std::io::Error),
    #[error("bad version {0}")]
    BadVersion(u8),
    #[error("bad type {0}")]
    BadType(u8),
    #[error("payload size {0} out of range")]
    BadSize(i32),
}

pub fn write_packet(w: &mut impl Write, ty: u8, payload: &[u8]) -> Result<(), PacketError> {
    let mut hdr = [0u8; HEADER];
    hdr[0] = VERSION;
    hdr[1] = ty;
    hdr[2..6].copy_from_slice(&(payload.len() as i32).to_be_bytes());
    w.write_all(&hdr)?;
    w.write_all(payload)?;
    w.flush()?;
    Ok(())
}

/// Returns (type, payload).
pub fn read_packet(r: &mut impl Read) -> Result<(u8, Vec<u8>), PacketError> {
    let mut hdr = [0u8; HEADER];
    r.read_exact(&mut hdr)?;
    let (version, ty) = (hdr[0], hdr[1]);
    if version != VERSION {
        return Err(PacketError::BadVersion(version));
    }
    if ty != TYPE_SPAKE2_MSG && ty != TYPE_PEER_INFO {
        return Err(PacketError::BadType(ty));
    }
    let len = i32::from_be_bytes(hdr[2..6].try_into().unwrap());
    if len <= 0 || len as usize > MAX_PAYLOAD {
        return Err(PacketError::BadSize(len));
    }
    let mut payload = vec![0u8; len as usize];
    r.read_exact(&mut payload)?;
    Ok((ty, payload))
}
