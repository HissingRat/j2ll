package xyz.melodysky.toolchain.nativetext;

import java.util.Locale;

/** Emits one compact, site-bound 32-bit codec directly into its owner. */
final class NativeTextCodecCEmitter {
    private final NativeTextStoragePermutationCEmitter storageEmitter =
            new NativeTextStoragePermutationCEmitter();

    String decodeInto(
            NativeTextEncoding encoding,
            String cipherExpression,
            String lengthExpression,
            String destinationExpression,
            String indent) {
        return decodeInto(
                encoding,
                null,
                cipherExpression,
                lengthExpression,
                destinationExpression,
                indent);
    }

    String decodeTupleInto(
            NativeTextTupleEncoding tuple,
            String cipherExpression,
            String lengthExpression,
            String destinationExpression,
            String indent) {
        return decodeInto(
                tuple.record(),
                tuple,
                cipherExpression,
                lengthExpression,
                destinationExpression,
                indent);
    }

    private String decodeInto(
            NativeTextEncoding encoding,
            NativeTextTupleEncoding tuple,
            String cipherExpression,
            String lengthExpression,
            String destinationExpression,
            String indent) {
        NativeTextCodecPlan plan = encoding.codecPlan();
        String token = encoding.symbol().substring("j2ll_nt_".length());
        String inner = indent + "    ";
        String loop = inner + "    ";
        String destination = "j2ll_nt_out_" + token;
        StringBuilder source = new StringBuilder()
                .append(indent)
                .append("{\n")
                .append(inner)
                .append("unsigned char* const ")
                .append(destination)
                .append(" = (unsigned char*)(")
                .append(destinationExpression)
                .append(");\n")
                .append(constant(inner, "k0", plan.key0()))
                .append(constant(inner, "step", plan.step()));
        appendFamilyConstants(source, plan, inner);
        source.append(storageEmitter.cursorDeclaration(
                        encoding,
                        token,
                        inner))
                .append(inner)
                .append("for (size_t i = 0u; i < ")
                .append(lengthExpression)
                .append("; i++) {\n")
                .append(loop)
                .append("const size_t p = ");
        if (plan.reverseTraversal()) {
            source.append('(')
                    .append(lengthExpression)
                    .append(" - 1u - i);\n");
        } else {
            source.append("i;\n");
        }
        source.append(loop)
                .append("const uint32_t n = (uint32_t)(p + 1u);\n");
        switch (plan.family()) {
            case WEYL_ARX -> emitWeyl(source, plan, token, loop);
            case DUAL_LANE_ARX -> emitDualLane(source, plan, token, loop);
            case FEISTEL_32 -> emitFeistel(source, plan, token, loop);
            case FOLD_ROTATE -> emitFoldRotate(source, plan, token, loop);
        }
        if (tuple != null) {
            emitTupleLaneMask(source, tuple, token, loop);
        }
        source.append(loop)
                .append(destination)
                .append("[p] = (unsigned char)(((const volatile unsigned char*)(")
                .append(cipherExpression)
                .append("))[j2ll_nt_s_")
                .append(token)
                .append("] ^ (unsigned char)(j2ll_nt_word_")
                .append(token)
                .append(" >> ")
                .append(plan.outputShift())
                .append("u)");
        if (tuple != null) {
            source.append(" ^ (unsigned char)j2ll_nt_lane_")
                    .append(token);
        }
        source.append(");\n")
                .append(storageEmitter.cursorAdvance(
                        encoding,
                        token,
                        lengthExpression,
                        loop))
                .append(inner)
                .append("}\n")
                .append(indent)
                .append("}\n");
        return source.toString();
    }

    private void emitTupleLaneMask(
            StringBuilder source,
            NativeTextTupleEncoding tuple,
            String token,
            String indent) {
        String mask = "j2ll_nt_lane_" + token;
        source.append(indent)
                .append("uint32_t ")
                .append(mask)
                .append(" = UINT32_C(0);\n");
        boolean first = true;
        for (int componentIndex = 0;
                componentIndex < tuple.componentCount();
                componentIndex++) {
            NativeTextTupleEncoding.Slice slice =
                    tuple.slice(componentIndex);
            if (slice.length() == 0) {
                continue;
            }
            NativeTextTupleEncoding.LanePlan lane = slice.lanePlan();
            source.append(indent)
                    .append(first ? "if" : "else if")
                    .append(" (p >= ")
                    .append(slice.offset())
                    .append("u && p < ")
                    .append(slice.offset() + slice.length())
                    .append("u) {\n")
                    .append(indent)
                    .append("    const uint32_t q = (uint32_t)(p - ")
                    .append(slice.offset())
                    .append("u + 1u);\n")
                    .append(indent)
                    .append("    uint32_t lane = UINT32_C(0x")
                    .append(hex32(lane.seed()))
                    .append(") + UINT32_C(0x")
                    .append(hex32(lane.step()))
                    .append(") * q;\n")
                    .append(indent)
                    .append("    lane ^= lane >> ")
                    .append(lane.shift0())
                    .append("u;\n")
                    .append(indent)
                    .append("    lane *= UINT32_C(0x")
                    .append(hex32(lane.multiplier()))
                    .append(");\n")
                    .append(indent)
                    .append("    lane ^= lane >> ")
                    .append(lane.shift1())
                    .append("u;\n")
                    .append(indent)
                    .append("    ")
                    .append(mask)
                    .append(" = lane >> ")
                    .append(lane.outputShift())
                    .append("u;\n")
                    .append(indent)
                    .append("}\n");
            first = false;
        }
    }

    private void appendFamilyConstants(
            StringBuilder source,
            NativeTextCodecPlan plan,
            String indent) {
        if (plan.family() == NativeTextCodecFamily.FEISTEL_32) {
            source.append(constant(indent, "m0", plan.multiplier0()));
            return;
        }
        if (plan.family() == NativeTextCodecFamily.DUAL_LANE_ARX) {
            source.append(constant(indent, "k1", plan.key1()))
                    .append(constant(indent, "k2", plan.key2()))
                    .append(constant(indent, "m0", plan.multiplier0()));
            return;
        }
        switch (plan.schedule()) {
            case 0 -> source.append(constant(indent, "k1", plan.key1()))
                    .append(constant(indent, "m0", plan.multiplier0()));
            case 1 -> source.append(constant(indent, "k2", plan.key2()))
                    .append(constant(
                            indent,
                            plan.family() == NativeTextCodecFamily.WEYL_ARX
                                    ? "m0"
                                    : "m1",
                            plan.family() == NativeTextCodecFamily.WEYL_ARX
                                    ? plan.multiplier0()
                                    : plan.multiplier1()));
            case 2 -> source.append(constant(indent, "k1", plan.key1()));
            default -> throw new IllegalStateException(
                    "unreachable native-text schedule");
        }
        if (plan.schedule() == 0
                && plan.family() == NativeTextCodecFamily.WEYL_ARX) {
            source.append(constant(indent, "m1", plan.multiplier1()));
        }
        if (plan.schedule() == 2) {
            if (plan.family() == NativeTextCodecFamily.WEYL_ARX) {
                source.append(constant(indent, "k2", plan.key2()));
            } else {
                source.append(constant(indent, "m0", plan.multiplier0()));
            }
        }
    }

    private String constant(String indent, String name, long value) {
        return indent
                + "const uint32_t "
                + name
                + " = UINT32_C(0x"
                + hex32((int) value)
                + ");\n";
    }

    private void emitWeyl(
            StringBuilder source,
            NativeTextCodecPlan plan,
            String token,
            String indent) {
        String lane = "j2ll_nt_w_" + token;
        source.append(indent)
                .append("uint32_t ")
                .append(lane)
                .append(" = k0 + step * n;\n");
        switch (plan.schedule()) {
            case 0 -> source.append(indent)
                    .append(lane)
                    .append(" ^= ")
                    .append(rotl32("(k1 + m0 * n)", rotation(plan.rotation0())))
                    .append(";\n")
                    .append(indent)
                    .append(lane)
                    .append(" *= m1;\n")
                    .append(indent)
                    .append(lane)
                    .append(" ^= ")
                    .append(lane)
                    .append(" >> ")
                    .append(shift(plan.shift0()))
                    .append("u;\n");
            case 1 -> source.append(indent)
                    .append(lane)
                    .append(" += ")
                    .append(rotr32("(k2 ^ n)", rotation(plan.rotation1())))
                    .append(";\n")
                    .append(indent)
                    .append(lane)
                    .append(" ^= ")
                    .append(lane)
                    .append(" >> ")
                    .append(shift(plan.shift1()))
                    .append("u;\n")
                    .append(indent)
                    .append(lane)
                    .append(" *= m0;\n");
            case 2 -> source.append(indent)
                    .append(lane)
                    .append(" ^= ")
                    .append(rotl32("(k1 ^ n)", rotation(plan.rotation0())))
                    .append(";\n")
                    .append(indent)
                    .append(lane)
                    .append(" += k2;\n")
                    .append(indent)
                    .append(lane)
                    .append(" ^= ")
                    .append(lane)
                    .append(" >> ")
                    .append(shift(plan.shift0()))
                    .append("u;\n");
            default -> throw new IllegalStateException("unreachable native-text schedule");
        }
        appendWord(source, token, lane, indent);
    }

    private void emitDualLane(
            StringBuilder source,
            NativeTextCodecPlan plan,
            String token,
            String indent) {
        String first = "j2ll_nt_d0_" + token;
        String second = "d1";
        source.append(indent)
                .append("uint32_t ")
                .append(first)
                .append(" = k0 + step * n;\n")
                .append(indent)
                .append("uint32_t ")
                .append(second)
                .append(" = k1 ^ (m0 * n + k2);\n");
        switch (plan.schedule()) {
            case 0 -> source.append(indent)
                    .append(first)
                    .append(" += ")
                    .append(rotl32(second, rotation(plan.rotation0())))
                    .append(";\n")
                    .append(indent)
                    .append(first)
                    .append(" ^= ")
                    .append(rotr32(
                            "(" + first + " + " + second + ")",
                            rotation(plan.rotation1())))
                    .append(";\n");
            case 1 -> source.append(indent)
                    .append(second)
                    .append(" += ")
                    .append(rotr32(first, rotation(plan.rotation1())))
                    .append(";\n")
                    .append(indent)
                    .append(first)
                    .append(" ^= ")
                    .append(rotl32(second, rotation(plan.rotation0())))
                    .append(";\n");
            case 2 -> source.append(indent)
                    .append(first)
                    .append(" ^= ")
                    .append(rotr32("(" + second + " + k2)", rotation(plan.rotation0())))
                    .append(";\n")
                    .append(indent)
                    .append(first)
                    .append(" += ")
                    .append(rotl32(second, rotation(plan.rotation1())))
                    .append(";\n");
            default -> throw new IllegalStateException("unreachable native-text schedule");
        }
        source.append(indent)
                .append("const uint32_t j2ll_nt_word_")
                .append(token)
                .append(" = ")
                .append(first)
                .append(" ^ (")
                .append(first)
                .append(" >> ")
                .append(shift(plan.shift0()))
                .append("u);\n");
    }

    private void emitFeistel(
            StringBuilder source,
            NativeTextCodecPlan plan,
            String token,
            String indent) {
        String left = "j2ll_nt_fl_" + token;
        String right = "fr";
        source.append(indent)
                .append("const uint32_t fb = k0 + step * n;\n")
                .append(indent)
                .append("uint32_t ")
                .append(left)
                .append(" = fb >> 16u;\n")
                .append(indent)
                .append("uint32_t ")
                .append(right)
                .append(" = fb & UINT32_C(0xffff);\n");
        for (int round = 0; round < 2; round++) {
            source.append(indent)
                    .append("const uint32_t fm_")
                    .append(round)
                    .append(" = ")
                    .append(rotl32(
                            "(" + right + " ^ UINT32_C(0x"
                                    + hex32(plan.feistelRoundKey(round) & 0xffff)
                                    + "))",
                            plan.feistelRotation(round)))
                    .append(" * m0;\n")
                    .append(indent)
                    .append("const uint32_t fn_")
                    .append(round)
                    .append(" = (")
                    .append(left)
                    .append(" ^ fm_")
                    .append(round)
                    .append(") & UINT32_C(0xffff);\n")
                    .append(indent)
                    .append(left)
                    .append(" = ")
                    .append(right)
                    .append(";\n")
                    .append(indent)
                    .append(right)
                    .append(" = fn_")
                    .append(round)
                    .append(";\n");
        }
        source.append(indent)
                .append("const uint32_t j2ll_nt_word_")
                .append(token)
                .append(" = (")
                .append(left)
                .append(" << 16u) | ")
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
                .append("uint32_t ")
                .append(value)
                .append(" = k0 ^ (step * n);\n");
        switch (plan.schedule()) {
            case 0 -> source.append(indent)
                    .append(value)
                    .append(" = ")
                    .append(rotl32("(" + value + " + k1)", rotation(plan.rotation0())))
                    .append(";\n")
                    .append(indent)
                    .append(value)
                    .append(" *= m0;\n");
            case 1 -> source.append(indent)
                    .append(value)
                    .append(" ^= ")
                    .append(rotr32("(" + value + " + k2)", rotation(plan.rotation1())))
                    .append(";\n")
                    .append(indent)
                    .append(value)
                    .append(" *= m1;\n");
            case 2 -> source.append(indent)
                    .append(value)
                    .append(" += ")
                    .append(rotl32("(k1 ^ n)", rotation(plan.rotation0())))
                    .append(";\n")
                    .append(indent)
                    .append(value)
                    .append(" *= m0;\n");
            default -> throw new IllegalStateException("unreachable native-text schedule");
        }
        source.append(indent)
                .append("const uint32_t j2ll_nt_word_")
                .append(token)
                .append(" = ")
                .append(value)
                .append(" ^ (")
                .append(value)
                .append(" >> ")
                .append(shift(plan.shift1()))
                .append("u);\n");
    }

    private void appendWord(
            StringBuilder source,
            String token,
            String value,
            String indent) {
        source.append(indent)
                .append("const uint32_t j2ll_nt_word_")
                .append(token)
                .append(" = ")
                .append(value)
                .append(";\n");
    }

    private int rotation(int value) {
        return 1 + Math.floorMod(value, 31);
    }

    private int shift(int value) {
        return 1 + Math.floorMod(value, 31);
    }

    private String rotl32(String expression, int rotation) {
        return "((" + expression + " << " + rotation + "u) | ("
                + expression + " >> " + (32 - rotation) + "u))";
    }

    private String rotr32(String expression, int rotation) {
        return "((" + expression + " >> " + rotation + "u) | ("
                + expression + " << " + (32 - rotation) + "u))";
    }

    private String hex32(int value) {
        return String.format(Locale.ROOT, "%08x", value);
    }
}
