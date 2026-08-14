package xyz.melodysky.toolchain;

import java.util.Objects;

/** Emits one plaintext intermediate leaf for immediate native-text rewrite. */
final class HostJniLowSensitivityThrowShardSource {
    String definition(
            HostJniLowSensitivityThrowShardPlan.Shard shard) {
        Objects.requireNonNull(shard, "shard");
        return new StringBuilder()
                .append("static void ")
                .append(shard.symbol())
                .append("(JNIEnv* env) {\n")
                .append("    j2ll_throw_new(env, \"")
                .append(CSourceEscaper.stringContents(
                        shard.exceptionClass()))
                .append("\", \"")
                .append(CSourceEscaper.stringContents(shard.message()))
                .append("\");\n")
                .append("}\n\n")
                .toString();
    }
}
