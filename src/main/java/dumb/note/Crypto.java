package dumb.note;

import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.math.ec.ECCurve;
import org.bouncycastle.math.ec.ECPoint;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;
import java.util.Locale;

import static java.util.Arrays.copyOfRange;

public class Crypto {

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    private static final String PROVIDER_BC = BouncyCastleProvider.PROVIDER_NAME;
    private static final X9ECParameters SECP256K1_PARAMS = CustomNamedCurves.getByName("secp256k1");
    private static final ECCurve CURVE = SECP256K1_PARAMS.getCurve();
    private static final ECPoint G = SECP256K1_PARAMS.getG();
    private static final SecureRandom secureRandom = new SecureRandom();

    public static byte[] hexToBytes(String s) {
        if (s == null) return null;
        var len = s.length();
        var data = new byte[len / 2];
        for (var i = 0; i < len; i += 2)
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i + 1), 16));
        return data;
    }

    public static String bytesToHex(byte[] bytes) {
        if (bytes == null) return null;
        var sb = new StringBuilder(bytes.length * 2);
        for (var b : bytes)
            sb.append(String.format("%02x", b));
        return sb.toString();
    }

    public static byte[] generatePrivateKey() {
        var privKey = new byte[32];
        secureRandom.nextBytes(privKey);
        var d = new BigInteger(1, privKey);
        if (d.signum() == 0 || d.compareTo(SECP256K1_PARAMS.getN()) >= 0) return generatePrivateKey();
        return privKey;
    }

    public static byte[] ensureCoordBytesAre32(byte[] coordBytes) {
        if (coordBytes.length == 32) return coordBytes;
        var out = new byte[32];
        if (coordBytes.length > 32) System.arraycopy(coordBytes, coordBytes.length - 32, out, 0, 32);
        else System.arraycopy(coordBytes, 0, out, 32 - coordBytes.length, coordBytes.length);
        return out;
    }

    public static byte[] bigIntegerTo32BytesPadded(BigInteger bi) {
        var bytes = bi.toByteArray();
        var l = bytes.length;
        if (l == 32) return bytes;
        var res = new byte[32];
        if (l > 32) System.arraycopy(bytes, l - 32, res, 0, 32);
        else System.arraycopy(bytes, 0, res, 32 - l, l);
        return res;
    }

    public static byte[] getPublicKeyXOnly(byte[] privateKeyBytes) {
        var p = G.multiply(new BigInteger(1, privateKeyBytes));
        if (p.isInfinity()) throw new IllegalArgumentException("Private key results in point at infinity");
        return ensureCoordBytesAre32(p.normalize().getAffineXCoord().getEncoded());
    }

    public static byte[] getPublicKeyCompressed(byte[] privateKeyBytes) {
        var p = G.multiply(new BigInteger(1, privateKeyBytes));
        if (p.isInfinity())
            throw new IllegalArgumentException("Private key results in point at infinity for compressed pubkey");
        return p.getEncoded(true);
    }

    public static byte[] getSharedSecret(byte[] myPrivateKeyBytes, byte[] theirPublicKeyCompressedBytes) {
        var theirPublicKey = CURVE.decodePoint(theirPublicKeyCompressedBytes);
        if (theirPublicKey.isInfinity())
            throw new IllegalArgumentException("Their public key is point at infinity");
        var sharedPoint = theirPublicKey.multiply(new BigInteger(1, myPrivateKeyBytes));
        if (sharedPoint.isInfinity()) throw new IllegalArgumentException("Shared secret point is at infinity");
        return bigIntegerTo32BytesPadded(sharedPoint.normalize().getAffineXCoord().toBigInteger());
    }

    public static String nip04Encrypt(String plaintext, byte[] sharedSecret32Bytes, byte[] theirPublicKeyXOnlyBytes) throws GeneralSecurityException {
        var iv = new byte[16];
        secureRandom.nextBytes(iv);
        var cipher = Cipher.getInstance("AES/CBC/PKCS5Padding", PROVIDER_BC);
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(sharedSecret32Bytes, "AES"), new IvParameterSpec(iv));
        var ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(ciphertext) + "?iv=" + Base64.getEncoder().encodeToString(iv);
    }

    public static String nip04Decrypt(String nip04Payload, byte[] sharedSecret32Bytes) throws GeneralSecurityException {
        var parts = nip04Payload.split("\\?iv=");
        if (parts.length != 2) throw new IllegalArgumentException("Invalid NIP-04 payload format");
        var ciphertext = Base64.getDecoder().decode(parts[0]);
        var iv = Base64.getDecoder().decode(parts[1]);
        var cipher = Cipher.getInstance("AES/CBC/PKCS5Padding", PROVIDER_BC);
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(sharedSecret32Bytes, "AES"), new IvParameterSpec(iv));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }

    public static byte[] generateAuxRand() {
        var r = new byte[32];
        secureRandom.nextBytes(r);
        return r;
    }

    public static byte[] getSharedSecretWithRetry(byte[] myPrivKeyBytes, byte[] theirXOnlyPubKeyBytes) throws GeneralSecurityException {
        var theirCompressed02 = new byte[33];
        theirCompressed02[0] = 0x02;
        System.arraycopy(theirXOnlyPubKeyBytes, 0, theirCompressed02, 1, 32);
        try {
            CURVE.decodePoint(theirCompressed02);
            return getSharedSecret(myPrivKeyBytes, theirCompressed02);
        } catch (Exception e) {
            var theirCompressed03 = new byte[33];
            theirCompressed03[0] = 0x03;
            System.arraycopy(theirXOnlyPubKeyBytes, 0, theirCompressed03, 1, 32);
            try {
                CURVE.decodePoint(theirCompressed03);
                return getSharedSecret(myPrivKeyBytes, theirCompressed03);
            } catch (Exception e2) {
                throw new GeneralSecurityException("Could not derive shared secret: Invalid recipient public key (x-only). " + e.getMessage() + " | " + e2.getMessage());
            }
        }
    }

    public static class Schnorr {
        private static final BigInteger
                EC_P = new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16),
                EC_N = new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);
        private static final X9ECParameters SECP256K1_PARAMS = CustomNamedCurves.getByName("secp256k1");
        private static final ECPoint EC_G = SECP256K1_PARAMS.getG();

        private static byte[] getEncodedPoint(ECPoint p) {
            return p.getEncoded(true);
        }

        private static byte[] sha256(byte[]... inputs) throws NoSuchAlgorithmException {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var input : inputs) digest.update(input);
            return digest.digest();
        }

        private static BigInteger liftX(BigInteger x) {
            var ySq = x.pow(3).add(BigInteger.valueOf(7)).mod(EC_P);
            var y = ySq.modPow(EC_P.add(BigInteger.ONE).divide(BigInteger.valueOf(4)), EC_P);
            if (!y.modPow(BigInteger.TWO, EC_P).equals(ySq)) return null;
            return y;
        }

        public static ECPoint liftXToPoint(byte[] xCoordBytes) {
            var x = new BigInteger(1, xCoordBytes);
            var y = liftX(x);
            return y == null ? null : SECP256K1_PARAMS.getCurve().createPoint(x, y);
        }

        private static boolean hasEvenY(ECPoint p) {
            return p.getAffineYCoord().toBigInteger().mod(BigInteger.TWO).equals(BigInteger.ZERO);
        }

        private static byte[] taggedHash(String tag, byte[] a, byte[] b, byte[] c) throws NoSuchAlgorithmException {
            var abc = new byte[a.length + b.length + c.length];
            System.arraycopy(a, 0, abc, 0, a.length);
            System.arraycopy(b, 0, abc, a.length, b.length);
            System.arraycopy(c, 0, abc, a.length + b.length, c.length);
            return taggedHash(tag, abc);
        }

        private static byte[] taggedHash(String tag, byte[] msg) throws NoSuchAlgorithmException {
            var tagHash = sha256(tag.getBytes(StandardCharsets.UTF_8));
            return sha256(tagHash, tagHash, msg);
        }

        public static byte[] sign(byte[] msgHash, byte[] seckey, byte[] auxRand) throws NoSuchAlgorithmException {
            var d0 = new BigInteger(1, seckey);
            if (!(BigInteger.ONE.compareTo(d0) <= 0 && d0.compareTo(EC_N.subtract(BigInteger.ONE)) <= 0))
                throw new IllegalArgumentException("Secret key is out of range.");
            var P_point = EC_G.multiply(d0);
            if (P_point.isInfinity()) throw new IllegalStateException("Public key point is infinity in sign");
            var P_normalized = P_point.normalize();
            var d = hasEvenY(P_normalized) ? d0 : EC_N.subtract(d0);
            var k0 = new BigInteger(1, taggedHash("BIP0340/aux", auxRand)).mod(EC_N);
            if (k0.equals(BigInteger.ZERO)) throw new RuntimeException("Auxiliary random data produced k=0.");
            var R_point = EC_G.multiply(k0);
            if (R_point.isInfinity()) throw new RuntimeException("Auxiliary random data produced R=infinity.");
            var R_normalized = R_point.normalize();
            var k = hasEvenY(R_normalized) ? k0 : EC_N.subtract(k0);
            var rX = ensureCoordBytesAre32(R_normalized.getAffineXCoord().getEncoded());
            var pX = ensureCoordBytesAre32(P_normalized.getAffineXCoord().getEncoded());
            var sBytes = bigIntegerTo32BytesPadded(k.add(new BigInteger(1, taggedHash("BIP0340/challenge", rX, pX, msgHash)).mod(EC_N).multiply(d)).mod(EC_N));
            var sig = new byte[64];
            System.arraycopy(rX, 0, sig, 0, 32);
            System.arraycopy(sBytes, 0, sig, 32, 32);
            return sig;
        }

        public static boolean verify(byte[] msgHash, byte[] pubkeyXOnly, byte[] sig) throws NoSuchAlgorithmException {
            if (pubkeyXOnly.length != 32 || sig.length != 64) return false;
            var P = liftXToPoint(pubkeyXOnly);
            if (P == null) return false;
            var P_normalized = P.normalize();
            var r = new BigInteger(1, copyOfRange(sig, 0, 32));
            var s = new BigInteger(1, copyOfRange(sig, 32, 64));
            if (r.compareTo(EC_P) >= 0 || s.compareTo(EC_N) >= 0) return false;
            var pXBytes = ensureCoordBytesAre32(P_normalized.getAffineXCoord().getEncoded());
            var rXBytes = bigIntegerTo32BytesPadded(r);
            var e = new BigInteger(1, taggedHash("BIP0340/challenge", rXBytes, pXBytes, msgHash)).mod(EC_N);
            var R_calc = EC_G.multiply(s).add(P_normalized.multiply(EC_N.subtract(e)));
            var R_calc_normalized = R_calc.normalize();
            if (R_calc_normalized.isInfinity()) return false;
            return hasEvenY(R_calc_normalized) && R_calc_normalized.getAffineXCoord().toBigInteger().equals(r);
        }
    }

    public static class Bech32 {
        private static final String CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
        private static final int[] GENERATOR = {0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3};

        private static int polymod(byte[] values) {
            var chk = 1;
            for (var v : values) {
                var top = chk >> 25;
                chk = (chk & 0x1ffffff) << 5 ^ v;
                for (var i = 0; i < 5; ++i) if ((top >> i & 1) == 1) chk ^= GENERATOR[i];
            }
            return chk;
        }

        private static byte[] expandHrp(String hrp) {
            var l = hrp.length();
            var ret = new byte[l * 2 + 1];
            for (var i = 0; i < l; ++i) {
                ret[i] = (byte) (hrp.charAt(i) >> 5);
                ret[i + l + 1] = (byte) (hrp.charAt(i) & 0x1f);
            }
            ret[l] = 0;
            return ret;
        }

        private static boolean verifyChecksum(String hrp, byte[] data) {
            var exp = expandHrp(hrp);
            var el = exp.length;
            var dl = data.length;
            var values = new byte[el + dl];
            System.arraycopy(exp, 0, values, 0, el);
            System.arraycopy(data, 0, values, el, dl);
            return polymod(values) == 1;
        }

        private static byte[] createChecksum(String hrp, byte[] data) {
            var exp = expandHrp(hrp);
            var el = exp.length;
            var dl = data.length;
            var values = new byte[el + dl + 6];
            System.arraycopy(exp, 0, values, 0, el);
            System.arraycopy(data, 0, values, el, dl);
            var mod = polymod(values) ^ 1;
            var ret = new byte[6];
            for (var i = 0; i < 6; ++i) ret[i] = (byte) (mod >> 5 * (5 - i) & 0x1f);
            return ret;
        }

        public static String encode(String hrp, byte[] data) {
            var checksum = createChecksum(hrp, data);
            var combined = new byte[data.length + checksum.length];
            System.arraycopy(data, 0, combined, 0, data.length);
            System.arraycopy(checksum, 0, combined, data.length, checksum.length);
            var sb = new StringBuilder(hrp).append('1');
            for (var b : combined) sb.append(CHARSET.charAt(b));
            return sb.toString();
        }

        public static Bech32.Bech32Data decode(String bech) throws Exception {
            if (!bech.equals(bech.toLowerCase(Locale.ROOT)) && !bech.equals(bech.toUpperCase(Locale.ROOT)))
                throw new Exception("Mixed case in Bech32 string");
            bech = bech.toLowerCase(Locale.ROOT);
            var pos = bech.lastIndexOf('1');
            var l = bech.length();
            if (pos < 1 || pos + 7 > l || l > 90)
                throw new Exception("Invalid Bech32 string structure or length");
            var hrp = bech.substring(0, pos);
            var data = new byte[l - 1 - pos];
            for (int i = 0, j = pos + 1; j < l; ++i, ++j) {
                var v = CHARSET.indexOf(bech.charAt(j));
                if (v == -1) throw new Exception("Invalid character in Bech32 string data part");
                data[i] = (byte) v;
            }
            if (!verifyChecksum(hrp, data)) throw new Exception("Bech32 checksum verification failed");
            return new Bech32.Bech32Data(hrp, copyOfRange(data, 0, data.length - 6));
        }

        private static byte[] convertBits(byte[] data, int fromBits, int toBits, boolean pad) throws Exception {
            var acc = 0;
            var bits = 0;
            var ret = new ByteArrayOutputStream();
            var maxv = (1 << toBits) - 1;
            for (var value : data) {
                var v = value & 0xff;
                if (v >> fromBits != 0) throw new Exception("Invalid data range for bit conversion");
                acc = acc << fromBits | v;
                bits += fromBits;
                while (bits >= toBits) {
                    bits -= toBits;
                    ret.write(acc >> bits & maxv);
                }
            }
            if (pad) {
                if (bits > 0) ret.write(acc << toBits - bits & maxv);
            } else if (bits >= fromBits || (acc << toBits - bits & maxv) != 0)
                throw new Exception("Invalid padding in bit conversion");
            return ret.toByteArray();
        }

        public static byte[] nip19Decode(String nip19String) throws Exception {
            return convertBits(decode(nip19String).data, 5, 8, false);
        }

        public static String nip19Encode(String hrp, byte[] data32Bytes) throws Exception {
            return encode(hrp, convertBits(data32Bytes, 8, 5, true));
        }

        public record Bech32Data(String hrp, byte[] data) {
        }
    }
}
