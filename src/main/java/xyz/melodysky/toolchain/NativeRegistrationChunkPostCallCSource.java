package xyz.melodysky.toolchain;

import java.util.Objects;

/** Emits the eight closed forward-chunk post-call preservation shapes. */
final class NativeRegistrationChunkPostCallCSource {
    String callAndReturn(
            NativeRegistrationChunkPostCallVariant variant,
            String call,
            long postCallSalt,
            String indent) {
        Objects.requireNonNull(variant, "variant");
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(indent, "indent");
        String salt = NativeRegistrationPostCallCSource.unsignedLong(
                postCallSalt);
        return switch (variant) {
            case JINT_U16_FOLD -> indent
                    + "volatile jint result = " + call + ";\n"
                    + indent + "volatile uint16_t fold = (uint16_t)((uint32_t)result ^ (uint32_t)" + salt + ");\n"
                    + indent + "fold ^= (uint16_t)(witness >> 3u);\n"
                    + indent + "witness += (uintptr_t)fold + (uintptr_t)(uint32_t)result;\n"
                    + indent + "fold = (uint16_t)(fold + (uint16_t)(witness >> 11u));\n"
                    + indent + "(void)fold;\n"
                    + indent + "return result;\n";
            case JLONG_U32_FOLD -> indent
                    + "volatile jlong result_wide = (jlong)(" + call + ");\n"
                    + indent + "volatile uint32_t fold = (uint32_t)(uint64_t)result_wide ^ (uint32_t)(" + salt + " >> 7u);\n"
                    + indent + "witness ^= (uintptr_t)fold + (uintptr_t)(uint64_t)result_wide;\n"
                    + indent + "fold += (uint32_t)(witness >> 13u);\n"
                    + indent + "result_wide += (jlong)(fold & UINT32_C(0));\n"
                    + indent + "return (jint)result_wide;\n";
            case JINT_DUAL_WORD -> indent
                    + "volatile jint result = " + call + ";\n"
                    + indent + "volatile uintptr_t left = witness ^ " + salt + ";\n"
                    + indent + "volatile uintptr_t right = (uintptr_t)(uint32_t)result + (" + salt + " >> 2u);\n"
                    + indent + "left += right ^ (witness << 1u);\n"
                    + indent + "right ^= left + (witness >> 5u);\n"
                    + indent + "witness = (left ^ right) + " + salt + ";\n"
                    + indent + "left ^= witness;\n"
                    + indent + "(void)right;\n"
                    + indent + "return result;\n";
            case JLONG_ORBIT -> indent
                    + "volatile jlong result_wide = (jlong)(" + call + ");\n"
                    + indent + "volatile uint64_t orbit = (uint64_t)result_wide ^ " + salt + ";\n"
                    + indent + "volatile uintptr_t notch = witness + (uintptr_t)(uint32_t)result_wide;\n"
                    + indent + "orbit = (orbit << 9u) | (orbit >> 55u);\n"
                    + indent + "notch ^= (uintptr_t)(orbit >> 17u);\n"
                    + indent + "witness += notch ^ (uintptr_t)orbit;\n"
                    + indent + "orbit ^= (uint64_t)witness;\n"
                    + indent + "notch += (uintptr_t)(uint64_t)result_wide;\n"
                    + indent + "(void)orbit;\n"
                    + indent + "return (jint)result_wide;\n";
            case JINT_MIXED_WIDTH -> indent
                    + "volatile jint result = " + call + ";\n"
                    + indent + "volatile uint8_t byte_lane = (uint8_t)((uint32_t)result ^ (uint32_t)" + salt + ");\n"
                    + indent + "volatile uint32_t word_lane = (uint32_t)witness + (uint32_t)(" + salt + " >> 19u);\n"
                    + indent + "volatile uintptr_t wide_lane = witness ^ (uintptr_t)(uint32_t)result;\n"
                    + indent + "word_lane += (uint32_t)byte_lane ^ (uint32_t)wide_lane;\n"
                    + indent + "wide_lane ^= (uintptr_t)word_lane << 3u;\n"
                    + indent + "byte_lane = (uint8_t)(byte_lane + (uint8_t)(wide_lane >> 9u));\n"
                    + indent + "witness = wide_lane + (uintptr_t)byte_lane + " + salt + ";\n"
                    + indent + "result ^= (jint)(word_lane & UINT32_C(0));\n"
                    + indent + "(void)wide_lane;\n"
                    + indent + "return result;\n";
            case JLONG_MIXED_WIDTH -> indent
                    + "volatile jlong result_wide = (jlong)(" + call + ");\n"
                    + indent + "volatile uint16_t half_lane = (uint16_t)((uint64_t)result_wide ^ " + salt + ");\n"
                    + indent + "volatile uint64_t full_lane = (uint64_t)witness + (uint64_t)(uint32_t)result_wide;\n"
                    + indent + "volatile uintptr_t side_lane = witness ^ (uintptr_t)(" + salt + " >> 5u);\n"
                    + indent + "full_lane ^= ((uint64_t)half_lane << 41u) | (uint64_t)side_lane;\n"
                    + indent + "half_lane = (uint16_t)(half_lane + (uint16_t)(full_lane >> 23u));\n"
                    + indent + "side_lane = (side_lane << 7u) ^ (uintptr_t)(full_lane >> 11u);\n"
                    + indent + "witness ^= side_lane + (uintptr_t)half_lane;\n"
                    + indent + "result_wide += (jlong)(half_lane & (uint16_t)0u);\n"
                    + indent + "(void)full_lane;\n"
                    + indent + "return (jint)result_wide;\n";
            case JINT_SIGNED_BRAID -> indent
                    + "volatile jint result = " + call + ";\n"
                    + indent + "volatile int64_t signed_lane = (int64_t)(uint32_t)result;\n"
                    + indent + "volatile uint64_t unsigned_lane = (uint64_t)(uint32_t)result ^ " + salt + ";\n"
                    + indent + "volatile uintptr_t bridge_lane = witness + (uintptr_t)(unsigned_lane >> 7u);\n"
                    + indent + "signed_lane ^= (int64_t)(unsigned_lane & UINT64_C(2147483647));\n"
                    + indent + "unsigned_lane += (uint64_t)bridge_lane ^ (uint64_t)signed_lane;\n"
                    + indent + "bridge_lane ^= (uintptr_t)(unsigned_lane >> 29u);\n"
                    + indent + "witness = bridge_lane + (uintptr_t)(uint64_t)signed_lane;\n"
                    + indent + "volatile jint preserved = result;\n"
                    + indent + "unsigned_lane ^= (uint64_t)(uint32_t)preserved;\n"
                    + indent + "(void)unsigned_lane;\n"
                    + indent + "return preserved;\n";
            case JLONG_SPLIT_WORD -> indent
                    + "volatile jlong result_wide = (jlong)(" + call + ");\n"
                    + indent + "volatile uint32_t low_lane = (uint32_t)(uint64_t)result_wide;\n"
                    + indent + "volatile uint32_t high_lane = (uint32_t)((uint64_t)result_wide >> 32u) ^ (uint32_t)" + salt + ";\n"
                    + indent + "volatile uintptr_t bridge_lane = witness ^ (uintptr_t)low_lane;\n"
                    + indent + "low_lane ^= (uint32_t)(bridge_lane >> 3u);\n"
                    + indent + "high_lane += low_lane ^ (uint32_t)(" + salt + " >> 31u);\n"
                    + indent + "bridge_lane = (bridge_lane << 5u) + (uintptr_t)high_lane;\n"
                    + indent + "witness += bridge_lane ^ (uintptr_t)low_lane;\n"
                    + indent + "volatile jint narrowed = (jint)result_wide;\n"
                    + indent + "narrowed += (jint)(high_lane & UINT32_C(0));\n"
                    + indent + "bridge_lane ^= (uintptr_t)(uint32_t)narrowed;\n"
                    + indent + "(void)bridge_lane;\n"
                    + indent + "return narrowed;\n";
        };
    }
}
