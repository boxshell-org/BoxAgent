//! SPAKE2 over Ed25519, faithful port of BoringSSL `spake25519.cc` as used by
//! AOSP `pairing_auth.cpp` (roles, names, password hack, transcript hash).
//!
//! The pairing initiator is always Alice ("adb pair client"); adbd answers as
//! Bob ("adb pair server"). Names include their trailing NUL (`sizeof` of the
//! C string literal) — this is required for the transcript to match.

use curve25519_dalek::{
    constants::ED25519_BASEPOINT_POINT,
    edwards::{CompressedEdwardsY, EdwardsPoint},
    scalar::Scalar,
};
use rand::RngCore;
use sha2::{Digest, Sha512};

/// Group order L = 2^252 + 27742317777372353535851937790883648493,
/// as little-endian u64 limbs.
const ORDER: [u64; 4] = [
    0x5812_631a_5cf5_d3ed,
    0x14de_f9de_a2f7_9cd6,
    0x0000_0000_0000_0000,
    0x1000_0000_0000_0000,
];

/// RFC 9382 / BoringSSL blinding points (compressed Edwards-Y encodings).
const M_COMPRESSED: [u8; 32] = [
    0xd0, 0x48, 0x03, 0x2c, 0x6e, 0xa0, 0xb6, 0xd6, 0x97, 0xdd, 0xc2, 0xe8,
    0x6b, 0xda, 0x85, 0xa3, 0x3a, 0xda, 0xc9, 0x20, 0xf1, 0xbf, 0x18, 0xe1,
    0xb0, 0xc6, 0xd1, 0x66, 0xa5, 0xce, 0xcd, 0xaf,
];
const N_COMPRESSED: [u8; 32] = [
    0xd3, 0xbf, 0xb5, 0x18, 0xf4, 0x4f, 0x34, 0x30, 0xf2, 0x9d, 0x0c, 0x92,
    0xaf, 0x50, 0x38, 0x65, 0xa1, 0xed, 0x32, 0x81, 0xdc, 0x69, 0xb3, 0x5d,
    0xd8, 0x68, 0xba, 0x85, 0xf8, 0x86, 0xc4, 0xab,
];

pub const CLIENT_NAME: &[u8] = b"adb pair client\0";
pub const SERVER_NAME: &[u8] = b"adb pair server\0";
pub const KEY_SIZE: usize = 64;

fn m_point() -> EdwardsPoint {
    CompressedEdwardsY(M_COMPRESSED)
        .decompress()
        .expect("M must decode")
}

fn n_point() -> EdwardsPoint {
    CompressedEdwardsY(N_COMPRESSED)
        .decompress()
        .expect("N must decode")
}

// -- 256-bit little-endian limb arithmetic (for the password-scalar hack) --

fn limbs_from_scalar(s: &Scalar) -> [u64; 4] {
    let b = s.to_bytes();
    [
        u64::from_le_bytes(b[0..8].try_into().unwrap()),
        u64::from_le_bytes(b[8..16].try_into().unwrap()),
        u64::from_le_bytes(b[16..24].try_into().unwrap()),
        u64::from_le_bytes(b[24..32].try_into().unwrap()),
    ]
}

fn limbs_add(a: &[u64; 4], b: &[u64; 4]) -> [u64; 4] {
    let mut out = [0u64; 4];
    let mut carry = 0u128;
    for i in 0..4 {
        let v = a[i] as u128 + b[i] as u128 + carry;
        out[i] = v as u64;
        carry = v >> 64;
    }
    out
}

fn limbs_double(a: &[u64; 4]) -> [u64; 4] {
    limbs_add(a, a)
}

fn limbs_to_bytes(a: &[u64; 4]) -> [u8; 32] {
    let mut out = [0u8; 32];
    for i in 0..4 {
        out[i * 8..i * 8 + 8].copy_from_slice(&a[i].to_le_bytes());
    }
    out
}

/// BoringSSL's unconditional password-scalar fix: add L / 2L / 4L under the
/// scalar's own low bits so the result is a multiple of 8 (value < 8L).
fn apply_password_scalar_hack(w: &Scalar) -> [u8; 32] {
    let mut s = limbs_from_scalar(w);
    let mut order = ORDER;
    for bit in 0..3 {
        if s[0] & (1 << bit) != 0 {
            s = limbs_add(&s, &order);
        }
        order = limbs_double(&order);
    }
    debug_assert_eq!(s[0] & 7, 0);
    limbs_to_bytes(&s)
}

/// Variable-time scalar * arbitrary point via double-and-add over the raw
/// 256-bit scalar (safe for values >= L, which `Scalar` cannot hold).
fn scalar_mul_raw(scalar_le: &[u8; 32], point: &EdwardsPoint) -> EdwardsPoint {
    let mut acc = EdwardsPoint::default(); // identity
    let mut p = *point;
    for byte in scalar_le.iter() {
        for bit in 0..8 {
            if byte & (1 << bit) != 0 {
                acc += p;
            }
            p = p + p;
        }
    }
    acc
}

fn update_lp(sha: &mut Sha512, data: &[u8]) {
    sha.update((data.len() as u64).to_le_bytes());
    sha.update(data);
}

/// SPAKE2 context for the client (Alice) role.
pub struct Spake2Client {
    private_scalar_le: [u8; 32],
    password_scalar_le: [u8; 32],
    password_hash: [u8; 64],
    my_msg: [u8; 32],
}

impl Spake2Client {
    /// `password` = pairing code bytes || 64 bytes of TLS EKM.
    pub fn new(password: &[u8]) -> Self {
        // private scalar: reduce(SHA-width random) then *8 (cofactor clear)
        let mut rnd = [0u8; 64];
        rand::rngs::OsRng.fill_bytes(&mut rnd);
        let x = Scalar::from_bytes_mod_order_wide(&rnd) * Scalar::from(8u8);

        let p = &x * &ED25519_BASEPOINT_POINT;

        let mut pw_hash = [0u8; 64];
        pw_hash.copy_from_slice(&Sha512::digest(password));
        let w = Scalar::from_bytes_mod_order_wide(&pw_hash);
        let w_hacked = apply_password_scalar_hack(&w);

        let mask = scalar_mul_raw(&w_hacked, &m_point());
        let p_star = p + mask;
        let my_msg = p_star.compress().to_bytes();

        Self {
            private_scalar_le: x.to_bytes(),
            password_scalar_le: w_hacked,
            password_hash: pw_hash,
            my_msg,
        }
    }

    pub fn msg(&self) -> &[u8; 32] {
        &self.my_msg
    }

    /// Consume the peer's message; produce the 64-byte shared key material.
    pub fn process_msg(&self, their_msg: &[u8]) -> Option<[u8; KEY_SIZE]> {
        if their_msg.len() != 32 {
            return None;
        }
        let mut tm = [0u8; 32];
        tm.copy_from_slice(their_msg);
        let q_star = CompressedEdwardsY(tm).decompress()?;

        let peers_mask = scalar_mul_raw(&self.password_scalar_le, &n_point());
        let q = q_star - peers_mask;

        let priv_scalar = Scalar::from_bytes_mod_order(self.private_scalar_le);
        let dh = (&priv_scalar * &q).compress().to_bytes();

        let mut sha = Sha512::new();
        update_lp(&mut sha, CLIENT_NAME);
        update_lp(&mut sha, SERVER_NAME);
        update_lp(&mut sha, &self.my_msg);
        update_lp(&mut sha, &tm);
        update_lp(&mut sha, &dh);
        update_lp(&mut sha, &self.password_hash);

        let mut key = [0u8; KEY_SIZE];
        key.copy_from_slice(&sha.finalize());
        Some(key)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Mirror-image Bob for loopback verification of the group math.
    struct Bob {
        private_scalar_le: [u8; 32],
        password_scalar_le: [u8; 32],
        password_hash: [u8; 64],
        my_msg: [u8; 32],
    }
    impl Bob {
        fn new(password: &[u8]) -> Self {
            let mut rnd = [0u8; 64];
            rand::rngs::OsRng.fill_bytes(&mut rnd);
            let x = Scalar::from_bytes_mod_order_wide(&rnd) * Scalar::from(8u8);
            let p = &x * &ED25519_BASEPOINT_POINT;
            let mut pw_hash = [0u8; 64];
            pw_hash.copy_from_slice(&Sha512::digest(password));
            let w = Scalar::from_bytes_mod_order_wide(&pw_hash);
            let w_hacked = apply_password_scalar_hack(&w);
            let mask = scalar_mul_raw(&w_hacked, &n_point());
            let my_msg = (p + mask).compress().to_bytes();
            Self {
                private_scalar_le: x.to_bytes(),
                password_scalar_le: w_hacked,
                password_hash: pw_hash,
                my_msg,
            }
        }
        fn process_msg(&self, their_msg: &[u8]) -> [u8; KEY_SIZE] {
            let mut tm = [0u8; 32];
            tm.copy_from_slice(their_msg);
            let q_star = CompressedEdwardsY(tm).decompress().unwrap();
            let peers_mask = scalar_mul_raw(&self.password_scalar_le, &m_point());
            let q = q_star - peers_mask;
            let priv_scalar = Scalar::from_bytes_mod_order(self.private_scalar_le);
            let dh = (&priv_scalar * &q).compress().to_bytes();
            let mut sha = Sha512::new();
            update_lp(&mut sha, CLIENT_NAME);
            update_lp(&mut sha, SERVER_NAME);
            update_lp(&mut sha, &tm); // their view: alice msg first
            update_lp(&mut sha, &self.my_msg);
            update_lp(&mut sha, &dh);
            update_lp(&mut sha, &self.password_hash);
            let mut key = [0u8; KEY_SIZE];
            key.copy_from_slice(&sha.finalize());
            key
        }
    }

    #[test]
    fn m_and_n_decode() {
        assert!(CompressedEdwardsY(M_COMPRESSED).decompress().is_some());
        assert!(CompressedEdwardsY(N_COMPRESSED).decompress().is_some());
    }

    #[test]
    fn password_scalar_hack_clears_low_bits() {
        let w = Scalar::from_bytes_mod_order_wide(&[0xabu8; 64]);
        let hacked = apply_password_scalar_hack(&w);
        assert_eq!(hacked[0] & 7, 0);
    }

    #[test]
    fn shared_key_matches() {
        let pw = b"123456";
        let alice = Spake2Client::new(pw);
        let bob = Bob::new(pw);
        let a_key = alice.process_msg(&bob.my_msg).unwrap();
        let b_key = bob.process_msg(alice.msg());
        assert_eq!(a_key, b_key);
    }

    #[test]
    fn different_passwords_diverge() {
        let alice = Spake2Client::new(b"111111");
        let bob = Bob::new(b"222222");
        let a_key = alice.process_msg(&bob.my_msg).unwrap();
        let b_key = bob.process_msg(alice.msg());
        assert_ne!(a_key, b_key);
    }
}
