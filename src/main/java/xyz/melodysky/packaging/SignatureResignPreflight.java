package xyz.melodysky.packaging;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import xyz.melodysky.config.SigningConfig;

public final class SignatureResignPreflight {
    private final Function<String, String> environment;

    public SignatureResignPreflight() {
        this(System::getenv);
    }

    public SignatureResignPreflight(Function<String, String> environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    public SignatureResignPreflightResult validate(SigningConfig signing) {
        if (signing == null) {
            return SignatureResignPreflightResult.failed(
                    "SIGNATURE_RESIGN_MISSING_CONFIG",
                    "signaturePolicy resign requires signing config");
        }
        if (!Files.isRegularFile(signing.keystorePath()) || !Files.isReadable(signing.keystorePath())) {
            return SignatureResignPreflightResult.failed(
                    "SIGNATURE_RESIGN_INVALID_KEYSTORE",
                    "keystore is missing or not readable: " + signing.keystorePath());
        }
        String storePassword = environment.apply(signing.storePasswordEnv());
        if (storePassword == null || storePassword.isEmpty()) {
            return SignatureResignPreflightResult.failed(
                    "SIGNATURE_RESIGN_MISSING_PASSWORD_ENV",
                    "missing store password environment variable: " + signing.storePasswordEnv());
        }
        String keyPassword = environment.apply(signing.keyPasswordEnv());
        if (keyPassword == null || keyPassword.isEmpty()) {
            return SignatureResignPreflightResult.failed(
                    "SIGNATURE_RESIGN_MISSING_PASSWORD_ENV",
                    "missing key password environment variable: " + signing.keyPasswordEnv());
        }

        KeyStore keyStore = loadKeyStore(signing, storePassword.toCharArray());
        if (keyStore == null) {
            return SignatureResignPreflightResult.failed(
                    "SIGNATURE_RESIGN_INVALID_KEYSTORE",
                    "keystore could not be loaded with the configured store password: " + signing.keystorePath());
        }
        try {
            if (!keyStore.containsAlias(signing.keyAlias())) {
                return SignatureResignPreflightResult.failed(
                        "SIGNATURE_RESIGN_KEY_NOT_FOUND",
                        "keystore does not contain key alias: " + signing.keyAlias());
            }
            keyStore.getKey(signing.keyAlias(), keyPassword.toCharArray());
        } catch (Exception exception) {
            return SignatureResignPreflightResult.failed(
                    "SIGNATURE_RESIGN_KEY_PASSWORD_INVALID",
                    "key password could not unlock alias: " + signing.keyAlias());
        }
        return SignatureResignPreflightResult.ok();
    }

    private KeyStore loadKeyStore(SigningConfig signing, char[] storePassword) {
        for (String type : List.of(KeyStore.getDefaultType(), "PKCS12", "JKS")) {
            try (InputStream input = Files.newInputStream(signing.keystorePath())) {
                KeyStore keyStore = KeyStore.getInstance(type);
                keyStore.load(input, storePassword);
                return keyStore;
            } catch (IOException exception) {
                return null;
            } catch (Exception ignored) {
                // Try the next common keystore type.
            }
        }
        return null;
    }
}
