package xyz.melodysky.toolchain;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import xyz.melodysky.backend.llvm.LlvmFunctionAbiPolicy;
import xyz.melodysky.ir.model.BusinessStringConstantRef;
import xyz.melodysky.ir.model.BusinessStringSymbolMapper;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminatorKind;

/** Collects all immutable implementation metadata in one traversal of an IR method. */
final class NativeImplementationEvidenceCollector {
    @FunctionalInterface
    interface InstructionMatcher {
        boolean matches(
                NativeImplementationEvidenceKind kind,
                IrInstruction instruction,
                Set<String> availableProgramMethods);
    }

    private final BusinessStringSymbolMapper businessStringSymbols;
    private final InstructionMatcher matcher;

    NativeImplementationEvidenceCollector(
            BusinessStringSymbolMapper businessStringSymbols,
            InstructionMatcher matcher) {
        this.businessStringSymbols = Objects.requireNonNull(
                businessStringSymbols,
                "businessStringSymbols");
        this.matcher = Objects.requireNonNull(matcher, "matcher");
    }

    NativeMethodImplementationEvidence collect(
            IrMethod method,
            List<String> directCallTargets,
            Set<String> availableProgramMethods) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(directCallTargets, "directCallTargets");
        Objects.requireNonNull(
                availableProgramMethods,
                "availableProgramMethods");
        Set<String> directTargets = Set.copyOf(directCallTargets);
        NativeImplementationEvidenceAccumulator evidence =
                new NativeImplementationEvidenceAccumulator(
                        directCallTargets);
        for (var block : method.blocks()) {
            if (block.terminator().kind() == IrTerminatorKind.THROW) {
                evidence.exceptionHelper = true;
                evidence.passesJniEnv = true;
            }
            block.exceptionEdges().forEach(edge ->
                    evidence.addCatchType(edge.catchType()));
            for (IrInstruction instruction : block.instructions()) {
                collectInstruction(
                        instruction,
                        directTargets,
                        availableProgramMethods,
                        evidence);
                instruction.exceptionSites().stream()
                        .flatMap(site -> site.handlers().stream())
                        .forEach(edge -> evidence.addCatchType(edge.catchType()));
            }
        }
        if (evidence.referenceComparisons.stream().anyMatch(operands ->
                operands.stream().noneMatch(evidence.directNullValues::contains))) {
            evidence.passesJniEnv = true;
        }
        return evidence.freeze();
    }

    private void collectInstruction(
            IrInstruction instruction,
            Set<String> directTargets,
            Set<String> availableProgramMethods,
            NativeImplementationEvidenceAccumulator evidence) {
        if (!instruction.exceptionSites().isEmpty()) {
            evidence.passesJniEnv = true;
        }
        if (instruction.opcode() == IrOpcode.CONST_NULL) {
            instruction.result().ifPresent(result ->
                    evidence.directNullValues.add(result.name()));
        }
        if (instruction.opcode() == IrOpcode.CMP_EQ_REF
                || instruction.opcode() == IrOpcode.CMP_NE_REF) {
            evidence.referenceComparisons.add(instruction.operands().stream()
                    .map(value -> value.name())
                    .toList());
        }

        boolean field = matches(
                NativeImplementationEvidenceKind.FIELD_ACCESS,
                instruction,
                availableProgramMethods);
        if (field) {
            evidence.fieldKeys.add(fieldKey(instruction));
            evidence.passesJniEnv = true;
            if (instruction.opcode() == IrOpcode.GET_STATIC
                    || instruction.opcode() == IrOpcode.PUT_STATIC
                    || instruction.opcode() == IrOpcode.GET_NATIVE_STATIC
                    || instruction.opcode() == IrOpcode.PUT_NATIVE_STATIC) {
                evidence.passesOwnerClass = true;
            }
        }

        boolean supportedAllocation = matches(
                NativeImplementationEvidenceKind.SUPPORTED_ALLOCATION,
                instruction,
                availableProgramMethods);
        if (supportedAllocation) {
            evidence.allocationHelper = true;
            instruction.symbol()
                    .filter(symbol -> symbol.startsWith("object:")
                            || symbol.startsWith("referenceArray:"))
                    .ifPresent(evidence.allocationKeys::add);
        }
        boolean typeHelper = matches(
                NativeImplementationEvidenceKind.TYPE_HELPER,
                instruction,
                availableProgramMethods);
        evidence.typeHelper |= typeHelper;
        if (matches(
                NativeImplementationEvidenceKind.SUPPORTED_TYPE,
                instruction,
                availableProgramMethods)) {
            evidence.typeCheckKeys.add(instruction.symbol().orElseThrow());
        }
        evidence.constructorCallHelper |= matches(
                NativeImplementationEvidenceKind.CONSTRUCTOR_CALL_HELPER,
                instruction,
                availableProgramMethods);
        if (matches(
                NativeImplementationEvidenceKind.SUPPORTED_CONSTRUCTOR_CALL,
                instruction,
                availableProgramMethods)) {
            evidence.constructorCallKeys.add(
                    instruction.symbol().orElseThrow());
        }

        collectCallEvidence(
                instruction,
                directTargets,
                availableProgramMethods,
                evidence);
        collectHelperEvidence(
                instruction,
                availableProgramMethods,
                evidence);

        if (instruction.opcode() == IrOpcode.CLASS_OBJECT
                || instruction.opcode() == IrOpcode.CONST_CLASS) {
            evidence.classObjectKeys.add(instruction.symbol().orElseThrow());
        }
        if (instruction.opcode() == IrOpcode.CONST_STRING
                || matches(NativeImplementationEvidenceKind.STRING_HELPER,
                        instruction, availableProgramMethods)
                || matches(NativeImplementationEvidenceKind.STRING_BUILDER_HELPER,
                        instruction, availableProgramMethods)) {
            evidence.stringHelperSymbols.add(
                    BusinessStringConstantRef.fromInstruction(instruction)
                            .map(constant -> constant.helperSymbol(
                                    businessStringSymbols))
                            .orElseGet(() -> NativeRuntimeHelperSymbol.base(
                                    instruction.symbol().orElseThrow())));
        }
        if (matches(NativeImplementationEvidenceKind.RUNTIME_METADATA_HELPER,
                instruction, availableProgramMethods)) {
            evidence.runtimeMetadataHelper = true;
            NativeRuntimeHelperSymbol.metadataKey(instruction)
                    .ifPresent(evidence.runtimeMetadataKeys::add);
        }
        collectJniSurface(
                instruction,
                directTargets,
                availableProgramMethods,
                evidence);
    }

    private void collectCallEvidence(
            IrInstruction instruction,
            Set<String> directTargets,
            Set<String> availableProgramMethods,
            NativeImplementationEvidenceAccumulator evidence) {
        boolean direct = instruction.symbol()
                .filter(directTargets::contains)
                .isPresent();
        if (instruction.opcode() == IrOpcode.CALL_STATIC
                && !direct
                && matches(NativeImplementationEvidenceKind.SUPPORTED_STATIC_BRIDGE,
                        instruction, availableProgramMethods)) {
            evidence.staticCallKeys.add(instruction.symbol().orElseThrow());
        }
        if (matches(NativeImplementationEvidenceKind.SUPPORTED_DISPATCH,
                instruction, availableProgramMethods)
                && (instruction.opcode() != IrOpcode.CALL_DIRECT || !direct)) {
            evidence.dispatchKeys.add(instruction.symbol().orElseThrow());
        }
    }

    private void collectHelperEvidence(
            IrInstruction instruction,
            Set<String> availableProgramMethods,
            NativeImplementationEvidenceAccumulator evidence) {
        evidence.jdkScalarHelper |= matches(
                NativeImplementationEvidenceKind.JDK_SCALAR_HELPER,
                instruction,
                availableProgramMethods)
                || matches(NativeImplementationEvidenceKind.PURE_NATIVE_JDK_HELPER,
                        instruction, availableProgramMethods);
        evidence.unsafeHelper |= matches(NativeImplementationEvidenceKind.UNSAFE_HELPER,
                instruction, availableProgramMethods);
        evidence.varHandleHelper |= matches(NativeImplementationEvidenceKind.VAR_HANDLE_HELPER,
                instruction, availableProgramMethods);
        evidence.lambdaHelper |= matches(NativeImplementationEvidenceKind.LAMBDA_HELPER,
                instruction, availableProgramMethods);
        evidence.arithmeticExceptionHelper |= matches(
                NativeImplementationEvidenceKind.ARITHMETIC_EXCEPTION_HELPER,
                instruction, availableProgramMethods);
        evidence.jvmNumericHelper |= matches(NativeImplementationEvidenceKind.JVM_NUMERIC_HELPER,
                instruction, availableProgramMethods);
        evidence.arrayHelper |= matches(NativeImplementationEvidenceKind.ARRAY_HELPER,
                instruction, availableProgramMethods);
        evidence.arraycopyHelper |= matches(NativeImplementationEvidenceKind.ARRAYCOPY_HELPER,
                instruction, availableProgramMethods);
        evidence.monitorHelper |= matches(NativeImplementationEvidenceKind.MONITOR_HELPER,
                instruction, availableProgramMethods);
    }

    private void collectJniSurface(
            IrInstruction instruction,
            Set<String> directTargets,
            Set<String> availableProgramMethods,
            NativeImplementationEvidenceAccumulator evidence) {
        boolean direct = instruction.symbol()
                .filter(directTargets::contains)
                .isPresent();
        if (LlvmFunctionAbiPolicy.literalOrClassObjectRequiresJniEnv(
                        instruction.opcode())
                || matches(NativeImplementationEvidenceKind.ARITHMETIC_EXCEPTION_HELPER,
                        instruction, availableProgramMethods)
                || matches(NativeImplementationEvidenceKind.ARRAY_HELPER,
                        instruction, availableProgramMethods)
                || matches(NativeImplementationEvidenceKind.ALLOCATION_HELPER,
                        instruction, availableProgramMethods)
                || matches(NativeImplementationEvidenceKind.TYPE_HELPER,
                        instruction, availableProgramMethods)
                || matches(NativeImplementationEvidenceKind.MONITOR_HELPER,
                        instruction, availableProgramMethods)
                || matches(NativeImplementationEvidenceKind.CLASS_INIT_HELPER,
                        instruction, availableProgramMethods)
                || matches(NativeImplementationEvidenceKind.CONSTRUCTOR_CALL_HELPER,
                        instruction, availableProgramMethods)
                || matches(NativeImplementationEvidenceKind.DISPATCH_HELPER,
                        instruction, availableProgramMethods)
                || ((instruction.opcode() == IrOpcode.CALL_STATIC
                                || matches(NativeImplementationEvidenceKind.DIRECT_SPECIAL_CALL,
                                        instruction, availableProgramMethods))
                        && direct)
                || (instruction.opcode() == IrOpcode.CALL_STATIC
                        && instruction.symbol().filter(
                                evidence.staticCallKeys::contains).isPresent())
                || (instruction.opcode() == IrOpcode.CALL_RUNTIME_HELPER
                        && instruction.symbol()
                                .map(NativeRuntimeHelperSymbol::requiresJniEnv)
                                .orElse(false))) {
            evidence.passesJniEnv = true;
        }
    }

    private boolean matches(
            NativeImplementationEvidenceKind kind,
            IrInstruction instruction,
            Set<String> availableProgramMethods) {
        return matcher.matches(kind, instruction, availableProgramMethods);
    }

    private String fieldKey(IrInstruction instruction) {
        boolean nativeSlot = instruction.opcode() == IrOpcode.GET_NATIVE_STATIC
                || instruction.opcode() == IrOpcode.PUT_NATIVE_STATIC;
        return (nativeSlot ? "native-slot:" : "")
                + instruction.symbol().orElseThrow();
    }

}
