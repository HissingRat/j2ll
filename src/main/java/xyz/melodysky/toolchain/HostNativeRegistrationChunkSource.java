package xyz.melodysky.toolchain;

import java.util.List;
import java.util.Objects;

/** Emits a bounded direct-call chain over the planned physical owners. */
final class HostNativeRegistrationChunkSource {
    String emit(NativeRegistrationControlTopologyPlan plan) {
        Objects.requireNonNull(plan, "plan");
        StringBuilder source = new StringBuilder();
        for (NativeRegistrationControlTopologyPlan.Chunk chunk
                : plan.chunks()) {
            appendPrototype(source, chunk.symbol());
        }
        if (!plan.chunks().isEmpty()) {
            source.append('\n');
        }
        for (int index = 0; index < plan.chunks().size(); index++) {
            appendDefinition(
                    source,
                    plan.chunks().get(index),
                    index + 1 < plan.chunks().size()
                            ? plan.chunks().get(index + 1).symbol()
                            : null);
        }
        return source.toString();
    }

    private void appendPrototype(
            StringBuilder source,
            String symbol) {
        source.append(NativeRegistrationControlCFunctionPolicy.prototype(
                        declaration(symbol)))
                .append('\n');
    }

    private void appendDefinition(
            StringBuilder source,
            NativeRegistrationControlTopologyPlan.Chunk chunk,
            String nextSymbol) {
        source.append(NativeRegistrationControlCFunctionPolicy
                        .definitionHeader(declaration(chunk.symbol())))
                .append('\n');
        for (NativeRegistrationControlTopologyPlan.Owner owner
                : chunk.owners()) {
            source.append("    if (")
                    .append(owner.symbol())
                    .append("(env, resolver, &registered_owners[")
                    .append(owner.index())
                    .append("]) != JNI_OK) {\n")
                    .append("        return JNI_ERR;\n")
                    .append("    }\n")
                    .append("    *registered_count = ")
                    .append(owner.index() + 1)
                    .append("u;\n");
        }
        if (nextSymbol == null) {
            source.append("    return JNI_OK;\n");
        } else {
            source.append("    volatile uintptr_t witness = (uintptr_t)(void*)registered_owners\n")
                    .append("            ^ (uintptr_t)(void*)registered_count\n")
                    .append("            ^ ")
                    .append(NativeRegistrationPostCallCSource.unsignedLong(
                            chunk.witnessSalt()))
                    .append(";\n")
                    .append(new NativeRegistrationChunkPostCallCSource()
                            .callAndReturn(
                                    chunk.postCallVariant(),
                                    nextSymbol
                                            + "(env, resolver, registered_owners, registered_count)",
                                    chunk.postCallSalt(),
                                    "    "));
        }
        source.append("}\n\n");
    }

    static String declaration(String symbol) {
        return "static jint " + symbol
                + "(JNIEnv* env, const j2ll_registration_resolver* resolver, jclass* registered_owners, size_t* registered_count)";
    }
}
