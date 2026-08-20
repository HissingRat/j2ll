package xyz.melodysky.toolchain;

import java.util.Objects;

/** Emits one of the closed, typed volatile post-call continuations. */
final class NativeRegistrationPostCallCSource {
    String callAndReturn(
            NativeRegistrationPostCallRecipe recipe,
            String call,
            long postCallSalt,
            String indent) {
        Objects.requireNonNull(recipe, "recipe");
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(indent, "indent");
        String salt = unsignedLong(postCallSalt);
        return switch (recipe) {
            case XOR_JINT -> indent
                    + "volatile jint result = " + call + ";\n"
                    + indent
                    + "witness ^= (uintptr_t)(uint32_t)result + "
                    + salt + ";\n"
                    + indent + "(void)witness;\n"
                    + indent + "return result;\n";
            case ADD_JLONG -> indent
                    + "volatile jlong result_wide = (jlong)(" + call + ");\n"
                    + indent
                    + "witness += ((uintptr_t)(uint64_t)result_wide ^ "
                    + salt + ");\n"
                    + indent + "witness ^= (witness >> 7u);\n"
                    + indent + "(void)witness;\n"
                    + indent + "return (jint)result_wide;\n";
            case MIRROR_JINT -> indent
                    + "volatile jint result = " + call + ";\n"
                    + indent
                    + "volatile uintptr_t mirror = (uintptr_t)(uint32_t)result ^ "
                    + salt + ";\n"
                    + indent
                    + "witness = (witness + mirror) ^ ("
                    + salt + " >> 1u);\n"
                    + indent + "mirror += witness ^ " + salt + ";\n"
                    + indent + "(void)mirror;\n"
                    + indent + "(void)witness;\n"
                    + indent + "return result;\n";
        };
    }

    static String unsignedLong(long value) {
        return "UINT64_C(" + Long.toUnsignedString(value) + ")";
    }
}
