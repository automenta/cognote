package dumb.note;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.math.ec.ECCurve;
import org.bouncycastle.math.ec.ECPoint;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListSelectionEvent;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.*;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;


public class Netention {
    private static final Logger logger = LoggerFactory.getLogger(Netention.class);

    static {
        Security.addProvider(new BouncyCastleProvider()); // Add BouncyCastle provider
    }

    public static void main(String[] args) {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "info");
        System.setProperty("org.slf4j.simpleLogger.showDateTime", "true");
        System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "yyyy-MM-dd HH:mm:ss:SSS Z");
        logger.info("Netention Application Startup Sequence Initiated...");
        SwingUtilities.invokeLater(() -> {
            var core = new Core();
            new NetentionUI(core);
        });
    }

    public enum UIFieldType {TEXT_FIELD, TEXT_AREA, COMBO_BOX, CHECK_BOX, PASSWORD_FIELD}
    // --- End Embedded SchnorrUtil ---

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface ConfigField {
        String label();

        String tooltip() default "";

        UIFieldType type() default UIFieldType.TEXT_FIELD;

        String[] choices() default {};

        String group() default "General";
    }
    // --- End Embedded Bech32 ---

    // --- Embedded SchnorrUtil (BIP-340) ---
    // Based on https://github.com/miketwk/bip-schnorr-java/blob/master/Schnorr.java
    // Adapted slightly for inclusion and to use BouncyCastle for SHA256 if not available natively.
    public static class SchnorrUtil {
        private static final BigInteger EC_P = new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16);
        private static final BigInteger EC_N = new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);
        private static final X9ECParameters SECP256K1_PARAMS = CustomNamedCurves.getByName("secp256k1");
        private static final ECPoint EC_G = SECP256K1_PARAMS.getG();

        private static byte[] getEncodedPoint(ECPoint p) {
            return p.getEncoded(true);
        } // Compressed

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
            if (y == null) return null;
            return SECP256K1_PARAMS.getCurve().createPoint(x, y);
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

            var P = EC_G.multiply(d0);
            var d = hasEvenY(P) ? d0 : EC_N.subtract(d0);

            var k0 = new BigInteger(1, taggedHash("BIP0340/aux", auxRand)).mod(EC_N);
            if (k0.equals(BigInteger.ZERO)) throw new RuntimeException("Auxiliary random data produced k=0.");

            var R = EC_G.multiply(k0);
            var k = hasEvenY(R) ? k0 : EC_N.subtract(k0);

            var rX = R.getAffineXCoord().getEncoded();
            if (rX.length > 32) rX = Arrays.copyOfRange(rX, rX.length - 32, rX.length); // Ensure 32 bytes
            else if (rX.length < 32) {
                var temp = new byte[32];
                System.arraycopy(rX, 0, temp, 32 - rX.length, rX.length);
                rX = temp;
            }


            var pX = P.getAffineXCoord().getEncoded();
            if (pX.length > 32) pX = Arrays.copyOfRange(pX, pX.length - 32, pX.length);
            else if (pX.length < 32) {
                var temp = new byte[32];
                System.arraycopy(pX, 0, temp, 32 - pX.length, pX.length);
                pX = temp;
            }


            var e = new BigInteger(1, taggedHash("BIP0340/challenge", rX, pX, msgHash)).mod(EC_N);
            var s = k.add(e.multiply(d)).mod(EC_N);

            var sBytes = s.toByteArray();
            if (sBytes.length > 32) sBytes = Arrays.copyOfRange(sBytes, sBytes.length - 32, sBytes.length);
            else if (sBytes.length < 32) {
                var temp = new byte[32];
                System.arraycopy(sBytes, 0, temp, 32 - sBytes.length, sBytes.length);
                sBytes = temp;
            }


            var sig = new byte[64];
            System.arraycopy(rX, 0, sig, 0, 32);
            System.arraycopy(sBytes, 0, sig, 32, 32);
            return sig;
        }

        public static boolean verify(byte[] msgHash, byte[] pubkeyXOnly, byte[] sig) throws NoSuchAlgorithmException {
            if (pubkeyXOnly.length != 32 || sig.length != 64) return false;

            var P = liftXToPoint(pubkeyXOnly);
            if (P == null) return false;

            var r = new BigInteger(1, Arrays.copyOfRange(sig, 0, 32));
            var s = new BigInteger(1, Arrays.copyOfRange(sig, 32, 64));

            if (r.compareTo(EC_P) >= 0 || s.compareTo(EC_N) >= 0) return false;

            var pX = P.getAffineXCoord().getEncoded();
            if (pX.length > 32) pX = Arrays.copyOfRange(pX, pX.length - 32, pX.length);
            else if (pX.length < 32) {
                var temp = new byte[32];
                System.arraycopy(pX, 0, temp, 32 - pX.length, pX.length);
                pX = temp;
            }

            var rX = r.toByteArray();
            if (rX.length > 32) rX = Arrays.copyOfRange(rX, rX.length - 32, rX.length);
            else if (rX.length < 32) {
                var temp = new byte[32];
                System.arraycopy(rX, 0, temp, 32 - rX.length, rX.length);
                rX = temp;
            }


            var e = new BigInteger(1, taggedHash("BIP0340/challenge", rX, pX, msgHash)).mod(EC_N);
            var R = EC_G.multiply(s).add(P.multiply(EC_N.subtract(e)));

            return !R.isInfinity() && hasEvenY(R) && R.getAffineXCoord().equals(r);
        }
    }

    // --- Embedded Bech32 ---
    // Based on reference implementations (e.g., sipa/bech32), adapted for Java
    public static class Bech32 {
        private static final String CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
        private static final int[] GENERATOR = {0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3};

        private static int polymod(byte[] values) {
            var chk = 1;
            for (var v : values) {
                var top = chk >> 25;
                chk = (chk & 0x1ffffff) << 5 ^ v;
                for (var i = 0; i < 5; ++i) if (((top >> i) & 1) == 1) chk ^= GENERATOR[i];
            }
            return chk;
        }

        private static byte[] expandHrp(String hrp) {
            var ret = new byte[hrp.length() * 2 + 1];
            for (var i = 0; i < hrp.length(); ++i) {
                ret[i] = (byte) (hrp.charAt(i) >> 5);
                ret[i + hrp.length() + 1] = (byte) (hrp.charAt(i) & 0x1f);
            }
            ret[hrp.length()] = 0;
            return ret;
        }

        private static boolean verifyChecksum(String hrp, byte[] data) {
            var exp = expandHrp(hrp);
            var values = new byte[exp.length + data.length];
            System.arraycopy(exp, 0, values, 0, exp.length);
            System.arraycopy(data, 0, values, exp.length, data.length);
            return polymod(values) == 1;
        }

        private static byte[] createChecksum(String hrp, byte[] data) {
            var exp = expandHrp(hrp);
            var values = new byte[exp.length + data.length + 6]; // 6 for checksum
            System.arraycopy(exp, 0, values, 0, exp.length);
            System.arraycopy(data, 0, values, exp.length, data.length);
            // polymod will append 6 zero bytes implicitly for checksum calculation
            var mod = polymod(values) ^ 1;
            var ret = new byte[6];
            for (var i = 0; i < 6; ++i) ret[i] = (byte) ((mod >> (5 * (5 - i))) & 0x1f);
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

        public static Bech32Data decode(String bech) throws Exception {
            if (!bech.equals(bech.toLowerCase(Locale.ROOT)) && !bech.equals(bech.toUpperCase(Locale.ROOT)))
                throw new Exception("Mixed case in Bech32 string");
            bech = bech.toLowerCase(Locale.ROOT);
            var pos = bech.lastIndexOf('1');
            if (pos < 1 || pos + 7 > bech.length() || bech.length() > 90)
                throw new Exception("Invalid Bech32 string structure or length");
            var hrp = bech.substring(0, pos);
            var data = new byte[bech.length() - 1 - pos];
            for (int i = 0, j = pos + 1; j < bech.length(); ++i, ++j) {
                var v = CHARSET.indexOf(bech.charAt(j));
                if (v == -1) throw new Exception("Invalid character in Bech32 string data part");
                data[i] = (byte) v;
            }
            if (!verifyChecksum(hrp, data)) throw new Exception("Bech32 checksum verification failed");
            return new Bech32Data(hrp, Arrays.copyOfRange(data, 0, data.length - 6)); // Exclude checksum
        }

        // NIP-19 specific: convert 8-bit bytes to 5-bit words and vice-versa
        private static byte[] convertBits(byte[] data, int fromBits, int toBits, boolean pad) throws Exception {
            var acc = 0;
            var bits = 0;
            var ret = new ByteArrayOutputStream();
            var maxv = (1 << toBits) - 1;
            for (var value : data) {
                var v = value & 0xff;
                if ((v >> fromBits) != 0) throw new Exception("Invalid data range for bit conversion");
                acc = (acc << fromBits) | v;
                bits += fromBits;
                while (bits >= toBits) {
                    bits -= toBits;
                    ret.write((acc >> bits) & maxv);
                }
            }
            if (pad) {
                if (bits > 0) ret.write((acc << (toBits - bits)) & maxv);
            } else if (bits >= fromBits || ((acc << (toBits - bits)) & maxv) != 0)
                throw new Exception("Invalid padding in bit conversion");
            return ret.toByteArray();
        }

        public static byte[] nip19Decode(String nip19String) throws Exception {
            var decoded = decode(nip19String);
            // NIP-19 may include a type byte before the actual data, handle if necessary
            // For npub/nsec, the data is directly the key bytes converted to 5-bit words.
            return convertBits(decoded.data, 5, 8, false);
        }

        public static String nip19Encode(String hrp, byte[] data32Bytes) throws Exception {
            var words = convertBits(data32Bytes, 8, 5, true);
            return encode(hrp, words);
        }

        public record Bech32Data(String hrp, byte[] data) {
        }
    }
    // --- End Custom Crypto Utilities ---

    // --- Custom Nostr Utilities ---
    public static class CustomNostrUtils {
        private static final ObjectMapper nostrJsonMapper = new ObjectMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        public static String toJson(Object object) throws JsonProcessingException {
            return nostrJsonMapper.writeValueAsString(object);
        }

        public static <T> T fromJson(String json, TypeReference<T> typeReference) throws JsonProcessingException {
            return nostrJsonMapper.readValue(json, typeReference);
        }

        public static class NostrEvent {
            public String id;
            public String pubkey;
            public long created_at;
            public int kind;
            public List<List<String>> tags = new ArrayList<>();
            public String content;
            public String sig;

            public String getSerializedForSigning() throws JsonProcessingException {
                List<Object> list = new ArrayList<>();
                list.add(0);
                list.add(this.pubkey);
                list.add(this.created_at);
                list.add(this.kind);
                list.add(this.tags);
                list.add(this.content);
                return nostrJsonMapper.writeValueAsString(list);
            }

            public void calculateId() throws NoSuchAlgorithmException, JsonProcessingException {
                var digest = MessageDigest.getInstance("SHA-256");
                var hash = digest.digest(getSerializedForSigning().getBytes(StandardCharsets.UTF_8));
                this.id = CustomCryptoUtils.bytesToHex(hash);
            }

            public void sign(byte[] privateKey32Bytes, byte[] auxRand32Bytes) throws GeneralSecurityException, JsonProcessingException {
                if (this.id == null) calculateId();
                var msgHash = CustomCryptoUtils.hexToBytes(this.id);
                this.sig = CustomCryptoUtils.bytesToHex(SchnorrUtil.sign(msgHash, privateKey32Bytes, auxRand32Bytes));
            }

            public boolean verifySignature() throws GeneralSecurityException, JsonProcessingException {
                if (this.id == null || this.pubkey == null || this.sig == null) return false;
                var msgHash = CustomCryptoUtils.hexToBytes(this.id);
                var pubkeyX = CustomCryptoUtils.hexToBytes(this.pubkey);
                var signature = CustomCryptoUtils.hexToBytes(this.sig);
                return SchnorrUtil.verify(msgHash, pubkeyX, signature);
            }
        }
    }

    public static class CustomCryptoUtils {
        private static final String PROVIDER_BC = BouncyCastleProvider.PROVIDER_NAME;
        private static final X9ECParameters SECP256K1_PARAMS = CustomNamedCurves.getByName("secp256k1");
        private static final ECCurve CURVE = SECP256K1_PARAMS.getCurve();
        private static final ECPoint G = SECP256K1_PARAMS.getG();
        private static final BigInteger N = SECP256K1_PARAMS.getN();
        private static final SecureRandom secureRandom = new SecureRandom();

        public static byte[] hexToBytes(String s) { /* Assume Unchanged from 1.5 */
            if (s == null) return null;
            var len = s.length();
            var data = new byte[len / 2];
            for (var i = 0; i < len; i += 2) {
                data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i + 1), 16));
            }
            return data;
        }

        public static String bytesToHex(byte[] bytes) { /* Assume Unchanged from 1.5 */
            if (bytes == null) return null;
            var sb = new StringBuilder();
            for (var b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }

        public static byte[] generatePrivateKey() {
            var privKey = new byte[32];
            secureRandom.nextBytes(privKey);
            return privKey;
        }

        public static byte[] getPublicKeyXOnly(byte[] privateKeyBytes) {
            var d = new BigInteger(1, privateKeyBytes);
            var P = G.multiply(d);
            return P.getAffineXCoord().toBigInteger().toByteArray(); // Ensure it's 32 bytes, pad/truncate if necessary
        }

        public static byte[] getPublicKeyCompressed(byte[] privateKeyBytes) {
            var d = new BigInteger(1, privateKeyBytes);
            var P = G.multiply(d);
            return P.getEncoded(true); // BouncyCastle's getEncoded(true) gives compressed format (33 bytes)
        }

        public static byte[] getSharedSecret(byte[] myPrivateKeyBytes, byte[] theirPublicKeyCompressedBytes) throws GeneralSecurityException {
            var dMy = new BigInteger(1, myPrivateKeyBytes);
            var theirP = CURVE.decodePoint(theirPublicKeyCompressedBytes); // Decode their compressed pubkey
            var sharedPoint = theirP.multiply(dMy).normalize(); // Perform ECDH
            // NIP-04 uses the x-coordinate of the shared point, hashed with SHA256, as the AES key
            var sharedX = sharedPoint.getAffineXCoord().toBigInteger().toByteArray();
            // Ensure sharedX is 32 bytes, left-padding with 0s if necessary
            if (sharedX.length < 32) {
                var padded = new byte[32];
                System.arraycopy(sharedX, 0, padded, 32 - sharedX.length, sharedX.length);
                sharedX = padded;
            } else if (sharedX.length > 32) { // Should not happen if derived from secp256k1 point
                sharedX = Arrays.copyOfRange(sharedX, sharedX.length - 32, sharedX.length);
            }
            // Per NIP-04, the shared secret for AES is just the x-coordinate of the ECDH point.
            // No extra SHA256 hashing of this x-coordinate is specified by NIP-04 for the AES key itself.
            return sharedX;
        }

        public static String nip04Encrypt(String plaintext, byte[] sharedSecret32Bytes, byte[] theirPublicKeyXOnlyBytes) throws GeneralSecurityException {
            // NIP-04 uses AES-256-CBC. The sharedSecret32Bytes IS the AES key.
            var random = new SecureRandom();
            var iv = new byte[16];
            random.nextBytes(iv);
            var cipher = Cipher.getInstance("AES/CBC/PKCS5Padding", PROVIDER_BC);
            var keySpec = new SecretKeySpec(sharedSecret32Bytes, "AES");
            var ivSpec = new IvParameterSpec(iv);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
            var ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ciphertext) + "?iv=" + Base64.getEncoder().encodeToString(iv);
        }

        public static String nip04Decrypt(String nip04Payload, byte[] sharedSecret32Bytes) throws GeneralSecurityException {
            var parts = nip04Payload.split("\\?iv=");
            if (parts.length != 2) throw new IllegalArgumentException("Invalid NIP-04 payload format");
            var ciphertext = Base64.getDecoder().decode(parts[0]);
            var iv = Base64.getDecoder().decode(parts[1]);
            var cipher = Cipher.getInstance("AES/CBC/PKCS5Padding", PROVIDER_BC);
            var keySpec = new SecretKeySpec(sharedSecret32Bytes, "AES");
            var ivSpec = new IvParameterSpec(iv);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
            var decryptedText = cipher.doFinal(ciphertext);
            return new String(decryptedText, StandardCharsets.UTF_8);
        }

        public static byte[] generateAuxRand() {
            var r = new byte[32];
            secureRandom.nextBytes(r);
            return r;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Note { /* Assume Unchanged from 1.5 */
        public String id = UUID.randomUUID().toString();
        public int version = 1;
        public Instant createdAt = Instant.now();
        public Instant updatedAt = Instant.now();
        public List<String> tags = new ArrayList<>();
        public Map<String, Object> content = new HashMap<>();
        public Map<String, Object> metadata = new HashMap<>();
        public List<Link> links = new ArrayList<>();
        public float[] embeddingV1;

        public Note() {
        }

        public Note(String t, String txt) {
            this.content.putAll(Map.of("title", t, "text", txt));
        }

        public String getTitle() {
            return (String) content.getOrDefault("title", "Untitled");
        }

        public void setTitle(String t) {
            content.put("title", t);
        }

        public String getText() {
            return (String) content.getOrDefault("text", "");
        }

        public void setText(String t) {
            content.put("text", t);
        }

        public float[] getEmbeddingV1() {
            return embeddingV1;
        }

        public void setEmbeddingV1(float[] e) {
            this.embeddingV1 = e;
        }

        public String getContentForEmbedding() {
            return getTitle() + "\n" + getText();
        }

        @Override
        public String toString() {
            return getTitle();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            return id.equals(((Note) o).id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Link { /* Assume Unchanged */
        public String targetNoteId;
        public String relationType;
        public Map<String, Object> properties = new HashMap<>();

        public Link() {
        }

        public Link(String t, String r) {
            this.targetNoteId = t;
            this.relationType = r;
        }
    }

    public static class Core { /* Adapted for new NostrService event type */
        private static final Logger logger = LoggerFactory.getLogger(Core.class);
        public final ObjectMapper json;
        public final NoteService note;
        public final ConfigService cfg;
        public final NostrService net;
        public final SyncService sync;
        public final LMService lm;
        private final Map<String, Consumer<String>> events = new ConcurrentHashMap<>();

        public Core() {
            this.json = new ObjectMapper().registerModule(new JavaTimeModule()).configure(SerializationFeature.INDENT_OUTPUT, true).configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
            var dDir = Paths.get(System.getProperty("user.home"), ".netention", "data");
            try {
                Files.createDirectories(dDir);
            } catch (IOException e) {
                logger.error("Failed to create data dir {}", dDir, e);
                throw new RuntimeException("Init failed: data dir error.", e);
            }
            this.note = new NoteService(dDir, json);
            this.cfg = new ConfigService(note);
            this.lm = new LMService(cfg);
            this.net = new NostrService(this.cfg, this::handleIncomingNostrEvent, () -> this.cfg.nostr.publicKeyBech32);
            this.sync = new SyncService(net);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Netention shutting down...");
                sync.stop();
                if (net.isEnabled()) net.setEnabled(false);
            }));
            logger.info("NetentionCore initialized.");
        }

        public void on(String cId, Consumer<String> l) {
            events.put(cId, l);
        }

        public void off(String cId) {
            events.remove(cId);
        }

        @SuppressWarnings("unchecked")
        private void handleIncomingNostrEvent(CustomNostrUtils.NostrEvent event) {
            logger.debug("Handling Nostr event: kind={},id={}", event.kind, event.id);
            if (event.kind == 4) {
                try {
                    var myPrivKeyBytes = Bech32.nip19Decode(net.getPrivateKeyBech32());
                    var senderPubKeyXOnlyBytes = CustomCryptoUtils.hexToBytes(event.pubkey);
                    var sharedSecret = net.getSharedSecretWithRetry(myPrivKeyBytes, senderPubKeyXOnlyBytes);
                    var decryptedContent = CustomCryptoUtils.nip04Decrypt(event.content, sharedSecret);
                    logger.info("Decrypted DM from {}: {}", event.pubkey.substring(0, 8), decryptedContent.substring(0, Math.min(decryptedContent.length(), 50)) + (decryptedContent.length() > 50 ? "..." : ""));
                    var partnerNpub = Bech32.nip19Encode("npub", senderPubKeyXOnlyBytes);
                    var chatId = "chat_" + partnerNpub;
                    var chatNote = note.getNote(chatId).orElseGet(() -> {
                        var nCN = new Note("Chat with " + partnerNpub.substring(0, 10) + "...", "");
                        nCN.id = chatId;
                        nCN.tags.addAll(List.of("chat", "nostr"));
                        nCN.metadata.put("nostrPubKey", partnerNpub);
                        nCN.content.put("messages", new ArrayList<Map<String, String>>());
                        logger.info("Created new chat note for {}", partnerNpub);
                        return nCN;
                    });
                    ((List<Map<String, String>>) chatNote.content.get("messages")).add(Map.of("sender", partnerNpub, "timestamp", Instant.ofEpochSecond(event.created_at).toString(), "text", decryptedContent));
                    note.saveNote(chatNote);
                    Optional.ofNullable(events.get(chatId)).ifPresent(l -> SwingUtilities.invokeLater(() -> l.accept(partnerNpub.substring(0, 8) + ": " + decryptedContent)));
                } catch (Exception e) {
                    logger.error("Error processing NIP04Event {}: {}", event.id, e.getMessage(), e);
                }
            } else if (event.kind == 1) {
                logger.info("Public text note from {}: {}", event.pubkey.substring(0, 8), event.content.substring(0, Math.min(event.content.length(), 50)) + (event.content.length() > 50 ? "..." : ""));
                var noteId = "nostr_event_" + event.id;
                if (note.getNote(noteId).isEmpty()) {
                    var pubN = new Note("Nostr: " + event.content.substring(0, Math.min(event.content.length(), 30)), event.content);
                    pubN.id = noteId;
                    pubN.tags.add("nostr_feed");
                    try {
                        pubN.metadata.putAll(Map.of("nostrEventId", event.id, "nostrPubKey", Bech32.nip19Encode("npub", CustomCryptoUtils.hexToBytes(event.pubkey))));
                    } catch (Exception e) {
                        logger.warn("Could not encode incoming pubkey to npub", e);
                    }
                    pubN.createdAt = Instant.ofEpochSecond(event.created_at);
                    note.saveNote(pubN);
                    logger.info("Saved public Nostr note {}", noteId);
                }
            }
        }
    }

    public static class NoteService { /* Assume Unchanged from 1.5 */
        private static final Logger logger = LoggerFactory.getLogger(NoteService.class);
        private final Path dataDir;
        private final ObjectMapper objectMapper;
        private final Map<String, Note> noteCache = new ConcurrentHashMap<>();

        public NoteService(Path dD, ObjectMapper om) {
            this.dataDir = dD;
            this.objectMapper = om;
            loadAllNotes();
        }

        private void loadAllNotes() {
            if (!Files.exists(dataDir)) {
                logger.warn("Data dir {} not exist.", dataDir);
                return;
            }
            try (var ps = Files.walk(dataDir)) {
                ps.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".json")).forEach(this::loadNoteFromFile);
                logger.info("Loaded {} notes.", noteCache.size());
            } catch (IOException e) {
                logger.error("Error walking data dir {}: {}", dataDir, e.getMessage(), e);
            }
        }

        private void loadNoteFromFile(Path fp) {
            try {
                var n = objectMapper.readValue(fp.toFile(), Note.class);
                noteCache.put(n.id, n);
                logger.debug("Loaded note {} from {}", n.id, fp);
            } catch (IOException e) {
                logger.error("Failed to load note from {}: {}", fp, e.getMessage(), e);
            }
        }

        public Optional<Note> getNote(String id) {
            return Optional.ofNullable(noteCache.get(id));
        }

        public List<Note> getAllNotes() {
            return new ArrayList<>(noteCache.values());
        }

        public List<Note> findNotes(Predicate<Note> f) {
            return noteCache.values().stream().filter(f).collect(Collectors.toList());
        }

        public Note saveNote(Note n) {
            n.updatedAt = Instant.now();
            var isNew = !noteCache.containsKey(n.id);
            if (isNew) {
                n.createdAt = Instant.now();
                n.version = 1;
            } else {
                n.version = noteCache.get(n.id).version + 1;
            }
            noteCache.put(n.id, n);
            try {
                objectMapper.writeValue(dataDir.resolve(n.id + ".json").toFile(), n);
                logger.info("Saved note {}(v{}). New:{}", n.id, n.version, isNew);
            } catch (IOException e) {
                logger.error("Failed to save note {}: {}", n.id, e.getMessage(), e);
            }
            return n;
        }

        public boolean deleteNote(String id) {
            if (!noteCache.containsKey(id)) {
                logger.warn("Attempted delete non-existent note {}", id);
                return false;
            }
            noteCache.remove(id);
            try {
                Files.deleteIfExists(dataDir.resolve(id + ".json"));
                logger.info("Deleted note {}", id);
                return true;
            } catch (IOException e) {
                logger.error("Failed to delete note file for {}: {}", id, e.getMessage(), e);
                return false;
            }
        }
    }

    public static class ConfigService { /* Adapted for new key generation */
        private static final Logger logger = LoggerFactory.getLogger(ConfigService.class);
        private static final String CONFIG_NOTE_PREFIX = "netention_config_";
        public final NostrSettings nostr = new NostrSettings();
        public final UISettings ui = new UISettings();
        public final LMSettings lm = new LMSettings();
        public final NoteService note;

        public ConfigService(NoteService noteService) {
            this.note = noteService;
            loadAllConfigs();
        }

        public void loadAllConfigs() {
            loadConfigForObject(nostr, "nostr");
            loadConfigForObject(ui, "ui");
            loadConfigForObject(lm, "llm");
            logger.info("All configurations loaded/initialized using annotation-driven objects.");
        }

        public void saveAllConfigs() {
            saveConfigForObject(nostr, "nostr");
            saveConfigForObject(ui, "ui");
            saveConfigForObject(lm, "llm");
            logger.info("All configurations persisted using annotation-driven objects.");
        }

        @SuppressWarnings("unchecked")
        private void loadConfigForObject(Object configInstance, String typeKey) {
            var noteId = CONFIG_NOTE_PREFIX + typeKey;
            note.getNote(noteId).ifPresentOrElse(
                    note -> {
                        logger.debug("{} config loaded from note {}", typeKey, noteId);
                        var savedValues = note.content;
                        for (var field : configInstance.getClass().getDeclaredFields()) {
                            if (field.isAnnotationPresent(ConfigField.class)) {
                                try {
                                    field.setAccessible(true);
                                    if (savedValues.containsKey(field.getName())) {
                                        var savedValue = savedValues.get(field.getName());
                                        if (field.getType().isAssignableFrom(List.class) && savedValue instanceof List) {
                                            field.set(configInstance, new ArrayList<>((List<?>) savedValue));
                                        } else if ((field.getType() == Boolean.class || field.getType() == boolean.class) && savedValue instanceof Boolean) {
                                            field.set(configInstance, savedValue);
                                        } else if (field.getType().isEnum() && savedValue instanceof String) {
                                            field.set(configInstance, Enum.valueOf((Class<Enum>) field.getType(), (String) savedValue));
                                        } else {
                                            field.set(configInstance, field.getType().cast(savedValue));
                                        }
                                    }
                                } catch (IllegalAccessException | ClassCastException e) {
                                    logger.error("Error loading config field {} for {}: {}", field.getName(), typeKey, e.getMessage());
                                }
                            }
                        }
                    },
                    () -> {
                        logger.info("{} config note {} not found, using defaults and saving.", typeKey, noteId);
                        saveConfigForObject(configInstance, typeKey);
                    }
            );
        }

        public void saveConfigForObject(Object configInstance, String typeKey) {
            var noteId = CONFIG_NOTE_PREFIX + typeKey;
            var cfgNote = note.getNote(noteId).orElse(new Note());
            cfgNote.id = noteId;
            cfgNote.content.clear();
            for (var field : configInstance.getClass().getDeclaredFields()) {
                if (field.isAnnotationPresent(ConfigField.class)) {
                    try {
                        field.setAccessible(true);
                        cfgNote.content.put(field.getName(), field.get(configInstance));
                    } catch (IllegalAccessException e) {
                        logger.error("Error saving config field {} for {}: {}", field.getName(), typeKey, e.getMessage());
                    }
                }
            }
            if (!cfgNote.tags.contains(typeKey + "_config")) cfgNote.tags.add(typeKey + "_config");
            if (!cfgNote.tags.contains("config")) cfgNote.tags.add("config");
            note.saveNote(cfgNote);
            logger.info("Saved {} config to note {}", typeKey, noteId);
        }

        public String generateNewNostrKeysAndUpdateConfig() {
            try {
                var privKeyRaw = CustomCryptoUtils.generatePrivateKey();
                var pubKeyXOnlyRaw = CustomCryptoUtils.getPublicKeyXOnly(privKeyRaw);
                nostr.privateKeyBech32 = Bech32.nip19Encode("nsec", privKeyRaw);
                nostr.publicKeyBech32 = Bech32.nip19Encode("npub", pubKeyXOnlyRaw);
                saveConfigForObject(nostr, "nostr");
                return "nsec: " + nostr.privateKeyBech32 + "\nnpub: " + nostr.publicKeyBech32;
            } catch (Exception e) {
                logger.error("Failed to generate Nostr keys", e);
                return "Error: " + e.getMessage();
            }
        }

        public static class NostrSettings {
            @ConfigField(label = "Relays (one per line)", type = UIFieldType.TEXT_AREA, group = "Connection")
            public List<String> relays = new ArrayList<>(List.of("wss://relay.damus.io", "wss://nos.lol"));
            @ConfigField(label = "Private Key (nsec)", tooltip = "Your Nostr secret key (nsec...)", type = UIFieldType.PASSWORD_FIELD, group = "Identity")
            public String privateKeyBech32 = "";
            public String publicKeyBech32 = "";
        }

        public static class UISettings { /* Assume Unchanged from 1.5 */
            @ConfigField(label = "Theme", type = UIFieldType.COMBO_BOX, choices = {"Default", "Dark"}, group = "Appearance")
            public String theme = "Default";
            @ConfigField(label = "Minimize to System Tray", tooltip = "If enabled, closing the window minimizes to tray instead of exiting.", group = "Behavior")
            public boolean minimizeToTray = true;
        }

        public static class LMSettings { /* Assume Unchanged from 1.5 */
            @ConfigField(label = "Provider", type = UIFieldType.COMBO_BOX, choices = {"NONE", "OLLAMA", "GEMINI"}, group = "General")
            public String provider = "NONE";
            @ConfigField(label = "API Key", tooltip = "Required for Gemini provider", group = "Gemini")
            public String geminiApiKey = "";
            @ConfigField(label = "Project ID", group = "Gemini")
            public String geminiProject = "";
            @ConfigField(label = "Location", group = "Gemini")
            public String geminiLocation = "us-central1";
            @ConfigField(label = "Chat Model", group = "Gemini")
            public String geminiChatModelName = "gemini-1.5-flash-001";
            @ConfigField(label = "Embedding Model", group = "Gemini")
            public String geminiEmbeddingModelName = "text-embedding-004";
            @ConfigField(label = "Base URL", group = "Ollama")
            public String ollamaBaseUrl = "http://localhost:11434";
            @ConfigField(label = "Chat Model", group = "Ollama")
            public String ollamaChatModelName = "llama3";
            @ConfigField(label = "Embedding Model", group = "Ollama")
            public String ollamaEmbeddingModelName = "nomic-embed-text";
        }
    }

    public static class LMService { /* Assume Unchanged from 1.5 */
        private static final Logger logger = LoggerFactory.getLogger(LMService.class);
        private final ConfigService.LMSettings llmCfg;
        private EmbeddingModel embeddingModel;
        private ChatLanguageModel chatModel;
        private volatile boolean isInitialized = false, isReady = false;

        public LMService(ConfigService cs) {
            this.llmCfg = cs.lm;
        }

        public static double cosineSimilarity(float[] vA, float[] vB) {
            if (vA == null || vB == null || vA.length == 0 || vA.length != vB.length) return 0.0;
            double d = 0.0, nA = 0.0, nB = 0.0;
            for (var i = 0; i < vA.length; i++) {
                d += vA[i] * vB[i];
                nA += vA[i] * vA[i];
                nB += vB[i] * vB[i];
            }
            return (nA == 0 || nB == 0) ? 0.0 : d / (Math.sqrt(nA) * Math.sqrt(nB));
        }

        public synchronized void init() {
            if (isInitialized && isReady) {
                logger.debug("LLMService already initialized/ready.");
                return;
            }
            isInitialized = false;
            isReady = false;
            var prov = llmCfg.provider;
            logger.info("Initializing LLMService with provider: {}", prov);
            try {
                switch (prov.toUpperCase()) {
                    case "OLLAMA":
                        embeddingModel = OllamaEmbeddingModel.builder().baseUrl(llmCfg.ollamaBaseUrl).modelName(llmCfg.ollamaEmbeddingModelName).timeout(Duration.ofSeconds(60)).build();
                        chatModel = OllamaChatModel.builder().baseUrl(llmCfg.ollamaBaseUrl).modelName(llmCfg.ollamaChatModelName).timeout(Duration.ofSeconds(120)).build();
                        break;
                    case "GEMINI":
                        embeddingModel = null;//VertexAiEmbeddingModel.builder().project(llmCfg.geminiProject).location(llmCfg.geminiLocation).modelName(llmCfg.geminiEmbeddingModelName).maxRetries(2).build();
                        chatModel = null;//VertexAiGeminiChatModel.builder().project(llmCfg.geminiProject).location(llmCfg.geminiLocation).modelName(llmCfg.geminiChatModelName).maxRetries(2).build();
                        break;
                    case "NONE":
                    default:
                        logger.info("LLM provider NONE/unsupported. LLM features disabled.");
                        isInitialized = true;
                        isReady = false;
                        return;
                }
                isReady = true;
                logger.info("LLMService initialized successfully for provider: {}", prov);
            } catch (Exception e) {
                logger.error("Failed to initialize LLM provider {}: {}. LLM features disabled.", prov, e.getMessage(), e);
                embeddingModel = null;
                chatModel = null;
                isReady = false;
            }
            isInitialized = true;
        }

        public boolean isReady() {
            if (!isInitialized) init();
            return isReady;
        }

        public Optional<float[]> generateEmbedding(String t) {
            if (!isReady()) {
                logger.warn("LLM not ready, cannot gen embedding.");
                return Optional.empty();
            }
            try {
                return Optional.of(embeddingModel.embed(t).content().vector());
            } catch (Exception e) {
                logger.error("Error gen embedding: {}", e.getMessage(), e);
                return Optional.empty();
            }
        }

        public Optional<String> chat(String p) {
            if (!isReady()) {
                logger.warn("LLM not ready, cannot chat.");
                return Optional.empty();
            }
            try {
                return Optional.of(chatModel.chat(p));
            } catch (Exception e) {
                logger.error("Error during chat: {}", e.getMessage(), e);
                return Optional.empty();
            }
        }

        public Optional<String> summarize(String t) {
            return (t == null || t.trim().isEmpty()) ? Optional.of("") : chat("Summarize concisely:\n\n" + t);
        }

        public Optional<String> askAboutText(String t, String q) {
            return (t == null || t.trim().isEmpty() || q == null || q.trim().isEmpty()) ? Optional.empty() : chat("Context:\n\"\"\"\n" + t + "\n\"\"\"\n\nQuestion: " + q + "\nAnswer:");
        }

        public Optional<List<String>> decomposeTask(String task) {
            return (task == null || task.trim().isEmpty()) ? Optional.empty() : chat("Decompose into sub-tasks (prefix each with '- '):\n" + task).map(r -> Stream.of(r.split("\\n")).map(String::trim).filter(s -> s.startsWith("- ")).map(s -> s.substring(2).trim()).filter(s -> !s.isEmpty()).collect(Collectors.toList()));
        }
    }

    public static class SyncService { /* Assume Unchanged from 1.5 */
        private static final Logger logger = LoggerFactory.getLogger(SyncService.class);
        private final NostrService net;
        private ScheduledExecutorService sched;
        private ScheduledFuture<?> syncTaskFut;
        private volatile boolean running = false;

        public SyncService(NostrService n) {
            this.net = n;
        }

        public synchronized void start() {
            if (running) {
                logger.info("Sync service already running.");
                return;
            }
            sched = Executors.newSingleThreadScheduledExecutor(r -> {
                var t = new Thread(r, "NetentionSyncThread");
                t.setDaemon(true);
                return t;
            });
            syncTaskFut = sched.scheduleAtFixedRate(() -> {
                try {
                    if (net.isEnabled()) {
                        logger.debug("Periodic sync: processing Nostr queue.");
                        net.processQueue();
                    } else {
                        logger.debug("Periodic sync: Nostr disabled.");
                    }
                } catch (Exception e) {
                    logger.error("Error during periodic sync", e);
                }
            }, 0, 30, TimeUnit.SECONDS);
            running = true;
            logger.info("Sync service started.");
        }

        public synchronized void stop() {
            if (!running) {
                logger.info("Sync service not running/stopped.");
                return;
            }
            running = false;
            if (syncTaskFut != null) syncTaskFut.cancel(false);
            if (sched != null) {
                sched.shutdown();
                try {
                    if (!sched.awaitTermination(5, TimeUnit.SECONDS)) sched.shutdownNow();
                } catch (InterruptedException e) {
                    sched.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            logger.info("Sync service stopped.");
        }

        public boolean isRunning() {
            return running;
        }
    }

    // --- NostrService Fully Refactored ---
    public static class NostrService {
        private static final Logger logger = LoggerFactory.getLogger(NostrService.class);
        private final ConfigService.NostrSettings nostrCfg;
        private final List<RelayConnection> relayConnections = new CopyOnWriteArrayList<>();
        private final ConcurrentLinkedQueue<NostrAction> actionQueue = new ConcurrentLinkedQueue<>();
        private final Consumer<CustomNostrUtils.NostrEvent> eventHandler;
        private final HttpClient httpClient;
        private byte[] privateKeyRaw; // 32-byte raw private key
        private String publicKeyXOnlyHex; // Hex of x-only pubkey (for event.pubkey)
        private volatile boolean enabled = false;


        public NostrService(ConfigService cs, Consumer<CustomNostrUtils.NostrEvent> eh, java.util.function.Supplier<String> selfNpubSupplier) {
            this.nostrCfg = cs.nostr;
            this.eventHandler = eh;
            this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            loadIdentity();
        }

        private void loadIdentity() {
            // 32-byte raw x-coordinate of public key
            byte[] publicKeyXOnlyRaw;
            if (nostrCfg.privateKeyBech32 == null || nostrCfg.privateKeyBech32.isEmpty()) {
                logger.warn("Nostr private key (nsec) not configured.");
                this.privateKeyRaw = null;
                publicKeyXOnlyRaw = null;
                this.publicKeyXOnlyHex = null;
                nostrCfg.publicKeyBech32 = "";
                return;
            }
            try {
                this.privateKeyRaw = Bech32.nip19Decode(nostrCfg.privateKeyBech32);
                publicKeyXOnlyRaw = CustomCryptoUtils.getPublicKeyXOnly(this.privateKeyRaw);
                this.publicKeyXOnlyHex = CustomCryptoUtils.bytesToHex(publicKeyXOnlyRaw);
                nostrCfg.publicKeyBech32 = Bech32.nip19Encode("npub", publicKeyXOnlyRaw);
                logger.info("Nostr identity loaded for pubkey: {}", nostrCfg.publicKeyBech32);
            } catch (Exception e) {
                logger.error("Failed to load Nostr identity from nsec: {}. Nostr unavailable.", e.getMessage(), e);
                this.privateKeyRaw = null;
                publicKeyXOnlyRaw = null;
                this.publicKeyXOnlyHex = null;
                nostrCfg.publicKeyBech32 = "";
            }
        }

        public String getPrivateKeyBech32() {
            return nostrCfg.privateKeyBech32;
        }

        public String getPublicKeyBech32() {
            return nostrCfg.publicKeyBech32;
        } // This is npub

        public boolean isEnabled() {
            return enabled;
        }

        public synchronized void setEnabled(boolean shouldEnable) { /* Assume Unchanged from 1.5, but uses new RelayConnection */
            if (shouldEnable == this.enabled) return;
            if (shouldEnable) {
                loadIdentity();
                if (privateKeyRaw == null || publicKeyXOnlyHex == null) {
                    logger.error("Cannot enable Nostr: identity not loaded/invalid.");
                    this.enabled = false;
                    return;
                }
                connectToRelays();
                processQueue();
            } else {
                disconnectFromRelays();
            }
            this.enabled = shouldEnable;
            logger.info("Nostr service {}", this.enabled ? "enabled" : "disabled");
        }

        private void connectToRelays() {
            disconnectFromRelays();
            if (nostrCfg.relays.isEmpty()) {
                logger.warn("No Nostr relays configured.");
                return;
            }
            var selfXOnlyHex = this.publicKeyXOnlyHex; // For REQ filter
            for (var relayUrl : nostrCfg.relays) {
                try {
                    var conn = new RelayConnection(URI.create(relayUrl), httpClient, this::handleRelayMessage, selfXOnlyHex);
                    conn.connect();
                    relayConnections.add(conn);
                } catch (Exception e) {
                    logger.error("Failed to initiate connection to relay {}: {}", relayUrl, e.getMessage());
                }
            }
        }

        private void disconnectFromRelays() {
            relayConnections.forEach(RelayConnection::close);
            relayConnections.clear();
            logger.info("All relay connections closed.");
        }

        private void handleRelayMessage(String relayUri, String message) { /* Assume Unchanged from 1.5, ensure mapToNostrEvent is robust */
            logger.trace("Relay {} RX: {}", relayUri, message);
            try {
                var msgList = CustomNostrUtils.fromJson(message, new TypeReference<List<Object>>() {
                });
                var type = (String) msgList.get(0);

                var n = msgList.size();
                if (n >= 3 && "EVENT".equals(type)) {
                    @SuppressWarnings("unchecked")
                    var eventMap = (Map<String, Object>) msgList.get(2);
                    var event = mapToNostrEvent(eventMap);
                    if (eventHandler != null) eventHandler.accept(event);
                } else if (n >= 2 && "NOTICE".equals(type)) {
                    logger.warn("Relay {} NOTICE: {}", relayUri, msgList.get(1));
                } else if (n >= 2 && "EOSE".equals(type)) {
                    logger.info("Relay {} EOSE for subscription: {}", relayUri, msgList.get(1));
                } else {
                    logger.debug("Relay {} unhandled message type: {}", relayUri, type);
                }
            } catch (Exception e) {
                logger.error("Error processing message from relay {}: {}", relayUri, message, e);
            }
        }

        private CustomNostrUtils.NostrEvent mapToNostrEvent(Map<String, Object> map) { /* Assume Unchanged from 1.5 */
            var e = new CustomNostrUtils.NostrEvent();
            e.id = (String) map.get("id");
            e.pubkey = (String) map.get("pubkey");
            e.created_at = ((Number) map.get("created_at")).longValue();
            e.kind = ((Number) map.get("kind")).intValue();
            e.content = (String) map.get("content");
            e.sig = (String) map.get("sig");
            if (map.get("tags") instanceof List) {
                ((List<?>) map.get("tags")).forEach(tagObj -> {
                    if (tagObj instanceof List<?> tl)
                        e.tags.add(tl.stream().map(Object::toString).toList());
                });
            }
            return e;
        }

        public void queueAction(NostrAction a) {
            actionQueue.add(a);
            if (enabled) processQueue();
        }

        public void processQueue() { /* Assume Unchanged from 1.5 */
            if (!enabled || privateKeyRaw == null) return;
            NostrAction action;
            while ((action = actionQueue.poll()) != null) {
                try {
                    switch (action.t) {
                        case PUBLISH_NOTE:
                            publishNoteInternal((Note) action.p);
                            break;
                        case SEND_DM:
                            @SuppressWarnings("unchecked") var dmParams = (Map<String, String>) action.p;
                            sendDirectMessageInternal(dmParams.get("recipientNpub"), dmParams.get("message"));
                            break;
                    }
                } catch (Exception e) {
                    logger.error("Error processing Nostr action {} from queue: {}", action.t, e.getMessage(), e);
                }
            }
        }

        private void publishNoteInternal(Note note) throws GeneralSecurityException, JsonProcessingException {
            var e = new CustomNostrUtils.NostrEvent();
            e.pubkey = this.publicKeyXOnlyHex;
            e.created_at = Instant.now().getEpochSecond();
            e.kind = 1;
            e.content = note.getTitle() + "\n\n" + note.getText();
            note.tags.forEach(t -> e.tags.add(List.of("t", t)));
            e.sign(this.privateKeyRaw, CustomCryptoUtils.generateAuxRand());
            broadcastToRelays(CustomNostrUtils.toJson(List.of("EVENT", e)));
            logger.info("Published Note (Kind 1): {}", e.id.substring(0, 8));
        }

        // NIP-04: ECDH shared secret derivation with retry for y-parity
        public byte[] getSharedSecretWithRetry(byte[] myPrivKeyBytes, byte[] theirXOnlyPubKeyBytes) throws GeneralSecurityException {
            // Try 0x02 prefix
            var theirCompressed02 = new byte[33];
            theirCompressed02[0] = 0x02;
            System.arraycopy(theirXOnlyPubKeyBytes, 0, theirCompressed02, 1, 32);
            try {
                // Verify if this forms a valid point on the curve before ECDH
                CustomCryptoUtils.CURVE.decodePoint(theirCompressed02); // Throws if invalid
                return CustomCryptoUtils.getSharedSecret(myPrivKeyBytes, theirCompressed02);
            } catch (Exception e) {
                // Try 0x03 prefix
                var theirCompressed03 = new byte[33];
                theirCompressed03[0] = 0x03;
                System.arraycopy(theirXOnlyPubKeyBytes, 0, theirCompressed03, 1, 32);
                try {
                    CustomCryptoUtils.CURVE.decodePoint(theirCompressed03); // Throws if invalid
                    return CustomCryptoUtils.getSharedSecret(myPrivKeyBytes, theirCompressed03);
                } catch (Exception e2) {
                    throw new GeneralSecurityException("Could not derive shared secret: Invalid recipient public key (x-only). " + e.getMessage() + " | " + e2.getMessage());
                }
            }
        }


        private void sendDirectMessageInternal(String recipientNpub, String message) throws Exception {
            var e = new CustomNostrUtils.NostrEvent();
            e.pubkey = this.publicKeyXOnlyHex;
            e.created_at = Instant.now().getEpochSecond();
            e.kind = 4;
            var recipientXOnlyBytes = Bech32.nip19Decode(recipientNpub);
            var sharedSecret = getSharedSecretWithRetry(this.privateKeyRaw, recipientXOnlyBytes);
            e.content = CustomCryptoUtils.nip04Encrypt(message, sharedSecret, recipientXOnlyBytes); // Pass recipientXOnly for context if needed by encrypt, though NIP-04 encrypt uses shared secret primarily
            e.tags.add(List.of("p", CustomCryptoUtils.bytesToHex(recipientXOnlyBytes)));
            e.sign(this.privateKeyRaw, CustomCryptoUtils.generateAuxRand());
            var eventJson = CustomNostrUtils.toJson(List.of("EVENT", e));
            broadcastToRelays(eventJson);
            logger.info("Sent DM (Kind 4) to {}: {}", recipientNpub.substring(0, 8), e.id.substring(0, 8));
        }

        private void broadcastToRelays(String jsonMessage) { /* Assume Unchanged from 1.5 */
            logger.debug("Broadcasting to relays: {}", jsonMessage.substring(0, Math.min(jsonMessage.length(), 100)));
            relayConnections.stream()
                    .filter(RelayConnection::isConnected)
                    .forEach(rc -> rc.send(jsonMessage));
        }

        public void publishNote(Note n) {
            queueAction(new NostrAction(NostrActionType.PUBLISH_NOTE, n));
        }

        public void sendDirectMessage(String recipientNpub, String message) {
            queueAction(new NostrAction(NostrActionType.SEND_DM, Map.of("recipientNpub", recipientNpub, "message", message)));
        }

        public void sendFriendRequest(String recipientNpub) {
            sendDirectMessage(recipientNpub, "Hello! I'd like to connect on Netention.");
        }

        private enum NostrActionType {PUBLISH_NOTE, SEND_DM}

        private static class RelayConnection implements WebSocket.Listener { /* Adapted to take selfPubKeyHex */
            private final URI uri;
            private final HttpClient http;
            private final StringBuilder messageBuffer = new StringBuilder();
            private final BiConsumer<String, String> onMessageCallback;
            private final String selfPublicKeyXOnlyHexForReq;
            private WebSocket socket;
            private volatile boolean connected = false;

            public RelayConnection(URI uri, HttpClient client, BiConsumer<String, String> onMessage, String selfPubKeyXOnlyHex) {
                this.uri = uri;
                this.http = client;
                this.onMessageCallback = onMessage;
                this.selfPublicKeyXOnlyHexForReq = selfPubKeyXOnlyHex;
            }

            public void connect() { /* Assume Unchanged from 1.5, but onOpen uses selfPublicKeyXOnlyHexForReq */
                if (connected && socket != null) return;
                logger.info("Connecting to relay: {}", uri);
                http.newWebSocketBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .buildAsync(uri, this)
                        .thenAccept(ws -> {
                            this.socket = ws;
                        })
                        .exceptionally(ex -> {
                            logger.error("Failed to connect to relay {}: {}", uri, ex.getMessage());
                            this.connected = false;
                            return null;
                        });
            }

            @Override
            public void onOpen(WebSocket ws) {
                logger.info("Connected to relay: {}", uri);
                this.connected = true;
                ws.request(1);
                try {
                    if (selfPublicKeyXOnlyHexForReq != null && !selfPublicKeyXOnlyHexForReq.isEmpty()) {
                        var subId = "mydms-" + UUID.randomUUID().toString().substring(0, 8);
                        Map<String, Object> dmFilter = Map.of("kinds", List.of(4), "#p", List.of(selfPublicKeyXOnlyHexForReq));
                        // Also general feed subscription (optional)
                        var generalSubId = "myfeed-" + UUID.randomUUID().toString().substring(0, 8);
                        var generalFeedFilter = Map.of("kinds", List.of(1), "limit", 20); // Limit initial load

                        var reqJsonDms = CustomNostrUtils.toJson(List.of("REQ", subId, dmFilter));
                        var reqJsonFeed = CustomNostrUtils.toJson(List.of("REQ", generalSubId, generalFeedFilter));

                        send(reqJsonDms);
                        send(reqJsonFeed);
                        logger.info("Sent REQ for DMs and Feed to relay {}", uri);
                    } else {
                        logger.warn("Cannot send REQ to relay {}: self public key hex not available.", uri);
                    }
                } catch (Exception e) {
                    logger.error("Error sending initial REQ to relay {}: {}", uri, e.getMessage());
                }
            }

            @Override
            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) { /* Assume Unchanged from 1.5 */
                messageBuffer.append(data);
                ws.request(1);
                if (last) {
                    if (onMessageCallback != null) {
                        try {
                            onMessageCallback.accept(uri.toString(), messageBuffer.toString());
                        } catch (Exception e) {
                            logger.error("Error in onMessageCallback for relay {}: {}", uri, e.getMessage(), e);
                        }
                    }
                    messageBuffer.setLength(0);
                }
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) { /* Assume Unchanged from 1.5 */
                logger.info("Disconnected from relay {}: {} - {}", uri, statusCode, reason);
                this.connected = false;
                return null;
            }

            @Override
            public void onError(WebSocket ws, Throwable error) { /* Assume Unchanged from 1.5 */
                logger.error("Error with relay {}: {}", uri, error.getMessage(), error);
                this.connected = false;
            }

            public void send(String message) { /* Assume Unchanged from 1.5 */
                if (connected && socket != null) {
                    logger.trace("Relay {} TX: {}", uri, message.substring(0, Math.min(message.length(), 100)));
                    socket.sendText(message, true);
                } else {
                    logger.warn("Cannot send message to relay {}: not connected.", uri);
                }
            }

            public void close() {
                if (socket != null) socket.sendClose(WebSocket.NORMAL_CLOSURE, "Client closing").join();
                connected = false;
            }

            public boolean isConnected() {
                return connected;
            }
        }

        private record NostrAction(NostrActionType t, Object p) {
        }
    }
    // --- End NostrService Refactored ---

    // UI Classes (NetentionUI, NavigationPanel, NoteEditorPanel, InspectorPanel, ChatPanel, SettingsPanel, StatusBarPanel)
    // These classes remain largely the same as in version 1.5 (the previous step with UI refactoring),
    // with minor adaptations for new NostrService method signatures or data types if any.
    // For brevity, they are omitted here but assume they are present and adapted.
    // Key adaptations:
    // - SettingsPanel: `generateNewNostrKeysAndUpdateConfig` uses the new CustomCryptoUtils/Bech32.
    //                  Display of npub from nsec also uses these.
    // - ChatPanel: `sendDirectMessage` in NostrService now takes npub.
    // - NetentionUI (MenuBar): "Add Nostr Friend" uses npub.
    public static class NetentionUI extends JFrame { /* Assume Adapted from 1.5 */
        private static final Logger logger = LoggerFactory.getLogger(NetentionUI.class);
        private final Core core;
        private final JSplitPane contentInspectorSplitPane;
        private final NavPanel navPanel;
        private final JPanel contentPanelHost;
        private final InspectorPanel inspectorPanel;
        private final StatusPanel statusPanel;
        private TrayIcon trayIcon;
        private SystemTray tray;

        public NetentionUI(Core core) {
            this.core = core;
            setTitle("Netention");
            setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            setSize(1024, 768);
            setLocationRelativeTo(null);
            addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    handleWindowClose();
                }

                @Override
                public void windowIconified(WindowEvent e) {
                    if (core.cfg.ui.minimizeToTray && tray != null) {
                        setVisible(false);
                        logger.debug("Window iconified, hiding to tray.");
                    }
                }
            });

            inspectorPanel = new InspectorPanel(core);

            setJMenuBar(createMenuBar());

            navPanel = new NavPanel(core, this::display, this::displayChatInEditor, this::displaySettingsInEditor, this::createNewNote);
            contentPanelHost = new JPanel(new BorderLayout());

            contentInspectorSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, contentPanelHost, inspectorPanel);
            contentInspectorSplitPane.setResizeWeight(0.8);
            contentInspectorSplitPane.setOneTouchExpandable(true);

            var mainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, navPanel, contentInspectorSplitPane);
            mainSplitPane.setDividerLocation(250);
            mainSplitPane.setOneTouchExpandable(true);
            add(mainSplitPane, BorderLayout.CENTER);

            statusPanel = new StatusPanel(core);
            add(statusPanel, BorderLayout.SOUTH);

            initSystemTray();
            updateTheme(core.cfg.ui.theme);
            setVisible(true);
            display(null);
            inspectorPanel.setVisible(false);
            contentInspectorSplitPane.setDividerLocation(1.0);
            logger.info("NetentionUI initialized.");
        }

        private void handleWindowClose() {
            if (core.cfg.ui.minimizeToTray && tray != null && trayIcon != null) {
                setVisible(false);
                logger.info("Window hidden to system tray.");
                trayIcon.displayMessage("Netention", "Running in background.", TrayIcon.MessageType.INFO);
            } else {
                logger.info("Exiting application via window close.");
                core.sync.stop();
                if (core.net.isEnabled()) core.net.setEnabled(false);
                System.exit(0);
            }
        }

        private void initSystemTray() {
            if (!SystemTray.isSupported()) {
                logger.warn("SystemTray is not supported.");
                return;
            }
            tray = SystemTray.getSystemTray();
            var image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            var g2d = image.createGraphics();
            g2d.setColor(Color.BLUE);
            g2d.fillRect(0, 0, 16, 16);
            g2d.setColor(Color.WHITE);
            g2d.drawString("N", 4, 12);
            g2d.dispose();

            var trayMenu = initMenu();

            trayIcon = new TrayIcon(image, "Netention", trayMenu);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> restoreWindow());
            try {
                tray.add(trayIcon);
                logger.info("System tray icon added.");
            } catch (AWTException e) {
                logger.error("Failed to add system tray icon: {}", e.getMessage(), e);
                trayIcon = null;
                tray = null;
            }
        }

        private @NotNull PopupMenu initMenu() {
            var trayMenu = new PopupMenu();
            var openItem = new MenuItem("Open Netention");
            openItem.addActionListener(e -> restoreWindow());
            trayMenu.add(openItem);
            var quickAddItem = new MenuItem("Quick Add Note");
            quickAddItem.addActionListener(e -> quickAddNoteFromTray());
            trayMenu.add(quickAddItem);
            trayMenu.addSeparator();
            var exitItem = new MenuItem("Exit");
            exitItem.addActionListener(e -> {
                tray.remove(trayIcon);
                System.exit(0);
            });
            trayMenu.add(exitItem);
            return trayMenu;
        }

        private void restoreWindow() {
            setVisible(true);
            setState(JFrame.NORMAL);
            toFront();
            logger.debug("Window restored.");
        }

        private void quickAddNoteFromTray() {
            restoreWindow();
            createNewNote();
        }

        private void setContentPanel(JComponent panel, Note contextNote) {
            contentPanelHost.removeAll();
            if (panel != null) contentPanelHost.add(panel, BorderLayout.CENTER);
            contentPanelHost.revalidate();
            contentPanelHost.repaint();
            inspectorPanel.setContextNote(contextNote);
            var showInspector = contextNote != null;
            if (inspectorPanel.isVisible() != showInspector) {
                inspectorPanel.setVisible(showInspector);
                contentInspectorSplitPane.setDividerLocation(showInspector ? 0.8 : 1.0);
            }
            logger.debug("Content panel set to: {}, context note: {}", panel != null ? panel.getClass().getSimpleName() : "empty", contextNote != null ? contextNote.id : "none");
        }

        public void display(@Nullable Note note) {
            if (note != null && note.tags.contains("chat")) {
                displayChatInEditor(note);
            } else {
                displayNoteInEditor(note);
            }
        }

        private void displayNoteInEditor(@Nullable Note note) {
            setContentPanel(new NoteEditorPanel(core, note, () -> {
                navPanel.refreshNotes();
                statusPanel.updateStatus(note == null || note.id == null ? "Note created" : "Note saved: " + (note != null ? note.getTitle() : "New Note"));
                inspectorPanel.setContextNote(note);
            }), note);
        }

        public void createNewNote() {
            display(null);
        }

        public void displayChatInEditor(Note chatNote) {
            if (!chatNote.tags.contains("chat")) {
                logger.warn("Attempted to open non-chat note {} in chat view.", chatNote.id);
                display(chatNote);
                return;
            }
            var partnerNpub = (String) chatNote.metadata.get("nostrPubKey");
            if (partnerNpub == null) {
                JOptionPane.showMessageDialog(this, "Chat partner PK (npub) not found.", "Chat Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            var chatP = new ChatPanel(core, chatNote, partnerNpub);
            setContentPanel(chatP, chatNote);
        }

        public void displaySettingsInEditor() {
            var settingsP = new SettingsPanel(core, this::updateThemeAndRestartMessage, () -> {
                statusPanel.updateStatus("LLM status updated.");
                navPanel.updateLLMButtonStates();
                var currentContent = contentPanelHost.getComponentCount() > 0 ? contentPanelHost.getComponent(0) : null;
                if (currentContent instanceof NoteEditorPanel nep) nep.updateLLMButtonStates();
                if (currentContent instanceof InspectorPanel ip) ip.updateLLMButtonStates();
            });
            setContentPanel(settingsP, null);
        }

        private void updateThemeAndRestartMessage(String themeName) {
            updateTheme(themeName);
            JOptionPane.showMessageDialog(this, "Theme changed. Some L&F changes may require restart.", "Theme Changed", JOptionPane.INFORMATION_MESSAGE);
        }

        private void updateTheme(String themeName) {
            try {
                if ("Dark".equalsIgnoreCase(themeName)) {
                    UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel");
//                Map.of("nimbusBase",new Color(18,30,49),
//                    "nimbusLightBackground",new Color(18,30,49),
//                    "control",new Color(50,50,50),
//                    "text",new Color(230,230,230),
//                    "nimbusFocus",new Color(115,164,209),
//                    "nimbusSelectionBackground",new Color(104,93,156),
//                    "List.background",new Color(40,40,40),"List.foreground",new Color(220,220,220),
//                    "TextField.background",new Color(50,50,50),"TextField.foreground",new Color(230,230,230),
//                    "TextArea.background",new Color(50,50,50),"TextArea.foreground",new Color(230,230,230),
//                    "Button.background",new Color(70,70,70),"Button.foreground",new Color(230,230,230))
//                    .forEach(UIManager::put);
                } else UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                SwingUtilities.updateComponentTreeUI(this);
                logger.info("Theme updated to: {}", themeName);
            } catch (Exception e) {
                logger.warn("Failed to set theme '{}': {}", themeName, e.getMessage());
            }
        }

        private JMenuBar createMenuBar() {
            var mb = new JMenuBar();
            var fileM = new JMenu("File");
            fileM.add(new JMenuItem(new AbstractAction("New Note") {
                public void actionPerformed(ActionEvent e) {
                    createNewNote();
                }
            }));
            fileM.add(new JMenuItem(new AbstractAction("Settings") {
                public void actionPerformed(ActionEvent e) {
                    displaySettingsInEditor();
                }
            }));
            fileM.addSeparator();
            fileM.add(new JMenuItem(new AbstractAction("Exit") {
                public void actionPerformed(ActionEvent e) {
                    handleWindowClose();
                }
            }));
            mb.add(fileM);
            var viewM = new JMenu("View");
            var toggleInspectorItem = new JCheckBoxMenuItem("Toggle Inspector Panel");
            toggleInspectorItem.setSelected(inspectorPanel.isVisible());
            toggleInspectorItem.addActionListener(e -> {
                var show = toggleInspectorItem.isSelected();
                inspectorPanel.setVisible(show);
                contentInspectorSplitPane.setDividerLocation(show ? 0.8 : 1.0);
                contentInspectorSplitPane.revalidate();
            });
            viewM.add(toggleInspectorItem);
            mb.add(viewM);
            var nostrM = new JMenu("Nostr");
            var toggleNostr = new JCheckBoxMenuItem("Enable Nostr");
            toggleNostr.setSelected(core.net.isEnabled());
            toggleNostr.addActionListener(e -> {
                var enable = toggleNostr.isSelected();
                core.net.setEnabled(enable);
                var msg = "Nostr " + (core.net.isEnabled() ? "Enabled" : "Disabled.");
                statusPanel.updateStatus(msg);
                JOptionPane.showMessageDialog(this, msg, "Nostr Status", JOptionPane.INFORMATION_MESSAGE);
            });
            nostrM.add(toggleNostr);
            nostrM.add(new JMenuItem(new AbstractAction("Add Nostr Friend") {
                public void actionPerformed(ActionEvent e) {
                    var pkNpub = JOptionPane.showInputDialog(NetentionUI.this, "Friend's Nostr public key (npub):");
                    if (pkNpub != null && !pkNpub.trim().isEmpty()) {
                        try {
                            Bech32.nip19Decode(pkNpub.trim());
                            core.net.sendFriendRequest(pkNpub.trim());
                            var cId = "chat_" + pkNpub.trim();
                            if (core.note.getNote(cId).isEmpty()) {
                                var fn = new Note("Chat with " + pkNpub.trim().substring(0, 10) + "...", "");
                                fn.id = cId;
                                fn.tags.addAll(List.of("friend_profile", "chat", "nostr"));
                                fn.metadata.put("nostrPubKey", pkNpub.trim());
                                fn.content.put("messages", new ArrayList<Map<String, String>>());
                                core.note.saveNote(fn);
                                navPanel.refreshNotes();
                                JOptionPane.showMessageDialog(NetentionUI.this, "Friend " + pkNpub.trim().substring(0, 10) + "... added & intro DM sent.", "Friend Added", JOptionPane.INFORMATION_MESSAGE);
                            } else
                                JOptionPane.showMessageDialog(NetentionUI.this, "Friend " + pkNpub.trim().substring(0, 10) + "... already exists.", "Friend Exists", JOptionPane.INFORMATION_MESSAGE);
                        } catch (Exception ex) {
                            JOptionPane.showMessageDialog(NetentionUI.this, "Invalid Nostr public key (npub): " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                        }
                    }
                }
            }));
            mb.add(nostrM);
            var syncM = new JMenu("Sync");
            var toggleSync = new JCheckBoxMenuItem("Enable Sync Service");
            toggleSync.setSelected(core.sync.isRunning());
            if (core.sync.isRunning()) core.sync.start();
            else core.sync.stop();
            toggleSync.addActionListener(e -> {
                var en = toggleSync.isSelected();
                if (en) core.sync.start();
                else core.sync.stop();
                statusPanel.updateStatus("Sync Service " + (en ? "Started" : "Stopped"));
            });
            syncM.add(toggleSync);
            mb.add(syncM);
            var llmM = new JMenu("LLM");
            llmM.add(new JMenuItem(new AbstractAction("Initialize LLM Service") {
                public void actionPerformed(ActionEvent e) {
                    core.lm.init();
                    var msg = "LLM Service " + (core.lm.isReady() ? "initialized." : "failed to initialize. Check settings/logs.");
                    JOptionPane.showMessageDialog(NetentionUI.this, msg, "LLM Status", core.lm.isReady() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.ERROR_MESSAGE);
                    statusPanel.updateStatus("LLM status updated.");
                    navPanel.updateLLMButtonStates();
                    var c = contentPanelHost.getComponentCount() > 0 ? contentPanelHost.getComponent(0) : null;
                    if (c instanceof NoteEditorPanel nep) nep.updateLLMButtonStates();
                    if (c instanceof InspectorPanel ip) ip.updateLLMButtonStates();
                }
            }));
            mb.add(llmM);
            return mb;
        }
    }

    public static class NavPanel extends JPanel { /* Assume Adapted from 1.5 */
        private final Core core;
        private final DefaultListModel<Note> listModel = new DefaultListModel<>();
        private final JList<Note> noteJList = new JList<>(listModel);
        private final JTextField searchField = new JTextField(15);
        private final JButton semanticSearchButton;

        public NavPanel(Core core, Consumer<Note> onShowNote, Consumer<Note> onShowChat, Runnable onSettings, Runnable onNewNote) {
            this.core = core;
            setLayout(new BorderLayout(5, 5));
            setBorder(new EmptyBorder(5, 5, 5, 5));
            noteJList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            noteJList.addListSelectionListener((ListSelectionEvent e) -> {
                if (!e.getValueIsAdjusting()) {
                    var sel = noteJList.getSelectedValue();
                    if (sel != null) (sel.tags.contains("chat") ? onShowChat : onShowNote).accept(sel);
                    else onShowNote.accept(null);
                }
            });
            add(new JScrollPane(noteJList), BorderLayout.CENTER);

            var searchPanel = new JPanel(new BorderLayout(5, 0));
            searchPanel.add(new JLabel("Search:"), BorderLayout.WEST);
            searchPanel.add(searchField, BorderLayout.CENTER);
            searchField.getDocument().addDocumentListener(new DocumentListener() {
                public void changedUpdate(DocumentEvent e) {
                    refreshNotes();
                }

                public void removeUpdate(DocumentEvent e) {
                    refreshNotes();
                }

                public void insertUpdate(DocumentEvent e) {
                    refreshNotes();
                }
            });
            semanticSearchButton = new JButton("AI");
            semanticSearchButton.setToolTipText("Semantic Search (AI)");
            semanticSearchButton.addActionListener(e -> performSemanticSearch());
            var combinedSearchPanel = new JPanel(new BorderLayout());
            combinedSearchPanel.add(searchPanel, BorderLayout.CENTER);
            combinedSearchPanel.add(semanticSearchButton, BorderLayout.EAST);

            var topControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
            var newNoteBtn = new JButton("+Note");
            newNoteBtn.setToolTipText("Create New Note");
            newNoteBtn.addActionListener(e -> onNewNote.run());
            topControls.add(newNoteBtn);
            var settingsBtn = new JButton("Prefs");
            settingsBtn.setToolTipText("Open Settings");
            settingsBtn.addActionListener(e -> onSettings.run());
            topControls.add(settingsBtn);

            var northPanel = new JPanel(new BorderLayout(0, 5));
            northPanel.add(topControls, BorderLayout.NORTH);
            northPanel.add(combinedSearchPanel, BorderLayout.CENTER);
            add(northPanel, BorderLayout.NORTH);
            refreshNotes();
            updateLLMButtonStates();
        }

        public void updateLLMButtonStates() {
            semanticSearchButton.setEnabled(core.lm.isReady());
        }

        public void refreshNotes() {
            refreshNotes(null);
        }

        public void refreshNotes(List<Note> notes) {
            var selectedBefore = noteJList.getSelectedValue();
            listModel.clear();
            var term = searchField.getText().toLowerCase();
            ((notes != null) ? notes : core.note.findNotes(n -> !n.tags.contains("config") && (term.isEmpty() || n.getTitle().toLowerCase().contains(term) || n.getText().toLowerCase().contains(term) || n.tags.stream().anyMatch(t -> t.toLowerCase().contains(term)))))
                    .stream().sorted((n1, n2) -> n2.updatedAt.compareTo(n1.updatedAt)).forEach(listModel::addElement);
            if (selectedBefore != null && listModel.contains(selectedBefore))
                noteJList.setSelectedValue(selectedBefore, true);
        }

        private void performSemanticSearch() {
            if (!core.lm.isReady()) {
                JOptionPane.showMessageDialog(this, "LLM Service not ready.", "LLM Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            var query = JOptionPane.showInputDialog(this, "Semantic search query:");
            if (query == null || query.trim().isEmpty()) return;
            core.lm.generateEmbedding(query).ifPresentOrElse(qEmb -> {
                var notesWithEmb = core.note.getAllNotes().stream().filter(n -> n.getEmbeddingV1() != null && !n.tags.contains("config")).collect(Collectors.toList());
                if (notesWithEmb.isEmpty()) {
                    JOptionPane.showMessageDialog(this, "No notes with embeddings.", "Semantic Search", JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                var scored = notesWithEmb.stream().map(n -> Map.entry(n, LMService.cosineSimilarity(qEmb, n.getEmbeddingV1()))).filter(entry -> entry.getValue() > 0.1).sorted((e1, e2) -> Double.compare(e2.getValue(), e1.getValue())).map(Map.Entry::getKey).collect(Collectors.toList());
                if (scored.isEmpty())
                    JOptionPane.showMessageDialog(this, "No relevant notes found.", "Semantic Search", JOptionPane.INFORMATION_MESSAGE);
                else refreshNotes(scored);
            }, () -> JOptionPane.showMessageDialog(this, "Failed to generate embedding for query.", "LLM Error", JOptionPane.ERROR_MESSAGE));
        }
    }

    public static class NoteEditorPanel extends JPanel { /* Assume Adapted from 1.5 */
        private final Core core;
        private final Runnable onSaveCb;
        private final JTextField titleF = new JTextField(40);
        private final JTextArea contentA = new JTextArea(15, 40);
        private final JTextField tagsF = new JTextField(40);
        private final JLabel embStatusL = new JLabel("Embedding: Unknown");
        private final JButton saveButton, publishButton, deleteButton;
        private Note currentNote;

        public NoteEditorPanel(Core core, Note note, Runnable onSaveCb) {
            super(new BorderLayout(5, 5));
            setBorder(new EmptyBorder(10, 10, 10, 10));
            this.core = core;
            this.currentNote = note == null ? null : core.note.getNote(note.id).orElse(note);
            this.onSaveCb = onSaveCb;

            var formP = new JPanel(new GridBagLayout());
            var gbc = new GridBagConstraints();
            gbc.insets = new Insets(2, 2, 2, 2);
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.anchor = GridBagConstraints.WEST;
            var y = 0;
            gbc.gridx = 0;
            gbc.gridy = y;
            formP.add(new JLabel("Title:"), gbc);
            gbc.gridx = 1;
            gbc.gridy = y++;
            gbc.weightx = 1.0;
            formP.add(titleF, gbc);
            gbc.gridx = 0;
            gbc.gridy = y;
            formP.add(new JLabel("Tags:"), gbc);
            gbc.gridx = 1;
            gbc.gridy = y++;
            formP.add(tagsF, gbc);
            tagsF.setToolTipText("Comma-separated");
            gbc.gridx = 0;
            gbc.gridy = y;
            gbc.anchor = GridBagConstraints.NORTHWEST;
            formP.add(new JLabel("Content:"), gbc);
            gbc.gridx = 0;
            gbc.gridy = ++y;
            gbc.gridwidth = 2;
            gbc.weighty = 1.0;
            gbc.fill = GridBagConstraints.BOTH;
            contentA.setLineWrap(true);
            contentA.setWrapStyleWord(true);
            formP.add(new JScrollPane(contentA), gbc);
            gbc.gridy = ++y;
            gbc.weighty = 0.0;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            formP.add(embStatusL, gbc);
            add(formP, BorderLayout.CENTER);

            var bottomButtonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            saveButton = new JButton("Save");
            saveButton.addActionListener(e -> saveNote(false));
            bottomButtonPanel.add(saveButton);
            publishButton = new JButton("Save & Publish (Nostr)");
            publishButton.addActionListener(e -> saveNote(true));
            bottomButtonPanel.add(publishButton);
            deleteButton = new JButton("Delete");
            deleteButton.addActionListener(e -> deleteCurrentNote());
            bottomButtonPanel.add(deleteButton);
            add(bottomButtonPanel, BorderLayout.SOUTH);

            if (note == null) {
                titleF.setText("");
                contentA.setText("Select a note or create a new one.");
                titleF.setEnabled(false);
                contentA.setEnabled(false);
                tagsF.setEnabled(false);
                embStatusL.setText("No note loaded.");
                saveButton.setEnabled(false);
                publishButton.setEnabled(false);
                deleteButton.setEnabled(false);
            } else {
                populateFields();
            }
            updateNostrButtonStates();
        }

        private void populateFields() {
            if (currentNote == null) return;
            titleF.setText(currentNote.getTitle());
            contentA.setText(currentNote.getText());
            tagsF.setText(String.join(", ", currentNote.tags));
            updateEmbeddingStatus();
            titleF.setEnabled(true);
            contentA.setEnabled(true);
            tagsF.setEnabled(true);
            saveButton.setEnabled(true);
            deleteButton.setEnabled(true);
            updateNostrButtonStates();
        }

        private void updateEmbeddingStatus() {
            embStatusL.setText("Embedding: " + (currentNote != null && currentNote.getEmbeddingV1() != null ? "Generated (" + currentNote.getEmbeddingV1().length + " dims)" : "Not Generated"));
        }

        public void updateLLMButtonStates() {
        }

        private void updateNostrButtonStates() {
            publishButton.setEnabled(core.net.isEnabled() && core.net.privateKeyRaw != null && currentNote != null);
        }

        public Note getCurrentNote() {
            return currentNote;
        }

        public void updateNoteFromFields() {
            if (currentNote == null) return;
            currentNote.setTitle(titleF.getText());
            currentNote.setText(contentA.getText());
            currentNote.tags.clear();
            Stream.of(tagsF.getText().split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(currentNote.tags::add);
        }

        private void saveNote(boolean andPublish) {
            if (currentNote == null) {
                currentNote = new Note(titleF.getText(), contentA.getText());
                titleF.setEnabled(true);
                contentA.setEnabled(true);
                tagsF.setEnabled(true);
                saveButton.setEnabled(true);
                deleteButton.setEnabled(true);
            }
            updateNoteFromFields();
            core.note.saveNote(currentNote);
            if (andPublish) {
                core.net.publishNote(currentNote);
            }
            if (onSaveCb != null) onSaveCb.run();
            updateNostrButtonStates();
        }

        private void deleteCurrentNote() {
            if (currentNote == null || currentNote.id == null) {
                JOptionPane.showMessageDialog(this, "No note loaded to delete.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (JOptionPane.showConfirmDialog(this, "Delete note '" + currentNote.getTitle() + "'?", "Confirm Delete", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION) {
                var deletedId = currentNote.id;
                core.note.deleteNote(deletedId);
                currentNote = null;
                if (onSaveCb != null) onSaveCb.run();
                JOptionPane.showMessageDialog(this, "Note deleted.", "Deleted", JOptionPane.INFORMATION_MESSAGE);
            }
        }
    }

    public static class InspectorPanel extends JPanel { /* Assume Adapted from 1.5 */
        private final Core core;
        private final JTextArea llmAnalysisArea;
        private final List<JButton> llmButtons = new ArrayList<>();
        private final JLabel noteInfoLabel;
        private Note contextNote;

        public InspectorPanel(Core core) {
            super(new BorderLayout(5, 5));
            this.core = core;
            setBorder(BorderFactory.createTitledBorder("Inspector"));
            setPreferredSize(new Dimension(250, 0));

            var contentPanel = new JPanel();
            contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));

            noteInfoLabel = new JLabel("No note selected.");
            noteInfoLabel.setBorder(new EmptyBorder(5, 5, 5, 5));
            contentPanel.add(noteInfoLabel);

            var llmToolbar = new JToolBar();
            llmToolbar.setFloatable(false);
            llmToolbar.setLayout(new FlowLayout(FlowLayout.LEFT));
            Stream.of("Embed:EMBED", "Summarize:SUMMARIZE", "Ask:ASK", "Decompose:DECOMPOSE").forEach(s -> {
                var p = s.split(":");
                var b = new JButton(p[0]);
                b.setActionCommand(p[1]);
                b.addActionListener(this::handleLLMAction);
                llmButtons.add(b);
                llmToolbar.add(b);
            });
            contentPanel.add(llmToolbar);

            llmAnalysisArea = new JTextArea(8, 20);
            llmAnalysisArea.setEditable(false);
            llmAnalysisArea.setLineWrap(true);
            llmAnalysisArea.setWrapStyleWord(true);
            llmAnalysisArea.setFont(llmAnalysisArea.getFont().deriveFont(Font.ITALIC));
            llmAnalysisArea.setBackground(getBackground().darker());
            contentPanel.add(new JScrollPane(llmAnalysisArea));

            add(contentPanel, BorderLayout.NORTH);
            updateLLMButtonStates();
        }

        public void setContextNote(Note note) {
            this.contextNote = note;
            if (note != null) {
                noteInfoLabel.setText("<html><b>" + note.getTitle() + "</b><br>Tags: " + String.join(", ", note.tags) + "<br>Updated: " + DateTimeFormatter.ISO_INSTANT.format(note.updatedAt.atZone(ZoneId.systemDefault())).substring(0, 19) + "</html>");
                displayLLMAnalysis();
            } else {
                noteInfoLabel.setText("No note selected.");
                llmAnalysisArea.setText("");
            }
            updateLLMButtonStates();
        }

        public void updateLLMButtonStates() {
            var llmReady = core.lm.isReady();
            llmButtons.forEach(b -> b.setEnabled(llmReady && contextNote != null));
        }

        private void displayLLMAnalysis() {
            if (contextNote == null) {
                llmAnalysisArea.setText("");
                return;
            }
            var sb = new StringBuilder();
            Optional.ofNullable(contextNote.metadata.get("llm:summary")).ifPresent(s -> sb.append("Summary:\n").append(s).append("\n\n"));
            Optional.ofNullable(contextNote.metadata.get("llm:decomposition")).ifPresent(d -> {
                if (d instanceof List) {
                    sb.append("Task Decomposition:\n");
                    ((List<?>) d).forEach(i -> sb.append("- ").append(i).append("\n"));
                    sb.append("\n");
                }
            });
            llmAnalysisArea.setText(sb.toString().trim());
            llmAnalysisArea.setCaretPosition(0);
        }

        private void handleLLMAction(ActionEvent e) {
            if (!core.lm.isReady() || contextNote == null) {
                JOptionPane.showMessageDialog(this, "LLM Service not ready or no note selected.", "LLM Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            var cmd = e.getActionCommand();
            var textContent = contextNote.getText();
            var titleContent = contextNote.getTitle();
            if ("EMBED".equals(cmd)) {
                core.lm.generateEmbedding(contextNote.getContentForEmbedding()).ifPresentOrElse(emb -> {
                    contextNote.setEmbeddingV1(emb);
                    core.note.saveNote(contextNote);
                    displayLLMAnalysis();
                    JOptionPane.showMessageDialog(this, "Embedding generated and saved.", "LLM", JOptionPane.INFORMATION_MESSAGE);
                }, () -> JOptionPane.showMessageDialog(this, "Failed to generate embedding.", "LLM Error", JOptionPane.ERROR_MESSAGE));
            } else if ("SUMMARIZE".equals(cmd)) {
                core.lm.summarize(textContent).ifPresent(s -> {
                    contextNote.metadata.put("llm:summary", s);
                    core.note.saveNote(contextNote);
                    displayLLMAnalysis();
                    JOptionPane.showMessageDialog(this, "Summary generated and saved.", "LLM", JOptionPane.INFORMATION_MESSAGE);
                });
            } else if ("ASK".equals(cmd)) {
                var q = JOptionPane.showInputDialog(this, "Ask about note content:");
                if (q != null && !q.trim().isEmpty())
                    core.lm.askAboutText(textContent, q).ifPresent(a -> JOptionPane.showMessageDialog(this, a, "Answer", JOptionPane.INFORMATION_MESSAGE));
            } else if ("DECOMPOSE".equals(cmd)) {
                core.lm.decomposeTask(titleContent.isEmpty() ? textContent : titleContent).ifPresent(d -> {
                    contextNote.metadata.put("llm:decomposition", d);
                    core.note.saveNote(contextNote);
                    displayLLMAnalysis();
                    JOptionPane.showMessageDialog(this, "Task decomposed and saved.", "LLM", JOptionPane.INFORMATION_MESSAGE);
                });
            }
        }
    }

    public static class ChatPanel extends JPanel { /* Assume Adapted from 1.5 */
        private final Core core;
        private final Note chatNote;
        private final String partnerNpub;
        private final JTextArea chatArea = new JTextArea(20, 50);
        private final JTextField messageInput = new JTextField(40);
        private final DateTimeFormatter chatTSFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

        public ChatPanel(Core core, Note chatNote, String partnerNpub) {
            super(new BorderLayout(5, 5));
            setBorder(new EmptyBorder(10, 10, 10, 10));
            this.core = core;
            this.chatNote = chatNote;
            this.partnerNpub = partnerNpub;
            chatArea.setEditable(false);
            chatArea.setLineWrap(true);
            chatArea.setWrapStyleWord(true);
            add(new JScrollPane(chatArea), BorderLayout.CENTER);
            var inputP = new JPanel(new BorderLayout(5, 0));
            inputP.add(messageInput, BorderLayout.CENTER);
            var sendB = new JButton("Send");
            sendB.addActionListener(e -> sendMessage());
            inputP.add(sendB, BorderLayout.EAST);
            add(inputP, BorderLayout.SOUTH);
            messageInput.addActionListener(e -> sendMessage());
            loadMessages();
            core.on(chatNote.id, this::appendMessageFromListener);
            addComponentListener(new ComponentAdapter() {
                @Override
                public void componentHidden(ComponentEvent e) {
                    core.off(chatNote.id);
                }
            });
        }

        @SuppressWarnings("unchecked")
        private void loadMessages() {
            chatArea.setText("");
            var fresh = core.note.getNote(chatNote.id).orElse(chatNote);
            ((List<Map<String, String>>) fresh.content.getOrDefault("messages", new ArrayList<>())).forEach(this::formatAndAppendMsg);
            scrollToBottom();
        }

        private void formatAndAppendMsg(Map<String, String> m) {
            var senderNpub = m.get("sender");
            var t = m.get("text");
            var ts = Instant.parse(m.get("timestamp"));
            var dn = senderNpub.equals(core.net.getPublicKeyBech32()) ? "Me" : senderNpub.substring(0, Math.min(senderNpub.length(), 8));
            chatArea.append(String.format("[%s] %s: %s\n", chatTSFormatter.format(ts), dn, t));
        }

        private void sendMessage() {
            var txt = messageInput.getText().trim();
            if (txt.isEmpty()) return;
            if (!core.net.isEnabled() || core.net.privateKeyRaw == null) {
                JOptionPane.showMessageDialog(this, "Nostr not enabled/configured.", "Nostr Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            core.net.sendDirectMessage(partnerNpub, txt);
            var entry = Map.of("sender", core.net.getPublicKeyBech32(), "timestamp", Instant.now().toString(), "text", txt);
            var cur = core.note.getNote(chatNote.id).orElse(chatNote);
            @SuppressWarnings("unchecked") var msgs = (List<Map<String, String>>) cur.content.computeIfAbsent("messages", k -> new ArrayList<>());
            msgs.add(entry);
            core.note.saveNote(cur);
            formatAndAppendMsg(entry);
            messageInput.setText("");
            scrollToBottom();
        }

        private void appendMessageFromListener(String rawMsg) {
            SwingUtilities.invokeLater(() -> {
                var fr = core.note.getNote(chatNote.id).orElse(chatNote);
                @SuppressWarnings("unchecked") var ms = (List<Map<String, String>>) fr.content.getOrDefault("messages", new ArrayList<>());
                if (!ms.isEmpty()) formatAndAppendMsg(ms.get(ms.size() - 1));
                scrollToBottom();
            });
        }

        private void scrollToBottom() {
            chatArea.setCaretPosition(chatArea.getDocument().getLength());
        }
    }

    public static class SettingsPanel extends JPanel { /* Adapted for new key utils */
        private final Core core;

        public SettingsPanel(Core core, Consumer<String> themeUpdater, Runnable llmInitCb) {
            super(new BorderLayout(10, 10));
            setBorder(new EmptyBorder(10, 10, 10, 10));
            this.core = core;

            var tabbedPane = new JTabbedPane();
            var nostrGroupedFields = groupFieldsByAnnotation(core.cfg.nostr);
            nostrGroupedFields.forEach((groupName, fields) -> tabbedPane.addTab("Nostr: " + groupName, buildConfigSubPanelFor(core.cfg.nostr, fields, "Nostr " + groupName)));

            var uiGroupedFields = groupFieldsByAnnotation(core.cfg.ui);
            uiGroupedFields.forEach((groupName, fields) -> tabbedPane.addTab("UI: " + groupName, buildConfigSubPanelFor(core.cfg.ui, fields, "UI " + groupName)));

            var llmGroupedFields = groupFieldsByAnnotation(core.cfg.lm);
            llmGroupedFields.forEach((groupName, fields) -> tabbedPane.addTab("LLM: " + groupName, buildConfigSubPanelFor(core.cfg.lm, fields, "LLM " + groupName)));

            add(tabbedPane, BorderLayout.CENTER);

            var saveButton = new JButton("Save All Settings");
            saveButton.addActionListener(e -> {
                core.cfg.saveAllConfigs();
                core.net.setEnabled(false); // Force re-load identity on next enable
                core.lm.init();
                JOptionPane.showMessageDialog(this, "All settings saved. Services re-initialized.", "Settings Saved", JOptionPane.INFORMATION_MESSAGE);
                if (themeUpdater != null) themeUpdater.accept(core.cfg.ui.theme);
                if (llmInitCb != null) llmInitCb.run();
            });
            var bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            bottomPanel.add(saveButton);
            add(bottomPanel, BorderLayout.SOUTH);
        }

        private Map<String, List<Field>> groupFieldsByAnnotation(Object configObject) {
            return Stream.of(configObject.getClass().getDeclaredFields())
                    .filter(f -> f.isAnnotationPresent(ConfigField.class))
                    .collect(Collectors.groupingBy(f -> f.getAnnotation(ConfigField.class).group()));
        }

        private JComponent buildConfigSubPanelFor(Object configObject, List<Field> fields, String title) {
            var panel = new JPanel(new GridBagLayout());
            panel.setName(title + " Settings Panel");
            var gbc = new GridBagConstraints();
            gbc.insets = new Insets(4, 4, 4, 4);
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.anchor = GridBagConstraints.WEST;
            var y = 0;
            final var pubKeyLabelHolder = new JLabel[1];

            for (var field : fields) {
                var cf = field.getAnnotation(ConfigField.class);
                gbc.gridx = 0;
                gbc.gridy = y;
                panel.add(new JLabel(cf.label()), gbc);
                var comp = createEditorComponent(field, configObject, cf);
                if (!cf.tooltip().isEmpty()) comp.setToolTipText(cf.tooltip());
                gbc.gridx = 1;
                gbc.gridy = y++;
                gbc.weightx = 1.0;
                panel.add(comp, gbc);
                gbc.weightx = 0.0;

                if (field.getName().equals("privateKeyBech32") && configObject instanceof ConfigService.NostrSettings) {
                    pubKeyLabelHolder[0] = new JLabel("Public Key (npub): " + ((ConfigService.NostrSettings) configObject).publicKeyBech32);
                    gbc.gridx = 1;
                    gbc.gridy = y++;
                    panel.add(pubKeyLabelHolder[0], gbc);
                    if (comp instanceof JPasswordField || comp instanceof JTextField) {
                        DocumentListener dl = new DocumentAdapter(de -> {
                            try {
                                var pkNsec = (comp instanceof JPasswordField) ? new String(((JPasswordField) comp).getPassword()) : ((JTextField) comp).getText();
                                if (pkNsec != null && !pkNsec.trim().isEmpty()) {
                                    var privKeyRaw = Bech32.nip19Decode(pkNsec);
                                    var pubKeyXOnlyRaw = CustomCryptoUtils.getPublicKeyXOnly(privKeyRaw);
                                    ((ConfigService.NostrSettings) configObject).publicKeyBech32 = Bech32.nip19Encode("npub", pubKeyXOnlyRaw);
                                } else {
                                    ((ConfigService.NostrSettings) configObject).publicKeyBech32 = "Enter nsec to derive";
                                }
                            } catch (Exception ex) {
                                ((ConfigService.NostrSettings) configObject).publicKeyBech32 = "Invalid nsec";
                            }
                            pubKeyLabelHolder[0].setText("Public Key (npub): " + ((ConfigService.NostrSettings) configObject).publicKeyBech32);
                        });
                        if (comp instanceof JPasswordField)
                            ((JPasswordField) comp).getDocument().addDocumentListener(dl);
                        else ((JTextField) comp).getDocument().addDocumentListener(dl);
                    }
                    var genKeysBtn = new JButton("Generate New Keys");
                    genKeysBtn.addActionListener(evt -> {
                        if (JOptionPane.showConfirmDialog(panel, "Generate new Nostr keys & overwrite? Backup existing!", "Confirm", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION) {
                            var keysInfo = core.cfg.generateNewNostrKeysAndUpdateConfig();
                            if (comp instanceof JPasswordField)
                                ((JPasswordField) comp).setText(((ConfigService.NostrSettings) configObject).privateKeyBech32);
                            else if (comp instanceof JTextField)
                                ((JTextField) comp).setText(((ConfigService.NostrSettings) configObject).privateKeyBech32);
                            pubKeyLabelHolder[0].setText("Public Key (npub): " + ((ConfigService.NostrSettings) configObject).publicKeyBech32);
                            var kda = new JTextArea(keysInfo, 5, 50);
                            kda.setEditable(false);
                            JOptionPane.showMessageDialog(panel, new JScrollPane(kda), "New Keys (Backup!)", JOptionPane.INFORMATION_MESSAGE);
                        }
                    });
                    gbc.gridx = 1;
                    gbc.gridy = y++;
                    gbc.anchor = GridBagConstraints.EAST;
                    panel.add(genKeysBtn, gbc);
                    gbc.anchor = GridBagConstraints.WEST;
                }
            }
            gbc.gridy = y;
            gbc.weighty = 1.0;
            panel.add(new JPanel(), gbc);
            return new JScrollPane(panel);
        }

        @SuppressWarnings("unchecked")
        private JComponent createEditorComponent(Field field, Object configObj, ConfigField cf) {
            try {
                field.setAccessible(true);
                var val = field.get(configObj);
                switch (cf.type()) {
                    case TEXT_AREA:
                        var ta = new JTextArea(3, 30);
                        if (val instanceof List) ta.setText(String.join("\n", (List<String>) val));
                        else if (val != null) ta.setText(val.toString());
                        ta.getDocument().addDocumentListener(new DocumentAdapter(e -> {
                            try {
                                field.set(configObj, new ArrayList<>(List.of(ta.getText().split("\\n"))));
                            } catch (IllegalAccessException ex) {
                                logger.error("Error setting TEXT_AREA field {}", field.getName(), ex);
                            }
                        }));
                        return new JScrollPane(ta);
                    case COMBO_BOX:
                        var cb = new JComboBox<String>(cf.choices());
                        if (val != null) cb.setSelectedItem(val.toString());
                        cb.addActionListener(e -> {
                            try {
                                field.set(configObj, cb.getSelectedItem());
                            } catch (IllegalAccessException ex) {
                                logger.error("Error setting COMBO_BOX field {}", field.getName(), ex);
                            }
                        });
                        return cb;
                    case CHECK_BOX:
                        var chkbx = new JCheckBox();
                        if (val instanceof Boolean) chkbx.setSelected((Boolean) val);
                        chkbx.addActionListener(e -> {
                            try {
                                field.set(configObj, chkbx.isSelected());
                            } catch (IllegalAccessException ex) {
                                logger.error("Error setting CHECK_BOX field {}", field.getName(), ex);
                            }
                        });
                        return chkbx;
                    case PASSWORD_FIELD:
                        var pf = new JPasswordField(30);
                        if (val != null) pf.setText(val.toString());
                        pf.getDocument().addDocumentListener(new DocumentAdapter(e -> {
                            try {
                                field.set(configObj, new String(pf.getPassword()));
                            } catch (IllegalAccessException ex) {
                                logger.error("Error setting PASSWORD_FIELD field {}", field.getName(), ex);
                            }
                        }));
                        return pf;
                    case TEXT_FIELD:
                    default:
                        var tf = new JTextField(30);
                        if (val != null) tf.setText(val.toString());
                        tf.getDocument().addDocumentListener(new DocumentAdapter(e -> {
                            try {
                                field.set(configObj, tf.getText());
                            } catch (IllegalAccessException ex) {
                                logger.error("Error setting TEXT_FIELD field {}", field.getName(), ex);
                            }
                        }));
                        return tf;
                }
            } catch (IllegalAccessException e) {
                return new JLabel("Error: " + e.getMessage());
            }
        }

        private record DocumentAdapter(Consumer<DocumentEvent> consumer) implements DocumentListener {

            @Override
            public void insertUpdate(DocumentEvent e) {
                consumer.accept(e);
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                consumer.accept(e);
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                consumer.accept(e);
            }
        }
    }

    public static class StatusPanel extends JPanel { /* Assume Unchanged from 1.5 */
        private final JLabel statusLabel;
        private final Core core;

        public StatusPanel(Core core) {
            super(new FlowLayout(FlowLayout.LEFT));
            this.core = core;
            setBorder(new EmptyBorder(2, 5, 2, 5));
            statusLabel = new JLabel("Initializing...");
            add(statusLabel);
            updateStatus("Application started.");
        }

        public void updateStatus(String message) {
            SwingUtilities.invokeLater(() -> statusLabel.setText(String.format("Status: %s | Nostr: %s | Sync: %s | LLM: %s", message, core.net.isEnabled() ? "ON" : "OFF", core.sync.isRunning() ? "RUN" : "STOP", core.lm.isReady() ? "READY" : "NOT READY")));
        }
    }
}