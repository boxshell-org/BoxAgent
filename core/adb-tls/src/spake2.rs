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

/// BoringSSL's blinding points (`kSpakeMSmallPrecomp` / `kSpakeNSmallPrecomp`
/// in `spake25519.cc`): hash-to-curve of "edwards25519 point generation seed
/// (M)" / "(N)". NOT the RFC 9382 constants — adbd pairs with BoringSSL, so
/// any other M/N yields mismatched keys and the device drops the PeerInfo.
const M_COMPRESSED: [u8; 32] = [
    0x5a, 0xda, 0x7e, 0x4b, 0xf6, 0xdd, 0xd9, 0xad, 0xb6, 0x62, 0x6d, 0x32, 0x13, 0x1c, 0x6b, 0x5c,
    0x51, 0xa1, 0xe3, 0x47, 0xa3, 0x47, 0x8f, 0x53, 0xcf, 0xcf, 0x44, 0x1b, 0x88, 0xee, 0xd1, 0x2e,
];
const N_COMPRESSED: [u8; 32] = [
    0x10, 0xe3, 0xdf, 0x0a, 0xe3, 0x7d, 0x8e, 0x7a, 0x99, 0xb5, 0xfe, 0x74, 0xb4, 0x46, 0x72, 0x10,
    0x3d, 0xbd, 0xdc, 0xbd, 0x06, 0xaf, 0x68, 0x0d, 0x71, 0x32, 0x9a, 0x11, 0x69, 0x3b, 0xc7, 0x78,
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

/// `s * 8` as a raw 256-bit integer (`s < L < 2^253`, so nothing overflows).
fn left_shift_3(s: &Scalar) -> [u8; 32] {
    let l = limbs_from_scalar(s);
    let mut out = [0u64; 4];
    for i in 0..4 {
        out[i] = l[i] << 3 | if i > 0 { l[i - 1] >> 61 } else { 0 };
    }
    limbs_to_bytes(&out)
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
        let mut rnd = [0u8; 64];
        rand::rngs::OsRng.fill_bytes(&mut rnd);
        Self::with_random(password, &rnd)
    }

    fn with_random(password: &[u8], rnd: &[u8; 64]) -> Self {
        // private scalar: reduce(64 random bytes) then a raw `<< 3`, exactly
        // like BoringSSL's `left_shift_3` — a multiple of 8 as an integer
        // (not mod L), so it clears any small-order part of the peer's point.
        let private_scalar_le = left_shift_3(&Scalar::from_bytes_mod_order_wide(rnd));

        let p = scalar_mul_raw(&private_scalar_le, &ED25519_BASEPOINT_POINT);

        let mut pw_hash = [0u8; 64];
        pw_hash.copy_from_slice(&Sha512::digest(password));
        let w = Scalar::from_bytes_mod_order_wide(&pw_hash);
        let w_hacked = apply_password_scalar_hack(&w);

        let mask = scalar_mul_raw(&w_hacked, &m_point());
        let p_star = p + mask;
        let my_msg = p_star.compress().to_bytes();

        Self {
            private_scalar_le,
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

        let dh = scalar_mul_raw(&self.private_scalar_le, &q)
            .compress()
            .to_bytes();

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
            let x = left_shift_3(&Scalar::from_bytes_mod_order_wide(&rnd));
            let p = scalar_mul_raw(&x, &ED25519_BASEPOINT_POINT);
            let mut pw_hash = [0u8; 64];
            pw_hash.copy_from_slice(&Sha512::digest(password));
            let w = Scalar::from_bytes_mod_order_wide(&pw_hash);
            let w_hacked = apply_password_scalar_hack(&w);
            let mask = scalar_mul_raw(&w_hacked, &n_point());
            let my_msg = (p + mask).compress().to_bytes();
            Self {
                private_scalar_le: x,
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
            let dh = scalar_mul_raw(&self.private_scalar_le, &q)
                .compress()
                .to_bytes();
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

    fn hex32(s: &str) -> [u8; 32] {
        let mut out = [0u8; 32];
        for (i, b) in out.iter_mut().enumerate() {
            *b = u8::from_str_radix(&s[i * 2..i * 2 + 2], 16).unwrap();
        }
        out
    }

    #[test]
    fn m_and_n_decode() {
        assert!(CompressedEdwardsY(M_COMPRESSED).decompress().is_some());
        assert!(CompressedEdwardsY(N_COMPRESSED).decompress().is_some());
    }

    /// Rows 0 and 1 (P and 2^64·P) of BoringSSL's `kSpake{M,N}SmallPrecomp`
    /// tables, re-encoded as compressed points: pins M/N to the exact
    /// points adbd uses rather than RFC 9382's.
    #[test]
    fn points_match_boringssl_precomp_tables() {
        let mut k64 = [0u8; 32];
        k64[8] = 1; // 2^64, little-endian
        for (p, row0, row1) in [
            (
                m_point(),
                "5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e",
                "5eacabe53ad5b0359f6d7fbac0850ef4703f13904c501aeec5eb69fe9842879d",
            ),
            (
                n_point(),
                "10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778",
                "688713646a10f745e00f3221597c0e50ad56d712697b58f8b93ba5bb4d1b879c",
            ),
        ] {
            assert_eq!(p.compress().to_bytes(), hex32(row0));
            assert_eq!(scalar_mul_raw(&k64, &p).compress().to_bytes(), hex32(row1));
        }
    }

    /// Known answer from an independent port of BoringSSL's spake25519
    /// (alice + bob, fixed randomness): our client must produce the same
    /// message and, fed Bob's message, the same 64-byte key.
    #[test]
    fn known_answer_matches_boringssl_port() {
        let mut pw = b"482913".to_vec();
        pw.extend(0u8..64);
        let mut ra = [0u8; 64];
        for (i, b) in ra.iter_mut().enumerate() {
            *b = (i as u8).wrapping_mul(7).wrapping_add(3);
        }
        let alice = Spake2Client::with_random(&pw, &ra);
        assert_eq!(
            alice.msg(),
            &hex32("c6b3744e8d18d6d76a8423bc411503f2cb53314c92dd713fcb12425777205cc6")
        );
        let bob_msg = hex32("b1a592a329d756b34079e70c146ab18bc17978ecfffb60fbcd712c68c16405eb");
        let key = alice.process_msg(&bob_msg).unwrap();
        let want = "245756da5585af7e96e413952493a5d56491690833c0d61febfbf9f0dfda0585\
                    330036a8b9833011e0090b38729b92309d613daf1786a047cf73c7bcd8905e30";
        let want: String = want.split_whitespace().collect();
        assert_eq!(key[..32], hex32(&want[..64]));
        assert_eq!(key[32..], hex32(&want[64..]));
    }

    #[test]
    fn left_shift_3_is_raw_times_8() {
        let s = Scalar::from_bytes_mod_order([0xff; 32]);
        let raw = left_shift_3(&s);
        assert_eq!(raw[0] & 7, 0);
        // Reduced mod L it must equal 8·s.
        assert_eq!(Scalar::from_bytes_mod_order(raw), s * Scalar::from(8u8));
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
