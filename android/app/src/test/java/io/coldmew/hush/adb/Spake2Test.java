package io.coldmew.hush.adb;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Random;
import org.junit.Test;

// SPAKE2 as used by adb pairing. Interoperability with Android's own (BoringSSL) side is proven on a
// phone; these tests pin the maths: agreement, wrong-code behaviour, valid points, and the result of
// an independent affine-coordinate computation of the same message.
public class Spake2Test {
    private static final byte[] CLIENT = "adb pair client\u0000".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SERVER = "adb pair server\u0000".getBytes(StandardCharsets.UTF_8);

    @Test public void sameCodeSameKey() throws Exception {
        for (int i = 0; i < 5; i++) {
            byte[] pw = ("123456" + i).getBytes(StandardCharsets.UTF_8);
            Spake2 a = new Spake2(Spake2.Role.ALICE, CLIENT, SERVER), b = new Spake2(Spake2.Role.BOB, SERVER, CLIENT);
            byte[] ma = a.generateMessage(pw), mb = b.generateMessage(pw);
            assertNotNull(Spake2.decode(ma));
            assertNotNull(Spake2.decode(mb));
            byte[] ka = a.processMessage(mb), kb = b.processMessage(ma);
            assertEquals(64, ka.length);
            assertArrayEquals(ka, kb);
        }
    }

    @Test public void wrongCodeDifferentKey() throws Exception {
        Spake2 a = new Spake2(Spake2.Role.ALICE, CLIENT, SERVER), b = new Spake2(Spake2.Role.BOB, SERVER, CLIENT);
        byte[] ma = a.generateMessage("111111".getBytes(StandardCharsets.UTF_8));
        byte[] mb = b.generateMessage("222222".getBytes(StandardCharsets.UTF_8));
        assertFalse(Arrays.equals(a.processMessage(mb), b.processMessage(ma)));
    }

    @Test public void wrongNamesDifferentKey() throws Exception {
        Spake2 a = new Spake2(Spake2.Role.ALICE, CLIENT, SERVER);
        Spake2 b = new Spake2(Spake2.Role.BOB, SERVER, "someone else".getBytes(StandardCharsets.UTF_8));
        byte[] pw = "123456".getBytes(StandardCharsets.UTF_8);
        byte[] ma = a.generateMessage(pw), mb = b.generateMessage(pw);
        assertFalse(Arrays.equals(a.processMessage(mb), b.processMessage(ma)));
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsNonPoint() throws Exception {
        Spake2 a = new Spake2(Spake2.Role.ALICE, CLIENT, SERVER);
        a.generateMessage("1".getBytes(StandardCharsets.UTF_8));
        byte[] bad = new byte[32];
        bad[0] = 2;   // y = 2 is not on edwards25519
        a.processMessage(bad);
    }

    // M and N are SHA-256 of their seeds, decoded as points (no rehash needed), as in BoringSSL.
    @Test public void constants() throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        assertArrayEquals(Spake2.encode(Spake2.mul(BigInteger.ONE, Spake2.M)),
                sha.digest("edwards25519 point generation seed (M)".getBytes(StandardCharsets.US_ASCII)));
        assertArrayEquals(Spake2.encode(Spake2.mul(BigInteger.ONE, Spake2.N)),
                sha.digest("edwards25519 point generation seed (N)".getBytes(StandardCharsets.US_ASCII)));
        // l * B is the neutral point (encoding of (0, 1)).
        byte[] neutral = new byte[32];
        neutral[0] = 1;
        assertArrayEquals(neutral, Spake2.encode(Spake2.mul(Spake2.L, Spake2.B)));
    }

    // The message equals k*B + w*M computed independently in affine coordinates.
    @Test public void matchesAffineReference() throws Exception {
        Random rnd = new Random(7);
        for (int run = 0; run < 3; run++) {
            byte[] random = new byte[64], pw = new byte[70];
            rnd.nextBytes(random);
            rnd.nextBytes(pw);
            byte[] msg = new Spake2(Spake2.Role.ALICE, CLIENT, SERVER).generateMessage(pw, random.clone());
            BigInteger k = le(random).mod(Spake2.L).shiftLeft(3).mod(BigInteger.ONE.shiftLeft(256));
            BigInteger w = le(MessageDigest.getInstance("SHA-512").digest(pw)).mod(Spake2.L);
            if (w.testBit(0)) w = w.add(Spake2.L);
            if (w.testBit(1)) w = w.add(Spake2.L.shiftLeft(1));
            if (w.testBit(2)) w = w.add(Spake2.L.shiftLeft(2));
            assertArrayEquals(encodeAffine(addAffine(mulAffine(k, Spake2.B), mulAffine(w, Spake2.M))), msg);
        }
    }

    @Test public void hkdfRfc5869Case3() throws Exception {   // no salt, no info
        byte[] ikm = new byte[22];
        Arrays.fill(ikm, (byte) 0x0b);
        assertArrayEquals(Spake2.hex("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"),
                AdbPairing.hkdf(ikm, new byte[0], 42));
    }

    // ---- affine reference (slow, independent formulas) ----
    private static final BigInteger P = Spake2.P;
    private static final BigInteger D = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);

    private static BigInteger[] addAffine(BigInteger[] a, BigInteger[] b) {
        BigInteger t = D.multiply(a[0]).multiply(b[0]).multiply(a[1]).multiply(b[1]).mod(P);
        BigInteger x = a[0].multiply(b[1]).add(a[1].multiply(b[0])).multiply(BigInteger.ONE.add(t).modInverse(P)).mod(P);
        BigInteger y = a[1].multiply(b[1]).add(a[0].multiply(b[0])).multiply(BigInteger.ONE.subtract(t).mod(P).modInverse(P)).mod(P);
        return new BigInteger[]{x, y};
    }

    private static BigInteger[] mulAffine(BigInteger k, BigInteger[] p) {
        BigInteger[] r = {BigInteger.ZERO, BigInteger.ONE};
        for (int i = k.bitLength() - 1; i >= 0; i--) {
            r = addAffine(r, r);
            if (k.testBit(i)) r = addAffine(r, p);
        }
        return r;
    }

    private static byte[] encodeAffine(BigInteger[] p) {
        byte[] be = p[1].toByteArray(), out = new byte[32];
        for (int i = 0; i < 32 && i < be.length; i++) out[i] = be[be.length - 1 - i];
        if (p[0].testBit(0)) out[31] |= (byte) 0x80;
        return out;
    }

    private static BigInteger le(byte[] b) {
        byte[] be = new byte[b.length + 1];
        for (int i = 0; i < b.length; i++) be[b.length - i] = b[i];
        return new BigInteger(be);
    }
}
