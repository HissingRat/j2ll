package xyz.melodysky.toolchain.nativetext;

import java.util.List;
import java.util.Optional;

/** Detects stable registration diagnostics that make useful cross-build xref anchors. */
final class GeneratedNativeRegistrationAnchorAudit {
    static final String STABLE_REGISTRATION_DIAGNOSTIC =
            "STABLE_REGISTRATION_DIAGNOSTIC";

    private static final List<String> FORBIDDEN_PLAINTEXT = List.of(
            "native registration rollback failed",
            "native registration exception restore failed",
            "native owner registration rollback failed",
            "native owner registration exception restore failed");

    Optional<GeneratedNativeHardeningFinding> inspect(String source) {
        int forbiddenPlaintext = FORBIDDEN_PLAINTEXT.stream()
                .mapToInt(source::indexOf)
                .filter(offset -> offset >= 0)
                .min()
                .orElse(-1);
        int literalFatalError = new CSourceCallLiteralScanner()
                .firstLiteralSecondArgument(source, "FatalError")
                .orElse(-1);
        int first = firstOffset(forbiddenPlaintext, literalFatalError);
        if (first < 0) {
            return Optional.empty();
        }
        return Optional.of(new GeneratedNativeHardeningFinding(
                STABLE_REGISTRATION_DIAGNOSTIC,
                lineOf(source, first),
                literalFatalError >= 0 && literalFatalError == first
                        ? "registration FatalError uses a direct C string literal"
                        : "registration failure diagnostic is stable plaintext"));
    }

    private int firstOffset(int first, int second) {
        if (first < 0) {
            return second;
        }
        if (second < 0) {
            return first;
        }
        return Math.min(first, second);
    }

    private int lineOf(String source, int offset) {
        int line = 1;
        for (int index = 0; index < offset; index++) {
            if (source.charAt(index) == '\n') {
                line++;
            }
        }
        return line;
    }
}
