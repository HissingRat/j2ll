package xyz.melodysky.backend.llvm.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record LlvmInstruction(
        Optional<String> result,
        LlvmType type,
        String opcode,
        List<String> operands,
        Optional<String> rawText,
        Optional<LlvmIrCallIndirectionRef> irCallIndirection,
        LlvmNativeUnwindSemantics nativeUnwindSemantics,
        Optional<LlvmDirectCallRef> directCall) {
    public LlvmInstruction {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(opcode, "opcode");
        operands = List.copyOf(Objects.requireNonNull(operands, "operands"));
        Objects.requireNonNull(rawText, "rawText");
        Objects.requireNonNull(irCallIndirection, "irCallIndirection");
        Objects.requireNonNull(nativeUnwindSemantics, "nativeUnwindSemantics");
        Objects.requireNonNull(directCall, "directCall");
        if (directCall.isPresent()
                && (rawText.isPresent()
                        || irCallIndirection.isPresent()
                        || !opcode.equals("call"))) {
            throw new IllegalArgumentException(
                    "structured direct call cannot carry raw or indirect-call state");
        }
    }

    public LlvmInstruction(
            Optional<String> result,
            LlvmType type,
            String opcode,
            List<String> operands,
            Optional<String> rawText,
            Optional<LlvmIrCallIndirectionRef> irCallIndirection,
            LlvmNativeUnwindSemantics nativeUnwindSemantics) {
        this(
                result,
                type,
                opcode,
                operands,
                rawText,
                irCallIndirection,
                nativeUnwindSemantics,
                Optional.empty());
    }

    public LlvmInstruction(
            Optional<String> result,
            LlvmType type,
            String opcode,
            List<String> operands,
            Optional<String> rawText) {
        this(
                result,
                type,
                opcode,
                operands,
                rawText,
                Optional.empty(),
                LlvmNativeUnwindSemantics.UNKNOWN,
                Optional.empty());
    }

    public LlvmInstruction(
            Optional<String> result,
            LlvmType type,
            String opcode,
            List<String> operands,
            Optional<String> rawText,
            Optional<LlvmIrCallIndirectionRef> irCallIndirection) {
        this(
                result,
                type,
                opcode,
                operands,
                rawText,
                irCallIndirection,
                LlvmNativeUnwindSemantics.UNKNOWN,
                Optional.empty());
    }

    public LlvmInstruction(Optional<String> result, LlvmType type, String opcode, List<String> operands) {
        this(result, type, opcode, operands, Optional.empty());
    }

    public static LlvmInstruction raw(Optional<String> result, String text) {
        return raw(result, text, LlvmNativeUnwindSemantics.UNKNOWN);
    }

    public static LlvmInstruction raw(
            Optional<String> result,
            String text,
            LlvmNativeUnwindSemantics nativeUnwindSemantics) {
        return new LlvmInstruction(
                result,
                LlvmType.VOID,
                "raw",
                List.of(),
                Optional.of(text),
                Optional.empty(),
                nativeUnwindSemantics,
                Optional.empty());
    }

    public static LlvmInstruction rawProvenNoNativeUnwind(
            Optional<String> result,
            String text) {
        return raw(result, text, LlvmNativeUnwindSemantics.PROVEN_ABSENT);
    }

    public static LlvmInstruction provenNoNativeUnwind(
            Optional<String> result,
            LlvmType type,
            String opcode,
            List<String> operands) {
        return new LlvmInstruction(
                result,
                type,
                opcode,
                operands,
                Optional.empty(),
                Optional.empty(),
                LlvmNativeUnwindSemantics.PROVEN_ABSENT,
                Optional.empty());
    }

    public static LlvmInstruction directCallProvenNoNativeUnwind(
            Optional<String> result,
            LlvmDirectCallRef call) {
        Objects.requireNonNull(call, "call");
        if ((call.returnType() == LlvmType.VOID) != result.isEmpty()) {
            throw new IllegalArgumentException(
                    "LLVM direct-call result must match its return type");
        }
        return new LlvmInstruction(
                result,
                call.returnType(),
                "call",
                List.of(),
                Optional.empty(),
                Optional.empty(),
                LlvmNativeUnwindSemantics.PROVEN_ABSENT,
                Optional.of(call));
    }

    public LlvmInstruction withIrCallIndirection(LlvmIrCallIndirectionRef reference) {
        return new LlvmInstruction(
                result,
                type,
                opcode,
                operands,
                rawText,
                Optional.of(Objects.requireNonNull(reference, "reference")),
                nativeUnwindSemantics,
                directCall);
    }
}
