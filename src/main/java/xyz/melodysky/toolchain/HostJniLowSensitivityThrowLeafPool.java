package xyz.melodysky.toolchain;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import xyz.melodysky.runtime.RuntimeTokenDomain;
import xyz.melodysky.runtime.RuntimeTokenMapper;

/**
 * Outlines explicitly allowlisted, metadata-free exception construction into
 * cold build-scoped leaves.
 *
 * <p>The pool never accepts an owner, member name, descriptor, reflection
 * target or business string. Calls outside the closed allowlist remain in
 * their original function-local native-text lifetime.</p>
 */
final class HostJniLowSensitivityThrowLeafPool {
    private static final Pattern THROW_NEW = Pattern.compile(
            "j2ll_throw_new\\s*\\(\\s*env\\s*,\\s*\"([^\"\\\\]*)\"\\s*,\\s*\"([^\"\\\\]*)\"\\s*\\)\\s*;");

    private final RuntimeTokenMapper runtimeTokens;
    private final EnumSet<ThrowLeaf> used = EnumSet.noneOf(ThrowLeaf.class);

    HostJniLowSensitivityThrowLeafPool(
            RuntimeTokenMapper runtimeTokens) {
        this.runtimeTokens = java.util.Objects.requireNonNull(
                runtimeTokens,
                "runtimeTokens");
    }

    String rewrite(String fragment) {
        Matcher matcher = THROW_NEW.matcher(fragment);
        StringBuffer rewritten = new StringBuffer(fragment.length());
        LinkedHashSet<ThrowLeaf> fragmentLeaves = new LinkedHashSet<>();
        while (matcher.find()) {
            ThrowLeaf leaf = ThrowLeaf.find(
                    matcher.group(1),
                    matcher.group(2));
            if (leaf == null) {
                matcher.appendReplacement(
                        rewritten,
                        Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            used.add(leaf);
            fragmentLeaves.add(leaf);
            matcher.appendReplacement(
                    rewritten,
                    Matcher.quoteReplacement(symbol(leaf) + "(env);"));
        }
        matcher.appendTail(rewritten);
        if (fragmentLeaves.isEmpty()) {
            return fragment;
        }
        StringBuilder declarations = new StringBuilder();
        for (ThrowLeaf leaf : fragmentLeaves) {
            declarations.append("static void ")
                    .append(symbol(leaf))
                    .append("(JNIEnv* env);\n");
        }
        return declarations.append('\n').append(rewritten).toString();
    }

    boolean isEmpty() {
        return used.isEmpty();
    }

    String definitions() {
        if (used.isEmpty()) {
            return "";
        }
        StringBuilder source = new StringBuilder();
        List<ThrowLeaf> leaves = runtimeTokens.physicalOrder(
                RuntimeTokenDomain.LOW_SENSITIVITY_RUNTIME,
                List.copyOf(used),
                ThrowLeaf::identity);
        for (ThrowLeaf leaf : leaves) {
            String symbol = symbol(leaf);
            source.append("static void ")
                    .append(symbol)
                    .append("(JNIEnv* env) __attribute__((noinline, cold));\n")
                    .append("static void ")
                    .append(symbol)
                    .append("(JNIEnv* env) {\n")
                    .append("    j2ll_throw_new(env, \"")
                    .append(CSourceEscaper.stringContents(
                            leaf.exceptionClass()))
                    .append("\", \"")
                    .append(CSourceEscaper.stringContents(leaf.message()))
                    .append("\");\n")
                    .append("}\n\n");
        }
        return source.toString();
    }

    Set<String> usedSymbols() {
        ArrayList<String> symbols = new ArrayList<>();
        for (ThrowLeaf leaf : used) {
            symbols.add(symbol(leaf));
        }
        return Set.copyOf(symbols);
    }

    private String symbol(ThrowLeaf leaf) {
        long token = runtimeTokens.token(
                RuntimeTokenDomain.LOW_SENSITIVITY_RUNTIME,
                leaf.identity());
        return "j2ll_l_" + HexFormat.of().toHexDigits(token);
    }

    private enum ThrowLeaf {
        ARRAY_NULL(
                "java/lang/NullPointerException",
                "array is null"),
        STRING_RECEIVER_NULL(
                "java/lang/NullPointerException",
                "string receiver is null"),
        DIVIDE_BY_ZERO(
                "java/lang/ArithmeticException",
                "/ by zero"),
        REFLECTION_RECEIVER_NULL(
                "java/lang/NullPointerException",
                "reflection receiver is null"),
        VAR_HANDLE_RECEIVER_NULL(
                "java/lang/NullPointerException",
                "VarHandle receiver is null"),
        STRING_BUILDER_RECEIVER_NULL(
                "java/lang/NullPointerException",
                "StringBuilder receiver is null"),
        MONITOR_NULL(
                "java/lang/NullPointerException",
                "monitor is null"),
        THROWABLE_NULL(
                "java/lang/NullPointerException",
                "throwable is null"),
        FIELD_RECEIVER_NULL(
                "java/lang/NullPointerException",
                "field receiver is null"),
        CALL_RECEIVER_NULL(
                "java/lang/NullPointerException",
                "call receiver is null"),
        BYTE_ARRAY_BOUNDS(
                "java/lang/ArrayIndexOutOfBoundsException",
                "byte array index out of bounds"),
        SHORT_ARRAY_BOUNDS(
                "java/lang/ArrayIndexOutOfBoundsException",
                "short array index out of bounds"),
        CHAR_ARRAY_BOUNDS(
                "java/lang/ArrayIndexOutOfBoundsException",
                "char array index out of bounds"),
        INT_ARRAY_BOUNDS(
                "java/lang/ArrayIndexOutOfBoundsException",
                "int array index out of bounds"),
        LONG_ARRAY_BOUNDS(
                "java/lang/ArrayIndexOutOfBoundsException",
                "long array index out of bounds"),
        FLOAT_ARRAY_BOUNDS(
                "java/lang/ArrayIndexOutOfBoundsException",
                "float array index out of bounds"),
        DOUBLE_ARRAY_BOUNDS(
                "java/lang/ArrayIndexOutOfBoundsException",
                "double array index out of bounds"),
        OBJECT_ARRAY_BOUNDS(
                "java/lang/ArrayIndexOutOfBoundsException",
                "object array index out of bounds"),
        NEGATIVE_OBJECT_ARRAY_LENGTH(
                "java/lang/NegativeArraySizeException",
                "negative object array length"),
        CHECKCAST_FAILED(
                "java/lang/ClassCastException",
                "j2ll checkcast failed"),
        SUBSTRING_RECEIVER_NULL(
                "java/lang/NullPointerException",
                "substring receiver is null"),
        STRING_NULL(
                "java/lang/NullPointerException",
                "string is null"),
        CONSTRUCTOR_RECEIVER_NULL(
                "java/lang/NullPointerException",
                "constructor receiver is null"),
        TEMPORARY_ARRAY_ALLOCATION_FAILED(
                "java/lang/OutOfMemoryError",
                "native temporary array allocation failed"),
        NEGATIVE_ARGUMENT(
                "java/lang/IllegalArgumentException",
                "negative");

        private final String exceptionClass;
        private final String message;

        ThrowLeaf(
                String exceptionClass,
                String message) {
            this.exceptionClass = exceptionClass;
            this.message = message;
        }

        static ThrowLeaf find(
                String exceptionClass,
                String message) {
            for (ThrowLeaf leaf : values()) {
                if (leaf.exceptionClass.equals(exceptionClass)
                        && leaf.message.equals(message)) {
                    return leaf;
                }
            }
            return null;
        }

        String exceptionClass() {
            return exceptionClass;
        }

        String message() {
            return message;
        }

        String identity() {
            return exceptionClass + '\0' + message;
        }
    }
}
