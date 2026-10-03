//! ADB identity: RSA-2048 keypair (PKCS#8 PEM), the Android "adb public key"
//! line for PeerInfo, and the self-signed TLS client certificate.
//!
//! `adb_client` keeps `ADBRsaKey` private and takes key material as a file
//! path, so we own the AOSP pubkey encoding here — same algorithm as
//! `libcrypto_utils/android_pubkey.cpp`.

use base64::{engine::general_purpose::STANDARD, Engine};
use num_bigint::{BigUint, ModInverse};
use num_traits::{FromPrimitive, ToPrimitive};
use rcgen::{CertificateParams, KeyPair, PKCS_RSA_SHA256};
use rsa::pkcs8::{DecodePrivateKey, EncodePrivateKey, LineEnding};
use rsa::traits::PublicKeyParts;
use rsa::RsaPrivateKey;
use rustls_pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use thiserror::Error;

const RSA_BITS: usize = 2048;
const MODULUS_SIZE_WORDS: u32 = 64; // 2048 bits / 32

#[derive(Debug, Error)]
pub enum KeyError {
    #[error("rsa: {0}")]
    Rsa(#[from] rsa::Error),
    #[error("pkcs8: {0}")]
    Pkcs8(#[from] rsa::pkcs8::Error),
    #[error("rcgen: {0}")]
    Rcgen(#[from] rcgen::Error),
    #[error("integer conversion")]
    Conversion,
}

/// Generate a fresh ADB keypair, returned as PKCS#8 PEM.
pub fn generate_key_pem() -> Result<String, KeyError> {
    let key = RsaPrivateKey::new(&mut rsa::rand_core::OsRng, RSA_BITS)?;
    Ok(key.to_pkcs8_pem(LineEnding::LF)?.to_string())
}

/// `base64(blob) boxagent` — the line adbd stores in `adb_keys`.
/// Blob layout (all little-endian):
///   u32 modulus_size_words | u32 n0inv | u8 n[256] | u8 rr[256] | u32 e
pub fn pubkey_line(pkcs8_pem: &str) -> Result<String, KeyError> {
    let priv_key = RsaPrivateKey::from_pkcs8_pem(pkcs8_pem)?;
    let n = priv_key.n().clone();
    let e = priv_key.e().to_u32().ok_or(KeyError::Conversion)?;

    let r32 = BigUint::from_u64(1 << 32).unwrap();
    let r = BigUint::from_u8(1).unwrap() << RSA_BITS;
    let rr = r.modpow(&BigUint::from_u8(2).unwrap(), &n);

    // n0inv = -(n^-1) mod 2^32, i.e. r32 - n0^-1 where n0 = n mod r32
    let n0 = &n % &r32;
    let n0inv = n0
        .clone()
        .mod_inverse(&r32)
        .and_then(|v| v.to_biguint())
        .ok_or(KeyError::Conversion)?;
    let n0inv = (&r32 - n0inv).to_u32().ok_or(KeyError::Conversion)?;

    let mut blob = Vec::with_capacity(4 + 4 + 256 + 256 + 4);
    blob.extend_from_slice(&MODULUS_SIZE_WORDS.to_le_bytes());
    blob.extend_from_slice(&n0inv.to_le_bytes());
    blob.extend_from_slice(&pad_le(&n.to_bytes_le(), 256));
    blob.extend_from_slice(&pad_le(&rr.to_bytes_le(), 256));
    blob.extend_from_slice(&e.to_le_bytes());

    Ok(format!("{} boxagent", STANDARD.encode(blob)))
}

fn pad_le(bytes: &[u8], len: usize) -> Vec<u8> {
    let mut v = vec![0u8; len];
    v[..bytes.len().min(len)].copy_from_slice(&bytes[..bytes.len().min(len)]);
    v
}

/// Self-signed X.509 cert over the ADB key (mirrors adb_client's transport).
pub fn self_signed_cert(
    pkcs8_pem: &str,
) -> Result<(Vec<CertificateDer<'static>>, PrivateKeyDer<'static>), KeyError> {
    let kp = KeyPair::from_pkcs8_pem_and_sign_algo(pkcs8_pem, &PKCS_RSA_SHA256)?;
    let cert = CertificateParams::default().self_signed(&kp)?;
    let key_der = PrivatePkcs8KeyDer::from(kp.serialize_der()).into();
    Ok((vec![cert.der().clone()], key_der))
}
