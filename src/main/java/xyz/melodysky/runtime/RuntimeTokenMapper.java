package xyz.melodysky.runtime;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Invocation-scoped, domain-separated 64-bit runtime metadata tokens.
 *
 * <p>The mapper owns the collision registry. Every producer and consumer in a
 * build must share the same build key; a same-domain collision fails during
 * planning/source generation instead of selecting an arbitrary metadata row.</p>
 */
public final class RuntimeTokenMapper {
    private static final byte[] TOKEN_DOMAIN =
            "j2ll-runtime-token-v2".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] LAYOUT_DOMAIN =
            "j2ll-runtime-layout-v2".getBytes(StandardCharsets.US_ASCII);
    private final byte[] buildKey;
    private final TokenDeriver tokenDeriver;
    private final Map<RuntimeTokenDomain, Map<Long, String>> identitiesByToken =
            new EnumMap<>(RuntimeTokenDomain.class);

    private RuntimeTokenMapper(byte[] buildKey) {
        this(buildKey, RuntimeTokenMapper::deriveToken);
    }

    RuntimeTokenMapper(byte[] buildKey, TokenDeriver tokenDeriver) {
        this.buildKey = Objects.requireNonNull(buildKey, "buildKey").clone();
        if (this.buildKey.length == 0) {
            throw new IllegalArgumentException(
                    "runtime token build key must not be empty");
        }
        this.tokenDeriver = Objects.requireNonNull(tokenDeriver, "tokenDeriver");
    }

    public static RuntimeTokenMapper fromBytes(byte[] buildKey) {
        return new RuntimeTokenMapper(buildKey);
    }

    public synchronized long token(
            RuntimeTokenDomain domain,
            String identity) {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(identity, "identity");
        long token = tokenDeriver.derive(buildKey, domain, identity);
        if (token == 0L || token == Long.MIN_VALUE) {
            throw new IllegalStateException(
                    "reserved runtime token derived for "
                            + domain
                            + "; choose a different build identity");
        }
        Map<Long, String> domainTokens =
                identitiesByToken.computeIfAbsent(
                        domain,
                        ignored -> new HashMap<>());
        String existing = domainTokens.putIfAbsent(token, identity);
        if (existing != null && !existing.equals(identity)) {
            throw new IllegalStateException(
                    "RUNTIME_TOKEN_COLLISION domain="
                            + domain
                            + " between two distinct metadata identities");
        }
        return token;
    }

    /**
     * Hash-only, build-scoped helper for a single metadata binding.
     */
    public String helperSymbol(
            RuntimeTokenDomain domain,
            String operation,
            String identity) {
        Objects.requireNonNull(operation, "operation");
        if (!operation.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException(
                    "runtime helper operation must be a safe C identifier token");
        }
        long token = token(
                domain,
                "helper:" + operation + "\0" + identity);
        return "j2ll_h_" + HexFormat.of().toHexDigits(token);
    }

    /**
     * Build-specific physical ordering that is independent from token value and
     * plaintext lexicographic order.
     */
    public <T> List<T> physicalOrder(
            RuntimeTokenDomain domain,
            List<T> values,
            java.util.function.Function<T, String> identity) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(identity, "identity");
        return values.stream()
                .sorted(Comparator
                        .comparingLong((T value) -> layoutRank(
                                domain,
                                identity.apply(value)))
                        .thenComparing(identity))
                .toList();
    }

    private long layoutRank(
            RuntimeTokenDomain domain,
            String identity) {
        return derive(
                LAYOUT_DOMAIN,
                buildKey,
                domain.name(),
                identity);
    }

    private static long deriveToken(
            byte[] buildKey,
            RuntimeTokenDomain domain,
            String identity) {
        return derive(TOKEN_DOMAIN, buildKey, domain.name(), identity);
    }

    private static long derive(
            byte[] purpose,
            byte[] buildKey,
            String domain,
            String identity) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateLengthPrefixed(digest, purpose);
            updateLengthPrefixed(digest, buildKey);
            updateLengthPrefixed(
                    digest,
                    domain.getBytes(StandardCharsets.US_ASCII));
            updateLengthPrefixed(
                    digest,
                    identity.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest.digest(), 0, Long.BYTES).getLong();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void updateLengthPrefixed(
            MessageDigest digest,
            byte[] value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(value.length)
                .array());
        digest.update(value);
    }

    @FunctionalInterface
    interface TokenDeriver {
        long derive(
                byte[] buildKey,
                RuntimeTokenDomain domain,
                String identity);
    }
}
