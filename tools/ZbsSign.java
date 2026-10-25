import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * ZombieBuddy .zbs signer (release tool, never shipped). Run with the source launcher:
 *   java tools/ZbsSign.java keygen <key file>                      new Ed25519 key (PKCS#8, mode 600); prints JavaModZBS:<hex>
 *   java tools/ZbsSign.java pubkey <key file>                      prints JavaModZBS:<hex> again
 *   java -cp ZombieBuddy.jar tools/ZbsSign.java sign <key file> <SteamID64> <jar>
 *       writes <jar>.zbs (lines ZBS / SteamID64:<id> / Signature:<hex>, Ed25519 over "ZBS:<id>:<sha256 hex of the jar>")
 *       and checks it with ZombieBuddy's own ZBSVerifier against the key (offline: no Steam profile fetch).
 * The format is ZombieBuddy 2.3.4's (me.zed_0xff.zombie_buddy.ZBSVerifier bytecode).
 */
public class ZbsSign {
    public static void main(String[] a) throws Exception {
        switch (a.length > 0 ? a[0] : "") {
            case "keygen" -> keygen(Path.of(a[1]));
            case "pubkey" -> System.out.println("JavaModZBS:" + pubHex(load(Path.of(a[1]))));
            case "sign" -> sign(Path.of(a[1]), a[2], Path.of(a[3]));
            default -> { System.err.println("usage: keygen <key> | pubkey <key> | sign <key> <SteamID64> <jar>"); System.exit(2); }
        }
    }

    static void keygen(Path key) throws Exception {
        if (Files.exists(key)) throw new IllegalStateException(key + " exists; not overwriting");
        Files.createDirectories(key.getParent(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Files.createFile(key, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.write(key, kp.getPrivate().getEncoded());
        System.out.println("JavaModZBS:" + rawHex(kp.getPublic()));
    }

    static PrivateKey load(Path key) throws Exception {
        return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(key)));
    }

    /** The public key from the private one: Ed25519 PKCS#8 carries only the seed, so sign a fixed message and derive. */
    static String pubHex(PrivateKey k) throws Exception {
        var kf = KeyFactory.getInstance("Ed25519");
        var spec = kf.getKeySpec(k, java.security.spec.EdECPrivateKeySpec.class);
        var gen = KeyPairGenerator.getInstance("Ed25519");
        gen.initialize(java.security.spec.NamedParameterSpec.ED25519, new FixedSeed(spec.getBytes()));
        return rawHex(gen.generateKeyPair().getPublic());
    }

    /** The raw 32-byte key: the X.509 encoding is a fixed 12-byte header plus the key. */
    static String rawHex(PublicKey p) {
        byte[] x = p.getEncoded();
        return HexFormat.of().formatHex(x, x.length - 32, x.length);
    }

    static void sign(Path key, String steamId, Path jar) throws Exception {
        if (!steamId.matches("\\d{17}")) throw new IllegalArgumentException("SteamID64 must be 17 digits");
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        PrivateKey k = load(key);
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(k);
        s.update(("ZBS:" + steamId + ":" + hash).getBytes(StandardCharsets.UTF_8));
        Path zbs = jar.resolveSibling(jar.getFileName() + ".zbs");
        Files.writeString(zbs, "ZBS\nSteamID64:" + steamId + "\nSignature:" + HexFormat.of().formatHex(s.sign()) + "\n");
        System.out.println("wrote " + zbs + " (jar sha256 " + hash + ")");
        check(jar, zbs, hash, steamId, pubHex(k));
    }

    /** ZombieBuddy's own verdict on the .zbs, with this key as the author's only known key. */
    static void check(Path jar, Path zbs, String hash, String steamId, String pub) throws Exception {
        Class<?> sidC = Class.forName("me.zed_0xff.zombie_buddy.SteamWorkshop$SteamID64");
        Object sid = sidC.getConstructor(String.class).newInstance(steamId);
        Class<?> aeC = Class.forName("me.zed_0xff.zombie_buddy.KnownAuthors$AuthorEntry");
        Object ae = aeC.getConstructor().newInstance();
        aeC.getField("id").set(ae, sid);
        aeC.getField("name").set(ae, "self-check");
        aeC.getField("keys").set(ae, List.of(pub));
        Method verify = Class.forName("me.zed_0xff.zombie_buddy.ZBSVerifier")
            .getMethod("verify", Path.class, Path.class, String.class, sidC, Map.class);
        Object v = verify.invoke(null, jar, zbs, hash, null, Map.of(sid, ae));
        System.out.println("ZombieBuddy verify: " + v);
        if (!v.getClass().getSimpleName().equals("ValidSignature")) throw new IllegalStateException("signature not valid for ZombieBuddy");
    }

    /** SecureRandom that hands out a fixed seed once (to rebuild the key pair from the stored private key). */
    static final class FixedSeed extends java.security.SecureRandom {
        private final byte[] seed;
        FixedSeed(byte[] seed) { this.seed = seed; }
        @Override public void nextBytes(byte[] b) { System.arraycopy(seed, 0, b, 0, b.length); }
    }
}
