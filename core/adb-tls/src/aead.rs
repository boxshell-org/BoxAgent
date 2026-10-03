//! AES-128-GCM channel used after SPAKE2 (port of AOSP `aes_128_gcm.cpp`).
//!
//! Key  = HKDF-SHA256(ikm = spake2 key material, salt = none,
//!                    info = "adb pairing_auth aes-128-gcm key") -> 16 bytes
//! Nonce = 12 zero bytes with a u64 LE counter in the first 8 bytes,
//!         separate counters for encrypt/decrypt, both starting at 0.

use aes_gcm::{
    aead::{Aead, KeyInit, Payload},
    Aes128Gcm, Nonce,
};
use hkdf::Hkdf;
use sha2::Sha256;
use thiserror::Error;

const INFO: &[u8] = b"adb pairing_auth aes-128-gcm key";

#[derive(Debug, Error)]
pub enum AeadError {
    #[error("aead op failed")]
    Op,
    #[error("hkdf failed")]
    Hkdf,
}

pub struct PairingCipher {
    key: [u8; 16],
    enc_seq: u64,
    dec_seq: u64,
}

impl PairingCipher {
    pub fn new(key_material: &[u8]) -> Self {
        let hk = Hkdf::<Sha256>::new(None, key_material);
        let mut key = [0u8; 16];
        hk.expand(INFO, &mut key).expect("hkdf expand 16");
        Self {
            key,
            enc_seq: 0,
            dec_seq: 0,
        }
    }

    fn cipher(&self) -> Aes128Gcm {
        Aes128Gcm::new_from_slice(&self.key).expect("key len")
    }

    fn nonce(seq: u64) -> Nonce<aes_gcm::aead::generic_array::typenum::U12> {
        let mut n = [0u8; 12];
        n[..8].copy_from_slice(&seq.to_le_bytes());
        *Nonce::from_slice(&n)
    }

    pub fn encrypt(&mut self, pt: &[u8]) -> Result<Vec<u8>, AeadError> {
        let nonce = Self::nonce(self.enc_seq);
        let ct = self
            .cipher()
            .encrypt(&nonce, Payload { msg: pt, aad: b"" })
            .map_err(|_| AeadError::Op)?;
        self.enc_seq += 1;
        Ok(ct)
    }

    pub fn decrypt(&mut self, ct: &[u8]) -> Result<Vec<u8>, AeadError> {
        let nonce = Self::nonce(self.dec_seq);
        let pt = self
            .cipher()
            .decrypt(&nonce, Payload { msg: ct, aad: b"" })
            .map_err(|_| AeadError::Op)?;
        self.dec_seq += 1;
        Ok(pt)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip() {
        let mut a = PairingCipher::new(&[7u8; 64]);
        let mut b = PairingCipher::new(&[7u8; 64]);
        let ct = a.encrypt(&[1, 2, 3, 4]).unwrap();
        assert_eq!(ct.len(), 4 + 16);
        let pt = b.decrypt(&ct).unwrap();
        assert_eq!(pt, vec![1, 2, 3, 4]);
        // second message uses seq=1
        let ct2 = a.encrypt(b"hello").unwrap();
        assert_eq!(b.decrypt(&ct2).unwrap(), b"hello");
    }
}
