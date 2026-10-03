import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.Provider;
import java.security.Security;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * CI-only observation of the SDK apksigner provider bootstrap. Not a production API and not a test of
 * signing: it never calls sign, verify or main, and touches no APK, key or certificate bytes.
 *
 * The jar's bytes are hashed with the JDK first. Only if they equal the reviewed public build-tools 37
 * apksigner.jar does it load those same bytes (from a private temporary copy) through an isolated class
 * loader and call ApkSignerTool's private static addProviders, a pinned inspection hook. That may load
 * Conscrypt native loading on the runner. One structured line is printed; no exception messages,
 * stack traces or environment values.
 *
 * Usage: java tools/ApkSignerProviderProbe.java <path to apksigner.jar> <full commit SHA>
 */
public class ApkSignerProviderProbe {
    private static final String PREFIX = "MUON_SDK_PROVIDER_PROBE ";
    /** The dated HTTPS-public build-tools 37 archive's apksigner.jar; a scoped allowlist, not a signature. */
    private static final String REVIEWED_SHA256 = "2defad215d7ff52968a409cde528cdaef7918b115e276b8e3378ca7a178e4180";
    private static final long MAX_BYTES = 16L * 1024 * 1024;

    public static void main(String[] args) {
        if (args.length != 2 || !args[1].matches("[0-9a-f]{40}")) {
            emit(null, null, "failed", "arguments", null);
            System.exit(2);
        }
        String commit = args[1];
        byte[] bytes;
        String sha256;
        try {
            Path jar = Path.of(args[0]);
            // The hash, not the path, decides; these checks only bound the read.
            if (!Files.isRegularFile(jar) || Files.size(jar) > MAX_BYTES) {
                emit(commit, null, "failed", "read", null);
                System.exit(1);
            }
            try (var input = Files.newInputStream(jar)) {
                bytes = input.readNBytes((int) MAX_BYTES + 1);
            }
            if (bytes.length > MAX_BYTES) throw new IOException("size");
            sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception failure) {
            emit(commit, null, "failed", "read", null);
            System.exit(1);
            return;
        }
        if (!REVIEWED_SHA256.equals(sha256)) {
            // Not the reviewed bytes: nothing from the jar is loaded or run.
            emit(commit, sha256, "skipped_unreviewed_hash", null, null);
            System.exit(3);
        }

        // The exit waits for the finally block, so the temporary copy is removed on failure too.
        int exit = 0;
        String observation = null;
        String stage = "copy";
        Path copy = null;
        try {
            copy = Files.createTempFile("apksigner-reviewed", ".jar",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.write(copy, bytes); // Exactly the hashed bytes.
            List<String> before = providerNames();
            stage = "load";
            try (URLClassLoader loader = new URLClassLoader(new URL[] {copy.toUri().toURL()},
                    ClassLoader.getPlatformClassLoader())) {
                Class<?> tool = Class.forName("com.android.apksigner.ApkSignerTool", false, loader);
                stage = "reflect";
                Method addProviders = tool.getDeclaredMethod("addProviders");
                addProviders.setAccessible(true);
                stage = "invoke";
                addProviders.invoke(null);
                stage = "observe";
                observation = observations(before);
            }
        } catch (Throwable failure) {
            emit(commit, sha256, "failed", stage, null);
            exit = 1;
        } finally {
            if (copy != null) {
                try { Files.deleteIfExists(copy); } catch (IOException ignored) { }
            }
        }
        if (exit == 0) emit(commit, sha256, "observed", null, observation);
        // Conscrypt may leave non-daemon resources; exit explicitly either way.
        System.exit(exit);
    }

    private static String observations(List<String> before) throws Exception {
        List<String> signatureCandidates = new ArrayList<>();
        Provider[] supporting = Security.getProviders("Signature.SHA256withRSA");
        if (supporting != null) for (Provider p : supporting) signatureCandidates.add(p.getName());
        return "\"providers_before\":" + names(before)
            + ",\"providers_after\":" + names(providerNames())
            + ",\"sha256_message_digest\":" + quote(MessageDigest.getInstance("SHA-256").getProvider().getName())
            // An uninitialized candidate only: not signing, key initialization or verifier algorithm choice.
            + ",\"sha256withrsa_uninitialized_candidate\":" + quote(Signature.getInstance("SHA256withRSA").getProvider().getName())
            + ",\"sha256withrsa_supporting_providers\":" + names(signatureCandidates)
            + ",\"x509_certificate_factory\":" + quote(CertificateFactory.getInstance("X.509").getProvider().getName());
    }

    private static List<String> providerNames() {
        List<String> names = new ArrayList<>();
        for (Provider p : Security.getProviders()) names.add(p.getName());
        return names;
    }

    private static void emit(String commit, String sha256, String status, String stage, String observations) {
        StringBuilder line = new StringBuilder(PREFIX).append('{');
        line.append("\"commit\":").append(quote(commit));
        line.append(",\"jar_sha256\":").append(quote(sha256));
        line.append(",\"reviewed_sha256\":").append(quote(REVIEWED_SHA256));
        line.append(",\"status\":").append(quote(status));
        line.append(",\"failure_stage\":").append(quote(stage));
        if (observations != null) line.append(',').append(observations);
        System.out.println(line.append('}'));
    }

    private static String names(List<String> names) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < names.size() && i < 32; i++) out.append(i == 0 ? "" : ",").append(quote(names.get(i)));
        return out.append(']').toString();
    }

    /** Bounded, printable-ASCII JSON string; anything else is replaced. */
    private static String quote(String value) {
        if (value == null) return "null";
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length() && i < 64; i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c >= 0x20 && c < 0x7f) out.append(c);
            else out.append('?');
        }
        return out.append('"').toString();
    }
}
