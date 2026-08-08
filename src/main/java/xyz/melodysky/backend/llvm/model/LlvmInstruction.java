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
        LlvmNativeUnwindSemantics nativeUnwindSemantics) {
    public LlvmInstruction {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(opcode, "opcode");
        operands = List.copyOf(Objects.requireNonNull(operands, "operands"));
        Objects.requireNonNull(rawText, "rawText");
        Objects.requireNonNull(irCallIndirection, "irCallIndirection");
        Objects.requireNonNull(nativeUnwindSemantics, "nativeUnwindSemantics");
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
                LlvmNativeUnwindSemantics.UNKNOWN);
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
                LlvmNativeUnwindSemantics.UNKNOWN);
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
                nativeUnwindSemantics);
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
                LlvmNativeUnwindSemantics.PROVEN_ABSENT);
    }

    public LlvmInstruction withIrCallIndirection(LlvmIrCallIndirectionRef reference) {
        return new LlvmInstruction(
                result,
                type,
                opcode,
                operands,
                rawText,
                Optional.of(Objects.requireNonNull(reference, "reference")),
                nativeUnwindSemantics);
    }
}
