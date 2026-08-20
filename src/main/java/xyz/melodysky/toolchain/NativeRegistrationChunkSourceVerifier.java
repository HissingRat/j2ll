package xyz.melodysky.toolchain;

/** Exact source gate for the bounded forward chunk chain. */
final class NativeRegistrationChunkSourceVerifier {
    void verify(
            NativeRegistrationControlSourceIndex index,
            NativeRegistrationControlTopologyPlan plan) {
        for (int ordinal = 0; ordinal < plan.chunks().size(); ordinal++) {
            NativeRegistrationControlTopologyPlan.Chunk chunk =
                    plan.chunks().get(ordinal);
            String declaration =
                    HostNativeRegistrationChunkSource.declaration(
                            chunk.symbol());
            if (index.codeCountExactAtIdentifier(
                            NativeRegistrationControlCFunctionPolicy
                                    .prototype(declaration),
                            chunk.symbol()) != 1) {
                fail("CHUNK_FUNCTION_POLICY_CLOSURE");
            }
            String body = index.functionBody(
                    NativeRegistrationControlCFunctionPolicy
                            .definitionHeader(declaration));
            String next = ordinal + 1 < plan.chunks().size()
                    ? plan.chunks().get(ordinal + 1).symbol()
                    : null;
            if (body == null
                    || !body.equals(expectedBody(chunk, next))) {
                fail("CHUNK_CLOSED_SCHEMA");
            }
        }
    }

    private String expectedBody(
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
            expected.append("    volatile uintptr_t witness = (uintptr_t)(void*)registered_owners\n")
                    .append("            ^ (uintptr_t)(void*)registered_count\n")
                    .append("            ^ ")
                    .append(NativeRegistrationPostCallCSource.unsignedLong(
                            chunk.witnessSalt()))
                    .append(";\n")
                    .append(expectedPostCall(
                            chunk.postCallVariant(),
                            nextSymbol
                                    + "(env, resolver, registered_owners, registered_count)",
                            chunk.postCallSalt()));
        }
        return expected.toString();
    }

    private String expectedPostCall(
            NativeRegistrationChunkPostCallVariant variant,
            String call,
            long postCallSalt) {
        String salt = NativeRegistrationPostCallCSource.unsignedLong(
                postCallSalt);
        return switch (variant) {
            case JINT_U16_FOLD -> "    volatile jint result = " + call + ";\n"
                    + "    volatile uint16_t fold = (uint16_t)((uint32_t)result ^ (uint32_t)" + salt + ");\n"
                    + "    fold ^= (uint16_t)(witness >> 3u);\n"
                    + "    witness += (uintptr_t)fold + (uintptr_t)(uint32_t)result;\n"
                    + "    fold = (uint16_t)(fold + (uint16_t)(witness >> 11u));\n"
                    + "    (void)fold;\n"
                    + "    return result;\n";
            case JLONG_U32_FOLD -> "    volatile jlong result_wide = (jlong)(" + call + ");\n"
                    + "    volatile uint32_t fold = (uint32_t)(uint64_t)result_wide ^ (uint32_t)(" + salt + " >> 7u);\n"
                    + "    witness ^= (uintptr_t)fold + (uintptr_t)(uint64_t)result_wide;\n"
                    + "    fold += (uint32_t)(witness >> 13u);\n"
                    + "    result_wide += (jlong)(fold & UINT32_C(0));\n"
                    + "    return (jint)result_wide;\n";
            case JINT_DUAL_WORD -> "    volatile jint result = " + call + ";\n"
                    + "    volatile uintptr_t left = witness ^ " + salt + ";\n"
                    + "    volatile uintptr_t right = (uintptr_t)(uint32_t)result + (" + salt + " >> 2u);\n"
                    + "    left += right ^ (witness << 1u);\n"
                    + "    right ^= left + (witness >> 5u);\n"
                    + "    witness = (left ^ right) + " + salt + ";\n"
                    + "    left ^= witness;\n"
                    + "    (void)right;\n"
                    + "    return result;\n";
            case JLONG_ORBIT -> "    volatile jlong result_wide = (jlong)(" + call + ");\n"
                    + "    volatile uint64_t orbit = (uint64_t)result_wide ^ " + salt + ";\n"
                    + "    volatile uintptr_t notch = witness + (uintptr_t)(uint32_t)result_wide;\n"
                    + "    orbit = (orbit << 9u) | (orbit >> 55u);\n"
                    + "    notch ^= (uintptr_t)(orbit >> 17u);\n"
                    + "    witness += notch ^ (uintptr_t)orbit;\n"
                    + "    orbit ^= (uint64_t)witness;\n"
                    + "    notch += (uintptr_t)(uint64_t)result_wide;\n"
                    + "    (void)orbit;\n"
                    + "    return (jint)result_wide;\n";
            case JINT_MIXED_WIDTH -> "    volatile jint result = " + call + ";\n"
                    + "    volatile uint8_t byte_lane = (uint8_t)((uint32_t)result ^ (uint32_t)" + salt + ");\n"
                    + "    volatile uint32_t word_lane = (uint32_t)witness + (uint32_t)(" + salt + " >> 19u);\n"
                    + "    volatile uintptr_t wide_lane = witness ^ (uintptr_t)(uint32_t)result;\n"
                    + "    word_lane += (uint32_t)byte_lane ^ (uint32_t)wide_lane;\n"
                    + "    wide_lane ^= (uintptr_t)word_lane << 3u;\n"
                    + "    byte_lane = (uint8_t)(byte_lane + (uint8_t)(wide_lane >> 9u));\n"
                    + "    witness = wide_lane + (uintptr_t)byte_lane + " + salt + ";\n"
                    + "    result ^= (jint)(word_lane & UINT32_C(0));\n"
                    + "    (void)wide_lane;\n"
                    + "    return result;\n";
            case JLONG_MIXED_WIDTH -> "    volatile jlong result_wide = (jlong)(" + call + ");\n"
                    + "    volatile uint16_t half_lane = (uint16_t)((uint64_t)result_wide ^ " + salt + ");\n"
                    + "    volatile uint64_t full_lane = (uint64_t)witness + (uint64_t)(uint32_t)result_wide;\n"
                    + "    volatile uintptr_t side_lane = witness ^ (uintptr_t)(" + salt + " >> 5u);\n"
                    + "    full_lane ^= ((uint64_t)half_lane << 41u) | (uint64_t)side_lane;\n"
                    + "    half_lane = (uint16_t)(half_lane + (uint16_t)(full_lane >> 23u));\n"
                    + "    side_lane = (side_lane << 7u) ^ (uintptr_t)(full_lane >> 11u);\n"
                    + "    witness ^= side_lane + (uintptr_t)half_lane;\n"
                    + "    result_wide += (jlong)(half_lane & (uint16_t)0u);\n"
                    + "    (void)full_lane;\n"
                    + "    return (jint)result_wide;\n";
            case JINT_SIGNED_BRAID -> "    volatile jint result = " + call + ";\n"
                    + "    volatile int64_t signed_lane = (int64_t)(uint32_t)result;\n"
                    + "    volatile uint64_t unsigned_lane = (uint64_t)(uint32_t)result ^ " + salt + ";\n"
                    + "    volatile uintptr_t bridge_lane = witness + (uintptr_t)(unsigned_lane >> 7u);\n"
                    + "    signed_lane ^= (int64_t)(unsigned_lane & UINT64_C(2147483647));\n"
                    + "    unsigned_lane += (uint64_t)bridge_lane ^ (uint64_t)signed_lane;\n"
                    + "    bridge_lane ^= (uintptr_t)(unsigned_lane >> 29u);\n"
                    + "    witness = bridge_lane + (uintptr_t)(uint64_t)signed_lane;\n"
                    + "    volatile jint preserved = result;\n"
                    + "    unsigned_lane ^= (uint64_t)(uint32_t)preserved;\n"
                    + "    (void)unsigned_lane;\n"
                    + "    return preserved;\n";
            case JLONG_SPLIT_WORD -> "    volatile jlong result_wide = (jlong)(" + call + ");\n"
                    + "    volatile uint32_t low_lane = (uint32_t)(uint64_t)result_wide;\n"
                    + "    volatile uint32_t high_lane = (uint32_t)((uint64_t)result_wide >> 32u) ^ (uint32_t)" + salt + ";\n"
                    + "    volatile uintptr_t bridge_lane = witness ^ (uintptr_t)low_lane;\n"
                    + "    low_lane ^= (uint32_t)(bridge_lane >> 3u);\n"
                    + "    high_lane += low_lane ^ (uint32_t)(" + salt + " >> 31u);\n"
                    + "    bridge_lane = (bridge_lane << 5u) + (uintptr_t)high_lane;\n"
                    + "    witness += bridge_lane ^ (uintptr_t)low_lane;\n"
                    + "    volatile jint narrowed = (jint)result_wide;\n"
                    + "    narrowed += (jint)(high_lane & UINT32_C(0));\n"
                    + "    bridge_lane ^= (uintptr_t)(uint32_t)narrowed;\n"
                    + "    (void)bridge_lane;\n"
                    + "    return narrowed;\n";
        };
    }

    private void fail(String code) {
        throw new IllegalStateException(
                "native registration control topology audit failed: "
                        + code);
    }
}
