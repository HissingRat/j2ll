package xyz.melodysky.toolchain;

/** Closure gate for the sole registration aggregate and JNI entry root. */
final class NativeRegistrationAggregateSourceVerifier {
    void verify(
            NativeRegistrationControlSourceIndex index,
            NativeRegistrationControlTopologyPlan plan) {
        verifyAggregate(index, plan);
        verifyJniOnLoad(index, plan);
    }

    private void verifyAggregate(
            NativeRegistrationControlSourceIndex index,
            NativeRegistrationControlTopologyPlan plan) {
        if (index.codeCountExactAtIdentifier(
                aggregatePrototype(plan.aggregateSymbol()),
                plan.aggregateSymbol()) != 1) {
            fail("AGGREGATE_NOINLINE_CLOSURE");
        }
        String body = index.functionBody(
                aggregateHeader(plan.aggregateSymbol()));
        if (body == null) {
            fail("AGGREGATE_DEFINITION_CLOSURE");
        }
        if (count(body, getEnv()) != 1
                || index.codeCountExact(getEnv()) != 1
                || count(body, "RegisterNatives(") != 0) {
            fail("AGGREGATE_JNI_BOUNDARY");
        }
        if (plan.owners().isEmpty()) {
            verifyZeroOwner(index, body);
            return;
        }
        verifyNonEmptyGlobalClosure(index, plan);
        verifyNonEmptyBody(body, plan);
        verifyNoBypass(body, plan);
    }

    private void verifyZeroOwner(
            NativeRegistrationControlSourceIndex index,
            String body) {
        if (!body.equals("\n    JNIEnv* env = NULL;\n"
                    + "    if (" + getEnv() + " != JNI_OK) {\n"
                    + "        return JNI_ERR;\n"
                    + "    }\n"
                    + "    return JNI_VERSION_1_8;\n")
                || index.codeCountExact("jclass registered_owners[") != 0
                || index.codeCountExact("size_t registered_count = 0u;") != 0
                || index.codeCountExact("rollback:") != 0
                || index.codeCountExact("(*env)->RegisterNatives(") != 0
                || index.codeCountExact("(*env)->UnregisterNatives(") != 0) {
            fail("ZERO_OWNER_CLOSURE");
        }
    }

    private void verifyNonEmptyGlobalClosure(
            NativeRegistrationControlSourceIndex index,
            NativeRegistrationControlTopologyPlan plan) {
        if (index.codeCountExact("jclass registered_owners[") != 1
                || index.codeCountExact("size_t registered_count = 0u;") != 1
                || index.codeCountExact("j2ll_registration_resolver resolver = {NULL, NULL, NULL, NULL};") != 1
                || index.codeCountExact("rollback:") != 1
                || index.codeCountExact("(*env)->RegisterNatives(")
                        != plan.owners().size()
                || index.codeCountExact("(*env)->UnregisterNatives(")
                        != plan.owners().size() + 1) {
            fail("AGGREGATE_UNIQUE_STATE_CLOSURE");
        }
    }

    private void verifyNonEmptyBody(
            String body,
            NativeRegistrationControlTopologyPlan plan) {
        String tailStart = "    jthrowable failure_exception = NULL;\n";
        int tailOffset = NativeRegistrationControlSourceIndex.exactIndexOf(
                body,
                tailStart,
                0);
        String prefix = tailOffset < 0
                ? ""
                : body.substring(0, tailOffset);
        NativeRegistrationControlSourceIndex prefixIndex =
                new NativeRegistrationControlSourceIndex(prefix);
        if (!body.startsWith("\n    JNIEnv* env = NULL;\n"
                        + "    if (" + getEnv() + " != JNI_OK) {\n"
                        + "        return JNI_ERR;\n"
                        + "    }\n")
                || tailOffset < 0
                || !body.substring(tailOffset).equals(
                        new NativeRegistrationAggregateTailSchema()
                                .expected(plan))
                || count(body, "j2ll_registration_resolver resolver = {NULL, NULL, NULL, NULL};") != 1
                || count(body, "jint resolver_status = j2ll_registration_resolver_open(") != 1
                || count(body, "char loader_anchor_text[sizeof(") != 1
                || count(body, "(*env)->EnsureLocalCapacity(env,") != 1
                || count(body, "j2ll_native_text_zero(loader_anchor_text, sizeof(loader_anchor_text));") != 1
                || count(body, "if (resolver_status != JNI_OK)") != 1
                || count(body, "j2ll_registration_resolver_close(env, &resolver);") != 4
                || count(prefix, "return JNI_ERR;") != 3
                || prefixIndex.identifierCount("return") != 3
                || prefixIndex.identifierCount("goto") != 0
                || count(body, "return JNI_VERSION_1_8;") != 1) {
            fail("AGGREGATE_ACTIVATION_CLOSURE");
        }
    }

    private void verifyNoBypass(
            String body,
            NativeRegistrationControlTopologyPlan plan) {
        NativeRegistrationControlSourceIndex bodyIndex =
                new NativeRegistrationControlSourceIndex(body);
        for (NativeRegistrationControlTopologyPlan.Owner owner
                : plan.owners()) {
            if (bodyIndex.identifierCount(owner.symbol()) != 0) {
                fail("AGGREGATE_BYPASSES_CHUNK_CHAIN");
            }
        }
        for (int ordinal = 1; ordinal < plan.chunks().size(); ordinal++) {
            if (bodyIndex.identifierCount(
                    plan.chunks().get(ordinal).symbol()) != 0) {
                fail("AGGREGATE_BYPASSES_CHUNK_CHAIN");
            }
        }
    }

    private void verifyJniOnLoad(
            NativeRegistrationControlSourceIndex index,
            NativeRegistrationControlTopologyPlan plan) {
        String body = index.functionBody(
                "JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {");
        String expected = "\n    (void)reserved;\n"
                + "    return "
                + plan.aggregateSymbol()
                + "(vm);\n";
        if (body == null || !body.equals(expected)) {
            fail("JNI_ONLOAD_CLOSURE");
        }
    }

    private String getEnv() {
        return "(*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_8)";
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

    private String aggregatePrototype(String symbol) {
        return aggregateHeader(symbol).replace(
                " {",
                " __attribute__((noinline));");
    }

    private String aggregateHeader(String symbol) {
        return "static jint " + symbol + "(JavaVM* vm) {";
    }

    private void fail(String code) {
        throw new IllegalStateException(
                "native registration control topology audit failed: "
                        + code);
    }
}
