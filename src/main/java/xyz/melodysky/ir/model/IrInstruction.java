package xyz.melodysky.ir.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record IrInstruction(
        Optional<IrValue> result,
        IrOpcode opcode,
        List<IrValue> operands,
        Optional<Integer> intLiteral,
        Optional<Long> longLiteral,
        Optional<Float> floatLiteral,
        Optional<Double> doubleLiteral,
        Optional<String> symbol,
        List<IrExceptionSite> exceptionSites,
        Optional<IrCallIndirectionRef> callIndirection) {
    public IrInstruction {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(opcode, "opcode");
        operands = List.copyOf(Objects.requireNonNull(operands, "operands"));
        Objects.requireNonNull(intLiteral, "intLiteral");
        Objects.requireNonNull(longLiteral, "longLiteral");
        Objects.requireNonNull(floatLiteral, "floatLiteral");
        Objects.requireNonNull(doubleLiteral, "doubleLiteral");
        Objects.requireNonNull(symbol, "symbol");
        exceptionSites = List.copyOf(Objects.requireNonNull(exceptionSites, "exceptionSites"));
        Objects.requireNonNull(callIndirection, "callIndirection");
    }

    public IrInstruction(
            Optional<IrValue> result,
            IrOpcode opcode,
            List<IrValue> operands,
            Optional<Integer> intLiteral,
            Optional<Long> longLiteral,
            Optional<Float> floatLiteral,
            Optional<Double> doubleLiteral,
            Optional<String> symbol,
            List<IrExceptionSite> exceptionSites) {
        this(
                result,
                opcode,
                operands,
                intLiteral,
                longLiteral,
                floatLiteral,
                doubleLiteral,
                symbol,
                exceptionSites,
                Optional.empty());
    }

    public IrInstruction(
            Optional<IrValue> result,
            IrOpcode opcode,
            List<IrValue> operands,
            Optional<Integer> intLiteral,
            Optional<Long> longLiteral,
            Optional<Float> floatLiteral,
            Optional<Double> doubleLiteral,
            Optional<String> symbol) {
        this(result, opcode, operands, intLiteral, longLiteral, floatLiteral, doubleLiteral, symbol, List.of());
    }

    public IrInstruction(
            Optional<IrValue> result,
            IrOpcode opcode,
            List<IrValue> operands,
            Optional<Integer> intLiteral) {
        this(
                result,
                opcode,
                operands,
                intLiteral,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of());
    }

    public IrInstruction(
            Optional<IrValue> result,
            IrOpcode opcode,
            List<IrValue> operands,
            Optional<Integer> intLiteral,
            Optional<Long> longLiteral,
            Optional<Float> floatLiteral,
            Optional<Double> doubleLiteral) {
        this(result, opcode, operands, intLiteral, longLiteral, floatLiteral, doubleLiteral, Optional.empty());
    }

    public static IrInstruction constInt(IrValue result, int value) {
        return new IrInstruction(
                Optional.of(result),
                IrOpcode.CONST_INT,
                List.of(),
                Optional.of(value),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of());
    }

    public static IrInstruction constLong(IrValue result, long value) {
        return new IrInstruction(
                Optional.of(result),
                IrOpcode.CONST_LONG,
                List.of(),
                Optional.empty(),
                Optional.of(value),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of());
    }

    public static IrInstruction constFloat(IrValue result, float value) {
        return new IrInstruction(
                Optional.of(result),
                IrOpcode.CONST_FLOAT,
                List.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(value),
                Optional.empty(),
                Optional.empty());
    }

    public static IrInstruction constDouble(IrValue result, double value) {
        return new IrInstruction(
                Optional.of(result),
                IrOpcode.CONST_DOUBLE,
                List.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(value),
                Optional.empty(),
                List.of());
    }

    public static IrInstruction constNull(IrValue result) {
        return new IrInstruction(
                Optional.of(result),
                IrOpcode.CONST_NULL,
                List.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of());
    }

    public static IrInstruction symbolicConstant(IrValue result, IrOpcode opcode, String symbol) {
        return new IrInstruction(
                Optional.of(result),
                opcode,
                List.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(symbol),
                List.of());
    }

    public static IrInstruction binary(IrValue result, IrOpcode opcode, IrValue left, IrValue right) {
        return new IrInstruction(
                Optional.of(result),
                opcode,
                List.of(left, right),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of());
    }

    public static IrInstruction unary(IrValue result, IrOpcode opcode, IrValue operand) {
        return new IrInstruction(
                Optional.of(result),
                opcode,
                List.of(operand),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of());
    }

    public static IrInstruction fieldGet(IrValue result, IrOpcode opcode, List<IrValue> operands, String fieldKey) {
        return new IrInstruction(
                Optional.of(result),
                opcode,
                operands,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(fieldKey),
                List.of());
    }

    public static IrInstruction fieldPut(IrOpcode opcode, List<IrValue> operands, String fieldKey) {
        return new IrInstruction(
                Optional.empty(),
                opcode,
                operands,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(fieldKey),
                List.of());
    }

    public static IrInstruction call(Optional<IrValue> result, IrOpcode opcode, List<IrValue> operands, String methodKey) {
        return new IrInstruction(
                result,
                opcode,
                operands,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(methodKey),
                List.of());
    }

    public static IrInstruction operation(
            Optional<IrValue> result,
            IrOpcode opcode,
            List<IrValue> operands,
            String symbol) {
        return new IrInstruction(
                result,
                opcode,
                operands,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(symbol),
                List.of());
    }

    public IrInstruction withCallIndirection(IrCallIndirectionRef reference) {
        return new IrInstruction(
                result,
                opcode,
                operands,
                intLiteral,
                longLiteral,
                floatLiteral,
                doubleLiteral,
                symbol,
                exceptionSites,
                Optional.of(Objects.requireNonNull(reference, "reference")));
    }

    public IrInstruction withExceptionSite(IrExceptionSite site) {
        java.util.ArrayList<IrExceptionSite> sites = new java.util.ArrayList<>(exceptionSites);
        sites.add(site);
        return new IrInstruction(
                result,
                opcode,
                operands,
                intLiteral,
                longLiteral,
                floatLiteral,
                doubleLiteral,
                symbol,
                sites,
                callIndirection);
    }

    public IrInstruction withExceptionSites(List<IrExceptionSite> sites) {
        return new IrInstruction(
                result,
                opcode,
                operands,
                intLiteral,
                longLiteral,
                floatLiteral,
                doubleLiteral,
                symbol,
                sites,
                callIndirection);
    }
}
