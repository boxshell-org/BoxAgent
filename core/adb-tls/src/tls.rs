//! rustls client for the pairing channel: no server verification (adbd
//! presents a self-signed cert) + client-auth with our ADB key's cert.

use rustls::{
    client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier},
    ClientConfig, ClientConnection, DigitallySignedStruct, SignatureScheme,
    StreamOwned,
};
use rustls_pki_types::{
    CertificateDer, PrivateKeyDer, ServerName, UnixTime,
};
use std::io::{Read, Write};
use std::net::{IpAddr, Shutdown, TcpStream};
use std::sync::Arc;
use thiserror::Error;

#[derive(Debug, Error)]
pub enum TlsError {
    #[error("io: {0}")]
    Io(#[from] std::io::Error),
    #[error("rustls: {0}")]
    Rustls(String),
}

#[derive(Debug)]
struct NoVerify;

impl ServerCertVerifier for NoVerify {
    fn verify_server_cert(
        &self,
        _e: &CertificateDer<'_>,
        _i: &[CertificateDer<'_>],
        _s: &ServerName<'_>,
        _o: &[u8],
        _n: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        _m: &[u8],
        _c: &CertificateDer<'_>,
        _d: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn verify_tls13_signature(
        &self,
        _m: &[u8],
        _c: &CertificateDer<'_>,
        _d: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        vec![
            SignatureScheme::RSA_PKCS1_SHA256,
            SignatureScheme::RSA_PKCS1_SHA384,
            SignatureScheme::RSA_PKCS1_SHA512,
            SignatureScheme::ECDSA_NISTP256_SHA256,
            SignatureScheme::ECDSA_NISTP384_SHA384,
            SignatureScheme::ECDSA_NISTP521_SHA512,
            SignatureScheme::RSA_PSS_SHA256,
            SignatureScheme::RSA_PSS_SHA384,
            SignatureScheme::RSA_PSS_SHA512,
            SignatureScheme::ED25519,
        ]
    }
}

/// Wrap `sock` in a TLS connection with our client cert; completes the
/// handshake before returning.
pub fn connect(
    sock: &TcpStream,
    certs: Vec<CertificateDer<'static>>,
    key: PrivateKeyDer,
) -> Result<StreamOwned<ClientConnection, TcpStream>, TlsError> {
    let config = ClientConfig::builder()
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(NoVerify))
        .with_client_auth_cert(certs, key)
        .map_err(|e| TlsError::Rustls(format!("{e}")))?;

    let name = ServerName::IpAddress(IpAddr::V4(std::net::Ipv4Addr::LOCALHOST));
    let conn = ClientConnection::new(Arc::new(config), name)
        .map_err(|e| TlsError::Rustls(format!("{e}")))?;

    let cloned = sock.try_clone()?;
    let mut stream = StreamOwned::new(conn, cloned);

    // Drive the handshake to completion (writes ClientHello, reads the rest).
    stream.flush()?;
    while stream.conn.is_handshaking() {
        stream.conn.complete_io(&mut stream.sock)?;
    }
    Ok(stream)
}

/// Convenience wrapper if callers need RAII close.
pub struct StreamOwnedGuard(pub StreamOwned<ClientConnection, TcpStream>);

impl Drop for StreamOwnedGuard {
    fn drop(&mut self) {
        let _ = self.0.sock.shutdown(Shutdown::Both);
    }
}
