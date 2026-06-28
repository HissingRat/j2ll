package xyz.melodysky.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.melodysky.config.SigningConfig;

class SignatureResignPreflightTest {
    @TempDir
    Path temp;

    @Test
    void rejectsMissingSigningConfig() {
        SignatureResignPreflightResult result = new SignatureResignPreflight(Map.<String, String>of()::get)
                .validate(null);

        assertFalse(result.successful());
        assertEquals("SIGNATURE_RESIGN_MISSING_CONFIG", result.reasonCode());
    }

    @Test
    void rejectsMissingOrUnreadableKeystoreBeforeReadingPasswords() {
        SigningConfig signing = new SigningConfig(
                temp.resolve("missing.p12"),
                "STORE_PASS",
                "alias",
                "KEY_PASS",
                null);

        SignatureResignPreflightResult result = new SignatureResignPreflight(Map.<String, String>of()::get)
                .validate(signing);

        assertFalse(result.successful());
        assertEquals("SIGNATURE_RESIGN_INVALID_KEYSTORE", result.reasonCode());
    }

    @Test
    void validatesLoadableKeystoreAndReportsMissingAlias() throws Exception {
        Path keystore = temp.resolve("empty.p12");
        try (OutputStream output = java.nio.file.Files.newOutputStream(keystore)) {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, "storepass".toCharArray());
            store.store(output, "storepass".toCharArray());
        }
        SigningConfig signing = new SigningConfig(keystore, "STORE_PASS", "missing", "KEY_PASS", null);

        SignatureResignPreflightResult result = new SignatureResignPreflight(Map.of(
                        "STORE_PASS", "storepass",
                        "KEY_PASS", "keypass")::get)
                .validate(signing);

        assertFalse(result.successful());
        assertEquals("SIGNATURE_RESIGN_KEY_NOT_FOUND", result.reasonCode());
        assertTrue(result.reason().contains("missing"));
    }
}
