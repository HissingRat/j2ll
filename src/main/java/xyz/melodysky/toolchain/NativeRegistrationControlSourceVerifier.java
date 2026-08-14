package xyz.melodysky.toolchain;

import java.util.List;
import java.util.Objects;

/** Final translation-unit closure gate for registration control topology. */
final class NativeRegistrationControlSourceVerifier {
    void verify(
            String source,
            NativeRegistrationControlTopologyPlan plan) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(plan, "plan");
        NativeRegistrationControlSourceIndex index =
                new NativeRegistrationControlSourceIndex(source);
        new NativeRegistrationControlPreprocessorVerifier().verify(
                index,
                plan);
        verifySymbolClosure(index, plan);
        verifyFailureLeaves(index, plan);
        verifyOwners(index, plan);
        verifyChunks(index, plan);
        new NativeRegistrationAggregateSourceVerifier().verify(
                index,
                plan);
    }

    private void verifyFailureLeaves(
            NativeRegistrationControlSourceIndex index,
            NativeRegistrationControlTopologyPlan plan) {
        for (String symbol : plan.failureSymbols().symbols()) {
            String prototype = "static void "
                    + symbol
                    + "(JNIEnv* env) __attribute__((noinline, cold));";
            String body = index.functionBody(
                    "static void " + symbol + "(JNIEnv* env) {");
            String suffix = "    (*env)->FatalError(env, message);\n"
                    + "    j2ll_native_text_zero(message, sizeof(message));\n";
            if (index.codeCountExactAtIdentifier(
                            prototype,
                            symbol) != 1
                    || body == null
                    || !body.startsWith(
                            "\n    char message[sizeof(")
                    || !body.endsWith(suffix)
                    || count(body, "char message[sizeof(") != 1
                    || count(body, "(*env)->FatalError(env, message);") != 1
                    || count(body,
                            "j2ll_native_text_zero(message, sizeof(message));")
                            != 1
                    || count(body, "(*env)->") != 1
                    || containsAny(
                            body,
                            List.of(
                                    "RegisterNatives",
                                    "UnregisterNatives",
                                    "ExceptionCheck",
                                    "ExceptionOccurred",
                                    "ExceptionClear",
                                    "j2ll_registration_resolver",
                                    "registered_owners",
                                    "registered_count",
                                    "JNINativeMethod",
                                    "rollback:",
                                    "dispatcher"))) {
                fail("FAILURE_LEAF_CLOSURE");
            }
        }
    }

    private void verifySymbolClosure(
            NativeRegistrationControlSourceIndex index,
            NativeRegistrationControlTopologyPlan plan) {
        requireCount(index, plan.aggregateSymbol(), 3);
        for (NativeRegistrationControlTopologyPlan.Owner owner
                : plan.owners()) {
            requireCount(index, owner.symbol(), 3);
        }
        for (NativeRegistrationControlTopologyPlan.Chunk chunk
                : plan.chunks()) {
            requireCount(index, chunk.symbol(), 3);
        }
        int ownerFailureCalls = plan.owners().size();
        int aggregateFailureCalls = plan.owners().isEmpty() ? 0 : 1;
        requireCount(
                index,
                plan.failureSymbols().ownerRollback(),
                2 + ownerFailureCalls);
        requireCount(
                index,
                plan.failureSymbols().ownerExceptionRestore(),
                2 + ownerFailureCalls);
        requireCount(
                index,
                plan.failureSymbols().aggregateRollback(),
                2 + aggregateFailureCalls);
        requireCount(
                index,
                plan.failureSymbols().aggregateExceptionRestore(),
                2 + aggregateFailureCalls);
    }

    private void verifyOwners(
            NativeRegistrationControlSourceIndex index,
            NativeRegistrationControlTopologyPlan plan) {
        for (NativeRegistrationControlTopologyPlan.Owner owner
                : plan.owners()) {
            String prototype = ownerPrototype(owner.symbol());
            if (index.codeCountExactAtIdentifier(
                    prototype,
                    owner.symbol()) != 1) {
                fail("OWNER_NOINLINE_CLOSURE");
            }
            String body = index.functionBody(ownerHeader(owner.symbol()));
            NativeRegistrationControlSourceIndex bodyIndex = body == null
                    ? null
                    : new NativeRegistrationControlSourceIndex(body);
            if (body == null
                    || count(body, "RegisterNatives(env, owner_class, methods, count)") != 1
                    || count(body, "UnregisterNatives(env, owner_class)") != 1
                    || bodyIndex.identifierCount(
                            plan.failureSymbols().ownerRollback()) != 1
                    || count(
                            body,
                            plan.failureSymbols().ownerRollback()
                                    + "(env);") != 1
                    || bodyIndex.identifierCount(
                            plan.failureSymbols().ownerExceptionRestore()) != 1
                    || count(
                            body,
                            plan.failureSymbols().ownerExceptionRestore()
                                    + "(env);") != 1) {
                fail("OWNER_HELPER_CLOSURE");
            }
        }
    }

    private void verifyChunks(
            NativeRegistrationControlSourceIndex index,
            NativeRegistrationControlTopologyPlan plan) {
        for (int ordinal = 0; ordinal < plan.chunks().size(); ordinal++) {
            NativeRegistrationControlTopologyPlan.Chunk chunk =
                    plan.chunks().get(ordinal);
            if (index.codeCountExactAtIdentifier(
                    chunkPrototype(chunk.symbol()),
                    chunk.symbol()) != 1) {
                fail("CHUNK_NOINLINE_CLOSURE");
            }
            String body = index.functionBody(chunkHeader(chunk.symbol()));
            String next = ordinal + 1 < plan.chunks().size()
                    ? plan.chunks().get(ordinal + 1).symbol()
                    : null;
            if (body == null
                    || !body.equals(expectedChunkBody(chunk, next))) {
                fail("CHUNK_CLOSED_SCHEMA");
            }
        }
    }

    private String expectedChunkBody(
            NativeRegistrationControlTopologyPlan.Chunk chunk,
            String nextSymbol) {
        StringBuilder expected = new StringBuilder("\n");
        for (NativeRegistrationControlTopologyPlan.Owner owner
                : chunk.owners()) {
            expected.append("    if (")
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
            expected.append("    return JNI_OK;\n");
        } else {
            expected.append("    return ")
                    .append(nextSymbol)
                    .append("(env, resolver, registered_owners, registered_count);\n");
        }
        return expected.toString();
    }

    private void requireCount(
            NativeRegistrationControlSourceIndex index,
            String symbol,
            int expected) {
        if (index.identifierCount(symbol) != expected) {
            fail("SYMBOL_REFERENCE_CLOSURE");
        }
    }

    private int count(String source, String needle) {
        int result = 0;
        int offset = 0;
        while ((offset = NativeRegistrationControlSourceIndex.exactIndexOf(
                source,
                needle,
                offset)) >= 0) {
            result++;
            offset += needle.length();
        }
        return result;
    }

    private boolean containsAny(
            String source,
            List<String> forbidden) {
        for (String value : forbidden) {
            if (NativeRegistrationControlSourceIndex.exactIndexOf(
                    source,
                    value,
                    0) >= 0) {
                return true;
            }
        }
        return false;
    }

    private String ownerPrototype(String symbol) {
        return ownerHeader(symbol).replace(" {", " __attribute__((noinline));");
    }

    private String ownerHeader(String symbol) {
        return "static jint " + symbol
                + "(JNIEnv* env, const j2ll_registration_resolver* resolver, jclass* registered_owner) {";
    }

    private String chunkPrototype(String symbol) {
        return chunkHeader(symbol).replace(" {", " __attribute__((noinline));");
    }

    private String chunkHeader(String symbol) {
        return "static jint " + symbol
                + "(JNIEnv* env, const j2ll_registration_resolver* resolver, jclass* registered_owners, size_t* registered_count) {";
    }

    private void fail(String code) {
        throw new IllegalStateException(
                "native registration control topology audit failed: "
                        + code);
    }
}
