package xyz.melodysky.toolchain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;

/** Build-scoped, purpose-separated identities for low-sensitivity shards. */
final class HostJniLowSensitivityThrowShardDeriver {
    private static final byte[] DOMAIN =
            "j2ll/low-sensitivity-throw-shards/v1"
                    .getBytes(StandardCharsets.US_ASCII);
    private static final String PLACEHOLDER_PREFIX =
            "j2ll_low_throw_site_";
    private static final String ANCHOR_PREFIX =
            "j2ll_low_throw_declarations_";

    private final byte[] buildKey;

    HostJniLowSensitivityThrowShardDeriver(
            NativeTextBuildKey buildKey) {
        this.buildKey = Objects.requireNonNull(buildKey, "buildKey")
                .bytes();
    }

    String sitePlaceholder(String siteIdentity) {
        return PLACEHOLDER_PREFIX
                + hashOnly("site-placeholder", List.of(siteIdentity));
    }

    String declarationAnchor() {
        return ANCHOR_PREFIX
                + hashOnly("declaration-anchor", List.of("translation-unit"));
    }

    String siteLayoutRank(
            String leafIdentity,
            String siteIdentity) {
        return HexFormat.of().formatHex(derive(
                "site-layout",
                List.of(leafIdentity, siteIdentity)));
    }

    String shardSymbol(
            String leafIdentity,
            int shardOrdinal,
            List<HostJniLowSensitivityThrowShardPlan.Site> sites) {
        if (shardOrdinal < 0) {
            throw new IllegalArgumentException(
                    "low-sensitivity shard ordinal must not be negative");
        }
        java.util.ArrayList<String> material = new java.util.ArrayList<>();
        material.add(leafIdentity);
        material.add(Integer.toString(shardOrdinal));
        for (HostJniLowSensitivityThrowShardPlan.Site site : sites) {
            material.add(site.identity());
        }
        return hashOnly("physical-leaf-symbol", material);
    }

    String shardLayoutRank(String symbol) {
        return HexFormat.of().formatHex(derive(
                "physical-leaf-layout",
                List.of(symbol)));
    }

    static String placeholderPrefix() {
        return PLACEHOLDER_PREFIX;
    }

    static String anchorPrefix() {
        return ANCHOR_PREFIX;
    }

    private String hashOnly(
            String purpose,
            List<String> components) {
        byte[] digest = derive(purpose, components);
        StringBuilder identifier = new StringBuilder(32);
        for (int index = 0; index < 16; index++) {
            identifier.append((char) ('a'
                    + ((digest[index] >>> 4) & 0x0f)));
            identifier.append((char) ('a'
                    + (digest[index] & 0x0f)));
        }
        return identifier.toString();
    }

    private byte[] derive(
            String purpose,
            List<String> components) {
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(components, "components");
        MessageDigest digest = sha256();
        update(digest, DOMAIN);
        update(digest, buildKey);
        update(digest, purpose.getBytes(StandardCharsets.US_ASCII));
        for (String component : components) {
            update(
                    digest,
                    Objects.requireNonNull(component, "component")
                            .getBytes(StandardCharsets.UTF_8));
        }
        return digest.digest();
    }

    private void update(
            MessageDigest digest,
            byte[] value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(value.length)
                .array());
        digest.update(value);
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    exception);
        }
    }
}
