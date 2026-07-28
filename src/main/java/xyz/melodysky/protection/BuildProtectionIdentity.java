package xyz.melodysky.protection;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import xyz.melodysky.config.ProtectionConfig;
import xyz.melodysky.config.ProtectionSeedMode;

/**
 * Domain-separated build protection key derivation.
 *
 * <p>The root and raw configured seed never leave this object. Callers receive
 * only purpose-scoped derived material, so two protection stages cannot
 * accidentally reuse the same token/key stream.</p>
 */
public final class BuildProtectionIdentity {
    private static final byte[] ROOT_LABEL =
            "j2ll/protection/root/v1\0".getBytes(StandardCharsets.UTF_8);
    private static final byte[] KDF_LABEL =
            "j2ll/protection/kdf/v1\0".getBytes(StandardCharsets.UTF_8);
    private final ProtectionSeedMode mode;
    private final byte[] root;

    private BuildProtectionIdentity(ProtectionSeedMode mode, byte[] root) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.root = root.clone();
    }

    public static BuildProtectionIdentity from(ProtectionConfig config) {
        Objects.requireNonNull(config, "config");
        MessageDigest digest = sha256();
        digest.update(ROOT_LABEL);
        digest.update(config.seed().getBytes(StandardCharsets.UTF_8));
        return new BuildProtectionIdentity(config.seedMode(), digest.digest());
    }

    public ProtectionSeedMode mode() {
        return mode;
    }

    public byte[] deriveBytes(
            BuildProtectionDomain domain,
            String context,
            int length) {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(context, "context");
        if (length < 1 || length > 4096) {
            throw new IllegalArgumentException("derived length must be between 1 and 4096 bytes");
        }
        byte[] output = new byte[length];
        int offset = 0;
        int counter = 1;
        while (offset < output.length) {
            Mac mac = hmac();
            mac.update(KDF_LABEL);
            mac.update(domain.wireName().getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            mac.update(context.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            mac.update(ByteBuffer.allocate(Integer.BYTES).putInt(counter++).array());
            byte[] block = mac.doFinal();
            int copied = Math.min(block.length, output.length - offset);
            System.arraycopy(block, 0, output, offset, copied);
            offset += copied;
        }
        return output;
    }

    public long deriveLong(
            BuildProtectionDomain domain,
            String context) {
        return ByteBuffer.wrap(deriveBytes(domain, context, Long.BYTES)).getLong();
    }

    public String deriveHex(
            BuildProtectionDomain domain,
            String context,
            int hexCharacters) {
        if (hexCharacters < 2 || hexCharacters % 2 != 0) {
            throw new IllegalArgumentException("hex length must be a positive even number");
        }
        return HexFormat.of().formatHex(deriveBytes(domain, context, hexCharacters / 2));
    }

    public String identityHash() {
        return deriveHex(
                BuildProtectionDomain.REPORT_IDENTITY,
                mode.wireName(),
                64);
    }

    private Mac hmac() {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(root, "HmacSHA256"));
            return mac;
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable", exception);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    @Override
    public String toString() {
        return "BuildProtectionIdentity[mode=" + mode.wireName() + "]";
    }
}
