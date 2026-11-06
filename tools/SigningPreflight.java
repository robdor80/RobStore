import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;

/** Reads existing keys only. stdout is private, captured by the caller; errors are fixed codes. */
class SigningPreflight {
    static void fail(String code) {
        System.err.println(code);
        System.exit(1);
    }

    public static void main(String[] args) {
        try {
            File file = new File(args[0]);
            char[] password = System.getenv("SIGNING_STORE_PASSWORD").toCharArray();
            // Java detects JKS and PKCS12 from the bytes, regardless of the filename.
            KeyStore store;
            try {
                store = KeyStore.getInstance(file, password);
            } catch (java.io.IOException error) {
                fail("STORE_PASSWORD"); return;
            } catch (java.security.KeyStoreException error) {
                fail("STORE_FORMAT"); return;
            }
            String expected = System.getenv("CERTIFICATE_SHA256");
            String alias = System.getenv("SIGNING_ALIAS");
            if (alias == null || alias.isEmpty()) {
                var matches = new ArrayList<String>();
                var aliases = store.aliases();
                while (aliases.hasMoreElements()) {
                    String entry = aliases.nextElement();
                    if (store.isKeyEntry(entry) && store.getCertificate(entry) != null &&
                            fingerprint(store, entry).equalsIgnoreCase(expected)) matches.add(entry);
                }
                if (matches.size() != 1) { fail("CERTIFICATE"); return; }
                alias = matches.get(0);
            }
            if (!store.isKeyEntry(alias)) { fail("ALIAS"); return; }
            if (store.getCertificate(alias) == null || !fingerprint(store, alias).equalsIgnoreCase(expected)) {
                fail("CERTIFICATE"); return;
            }
            String keyPassword = System.getenv("SIGNING_KEY_PASSWORD");
            if (keyPassword == null || keyPassword.isEmpty()) keyPassword = System.getenv("SIGNING_STORE_PASSWORD");
            try {
                if (!(store.getKey(alias, keyPassword.toCharArray()) instanceof PrivateKey)) {
                    fail("ALIAS"); return;
                }
            } catch (java.security.UnrecoverableKeyException error) {
                fail("KEY_PASSWORD"); return;
            }
            System.out.print(Base64.getEncoder().encodeToString(alias.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            // Never print exceptions: providers can include private paths and aliases.
            fail("PREFLIGHT");
        }
    }

    static String fingerprint(KeyStore store, String alias) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(store.getCertificate(alias).getEncoded()));
    }
}
