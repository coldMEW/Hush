package io.coldmew.hush.adb;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

// SPAKE2 over edwards25519, exactly as BoringSSL implements it (crypto/curve25519/spake25519.c),
// which is what Android's Wireless debugging pairing uses (adb pairing_auth). Each side sends one
// 32-byte message; both derive the same 64-byte key only if they used the same password.
//
// Plain BigInteger arithmetic in extended coordinates: pairing happens once, on the phone itself,
// and takes a few tens of milliseconds. The steps and constants follow BoringSSL line by line:
//  - private key: 64 random bytes reduced mod l, times 8 (clears the cofactor);
//  - password scalar: SHA-512(password) mod l, plus multiples of l so that it is a multiple of 8
//    (BoringSSL's compatible "password scalar hack");
//  - message = k*B + w*M (Alice) or k*B + w*N (Bob);
//  - key = SHA-512 of the length-prefixed names, messages, k*(Q - w*N|M) and SHA-512(password).
final class Spake2 {
    enum Role { ALICE, BOB }

    static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    static final BigInteger L = BigInteger.ONE.shiftLeft(252).add(new BigInteger("27742317777372353535851937790883648493"));
    private static final BigInteger D = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    private static final BigInteger D2 = D.shiftLeft(1).mod(P);
    private static final BigInteger SQRT_M1 = BigInteger.valueOf(2).modPow(P.subtract(BigInteger.ONE).shiftRight(2), P);
    private static final BigInteger TWO_256 = BigInteger.ONE.shiftLeft(256);

    // The base point and the two SPAKE2 points of draft-ietf-kitten-krb-spake-preauth (appendix B):
    // SHA-256 of "edwards25519 point generation seed (M|N)", decoded as a point. These are the points
    // in BoringSSL's kSpakeMSmallPrecomp / kSpakeNSmallPrecomp tables.
    static final BigInteger[] B = decode(hex("5866666666666666666666666666666666666666666666666666666666666666"));
    static final BigInteger[] M = decode(hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"));
    static final BigInteger[] N = decode(hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"));

    private final Role role;
    private final byte[] myName, theirName;
    private BigInteger privateKey, passwordScalar;
    private byte[] passwordHash, myMsg;

    Spake2(Role role, byte[] myName, byte[] theirName) {
        this.role = role;
        this.myName = myName.clone();
        this.theirName = theirName.clone();
    }

    byte[] generateMessage(byte[] password) throws GeneralSecurityException {
        byte[] random = new byte[64];
        new SecureRandom().nextBytes(random);
        return generateMessage(password, random);
    }

    // Package-private with a fixed random value for tests.
    byte[] generateMessage(byte[] password, byte[] random64) throws GeneralSecurityException {
        if (myMsg != null) throw new IllegalStateException("message already generated");
        privateKey = le(random64).mod(L).shiftLeft(3).mod(TWO_256);
        passwordHash = MessageDigest.getInstance("SHA-512").digest(password);
        BigInteger w = le(passwordHash).mod(L);
        if (w.testBit(0)) w = w.add(L);
        if (w.testBit(1)) w = w.add(L.shiftLeft(1));
        if (w.testBit(2)) w = w.add(L.shiftLeft(2));
        passwordScalar = w;
        BigInteger[] mask = mul(passwordScalar, role == Role.ALICE ? M : N);
        myMsg = encode(add(mul(privateKey, B), mask));
        Arrays.fill(random64, (byte) 0);
        return myMsg.clone();
    }

    // The shared key (64 bytes). Throws IllegalArgumentException if their message is not a point.
    byte[] processMessage(byte[] theirMsg) throws GeneralSecurityException {
        if (myMsg == null) throw new IllegalStateException("generate the message first");
        if (theirMsg.length != 32) throw new IllegalArgumentException("SPAKE2 message must be 32 bytes");
        BigInteger[] qStar = decode(theirMsg);
        if (qStar == null) throw new IllegalArgumentException("SPAKE2 message is not a point");
        BigInteger[] peersMask = mul(passwordScalar, role == Role.ALICE ? N : M);
        BigInteger[] q = add(ext(qStar), negate(peersMask));
        byte[] dh = encode(mulExt(privateKey, q));
        MessageDigest sha = MessageDigest.getInstance("SHA-512");
        if (role == Role.ALICE) {
            prefixed(sha, myName); prefixed(sha, theirName); prefixed(sha, myMsg); prefixed(sha, theirMsg);
        } else {
            prefixed(sha, theirName); prefixed(sha, myName); prefixed(sha, theirMsg); prefixed(sha, myMsg);
        }
        prefixed(sha, dh);
        prefixed(sha, passwordHash);
        privateKey = null;
        passwordScalar = null;
        return sha.digest();
    }

    private static void prefixed(MessageDigest sha, byte[] data) {
        long n = data.length;
        byte[] len = new byte[8];
        for (int i = 0; i < 8; i++) len[i] = (byte) (n >>> (8 * i));
        sha.update(len);
        sha.update(data);
    }

    // ---- edwards25519 in extended coordinates (X:Y:Z:T), x = X/Z, y = Y/Z, xy = T/Z ----

    private static BigInteger[] ext(BigInteger[] affine) {
        return new BigInteger[]{affine[0], affine[1], BigInteger.ONE, affine[0].multiply(affine[1]).mod(P)};
    }

    // Unified addition (add-2008-hwcd-3, a = -1); also used for doubling.
    static BigInteger[] add(BigInteger[] p, BigInteger[] q) {
        BigInteger a = p[1].subtract(p[0]).multiply(q[1].subtract(q[0])).mod(P);
        BigInteger b = p[1].add(p[0]).multiply(q[1].add(q[0])).mod(P);
        BigInteger c = p[3].multiply(D2).multiply(q[3]).mod(P);
        BigInteger d = p[2].shiftLeft(1).multiply(q[2]).mod(P);
        BigInteger e = b.subtract(a), f = d.subtract(c), g = d.add(c), h = b.add(a);
        return new BigInteger[]{e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P)};
    }

    private static BigInteger[] negate(BigInteger[] p) {
        return new BigInteger[]{P.subtract(p[0]).mod(P), p[1], p[2], P.subtract(p[3]).mod(P)};
    }

    // k times an affine point (result in extended coordinates).
    static BigInteger[] mul(BigInteger k, BigInteger[] affine) { return mulExt(k, ext(affine)); }

    private static BigInteger[] mulExt(BigInteger k, BigInteger[] p) {
        BigInteger[] r = {BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO};
        for (int i = k.bitLength() - 1; i >= 0; i--) {
            r = add(r, r);
            if (k.testBit(i)) r = add(r, p);
        }
        return r;
    }

    // 32-byte encoding: y little-endian, top bit = parity of x.
    static byte[] encode(BigInteger[] p) {
        BigInteger zi = p[2].modInverse(P);
        BigInteger x = p[0].multiply(zi).mod(P), y = p[1].multiply(zi).mod(P);
        byte[] out = toLe(y);
        if (x.testBit(0)) out[31] |= (byte) 0x80;
        return out;
    }

    // Affine point from its encoding, or null if it is not on the curve.
    static BigInteger[] decode(byte[] s) {
        byte[] c = s.clone();
        boolean odd = (c[31] & 0x80) != 0;
        c[31] &= 0x7f;
        BigInteger y = le(c);
        if (y.compareTo(P) >= 0) return null;
        BigInteger u = y.multiply(y).subtract(BigInteger.ONE).mod(P);
        BigInteger v = D.multiply(y).multiply(y).add(BigInteger.ONE).mod(P);
        BigInteger x2 = u.multiply(v.modInverse(P)).mod(P);
        if (x2.signum() == 0) return odd ? null : new BigInteger[]{BigInteger.ZERO, y};
        BigInteger x = x2.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P);
        if (!x.multiply(x).mod(P).equals(x2)) x = x.multiply(SQRT_M1).mod(P);
        if (!x.multiply(x).mod(P).equals(x2)) return null;
        if (x.testBit(0) != odd) x = P.subtract(x);
        return new BigInteger[]{x, y};
    }

    private static BigInteger le(byte[] b) {
        byte[] be = new byte[b.length + 1];
        for (int i = 0; i < b.length; i++) be[b.length - i] = b[i];
        return new BigInteger(be);
    }

    private static byte[] toLe(BigInteger v) {
        byte[] be = v.toByteArray(), out = new byte[32];
        for (int i = 0; i < 32 && i < be.length; i++) out[i] = be[be.length - 1 - i];
        return out;
    }

    static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }
}
