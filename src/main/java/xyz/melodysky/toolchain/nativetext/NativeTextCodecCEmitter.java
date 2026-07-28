package xyz.melodysky.toolchain.nativetext;

import java.util.Locale;

/**
 * Emits one site-bound codec schedule directly into its owning function.
 */
final class NativeTextCodecCEmitter {
    private final NativeTextStoragePermutationCEmitter storageEmitter =
            new NativeTextStoragePermutationCEmitter();

    String decodeInto(
            NativeTextEncoding encoding,
            String cipherExpression,
            String lengthExpression,
            String destinationExpression,
            String indent) {
        NativeTextCodecPlan plan = encoding.codecPlan();
        String token = encoding.symbol().substring("j2ll_nt_".length());
        String inner = indent + "    ";
        String loop = inner + "    ";
        StringBuilder source = new StringBuilder()
                .append(indent)
                .append("{\n")
                .append(constant(inner, "k0", token, plan.key0()))
                .append(constant(inner, "step", token, plan.step()));
        if (plan.family() != NativeTextCodecFamily.FEISTEL_32) {
            source.append(constant(inner, "k1", token, plan.key1()))
                    .append(constant(inner, "k2", token, plan.key2()))
                    .append(constant(
                            inner,
                            "m0",
                            token,
                            plan.multiplier0()))
                    .append(constant(
                            inner,
                            "m1",
                            token,
                            plan.multiplier1()));
        }
        source.append(storageEmitter.cursorDeclaration(
                        encoding,
                        token,
                        inner))
                .append(inner)
                .append("for (size_t j2ll_nt_i_")
                .append(token)
                .append(" = 0u; j2ll_nt_i_")
                .append(token)
                .append(" < ")
                .append(lengthExpression)
                .append("; j2ll_nt_i_")
                .append(token)
                .append("++) {\n")
                .append(loop)
                .append("const size_t j2ll_nt_p_")
                .append(token)
                .append(" = ");
        if (plan.reverseTraversal()) {
            source.append('(')
                    .append(lengthExpression)
                    .append(" - 1u - j2ll_nt_i_")
                    .append(token)
                    .append(");\n");
        } else {
            source.append("j2ll_nt_i_")
                    .append(token)
                    .append(";\n");
        }
        source.append(loop)
                .append("const uint64_t j2ll_nt_n_")
                .append(token)
                .append(" = (uint64_t)(j2ll_nt_p_")
                .append(token)
                .append(" + 1u);\n");
        switch (plan.family()) {
            case WEYL_ARX -> emitWeyl(source, plan, token, loop);
            case DUAL_LANE_ARX -> emitDualLane(source, plan, token, loop);
            case FEISTEL_32 -> emitFeistel(source, plan, token, loop);
            case FOLD_ROTATE -> emitFoldRotate(source, plan, token, loop);
        }
        source.append(loop)
                .append("((unsigned char*)(")
                .append(destinationExpression)
                .append("))[j2ll_nt_p_")
                .append(token)
                .append("] = (unsigned char)(")
                .append("((const volatile unsigned char*)(")
                .append(cipherExpression)
                .append("))[j2ll_nt_s_")
                .append(token)
                .append("]")
                .append(" ^ (unsigned char)(j2ll_nt_word_")
                .append(token)
                .append(" >> ")
                .append(plan.outputShift())
                .append("u));\n");
        source.append(storageEmitter.cursorAdvance(
                encoding,
                token,
                lengthExpression,
                loop));
        source.append(inner)
                .append("}\n")
                .append(indent)
                .append("}\n");
        return source.toString();
    }

    private String constant(
            String indent,
            String name,
            String token,
            long value) {
        return indent
                + "const uint64_t j2ll_nt_"
                + name
                + '_'
                + token
                + " = UINT64_C(0x"
                + hex(value)
                + ");\n";
    }

    private void emitWeyl(
            StringBuilder source,
            NativeTextCodecPlan plan,
            String token,
            String indent) {
        String lane = "j2ll_nt_w_" + token;
        String companion = "j2ll_nt_wc_" + token;
        source.append(indent)
                .append("uint64_t ")
                .append(lane)
                .append(" = j2ll_nt_k0_")
                .append(token)
                .append(" + j2ll_nt_step_")
                .append(token)
                .append(" * j2ll_nt_n_")
                .append(token)
                .append(";\n")
                .append(indent)
                .append("uint64_t ")
                .append(companion)
                .append(" = j2ll_nt_k1_")
                .append(token)
                .append(" ^ (j2ll_nt_k2_")
                .append(token)
                .append(" + j2ll_nt_m0_")
                .append(token)
                .append(" * j2ll_nt_n_")
                .append(token)
                .append(");\n");
        switch (plan.schedule()) {
            case 0 -> source.append(indent)
                    .append(lane)
                    .append(" ^= ")
                    .append(rotl64(companion, plan.rotation0()))
                    .append(";\n")
                    .append(indent)
                    .append(lane)
                    .append(" = (")
                    .append(lane)
                    .append(" ^ (")
                    .append(lane)
                    .append(" >> ")
                    .append(plan.shift0())
                    .append("u)) * j2ll_nt_m1_")
                    .append(token)
                    .append(";\n")
                    .append(indent)
                    .append(lane)
                    .append(" ^= ")
                    .append(lane)
                    .append(" >> ")
                    .append(plan.shift1())
                    .append("u;\n");
            case 1 -> source.append(indent)
                    .append(lane)
                    .append(" += ")
                    .append(rotr64(companion, plan.rotation0()))
                    .append(";\n")
                    .append(indent)
                    .append(lane)
                    .append(" ^= ")
                    .append(lane)
                    .append(" >> ")
                    .append(plan.shift1())
                    .append("u;\n")
                    .append(indent)
                    .append(lane)
                    .append(" *= j2ll_nt_m1_")
                    .append(token)
                    .append(";\n")
                    .append(indent)
                    .append(lane)
                    .append(" ^= ")
                    .append(rotl64(
                            "(" + lane + " + j2ll_nt_k2_" + token + ")",
                            plan.rotation1()))
                    .append(";\n");
            case 2 -> source.append(indent)
                    .append(lane)
                    .append(" ^= ")
                    .append(rotl64(
                            "(" + companion + " + j2ll_nt_k2_" + token + ")",
                            plan.rotation1()))
                    .append(";\n")
                    .append(indent)
                    .append(lane)
                    .append(" += ")
                    .append(rotr64(
                            "(" + lane + " ^ j2ll_nt_k1_" + token + ")",
                            plan.rotation0()))
                    .append(";\n")
                    .append(indent)
                    .append(lane)
                    .append(" = (")
                    .append(lane)
                    .append(" ^ (")
                    .append(lane)
                    .append(" >> ")
                    .append(plan.shift0())
                    .append("u)) * j2ll_nt_m1_")
                    .append(token)
                    .append(";\n")
                    .append(indent)
                    .append(lane)
                    .append(" ^= ")
                    .append(lane)
                    .append(" >> ")
                    .append(plan.shift1())
                    .append("u;\n");
            default -> throw new IllegalStateException("unreachable native-text schedule");
        }
        source.append(indent)
                .append("const uint64_t j2ll_nt_word_")
                .append(token)
                .append(" = ")
                .append(lane)
                .append(" ^ ")
                .append(rotr64(companion, plan.rotation1()))
                .append(";\n");
    }

    private void emitDualLane(
            StringBuilder source,
            NativeTextCodecPlan plan,
            String token,
            String indent) {
        String first = "j2ll_nt_d0_" + token;
        String second = "j2ll_nt_d1_" + token;
        source.append(indent)
                .append("uint64_t ")
                .append(first)
                .append(" = j2ll_nt_k0_")
                .append(token)
                .append(" + j2ll_nt_step_")
                .append(token)
                .append(" * j2ll_nt_n_")
                .append(token)
                .append(";\n")
                .append(indent)
                .append("uint64_t ")
                .append(second)
                .append(" = j2ll_nt_k1_")
                .append(token)
                .append(" ^ (j2ll_nt_m0_")
                .append(token)
                .append(" * j2ll_nt_n_")
                .append(token)
                .append(" + j2ll_nt_k2_")
                .append(token)
                .append(");\n");
        switch (plan.schedule()) {
            case 0 -> source.append(indent)
                    .append(first)
                    .append(" += ")
                    .append(rotl64(second, plan.rotation0()))
                    .append(";\n")
                    .append(indent)
                    .append(second)
                    .append(" ^= ")
                    .append(rotr64(first, plan.rotation1()))
                    .append(";\n")
                    .append(indent)
                    .append(first)
                    .append(" ^= ")
                    .append(second)
                    .append(" + j2ll_nt_m1_")
                    .append(token)
                    .append(";\n");
            case 1 -> source.append(indent)
                    .append(second)
                    .append(" += ")
                    .append(rotr64(first, plan.rotation1()))
                    .append(";\n")
                    .append(indent)
                    .append(first)
                    .append(" ^= ")
                    .append(rotl64(second, plan.rotation0()))
                    .append(";\n")
                    .append(indent)
                    .append(second)
                    .append(" += ")
                    .append(first)
                    .append(" ^ j2ll_nt_m1_")
                    .append(token)
                    .append(";\n");
            case 2 -> source.append(indent)
                    .append(first)
                    .append(" ^= ")
                    .append(rotr64(
                            "(" + second + " + j2ll_nt_k2_" + token + ")",
                            plan.rotation0()))
                    .append(";\n")
                    .append(indent)
                    .append(second)
                    .append(" += ")
                    .append(rotl64(first, plan.rotation1()))
                    .append(";\n")
                    .append(indent)
                    .append(first)
                    .append(" += ")
                    .append(second)
                    .append(" ^ j2ll_nt_m1_")
                    .append(token)
                    .append(";\n");
            default -> throw new IllegalStateException("unreachable native-text schedule");
        }
        source.append(indent)
                .append(first)
                .append(" ^= ")
                .append(first)
                .append(" >> ")
                .append(plan.shift0())
                .append("u;\n")
                .append(indent)
                .append(second)
                .append(" ^= ")
                .append(second)
                .append(" >> ")
                .append(plan.shift1())
                .append("u;\n")
                .append(indent)
                .append("const uint64_t j2ll_nt_word_")
                .append(token)
                .append(" = ")
                .append(first)
                .append(" ^ ")
                .append(rotl64(second, plan.rotation1()))
                .append(";\n");
    }

    private void emitFeistel(
            StringBuilder source,
            NativeTextCodecPlan plan,
            String token,
            String indent) {
        String base = "j2ll_nt_fb_" + token;
        String left = "j2ll_nt_fl_" + token;
        String right = "j2ll_nt_fr_" + token;
        String mixed = "j2ll_nt_fm_" + token;
        String next = "j2ll_nt_fn_" + token;
        source.append(indent)
                .append("const uint64_t ")
                .append(base)
                .append(" = j2ll_nt_k0_")
                .append(token)
                .append(" + j2ll_nt_step_")
                .append(token)
                .append(" * j2ll_nt_n_")
                .append(token)
                .append(";\n")
                .append(indent)
                .append("uint32_t ")
                .append(left)
                .append(" = (uint32_t)(")
                .append(base)
                .append(" >> 32u);\n")
                .append(indent)
                .append("uint32_t ")
                .append(right)
                .append(" = (uint32_t)")
                .append(base)
                .append(";\n");
        for (int round = 0; round < 4; round++) {
            int rotation = plan.feistelRotation(round);
            source.append(indent)
                    .append("const uint32_t ")
                    .append(mixed)
                    .append('_')
                    .append(round)
                    .append(" = ")
                    .append(rotl32(
                            "(" + right + " ^ UINT32_C(0x"
                                    + hex32(plan.feistelRoundKey(round))
                                    + "))",
                            rotation))
                    .append(" * UINT32_C(0x")
                    .append(hex32((int) plan.multiplier0()))
                    .append(");\n")
                    .append(indent)
                    .append("const uint32_t ")
                    .append(next)
                    .append('_')
                    .append(round)
                    .append(" = ")
                    .append(left)
                    .append(" ^ ")
                    .append(mixed)
                    .append('_')
                    .append(round)
                    .append(";\n")
                    .append(indent)
                    .append(left)
                    .append(" = ")
                    .append(right)
                    .append(";\n")
                    .append(indent)
                    .append(right)
                    .append(" = ")
                    .append(next)
                    .append('_')
                    .append(round)
                    .append(";\n");
        }
        source.append(indent)
                .append("const uint64_t j2ll_nt_word_")
                .append(token)
                .append(" = ((uint64_t)")
                .append(left)
                .append(" << 32u) | (uint64_t)")
                .append(right)
                .append(";\n");
    }

    private void emitFoldRotate(
            StringBuilder source,
            NativeTextCodecPlan plan,
            String token,
            String indent) {
        String value = "j2ll_nt_r_" + token;
        source.append(indent)
                .append("uint64_t ")
                .append(value)
                .append(" = j2ll_nt_k0_")
                .append(token)
                .append(" ^ (j2ll_nt_step_")
                .append(token)
                .append(" * j2ll_nt_n_")
                .append(token)
                .append(");\n");
        switch (plan.schedule()) {
            case 0 -> source.append(indent)
                    .append(value)
                    .append(" = ")
                    .append(rotl64(
                            "(" + value + " + j2ll_nt_k1_" + token + ")",
                            plan.rotation0()))
                    .append(";\n")
                    .append(indent)
                    .append(value)
                    .append(" *= j2ll_nt_m0_")
                    .append(token)
                    .append(";\n")
                    .append(indent)
                    .append(value)
                    .append(" ^= ")
                    .append(rotr64(
                            "(" + value + " + j2ll_nt_k2_" + token + ")",
                            plan.rotation1()))
                    .append(";\n")
                    .append(indent)
                    .append(value)
                    .append(" += j2ll_nt_m1_")
                    .append(token)
                    .append(" * j2ll_nt_n_")
                    .append(token)
                    .append(";\n");
            case 1 -> source.append(indent)
                    .append(value)
                    .append(" ^= ")
                    .append(rotr64(
                            "(" + value + " + j2ll_nt_k2_" + token + ")",
                            plan.rotation1()))
                    .append(";\n")
                    .append(indent)
                    .append(value)
                    .append(" *= j2ll_nt_m1_")
                    .append(token)
                    .append(";\n")
                    .append(indent)
                    .append(value)
                    .append(" = ")
                    .append(rotl64(
                            "(" + value + " + j2ll_nt_k1_" + token + ")",
                            plan.rotation0()))
                    .append(";\n")
                    .append(indent)
                    .append(value)
                    .append(" ^= j2ll_nt_m0_")
                    .append(token)
                    .append(" * j2ll_nt_n_")
                    .append(token)
                    .append(";\n");
            case 2 -> source.append(indent)
                    .append(value)
                    .append(" += ")
                    .append(rotl64(
                            "(j2ll_nt_k1_" + token + " ^ j2ll_nt_n_" + token + ")",
                            plan.rotation0()))
                    .append(";\n")
                    .append(indent)
                    .append(value)
                    .append(" ^= ")
                    .append(value)
                    .append(" >> ")
                    .append(plan.shift0())
                    .append("u;\n")
                    .append(indent)
                    .append(value)
                    .append(" *= j2ll_nt_m0_")
                    .append(token)
                    .append(";\n")
                    .append(indent)
                    .append(value)
                    .append(" = ")
                    .append(rotr64(
                            "(" + value + " ^ j2ll_nt_k2_" + token + ")",
                            plan.rotation1()))
                    .append(";\n")
                    .append(indent)
                    .append(value)
                    .append(" += j2ll_nt_m1_")
                    .append(token)
                    .append(";\n");
            default -> throw new IllegalStateException("unreachable native-text schedule");
        }
        source.append(indent)
                .append("const uint64_t j2ll_nt_word_")
                .append(token)
                .append(" = ")
                .append(value)
                .append(" ^ (")
                .append(value)
                .append(" >> ")
                .append(plan.shift1())
                .append("u);\n");
    }

    private String rotl64(String expression, int rotation) {
        return "((" + expression + " << " + rotation + "u) | ("
                + expression + " >> " + (64 - rotation) + "u))";
    }

    private String rotr64(String expression, int rotation) {
        return "((" + expression + " >> " + rotation + "u) | ("
                + expression + " << " + (64 - rotation) + "u))";
    }

    private String rotl32(String expression, int rotation) {
        return "((" + expression + " << " + rotation + "u) | ("
                + expression + " >> " + (32 - rotation) + "u))";
    }

    private String hex(long value) {
        return String.format(Locale.ROOT, "%016x", value);
    }

    private String hex32(int value) {
        return String.format(Locale.ROOT, "%08x", value);
    }
}
