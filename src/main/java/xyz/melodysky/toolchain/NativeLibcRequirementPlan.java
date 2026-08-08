package xyz.melodysky.toolchain;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Link-time libc requirement derived from the final generated C surface.
 *
 * <p>The empty plan is intentionally strict: libc may be omitted only when no
 * generated source contains a call to a C library routine in the closed set
 * below. False positives are safe because they retain libc; a new routine must
 * be added here before its source emitter can participate in libc-free builds.</p>
 */
public record NativeLibcRequirementPlan(boolean required, Set<Reason> reasons) {
    private static final Pattern LIBC_CALL = Pattern.compile(
            "(?<![A-Za-z0-9_])"
                    + "(malloc|calloc|realloc|free|memcpy|memmove|memset|"
                    + "strlen|strcmp|strncmp|strcpy|strncpy|strcat|strncat)"
                    + "\\s*\\(");

    public NativeLibcRequirementPlan {
        reasons = Set.copyOf(Objects.requireNonNull(reasons, "reasons"));
        if (required != !reasons.isEmpty()) {
            throw new IllegalArgumentException(
                    "libc requirement and reason set must agree");
        }
    }

    static NativeLibcRequirementPlan inspect(String generatedC) {
        Objects.requireNonNull(generatedC, "generatedC");
        EnumSet<Reason> reasons = EnumSet.noneOf(Reason.class);
        Matcher matcher = LIBC_CALL.matcher(generatedC);
        while (matcher.find()) {
            reasons.add(reasonFor(matcher.group(1)));
        }
        return new NativeLibcRequirementPlan(!reasons.isEmpty(), reasons);
    }

    static NativeLibcRequirementPlan inspectAll(List<String> generatedSources) {
        Objects.requireNonNull(generatedSources, "generatedSources");
        EnumSet<Reason> reasons = EnumSet.noneOf(Reason.class);
        for (String source : generatedSources) {
            reasons.addAll(inspect(Objects.requireNonNull(source, "generated source")).reasons());
        }
        return new NativeLibcRequirementPlan(!reasons.isEmpty(), reasons);
    }

    static NativeLibcRequirementPlan retaining() {
        return new NativeLibcRequirementPlan(
                true,
                EnumSet.of(Reason.CONSERVATIVE_COMPATIBILITY));
    }

    private static Reason reasonFor(String function) {
        return switch (function) {
            case "malloc", "calloc", "realloc", "free" ->
                    Reason.DYNAMIC_ALLOCATION;
            case "memcpy", "memmove", "memset" ->
                    Reason.MEMORY_ROUTINE;
            default -> Reason.STRING_ROUTINE;
        };
    }

    public enum Reason {
        DYNAMIC_ALLOCATION,
        MEMORY_ROUTINE,
        STRING_ROUTINE,
        CONSERVATIVE_COMPATIBILITY
    }
}
