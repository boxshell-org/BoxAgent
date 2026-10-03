//! Wireless-debugging pairing client for Android 11+.
//!
//! Flow (AOSP `adb pair` semantics, per `pairing_auth.cpp` + Shizuku's port):
//!   TCP connect → TLS handshake (client cert = adb key, no server verify)
//!   → EKM("adb-label\0", 64B) → SPAKE2 with password = code||EKM
//!   → pairing packets → AES-128-GCM PeerInfo exchange carrying our adb pubkey.

mod aead;
mod key;
mod packet;
mod spake2;
mod tls;

pub use key::{generate_key_pem, pubkey_line, KeyError};

use aead::PairingCipher;
use spake2::Spake2Client;
use std::net::{SocketAddr, TcpStream};
use std::time::Duration;
use thiserror::Error;
use tracing::debug;

const EKM_LABEL: &[u8] = b"adb-label\0";
const EKM_LEN: usize = 64;
const PEER_INFO_SIZE: usize = packet::MAX_PEER_INFO;
const PEER_INFO_TYPE_PUBKEY: u8 = 0;

#[derive(Debug, Error)]
pub enum PairError {
    #[error("key: {0}")]
    Key(#[from] KeyError),
    #[error("tls: {0}")]
    Tls(#[from] tls::TlsError),
    #[error("packet: {0}")]
    Packet(#[from] packet::PacketError),
    #[error("peer rejected SPAKE2 message")]
    SpakeRejected,
    #[error("cipher: {0}")]
    Cipher(#[from] aead::AeadError),
    #[error("unexpected peer info (size {0})")]
    BadPeerInfo(usize),
}

/// Device information returned by the pairing server (its PeerInfo payload).
#[derive(Debug)]
pub struct PairResult {
    /// Trailing-zero-trimmed contents of the server's PeerInfo data field.
    pub device_guid: Vec<u8>,
}

/// Pair with a device at `addr` using the 6-digit wireless-debugging `code`.
/// `adb_key_pem` is our persistent PKCS#8 identity (generate once, reuse).
pub fn pair(
    addr: SocketAddr,
    code: &str,
    adb_key_pem: &str,
) -> Result<PairResult, PairError> {
    let (certs, key_der) = key::self_signed_cert(adb_key_pem)?;
    let pubkey = key::pubkey_line(adb_key_pem)?;

    let sock = TcpStream::connect_timeout(&addr, Duration::from_secs(10))?;
    sock.set_nodelay(true).ok();
    sock.set_read_timeout(Some(Duration::from_secs(15))).ok();
    sock.set_write_timeout(Some(Duration::from_secs(15))).ok();

    let mut stream = tls::connect(&sock, certs, key_der)?;

    // EKM-bounded SPAKE2 password: code || exportKeyingMaterial("adb-label\0")
    let mut ekm = vec![0u8; EKM_LEN];
    stream
        .conn
        .export_keying_material(&mut ekm, EKM_LABEL, None)
        .map_err(|e| tls::TlsError::Rustls(e.to_string()))?;
    let mut password = code.as_bytes().to_vec();
    password.extend_from_slice(&ekm);

    let spake = Spake2Client::new(&password);

    // 1) SPAKE2 message exchange
    packet::write_packet(&mut stream, packet::TYPE_SPAKE2_MSG, spake.msg())?;
    let (ty, their_msg) = packet::read_packet(&mut stream)?;
    if ty != packet::TYPE_SPAKE2_MSG {
        return Err(PairError::SpakeRejected);
    }
    let key_material = spake
        .process_msg(&their_msg)
        .ok_or(PairError::SpakeRejected)?;
    debug!("spake2 complete, key material {}B", key_material.len());

    // 2) Encrypted PeerInfo exchange
    let mut cipher = PairingCipher::new(&key_material);
    let mut peer_info = vec![0u8; PEER_INFO_SIZE];
    peer_info[0] = PEER_INFO_TYPE_PUBKEY;
    let n = pubkey.len().min(PEER_INFO_SIZE - 1);
    peer_info[1..1 + n].copy_from_slice(&pubkey.as_bytes()[..n]);
    let ct = cipher.encrypt(&peer_info)?;
    packet::write_packet(&mut stream, packet::TYPE_PEER_INFO, &ct)?;

    let (ty, their_peer_ct) = packet::read_packet(&mut stream)?;
    if ty != packet::TYPE_PEER_INFO {
        return Err(PairError::SpakeRejected);
    }
    let their_peer = cipher.decrypt(&their_peer_ct)?;
    if their_peer.len() != PEER_INFO_SIZE {
        return Err(PairError::BadPeerInfo(their_peer.len()));
    }
    let guid: Vec<u8> = their_peer[1..]
        .iter()
        .copied()
        .take_while(|b| *b != 0)
        .collect();
    debug!("paired, device guid {:?}", String::from_utf8_lossy(&guid));

    Ok(PairResult {
        device_guid: guid,
    })
}
