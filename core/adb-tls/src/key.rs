//! ADB identity: RSA-2048 keypair (PKCS#8 PEM), the Android "adb public key"
//! line for PeerInfo, and the self-signed TLS client certificate.

use adb_client::message_devices::models::ADBRsaKey;
use rcgen::{CertificateParams, KeyPair, PKCS_RSA_SHA256};
use rsa::pkcs8::{EncodePrivateKey, LineEnding};
use rsa::RsaPrivateKey;
use rustls_pki_types::{CertificateDer, PrivateKeyDer};
use thiserror::Error;

#[derive(Debug, Error)]
pub enum KeyError {
    #[error("rsa: {0}")]
    Rsa(#[from] rsa::Error),
    #[error("pkcs8: {0}")]
    Pkcs8(#[from] rsa::pkcs8::Error),
    #[error("rcgen: {0}")]
    Rcgen(#[from] rcgen::Error),
    #[error("adb key: {0}")]
    Adb(String),
}

/// Generate a fresh ADB keypair, returned as PKCS#8 PEM.
pub fn generate_key_pem() -> Result<String, KeyError> {
    let key = RsaPrivateKey::new(&mut rsa::rand_core::OsRng, 2048)?;
    Ok(key.to_pkcs8_pem(LineEnding::LF)?.to_string())
}

/// `base64(blob) comment` — the line adbd stores in `adb_keys`.
pub fn pubkey_line(pkcs8_pem: &str) -> Result<String, KeyError> {
    let k = ADBRsaKey::new_from_pkcs8(pkcs8_pem)
        .map_err(|e| KeyError::Adb(format!("{e}")))?;
    k.android_pubkey_encode()
        .map_err(|e| KeyError::Adb(format!("{e}")))
}

/// Self-signed X.509 cert over the ADB key (mirrors adb_client's transport).
pub fn self_signed_cert(
    pkcs8_pem: &str,
) -> Result<(Vec<CertificateDer<'static>>, PrivateKeyDer), KeyError> {
    let kp = KeyPair::from_pkcs8_pem_and_sign_algo(pkcs8_pem, &PKCS_RSA_SHA256)?;
    let cert = CertificateParams::default().self_signed(&kp)?;
    let key_der = PrivateKeyDer::try_from(kp.serialize_der())
        .map_err(|e| KeyError::Adb(format!("key der: {e}")))?;
    Ok((vec![cert.der().clone()], key_der))
}
