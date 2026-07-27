package xyz.melodysky.report;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public record SensitivePlaintextFact(
        String plaintext,
        String literalHash,
        String sourceMethod,
        String protectionPass,
        List<String> artifactSurfaces,
        String pathKind,
        String gateMode,
        String sourceSurface,
        String reason,
        String promotionReason) {
    public SensitivePlaintextFact {
        Objects.requireNonNull(plaintext, "plaintext");
        literalHash = literalHash == null || literalHash.isBlank() ? sha256(plaintext) : literalHash;
        Objects.requireNonNull(sourceMethod, "sourceMethod");
        Objects.requireNonNull(protectionPass, "protectionPass");
        artifactSurfaces = artifactSurfaces.stream()
                .filter(Objects::nonNull)
                .sorted()
                .distinct()
                .toList();
        pathKind = defaultText(pathKind, "LLVM_NATIVE_PATH");
        gateMode = defaultText(gateMode, "blocking");
        sourceSurface = defaultText(sourceSurface, firstSurface(artifactSurfaces));
        reason = defaultText(reason, "SENSITIVE_PLAINTEXT_FACT");
        promotionReason = defaultText(promotionReason, defaultPromotionReason(pathKind, gateMode));
    }

    public static SensitivePlaintextFact of(
            String plaintext,
            String sourceMethod,
            String protectionPass,
            List<String> artifactSurfaces) {
        return new SensitivePlaintextFact(
                plaintext,
                sha256(plaintext),
                sourceMethod,
                protectionPass,
                artifactSurfaces,
                "LLVM_NATIVE_PATH",
                "blocking",
                firstSurface(artifactSurfaces),
                "LLVM_NATIVE_PATH_SENSITIVE_LITERAL",
                "llvmNativeSurface");
    }

    public SensitivePlaintextFact withAuditClassification(String pathKind, String gateMode, String reason) {
        return withAuditClassification(pathKind, gateMode, reason, defaultPromotionReason(pathKind, gateMode));
    }

    public SensitivePlaintextFact withAuditClassification(
            String pathKind,
            String gateMode,
            String reason,
            String promotionReason) {
        return new SensitivePlaintextFact(
                plaintext,
                literalHash,
                sourceMethod,
                protectionPass,
                artifactSurfaces,
                pathKind,
                gateMode,
                sourceSurface,
                reason,
                promotionReason);
    }

    public SensitivePlaintextFact withSourceSurface(String sourceSurface) {
        return new SensitivePlaintextFact(
                plaintext,
                literalHash,
                sourceMethod,
                protectionPass,
                artifactSurfaces,
                pathKind,
                gateMode,
                sourceSurface,
                reason,
                promotionReason);
    }

    public String passName() {
        return protectionPass;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String defaultText(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String defaultPromotionReason(String pathKind, String gateMode) {
        if ("LLVM_NATIVE_PATH".equals(pathKind) && "blocking".equals(gateMode)) {
            return "llvmNativeSurface";
        }
        if ("TEMPLATE_JNI_PATH_STABLE_SURFACE".equals(pathKind) && "blocking".equals(gateMode)) {
            return "templateStableSurface";
        }
        if ("blocking".equals(gateMode)) {
            return "stableGeneratedCSurface";
        }
        return "metadataSensitiveObservedOnly";
    }

    private static String firstSurface(List<String> artifactSurfaces) {
        return artifactSurfaces == null || artifactSurfaces.isEmpty()
                ? "UNKNOWN"
                : normalizeSurface(artifactSurfaces.stream().filter(Objects::nonNull).sorted().findFirst().orElse("UNKNOWN"));
    }

    private static String normalizeSurface(String surface) {
        return switch (surface) {
            case "llvm-ir" -> "LL";
            case "generated-c" -> "GENERATED_C";
            case "native-library" -> "SYMBOL";
            case "jar-entry" -> "JAR_ENTRY";
            default -> surface.toUpperCase(java.util.Locale.ROOT).replace('-', '_');
        };
    }
}
