package xyz.melodysky.toolchain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import xyz.melodysky.backend.llvm.LlvmNameMangler;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrTerminatorKind;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;
import xyz.melodysky.ir.model.NativeFieldSlotRef;
import xyz.melodysky.analysis.field.NativeFieldStorageKind;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.MethodRewriteStrategy;
import xyz.melodysky.packaging.NativeRegistrationEntry;
import xyz.melodysky.packaging.NativeRegistrationPlan;
import xyz.melodysky.runtime.jni.JniTypeMapper;

public final class NativeImplementationPlanner {
    private static final Set<String> LLVM_SCALAR_DESCRIPTORS = Set.of("Z", "B", "C", "S", "I", "J", "F", "D");

    private final LlvmNameMangler llvmNameMangler;
    private final JniTypeMapper typeMapper = new JniTypeMapper();
    private final NativeExceptionFlowSupport exceptionFlowSupport = new NativeExceptionFlowSupport();

    public NativeImplementationPlanner() {
        this(new LlvmNameMangler());
    }

    public NativeImplementationPlanner(LlvmNameMangler llvmNameMangler) {
        this.llvmNameMangler = llvmNameMangler;
    }

    public NativeImplementationPlan plan(
            NativeRegistrationPlan registrationPlan,
            List<MethodRewriteDecision> decisions,
            Map<String, IrMethod> irMethods) {
        return plan(
                registrationPlan,
                decisions,
                irMethods,
                irMethods.keySet(),
                Set.of());
    }

    public NativeImplementationPlan plan(
            NativeRegistrationPlan registrationPlan,
            List<MethodRewriteDecision> decisions,
            Map<String, IrMethod> irMethods,
            Set<String> availableProgramMethodKeys) {
        return plan(
                registrationPlan,
                decisions,
                irMethods,
                availableProgramMethodKeys,
                Set.of());
    }

    public NativeImplementationPlan plan(
            NativeRegistrationPlan registrationPlan,
            List<MethodRewriteDecision> decisions,
            Map<String, IrMethod> irMethods,
            Set<String> availableProgramMethodKeys,
            Set<String> compilerInternalMethodKeys) {
        ArrayList<NativeMethodImplementation> implementations = new ArrayList<>();
        Map<String, NativeRegistrationEntry> entriesByMethod = new LinkedHashMap<>();
        Map<String, MethodRewriteDecision> decisionsByMethod = new LinkedHashMap<>();
        for (NativeRegistrationEntry entry : registrationPlan.entries()) {
            Optional<MethodRewriteDecision> maybeDecision = decisionFor(entry, decisions);
            if (maybeDecision.isEmpty()) {
                continue;
            }
            MethodRewriteDecision decision = maybeDecision.orElseThrow();
            if (decision.strategy() == MethodRewriteStrategy.NOT_APPLICABLE
                    || decision.strategy() == MethodRewriteStrategy.INTERFACE_METHOD_STUB) {
                continue;
            }
            entriesByMethod.put(decision.method().methodKey(), entry);
            decisionsByMethod.put(decision.method().methodKey(), decision);
        }
        LinkedHashSet<String> supportedLlvmMethods = new LinkedHashSet<>(compilerInternalMethodKeys);
        boolean changed;
        do {
            changed = false;
            for (Map.Entry<String, MethodRewriteDecision> entry : decisionsByMethod.entrySet()) {
                if (supportedLlvmMethods.contains(entry.getKey())) {
                    continue;
                }
                IrMethod irMethod = irMethods.get(entry.getKey());
                if (irMethod != null && supportsLlvmNativePath(
                        entry.getValue(),
                        irMethod,
                        sameOwnerDirectCallTargets(irMethod, supportedLlvmMethods),
                        availableProgramMethodKeys)) {
                    supportedLlvmMethods.add(entry.getKey());
                    changed = true;
                }
            }
        } while (changed);
        for (Map.Entry<String, MethodRewriteDecision> planned : decisionsByMethod.entrySet()) {
            NativeRegistrationEntry entry = entriesByMethod.get(planned.getKey());
            MethodRewriteDecision decision = planned.getValue();
            Optional<IrMethod> maybeIr = Optional.ofNullable(irMethods.get(decision.method().methodKey()));
            if (maybeIr.isPresent() && supportedLlvmMethods.contains(decision.method().methodKey())) {
                IrMethod irMethod = maybeIr.orElseThrow();
                List<String> fieldKeys = fieldKeys(irMethod);
                List<String> directCallTargets = directCallTargets(irMethod, supportedLlvmMethods);
                List<String> allocationKeys = allocationKeys(irMethod);
                List<String> typeCheckKeys = typeCheckKeys(irMethod);
                List<String> classObjectKeys = classObjectKeys(irMethod);
                List<String> runtimeMetadataKeys = runtimeMetadataKeys(irMethod);
                List<String> constructorCallKeys = constructorCallKeys(irMethod);
                List<String> staticCallKeys = staticCallKeys(
                        irMethod,
                        directCallTargets,
                        availableProgramMethodKeys);
                List<String> dispatchKeys = dispatchKeys(irMethod);
                List<String> stringHelperSymbols = stringHelperSymbols(irMethod);
                boolean jdkScalarHelper = containsJdkScalarHelper(irMethod);
                boolean allocationHelper = containsAllocationHelper(irMethod);
                boolean typeHelper = containsTypeHelper(irMethod);
                boolean constructorCallHelper = containsConstructorCallHelper(irMethod);
                boolean arithmeticExceptionHelper = containsArithmeticExceptionHelper(irMethod);
                boolean jvmNumericHelper = containsJvmNumericHelper(irMethod);
                boolean arrayHelper = containsArrayHelper(irMethod);
                boolean arraycopyHelper = containsArraycopyHelper(irMethod);
                boolean unsafeHelper = containsUnsafeHelper(irMethod);
                boolean varHandleHelper = containsVarHandleHelper(irMethod);
                boolean lambdaHelper = containsLambdaHelper(irMethod);
                boolean monitorHelper = containsMonitorHelper(irMethod);
                boolean exceptionHelper = containsThrowTerminator(irMethod);
                boolean runtimeMetadataHelper = containsRuntimeMetadataHelper(irMethod);
                boolean classInitHelper = containsClassInitHelper(irMethod);
                boolean passesJniEnv = needsJniEnv(irMethod, directCallTargets, staticCallKeys);
                boolean passesOwnerClass = needsOwnerClass(irMethod, directCallTargets);
                implementations.add(new NativeMethodImplementation(
                        entry,
                        decision,
                        NativeImplementationPath.LLVM_NATIVE_PATH,
                        Optional.of(llvmNameMangler.functionName(irMethod)),
                        reasonCode(
                                fieldKeys,
                                directCallTargets,
                                allocationKeys,
                                typeCheckKeys,
                                constructorCallKeys,
                                staticCallKeys,
                                dispatchKeys,
                                stringHelperSymbols,
                                jdkScalarHelper,
                                allocationHelper,
                                typeHelper,
                                constructorCallHelper,
                                arithmeticExceptionHelper,
                                jvmNumericHelper,
                                arrayHelper,
                                arraycopyHelper,
                                varHandleHelper,
                                lambdaHelper,
                                unsafeHelper,
                                monitorHelper,
                                exceptionHelper,
                                runtimeMetadataHelper,
                                decision.method().accessFlags().isSynchronized()),
                        passesJniEnv,
                        passesOwnerClass,
                        fieldKeys,
                        directCallTargets,
                        allocationKeys,
                        typeCheckKeys,
                        classObjectKeys,
                        runtimeMetadataKeys,
                        constructorCallKeys,
                        staticCallKeys,
                        dispatchKeys,
                        stringHelperSymbols,
                        Optional.of(irMethod)));
            } else if (maybeIr.isPresent() && supportsGenericBodyHelper(decision, maybeIr.orElseThrow())) {
                implementations.add(new NativeMethodImplementation(
                        entry,
                        decision,
                        NativeImplementationPath.TEMPLATE_JNI_PATH,
                        Optional.empty(),
                        decision.strategy() == MethodRewriteStrategy.CONSTRUCTOR_STUB
                                ? "GENERIC_CONSTRUCTOR_BODY_HELPER"
                                : "GENERIC_CLASS_INITIALIZER_BODY_HELPER",
                        false,
                        false,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        maybeIr));
            }
        }
        return new NativeImplementationPlan(implementations);
    }

    private boolean supportsGenericBodyHelper(MethodRewriteDecision decision, IrMethod method) {
        if (decision.strategy() != MethodRewriteStrategy.CONSTRUCTOR_STUB
                && decision.strategy() != MethodRewriteStrategy.CLASS_INITIALIZER_STUB) {
            return false;
        }
        var bodyBlocks = method.blocks();
        if (decision.strategy() == MethodRewriteStrategy.CLASS_INITIALIZER_STUB) {
            bodyBlocks = method.blocks().stream()
                    .filter(block -> !block.name().equals("$class_init_failed"))
                    .toList();
        }
        for (var block : bodyBlocks) {
            if (block.parameters().size() > 0 || !supportsGenericBodyTerminator(block.terminator())) {
                return false;
            }
            for (IrInstruction instruction : block.instructions()) {
                if (!supportsGenericBodyInstruction(instruction)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean supportsGenericBodyTerminator(IrTerminator terminator) {
        if (terminator.kind() == IrTerminatorKind.RETURN) {
            return terminator.value().isEmpty();
        }
        if (terminator.kind() == IrTerminatorKind.GOTO) {
            return terminator.target().isPresent() && terminator.targetArguments().isEmpty();
        }
        if (terminator.kind() == IrTerminatorKind.BRANCH) {
            return terminator.condition().map(IrValue::type).filter(type -> type == IrType.I1).isPresent()
                    && terminator.trueTarget().isPresent()
                    && terminator.falseTarget().isPresent()
                    && terminator.trueTargetArguments().isEmpty()
                    && terminator.falseTargetArguments().isEmpty();
        }
        return false;
    }

    private boolean supportsGenericBodyInstruction(IrInstruction instruction) {
        if (instruction.opcode() == IrOpcode.CALL_SPECIAL) {
            return instruction.symbol().map(symbol -> symbol.equals("java/lang/Object#<init>!()V")).orElse(false);
        }
        if (instruction.opcode() == IrOpcode.CLASS_INIT_BEGIN
                || instruction.opcode() == IrOpcode.CLASS_INIT_END
                || instruction.opcode() == IrOpcode.CLASS_INIT_HAPPENS_BEFORE
                || instruction.opcode() == IrOpcode.CLASS_OBJECT
                || instruction.opcode() == IrOpcode.FINAL_FIELD_PUBLICATION) {
            return true;
        }
        if (instruction.opcode() == IrOpcode.CONST_STRING || instruction.opcode() == IrOpcode.CONST_NULL) {
            return true;
        }
        if (isStringHelperInstruction(instruction)) {
            return supportsStringHelperInstruction(instruction);
        }
        if (instruction.opcode() == IrOpcode.NEW_ARRAY) {
            return instruction.symbol().map(symbol -> symbol.equals("primitiveArray:int")).orElse(false);
        }
        if (instruction.opcode() == IrOpcode.PUT_FIELD || instruction.opcode() == IrOpcode.PUT_STATIC) {
            return true;
        }
        if (instruction.result().map(IrValue::type).filter(type -> !isSupportedValueType(type)).isPresent()) {
            return false;
        }
        if (instruction.operands().stream().map(IrValue::type).anyMatch(type -> !isSupportedValueType(type))) {
            return false;
        }
        return switch (instruction.opcode()) {
            case CONST_INT, CONST_LONG, ADD_I32, SUB_I32, MUL_I32, ADD_I64, SUB_I64, MUL_I64 -> true;
            case CMP_EQ_I32, CMP_NE_I32, CMP_LT_I32, CMP_LE_I32, CMP_GT_I32, CMP_GE_I32,
                    CMP_EQ_REF, CMP_NE_REF -> true;
            default -> false;
        };
    }

    public boolean supportsLlvmNativePath(MethodRewriteDecision decision, IrMethod method) {
        return supportsLlvmNativePath(decision, method, Set.of(), Set.of());
    }

    private boolean supportsLlvmNativePath(
            MethodRewriteDecision decision,
            IrMethod method,
            Set<String> directCallTargets,
            Set<String> availableProgramMethods) {
        if (decision.method().name().equals("<init>")
                || decision.method().name().equals("<clinit>")
                || decision.method().accessFlags().isInterface()) {
            return false;
        }
        if (decision.method().accessFlags().isSynchronized()
                && !containsMonitorHelper(method)) {
            return false;
        }
        if (exceptionFlowSupport.hasUnsupportedProtectedJvmFlow(method)) {
            return false;
        }
        if (!supportsJvmHostedDescriptor(decision.method().descriptor())) {
            return false;
        }
        if (!isSupportedReturnType(method.returnType())) {
            return false;
        }
        if (!supportsParameters(decision, method)) {
            return false;
        }
        if (method.blocks().isEmpty()) {
            return false;
        }
        for (var block : method.blocks()) {
            if (block.parameters().stream().map(IrValue::type).anyMatch(type -> !isSupportedValueType(type))) {
                return false;
            }
            for (IrInstruction instruction : block.instructions()) {
                if (!instruction.exceptionSites().isEmpty()
                        && !isExceptionAwareHelperInstruction(instruction)) {
                    return false;
                }
                if (!supportsLlvmInstruction(instruction, directCallTargets, availableProgramMethods)) {
                    return false;
                }
            }
            if (!supportsTerminator(block.terminator())) {
                return false;
            }
        }
        return true;
    }

    private Optional<MethodRewriteDecision> decisionFor(
            NativeRegistrationEntry entry,
            List<MethodRewriteDecision> decisions) {
        return decisions.stream()
                .filter(decision -> decision.registrationOwner().equals(entry.registrationOwner()))
                .filter(decision -> decision.generatedHelperName().orElse(decision.method().name()).equals(entry.methodName()))
                .filter(decision -> registeredDescriptor(decision).equals(entry.descriptor()))
                .findFirst();
    }

    private String registeredDescriptor(MethodRewriteDecision decision) {
        if (decision.strategy() == MethodRewriteStrategy.CONSTRUCTOR_STUB) {
            String descriptor = decision.method().descriptor();
            int close = descriptor.indexOf(')');
            return "(L" + decision.method().owner() + ";" + descriptor.substring(1, close) + ")V";
        }
        if (decision.strategy() == MethodRewriteStrategy.CLASS_INITIALIZER_STUB) {
            return "()V";
        }
        return decision.method().descriptor();
    }

    private boolean supportsJvmHostedDescriptor(String descriptor) {
        if (!typeMapper.parameterDescriptors(descriptor).stream().allMatch(this::isSupportedDescriptor)) {
            return false;
        }
        String returnDescriptor = typeMapper.returnDescriptor(descriptor);
        return returnDescriptor.equals("V") || isSupportedDescriptor(returnDescriptor);
    }

    private boolean isSupportedDescriptor(String descriptor) {
        return LLVM_SCALAR_DESCRIPTORS.contains(descriptor)
                || descriptor.equals("[Z")
                || descriptor.equals("[B")
                || descriptor.equals("[S")
                || descriptor.equals("[C")
                || descriptor.equals("[I")
                || descriptor.equals("[J")
                || descriptor.equals("[F")
                || descriptor.equals("[D")
                || descriptor.startsWith("[L")
                || descriptor.startsWith("L");
    }

    private boolean supportsLlvmInstruction(
            IrInstruction instruction,
            Set<String> directCallTargets,
            Set<String> availableProgramMethods) {
        if (isThrowableSemanticUnsupportedCall(instruction)) {
            return false;
        }
        if (isFieldAccess(instruction.opcode())) {
            return supportsFieldInstruction(instruction);
        }
        if (isArrayHelperInstruction(instruction)) {
            return supportsArrayInstruction(instruction);
        }
        if (isAllocationHelperInstruction(instruction)) {
            return supportsAllocationInstruction(instruction);
        }
        if (isClassInitGuardInstruction(instruction)) {
            return supportsClassInitGuardInstruction(instruction);
        }
        if (isTypeHelperInstruction(instruction)) {
            return supportsTypeInstruction(instruction);
        }
        if (isConstructorCallHelperInstruction(instruction)) {
            return supportsConstructorCallInstruction(instruction);
        }
        if (isStringHelperInstruction(instruction)) {
            return supportsStringHelperInstruction(instruction);
        }
        if (isStringBuilderHelperInstruction(instruction)) {
            return supportsStringBuilderHelperInstruction(instruction);
        }
        if (isArraycopyHelperInstruction(instruction)) {
            return supportsArraycopyHelperInstruction(instruction);
        }
        if (isRuntimeMetadataHelperInstruction(instruction)) {
            return supportsRuntimeMetadataHelperInstruction(instruction);
        }
        if (isVarHandleHelperInstruction(instruction)) {
            return supportsVarHandleHelperInstruction(instruction);
        }
        if (isLambdaHelperInstruction(instruction)) {
            return supportsLambdaHelperInstruction(instruction);
        }
        if (isUnsafeHelperInstruction(instruction)) {
            return supportsUnsafeHelperInstruction(instruction);
        }
        if (isJdkScalarHelperInstruction(instruction)) {
            return supportsJdkScalarHelperInstruction(instruction);
        }
        if (isDispatchHelperInstruction(instruction)) {
            return supportsDispatchHelperInstruction(instruction);
        }
        if (isMonitorHelperInstruction(instruction)) {
            return supportsMonitorHelperInstruction(instruction);
        }
        if (instruction.opcode() == IrOpcode.CONST_NULL) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.REFERENCE).isPresent()
                    && instruction.operands().isEmpty();
        }
        if (isSymbolicConstantInstruction(instruction)) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.REFERENCE).isPresent()
                    && instruction.operands().isEmpty()
                    && instruction.symbol().isPresent();
        }
        if (instruction.opcode() == IrOpcode.CALL_STATIC) {
            return instruction.symbol().filter(directCallTargets::contains).isPresent()
                    && instruction.result().map(IrValue::type).filter(type -> !isSupportedValueType(type)).isEmpty()
                    && instruction.operands().stream().map(IrValue::type).allMatch(this::isSupportedValueType)
                    || supportsStaticCallBridgeInstruction(instruction, availableProgramMethods);
        }
        if (isDirectSpecialCallInstruction(instruction)) {
            return instruction.symbol().filter(directCallTargets::contains).isPresent()
                    && instruction.result().map(IrValue::type).filter(type -> !isSupportedValueType(type)).isEmpty()
                    && instruction.operands().stream().map(IrValue::type).allMatch(this::isSupportedValueType);
        }
        if (instruction.opcode() == IrOpcode.CMP_EQ_REF || instruction.opcode() == IrOpcode.CMP_NE_REF) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.I1).isPresent()
                    && instruction.operands().stream().map(IrValue::type).allMatch(type -> type == IrType.REFERENCE);
        }
        if (isArithmeticExceptionHelperInstruction(instruction)) {
            return instruction.result().map(IrValue::type).filter(this::isPrimitiveScalar).isPresent()
                    && instruction.operands().stream().map(IrValue::type).allMatch(this::isPrimitiveScalar);
        }
        if (isJvmNumericHelperInstruction(instruction)) {
            return instruction.result().map(IrValue::type).filter(this::isPrimitiveScalar).isPresent()
                    && instruction.operands().stream().map(IrValue::type).allMatch(this::isPrimitiveScalar);
        }
        if (isMemoryFenceInstruction(instruction)) {
            return instruction.result().isEmpty()
                    && instruction.operands().stream().map(IrValue::type).allMatch(this::isSupportedValueType);
        }
        if (instruction.result().map(IrValue::type).filter(type -> !isPrimitiveScalar(type)).isPresent()) {
            return false;
        }
        if (instruction.operands().stream().map(IrValue::type).anyMatch(type -> !isPrimitiveScalar(type))) {
            return false;
        }
        return switch (instruction.opcode()) {
            case CONST_INT, CONST_LONG, CONST_FLOAT, CONST_DOUBLE,
                    ADD_I32, SUB_I32, MUL_I32,
                    ADD_I64, SUB_I64, MUL_I64,
                    SHL_I32, SHR_I32, USHR_I32,
                    AND_I32, OR_I32, XOR_I32,
                    SHL_I64, SHR_I64, USHR_I64,
                    AND_I64, OR_I64, XOR_I64,
                    BITCAST_I32_TO_F32, BITCAST_I64_TO_F64,
                    ADD_F32, SUB_F32, MUL_F32, DIV_F32, REM_F32, NEG_F32,
                    ADD_F64, SUB_F64, MUL_F64, DIV_F64, REM_F64, NEG_F64,
                    NEG_I32, NEG_I64,
                    CMP_EQ_I32, CMP_NE_I32, CMP_LT_I32, CMP_LE_I32, CMP_GT_I32, CMP_GE_I32,
                    I2L, I2F, I2D, L2I, L2F, L2D, F2D, D2F -> true;
            default -> false;
        };
    }

    private boolean supportsTerminator(IrTerminator terminator) {
        if (terminator.kind() == IrTerminatorKind.THROW) {
            return terminator.value().map(IrValue::type).filter(type -> type == IrType.REFERENCE).isPresent();
        }
        if (terminator.value().map(IrValue::type).filter(type -> !isSupportedValueType(type)).isPresent()) {
            return false;
        }
        if (terminator.condition().map(IrValue::type).filter(type -> type != IrType.I1).isPresent()) {
            return false;
        }
        return primitiveArguments(terminator.targetArguments())
                && primitiveArguments(terminator.trueTargetArguments())
                && primitiveArguments(terminator.falseTargetArguments())
                && primitiveArguments(terminator.defaultTargetArguments())
                && terminator.switchCases().stream().allMatch(switchCase -> primitiveArguments(switchCase.arguments()));
    }

    private boolean primitiveArguments(List<IrValue> values) {
        return values.stream().map(IrValue::type).allMatch(this::isSupportedValueType);
    }

    private boolean isSupportedReturnType(IrType type) {
        return type == IrType.VOID || isSupportedValueType(type);
    }

    private boolean isSupportedValueType(IrType type) {
        return isPrimitiveScalar(type) || type == IrType.REFERENCE;
    }

    private boolean isPrimitiveScalar(IrType type) {
        return type == IrType.I1
                || type == IrType.I32
                || type == IrType.I64
                || type == IrType.F32
                || type == IrType.F64;
    }

    private boolean supportsParameters(MethodRewriteDecision decision, IrMethod method) {
        int start = decision.method().accessFlags().isStatic() ? 0 : 1;
        if (!decision.method().accessFlags().isStatic()) {
            if (method.parameters().isEmpty() || method.parameters().get(0).type() != IrType.REFERENCE) {
                return false;
            }
        }
        return method.parameters().stream().skip(start).map(IrValue::type).allMatch(this::isSupportedValueType);
    }

    private boolean supportsFieldInstruction(IrInstruction instruction) {
        if (instruction.opcode() == IrOpcode.GET_STATIC
                || instruction.opcode() == IrOpcode.GET_NATIVE_STATIC) {
            return instruction.operands().isEmpty()
                    && instruction.result().map(IrValue::type).filter(type ->
                            instruction.opcode() == IrOpcode.GET_NATIVE_STATIC
                                    ? nativeFieldKindMatches(instruction, type)
                                    : isSupportedFieldType(type)).isPresent();
        }
        if (instruction.opcode() == IrOpcode.PUT_STATIC
                || instruction.opcode() == IrOpcode.PUT_NATIVE_STATIC) {
            return instruction.operands().size() == 1
                    && (instruction.opcode() == IrOpcode.PUT_NATIVE_STATIC
                            ? nativeFieldKindMatches(
                                    instruction,
                                    instruction.operands().get(0).type())
                            : isSupportedFieldType(instruction.operands().get(0).type()));
        }
        if (instruction.opcode() == IrOpcode.GET_FIELD) {
            return instruction.operands().size() == 1
                    && instruction.operands().get(0).type() == IrType.REFERENCE
                    && instruction.result().map(IrValue::type).filter(this::isSupportedFieldType).isPresent();
        }
        if (instruction.opcode() == IrOpcode.PUT_FIELD) {
            return instruction.operands().size() == 2
                    && instruction.operands().get(0).type() == IrType.REFERENCE
                    && isSupportedFieldType(instruction.operands().get(1).type());
        }
        return false;
    }

    private boolean isSupportedFieldType(IrType type) {
        return type == IrType.I32
                || type == IrType.I64
                || type == IrType.F32
                || type == IrType.F64
                || type == IrType.REFERENCE;
    }

    private boolean nativeFieldKindMatches(IrInstruction instruction, IrType type) {
        return instruction.symbol()
                .flatMap(NativeFieldSlotRef::parse)
                .map(slot -> nativeFieldIrType(slot.kind()) == type)
                .orElse(false);
    }

    private IrType nativeFieldIrType(NativeFieldStorageKind kind) {
        return switch (kind) {
            case BOOLEAN, BYTE, SHORT, CHAR, INT -> IrType.I32;
            case LONG -> IrType.I64;
            case FLOAT -> IrType.F32;
            case DOUBLE -> IrType.F64;
            case REFERENCE -> IrType.REFERENCE;
        };
    }

    private boolean supportsArrayInstruction(IrInstruction instruction) {
        if (instruction.opcode() == IrOpcode.ARRAY_LENGTH) {
            return instruction.operands().size() == 1
                    && instruction.operands().get(0).type() == IrType.REFERENCE
                    && instruction.result().map(IrValue::type).filter(type -> type == IrType.I32).isPresent();
        }
        if (instruction.opcode() == IrOpcode.ARRAY_LOAD_I32) {
            return instruction.operands().size() == 2
                    && instruction.operands().get(0).type() == IrType.REFERENCE
                    && instruction.operands().get(1).type() == IrType.I32
                    && instruction.result().map(IrValue::type).filter(type -> type == IrType.I32).isPresent()
                    && instruction.symbol()
                            .map(symbol -> symbol.equals("int")
                                    || symbol.equals("byteOrBoolean")
                                    || symbol.equals("short")
                                    || symbol.equals("char"))
                            .orElse(false);
        }
        if (instruction.opcode() == IrOpcode.ARRAY_LOAD_I64) {
            return instruction.operands().size() == 2
                    && instruction.operands().get(0).type() == IrType.REFERENCE
                    && instruction.operands().get(1).type() == IrType.I32
                    && instruction.result().map(IrValue::type).filter(type -> type == IrType.I64).isPresent()
                    && instruction.symbol().map(symbol -> symbol.equals("long")).orElse(false);
        }
        if (instruction.opcode() == IrOpcode.ARRAY_LOAD_F32) {
            return instruction.operands().size() == 2
                    && instruction.operands().get(0).type() == IrType.REFERENCE
                    && instruction.operands().get(1).type() == IrType.I32
                    && instruction.result().map(IrValue::type).filter(type -> type == IrType.F32).isPresent()
                    && instruction.symbol().map(symbol -> symbol.equals("float")).orElse(false);
        }
        if (instruction.opcode() == IrOpcode.ARRAY_LOAD_F64) {
            return instruction.operands().size() == 2
                    && instruction.operands().get(0).type() == IrType.REFERENCE
                    && instruction.operands().get(1).type() == IrType.I32
                    && instruction.result().map(IrValue::type).filter(type -> type == IrType.F64).isPresent()
                    && instruction.symbol().map(symbol -> symbol.equals("double")).orElse(false);
        }
        if (instruction.opcode() == IrOpcode.ARRAY_STORE_I32) {
            return instruction.operands().size() == 3
                    && instruction.operands().get(0).type() == IrType.REFERENCE
                    && instruction.operands().get(1).type() == IrType.I32
                    && instruction.operands().get(2).type() == IrType.I32
                    && instruction.result().isEmpty()
                    && instruction.symbol()
                            .map(symbol -> symbol.equals("int")
                                    || symbol.equals("byteOrBoolean")
                                    || symbol.equals("short")
                                    || symbol.equals("char"))
                            .orElse(false);
        }
        if (instruction.opcode() == IrOpcode.ARRAY_STORE_I64) {
            return instruction.operands().size() == 3
                    && instruction.operands().get(0).type() == IrType.REFERENCE
                    && instruction.operands().get(1).type() == IrType.I32
                    && instruction.operands().get(2).type() == IrType.I64
                    && instruction.result().isEmpty()
                    && instruction.symbol().map(symbol -> symbol.equals("long")).orElse(false);
        }
        if (instruction.opcode() == IrOpcode.ARRAY_STORE_F32) {
            return instruction.operands().size() == 3
                    && instruction.operands().get(0).type() == IrType.REFERENCE
                    && instruction.operands().get(1).type() == IrType.I32
                    && instruction.operands().get(2).type() == IrType.F32
                    && instruction.result().isEmpty()
                    && instruction.symbol().map(symbol -> symbol.equals("float")).orElse(false);
        }
        if (instruction.opcode() == IrOpcode.ARRAY_STORE_F64) {
            return instruction.operands().size() == 3
                    && instruction.operands().get(0).type() == IrType.REFERENCE
                    && instruction.operands().get(1).type() == IrType.I32
                    && instruction.operands().get(2).type() == IrType.F64
                    && instruction.result().isEmpty()
                    && instruction.symbol().map(symbol -> symbol.equals("double")).orElse(false);
        }
        if (instruction.opcode() == IrOpcode.ARRAY_LOAD_REF) {
            return instruction.operands().size() == 2
                    && instruction.operands().get(0).type() == IrType.REFERENCE
                    && instruction.operands().get(1).type() == IrType.I32
                    && instruction.result().map(IrValue::type).filter(type -> type == IrType.REFERENCE).isPresent();
        }
        if (instruction.opcode() == IrOpcode.ARRAY_STORE_REF) {
            return instruction.operands().size() == 3
                    && instruction.operands().get(0).type() == IrType.REFERENCE
                    && instruction.operands().get(1).type() == IrType.I32
                    && instruction.operands().get(2).type() == IrType.REFERENCE
                    && instruction.result().isEmpty();
        }
        return false;
    }

    private boolean isFieldAccess(IrOpcode opcode) {
        return opcode == IrOpcode.GET_STATIC
                || opcode == IrOpcode.PUT_STATIC
                || opcode == IrOpcode.GET_NATIVE_STATIC
                || opcode == IrOpcode.PUT_NATIVE_STATIC
                || opcode == IrOpcode.GET_FIELD
                || opcode == IrOpcode.PUT_FIELD;
    }

    private boolean isArithmeticExceptionHelperInstruction(IrInstruction instruction) {
        return instruction.opcode() == IrOpcode.DIV_I32
                || instruction.opcode() == IrOpcode.REM_I32
                || instruction.opcode() == IrOpcode.DIV_I64
                || instruction.opcode() == IrOpcode.REM_I64;
    }

    private boolean isJvmNumericHelperInstruction(IrInstruction instruction) {
        return instruction.opcode() == IrOpcode.I2B
                || instruction.opcode() == IrOpcode.I2C
                || instruction.opcode() == IrOpcode.I2S
                || instruction.opcode() == IrOpcode.F2I
                || instruction.opcode() == IrOpcode.F2L
                || instruction.opcode() == IrOpcode.D2I
                || instruction.opcode() == IrOpcode.D2L
                || instruction.opcode() == IrOpcode.LCMP
                || instruction.opcode() == IrOpcode.FCMPL
                || instruction.opcode() == IrOpcode.FCMPG
                || instruction.opcode() == IrOpcode.DCMPL
                || instruction.opcode() == IrOpcode.DCMPG;
    }

    private boolean isMemoryFenceInstruction(IrInstruction instruction) {
        return instruction.opcode() == IrOpcode.VOLATILE_READ_BARRIER
                || instruction.opcode() == IrOpcode.VOLATILE_WRITE_BARRIER
                || instruction.opcode() == IrOpcode.FINAL_FIELD_PUBLICATION
                || instruction.opcode() == IrOpcode.MONITOR_HAPPENS_BEFORE
                || instruction.opcode() == IrOpcode.CLASS_INIT_HAPPENS_BEFORE;
    }

    private boolean isArrayHelperInstruction(IrInstruction instruction) {
        return instruction.opcode() == IrOpcode.ARRAY_LENGTH
                || instruction.opcode() == IrOpcode.ARRAY_LOAD_I32
                || instruction.opcode() == IrOpcode.ARRAY_LOAD_I64
                || instruction.opcode() == IrOpcode.ARRAY_LOAD_F32
                || instruction.opcode() == IrOpcode.ARRAY_LOAD_F64
                || instruction.opcode() == IrOpcode.ARRAY_STORE_I32
                || instruction.opcode() == IrOpcode.ARRAY_STORE_I64
                || instruction.opcode() == IrOpcode.ARRAY_STORE_F32
                || instruction.opcode() == IrOpcode.ARRAY_STORE_F64
                || instruction.opcode() == IrOpcode.ARRAY_LOAD_REF
                || instruction.opcode() == IrOpcode.ARRAY_STORE_REF;
    }

    private boolean isExceptionAwareHelperInstruction(IrInstruction instruction) {
        return isArithmeticExceptionHelperInstruction(instruction)
                || isArrayHelperInstruction(instruction)
                || isAllocationHelperInstruction(instruction)
                || isClassInitGuardInstruction(instruction)
                || isTypeHelperInstruction(instruction)
                || isConstructorCallHelperInstruction(instruction)
                || isStringHelperInstruction(instruction)
                || isStringBuilderHelperInstruction(instruction)
                || isArraycopyHelperInstruction(instruction)
                || isRuntimeMetadataHelperInstruction(instruction)
                || isVarHandleHelperInstruction(instruction)
                || isUnsafeHelperInstruction(instruction)
                || isJdkScalarHelperInstruction(instruction)
                || isMonitorHelperInstruction(instruction)
                || isDispatchHelperInstruction(instruction)
                || isFieldAccess(instruction.opcode());
    }

    private boolean isAllocationHelperInstruction(IrInstruction instruction) {
        return instruction.opcode() == IrOpcode.NEW_ARRAY
                || instruction.opcode() == IrOpcode.NEW_OBJECT
                || instruction.opcode() == IrOpcode.NEW_MULTI_ARRAY;
    }

    private boolean isSymbolicConstantInstruction(IrInstruction instruction) {
        return instruction.opcode() == IrOpcode.CONST_STRING
                || instruction.opcode() == IrOpcode.CONST_CLASS;
    }

    private boolean supportsAllocationInstruction(IrInstruction instruction) {
        if (instruction.opcode() == IrOpcode.NEW_MULTI_ARRAY) {
            return false;
        }
        if (instruction.opcode() == IrOpcode.NEW_OBJECT) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.REFERENCE).isPresent()
                    && instruction.operands().isEmpty()
                    && instruction.symbol().map(symbol -> symbol.startsWith("object:")).orElse(false);
        }
        if (instruction.opcode() != IrOpcode.NEW_ARRAY) {
            return false;
        }
        return instruction.result().map(IrValue::type).filter(type -> type == IrType.REFERENCE).isPresent()
                && instruction.operands().size() == 1
                && instruction.operands().get(0).type() == IrType.I32
                && instruction.symbol()
                        .map(symbol -> isSupportedPrimitiveArraySymbol(symbol) || isSupportedReferenceArraySymbol(symbol))
                        .orElse(false);
    }

    private boolean isSupportedPrimitiveArraySymbol(String symbol) {
        return symbol.equals("primitiveArray:boolean")
                || symbol.equals("primitiveArray:byte")
                || symbol.equals("primitiveArray:short")
                || symbol.equals("primitiveArray:char")
                || symbol.equals("primitiveArray:int")
                || symbol.equals("primitiveArray:long")
                || symbol.equals("primitiveArray:float")
                || symbol.equals("primitiveArray:double");
    }

    private boolean isSupportedReferenceArraySymbol(String symbol) {
        if (!symbol.startsWith("referenceArray:")) {
            return false;
        }
        String component = symbol.substring("referenceArray:".length());
        return !component.isEmpty()
                && (!component.startsWith("[") || isValidArrayDescriptor(component));
    }

    private boolean isValidArrayDescriptor(String descriptor) {
        int componentIndex = 0;
        while (componentIndex < descriptor.length()
                && descriptor.charAt(componentIndex) == '[') {
            componentIndex++;
        }
        if (componentIndex == 0 || componentIndex >= descriptor.length()) {
            return false;
        }
        char componentType = descriptor.charAt(componentIndex);
        if ("ZBSCIJFD".indexOf(componentType) >= 0) {
            return componentIndex + 1 == descriptor.length();
        }
        return componentType == 'L'
                && componentIndex + 2 < descriptor.length()
                && descriptor.charAt(descriptor.length() - 1) == ';'
                && descriptor.indexOf(';', componentIndex + 1) == descriptor.length() - 1;
    }

    private boolean isClassInitGuardInstruction(IrInstruction instruction) {
        return instruction.opcode() == IrOpcode.CLASS_OBJECT
                || instruction.opcode() == IrOpcode.CLASS_INIT_GUARD
                || instruction.opcode() == IrOpcode.CLASS_INIT_HAPPENS_BEFORE;
    }

    private boolean supportsClassInitGuardInstruction(IrInstruction instruction) {
        if (instruction.opcode() == IrOpcode.CLASS_OBJECT) {
            return instruction.operands().size() == 1
                    && instruction.operands().get(0).type() == IrType.I64
                    && instruction.result().map(IrValue::type).filter(type -> type == IrType.REFERENCE).isPresent();
        }
        if (instruction.opcode() == IrOpcode.CLASS_INIT_GUARD) {
            return instruction.operands().size() == 1
                    && instruction.operands().get(0).type() == IrType.REFERENCE
                    && instruction.result().isEmpty();
        }
        return instruction.opcode() == IrOpcode.CLASS_INIT_HAPPENS_BEFORE
                && instruction.operands().stream().map(IrValue::type).allMatch(type -> type == IrType.REFERENCE)
                && instruction.result().isEmpty();
    }

    private boolean isTypeHelperInstruction(IrInstruction instruction) {
        return instruction.opcode() == IrOpcode.CHECKCAST || instruction.opcode() == IrOpcode.INSTANCEOF;
    }

    private boolean supportsTypeInstruction(IrInstruction instruction) {
        if (instruction.opcode() == IrOpcode.CHECKCAST) {
            return instruction.operands().size() == 1
                    && instruction.operands().get(0).type() == IrType.REFERENCE
                    && instruction.result().map(IrValue::type).filter(type -> type == IrType.REFERENCE).isPresent()
                    && instruction.symbol().map(symbol -> symbol.startsWith("checkcast:")).orElse(false);
        }
        if (instruction.opcode() == IrOpcode.INSTANCEOF) {
            return instruction.operands().size() == 1
                    && instruction.operands().get(0).type() == IrType.REFERENCE
                    && instruction.result().map(IrValue::type).filter(type -> type == IrType.I32).isPresent()
                    && instruction.symbol().map(symbol -> symbol.startsWith("instanceof:")).orElse(false);
        }
        return false;
    }

    private boolean isConstructorCallHelperInstruction(IrInstruction instruction) {
        return instruction.opcode() == IrOpcode.CALL_SPECIAL
                && instruction.symbol().map(symbol -> symbol.contains("#<init>!")).orElse(false);
    }

    private boolean isDirectSpecialCallInstruction(IrInstruction instruction) {
        return instruction.opcode() == IrOpcode.CALL_SPECIAL
                && instruction.symbol().map(symbol -> !symbol.contains("#<init>!")).orElse(false);
    }

    private boolean supportsConstructorCallInstruction(IrInstruction instruction) {
        if (!isConstructorCallHelperInstruction(instruction)
                || instruction.result().isPresent()
                || instruction.operands().isEmpty()
                || instruction.operands().get(0).type() != IrType.REFERENCE) {
            return false;
        }
        String descriptor = constructorDescriptor(instruction.symbol().orElseThrow());
        return typeMapper.returnDescriptor(descriptor).equals("V")
                && operandsMatchDescriptor(descriptor, instruction.operands().subList(1, instruction.operands().size()));
    }

    private boolean supportsStaticCallBridgeInstruction(IrInstruction instruction, Set<String> availableProgramMethods) {
        String descriptor = instruction.symbol().flatMap(this::methodDescriptor).orElse("");
        if (!descriptor.startsWith("(") || !supportsJvmHostedDescriptor(descriptor)) {
            return false;
        }
        String returnDescriptor = typeMapper.returnDescriptor(descriptor);
        if (returnDescriptor.equals("V")) {
            if (instruction.result().isPresent()) {
                return false;
            }
        } else if (instruction.result().isEmpty()
                || descriptorType(returnDescriptor) != instruction.result().orElseThrow().type()) {
            return false;
        }
        return operandsMatchDescriptor(descriptor, instruction.operands());
    }

    private boolean operandsMatchDescriptor(String descriptor, List<IrValue> operands) {
        List<String> parameterDescriptors = typeMapper.parameterDescriptors(descriptor);
        if (parameterDescriptors.size() != operands.size()) {
            return false;
        }
        for (int index = 0; index < parameterDescriptors.size(); index++) {
            if (descriptorType(parameterDescriptors.get(index)) != operands.get(index).type()) {
                return false;
            }
        }
        return true;
    }

    private IrType descriptorType(String descriptor) {
        return switch (descriptor.charAt(0)) {
            case 'Z', 'B', 'C', 'S', 'I' -> IrType.I32;
            case 'J' -> IrType.I64;
            case 'F' -> IrType.F32;
            case 'D' -> IrType.F64;
            case '[', 'L' -> IrType.REFERENCE;
            default -> throw new IllegalArgumentException("unsupported descriptor type: " + descriptor);
        };
    }

    private boolean isStringHelperInstruction(IrInstruction instruction) {
        return instruction.opcode() == IrOpcode.CALL_RUNTIME_HELPER
                && instruction.symbol()
                        .map(symbol -> runtimeHelperBaseSymbol(symbol).equals("j2ll_rt_string_length")
                                || runtimeHelperBaseSymbol(symbol).equals("j2ll_rt_string_is_empty")
                                || runtimeHelperBaseSymbol(symbol).equals("j2ll_rt_string_char_at")
                                || runtimeHelperBaseSymbol(symbol).equals("j2ll_rt_string_equals")
                                || runtimeHelperBaseSymbol(symbol).equals("j2ll_rt_string_starts_with")
                                || runtimeHelperBaseSymbol(symbol).equals("j2ll_rt_string_ends_with")
                                || runtimeHelperBaseSymbol(symbol).equals("j2ll_rt_string_substring")
                                || runtimeHelperBaseSymbol(symbol).equals("j2ll_rt_string_substring_range")
                                || runtimeHelperBaseSymbol(symbol).equals("j2ll_rt_string_constant"))
                        .orElse(false);
    }

    private boolean supportsStringHelperInstruction(IrInstruction instruction) {
        String symbol = runtimeHelperBaseSymbol(instruction.symbol().orElseThrow());
        if (symbol.equals("j2ll_rt_string_constant")) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.REFERENCE).isPresent()
                    && instruction.operands().size() == 1
                    && instruction.operands().get(0).type() == IrType.I64;
        }
        if (symbol.equals("j2ll_rt_string_length") || symbol.equals("j2ll_rt_string_is_empty")) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.I32).isPresent()
                    && instruction.operands().size() == 1
                    && instruction.operands().get(0).type() == IrType.REFERENCE;
        }
        if (symbol.equals("j2ll_rt_string_char_at")) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.I32).isPresent()
                    && instruction.operands().size() == 2
                    && instruction.operands().get(0).type() == IrType.REFERENCE
                    && instruction.operands().get(1).type() == IrType.I32;
        }
        if (symbol.equals("j2ll_rt_string_equals")
                || symbol.equals("j2ll_rt_string_starts_with")
                || symbol.equals("j2ll_rt_string_ends_with")) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.I32).isPresent()
                    && instruction.operands().size() == 2
                    && instruction.operands().stream().map(IrValue::type).allMatch(type -> type == IrType.REFERENCE);
        }
        if (symbol.equals("j2ll_rt_string_substring")) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.REFERENCE).isPresent()
                    && instruction.operands().size() == 2
                    && instruction.operands().get(0).type() == IrType.REFERENCE
                    && instruction.operands().get(1).type() == IrType.I32;
        }
        if (symbol.equals("j2ll_rt_string_substring_range")) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.REFERENCE).isPresent()
                    && instruction.operands().size() == 3
                    && instruction.operands().get(0).type() == IrType.REFERENCE
                    && instruction.operands().get(1).type() == IrType.I32
                    && instruction.operands().get(2).type() == IrType.I32;
        }
        return false;
    }

    private boolean isStringBuilderHelperInstruction(IrInstruction instruction) {
        return instruction.opcode() == IrOpcode.CALL_RUNTIME_HELPER
                && instruction.symbol()
                        .map(symbol -> runtimeHelperBaseSymbol(symbol).startsWith("j2ll_rt_string_builder_"))
                        .orElse(false);
    }

    private boolean supportsStringBuilderHelperInstruction(IrInstruction instruction) {
        String symbol = runtimeHelperBaseSymbol(instruction.symbol().orElseThrow());
        if (symbol.equals("j2ll_rt_string_builder_new")) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.REFERENCE).isPresent()
                    && instruction.operands().isEmpty();
        }
        if (symbol.equals("j2ll_rt_string_builder_init")) {
            return instruction.result().isEmpty()
                    && instruction.operands().size() == 1
                    && instruction.operands().get(0).type() == IrType.REFERENCE;
        }
        if (symbol.equals("j2ll_rt_string_builder_to_string")) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.REFERENCE).isPresent()
                    && instruction.operands().size() == 1
                    && instruction.operands().get(0).type() == IrType.REFERENCE;
        }
        if (symbol.equals("j2ll_rt_string_builder_append_ref")) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.REFERENCE).isPresent()
                    && instruction.operands().size() == 2
                    && instruction.operands().stream().map(IrValue::type).allMatch(type -> type == IrType.REFERENCE);
        }
        if (symbol.equals("j2ll_rt_string_builder_append_i32")) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.REFERENCE).isPresent()
                    && instruction.operands().size() == 2
                    && instruction.operands().get(0).type() == IrType.REFERENCE
                    && instruction.operands().get(1).type() == IrType.I32;
        }
        if (symbol.equals("j2ll_rt_string_builder_append_i64")) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.REFERENCE).isPresent()
                    && instruction.operands().size() == 2
                    && instruction.operands().get(0).type() == IrType.REFERENCE
                    && instruction.operands().get(1).type() == IrType.I64;
        }
        if (symbol.equals("j2ll_rt_string_builder_append_f32")) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.REFERENCE).isPresent()
                    && instruction.operands().size() == 2
                    && instruction.operands().get(0).type() == IrType.REFERENCE
                    && instruction.operands().get(1).type() == IrType.F32;
        }
        if (symbol.equals("j2ll_rt_string_builder_append_f64")) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.REFERENCE).isPresent()
                    && instruction.operands().size() == 2
                    && instruction.operands().get(0).type() == IrType.REFERENCE
                    && instruction.operands().get(1).type() == IrType.F64;
        }
        return false;
    }

    private boolean isArraycopyHelperInstruction(IrInstruction instruction) {
        return instruction.opcode() == IrOpcode.CALL_RUNTIME_HELPER
                && instruction.symbol()
                        .map(symbol -> runtimeHelperBaseSymbol(symbol).equals("j2ll_rt_system_arraycopy"))
                        .orElse(false);
    }

    private boolean supportsArraycopyHelperInstruction(IrInstruction instruction) {
        return instruction.result().isEmpty()
                && instruction.operands().size() == 5
                && instruction.operands().get(0).type() == IrType.REFERENCE
                && instruction.operands().get(1).type() == IrType.I32
                && instruction.operands().get(2).type() == IrType.REFERENCE
                && instruction.operands().get(3).type() == IrType.I32
                && instruction.operands().get(4).type() == IrType.I32;
    }

    private boolean isJdkScalarHelperInstruction(IrInstruction instruction) {
        return instruction.opcode() == IrOpcode.CALL_RUNTIME_HELPER
                && instruction.symbol()
                        .map(symbol -> runtimeHelperBaseSymbol(symbol).equals("j2ll_rt_math_abs_i32")
                                || runtimeHelperBaseSymbol(symbol).equals("j2ll_rt_math_abs_i64")
                                || runtimeHelperBaseSymbol(symbol).equals("j2ll_rt_math_abs_f32")
                                || runtimeHelperBaseSymbol(symbol).equals("j2ll_rt_math_abs_f64")
                                || runtimeHelperBaseSymbol(symbol).equals("j2ll_rt_math_min_i32")
                                || runtimeHelperBaseSymbol(symbol).equals("j2ll_rt_math_min_i64")
                                || runtimeHelperBaseSymbol(symbol).equals("j2ll_rt_math_min_f32")
                                || runtimeHelperBaseSymbol(symbol).equals("j2ll_rt_math_min_f64")
                                || runtimeHelperBaseSymbol(symbol).equals("j2ll_rt_math_max_i32")
                                || runtimeHelperBaseSymbol(symbol).equals("j2ll_rt_math_max_i64")
                                || runtimeHelperBaseSymbol(symbol).equals("j2ll_rt_math_max_f32")
                                || runtimeHelperBaseSymbol(symbol).equals("j2ll_rt_math_max_f64")
                                || runtimeHelperBaseSymbol(symbol).startsWith("j2ll_rt_integer_")
                                || runtimeHelperBaseSymbol(symbol).startsWith("j2ll_rt_long_")
                                || runtimeHelperBaseSymbol(symbol).startsWith("j2ll_rt_boolean_")
                                || runtimeHelperBaseSymbol(symbol).startsWith("j2ll_rt_double_")
                                || runtimeHelperBaseSymbol(symbol).startsWith("j2ll_rt_objects_"))
                        .orElse(false);
    }

    private boolean isUnsafeHelperInstruction(IrInstruction instruction) {
        return instruction.opcode() == IrOpcode.CALL_RUNTIME_HELPER
                && instruction.symbol()
                        .map(symbol -> runtimeHelperBaseSymbol(symbol).startsWith("j2ll_rt_unsafe_"))
                        .orElse(false);
    }

    private boolean isVarHandleHelperInstruction(IrInstruction instruction) {
        return instruction.opcode() == IrOpcode.CALL_RUNTIME_HELPER
                && instruction.symbol()
                        .map(symbol -> runtimeHelperBaseSymbol(symbol).startsWith("j2ll_rt_var_handle_"))
                        .orElse(false);
    }

    private boolean isLambdaHelperInstruction(IrInstruction instruction) {
        return instruction.opcode() == IrOpcode.CALL_RUNTIME_HELPER
                && instruction.symbol()
                        .map(symbol -> runtimeHelperBaseSymbol(symbol).equals("j2ll_rt_lambda_new")
                                && symbol.contains("|lambda:"))
                        .orElse(false);
    }

    private boolean supportsLambdaHelperInstruction(IrInstruction instruction) {
        return instruction.result().map(IrValue::type).filter(type -> type == IrType.REFERENCE).isPresent()
                && instruction.operands().size() == 2
                && instruction.operands().get(0).type() == IrType.I64
                && instruction.operands().get(1).type() == IrType.REFERENCE;
    }

    private boolean supportsVarHandleHelperInstruction(IrInstruction instruction) {
        String symbol = runtimeHelperBaseSymbol(instruction.symbol().orElseThrow());
        if (symbol.equals("j2ll_rt_var_handle_get_int")
                || symbol.equals("j2ll_rt_var_handle_get_volatile_int")) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.I32).isPresent()
                    && instruction.operands().size() == 2
                    && instruction.operands().get(0).type() == IrType.REFERENCE
                    && instruction.operands().get(1).type() == IrType.REFERENCE;
        }
        if (symbol.equals("j2ll_rt_var_handle_set_int")
                || symbol.equals("j2ll_rt_var_handle_set_volatile_int")) {
            return instruction.result().isEmpty()
                    && instruction.operands().size() == 3
                    && instruction.operands().get(0).type() == IrType.REFERENCE
                    && instruction.operands().get(1).type() == IrType.REFERENCE
                    && instruction.operands().get(2).type() == IrType.I32;
        }
        if (symbol.equals("j2ll_rt_var_handle_compare_and_set_int")) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.I32).isPresent()
                    && instruction.operands().size() == 4
                    && instruction.operands().get(0).type() == IrType.REFERENCE
                    && instruction.operands().get(1).type() == IrType.REFERENCE
                    && instruction.operands().get(2).type() == IrType.I32
                    && instruction.operands().get(3).type() == IrType.I32;
        }
        return false;
    }

    private boolean supportsUnsafeHelperInstruction(IrInstruction instruction) {
        String symbol = runtimeHelperBaseSymbol(instruction.symbol().orElseThrow());
        if (symbol.equals("j2ll_rt_unsafe_object_field_offset")) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.I64).isPresent()
                    && instruction.operands().size() == 1
                    && instruction.operands().get(0).type() == IrType.REFERENCE;
        }
        if (symbol.equals("j2ll_rt_unsafe_get_int")) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.I32).isPresent()
                    && instruction.operands().size() == 2
                    && instruction.operands().get(0).type() == IrType.REFERENCE
                    && instruction.operands().get(1).type() == IrType.I64;
        }
        if (symbol.equals("j2ll_rt_unsafe_get") || symbol.equals("j2ll_rt_unsafe_get_volatile")) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.REFERENCE).isPresent()
                    && instruction.operands().size() == 2
                    && instruction.operands().get(0).type() == IrType.REFERENCE
                    && instruction.operands().get(1).type() == IrType.REFERENCE;
        }
        if (symbol.equals("j2ll_rt_unsafe_put_int")) {
            return instruction.result().isEmpty()
                    && instruction.operands().size() == 3
                    && instruction.operands().get(0).type() == IrType.REFERENCE
                    && instruction.operands().get(1).type() == IrType.I64
                    && instruction.operands().get(2).type() == IrType.I32;
        }
        if (symbol.equals("j2ll_rt_unsafe_compare_and_swap_int")) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.I32).isPresent()
                    && instruction.operands().size() == 4
                    && instruction.operands().get(0).type() == IrType.REFERENCE
                    && instruction.operands().get(1).type() == IrType.I64
                    && instruction.operands().get(2).type() == IrType.I32
                    && instruction.operands().get(3).type() == IrType.I32;
        }
        if (symbol.equals("j2ll_rt_unsafe_allocate_instance")) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.REFERENCE).isPresent()
                    && instruction.operands().size() == 1
                    && instruction.operands().get(0).type() == IrType.REFERENCE;
        }
        return false;
    }

    private boolean supportsJdkScalarHelperInstruction(IrInstruction instruction) {
        String symbol = runtimeHelperBaseSymbol(instruction.symbol().orElseThrow());
        if (symbol.endsWith("_i32")) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.I32).isPresent()
                    && !instruction.operands().isEmpty()
                    && instruction.operands().stream().map(IrValue::type).allMatch(type -> type == IrType.I32);
        }
        if (symbol.endsWith("_i64")) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.I64).isPresent()
                    && !instruction.operands().isEmpty()
                    && instruction.operands().stream().map(IrValue::type).allMatch(type -> type == IrType.I64);
        }
        if (symbol.endsWith("_f32")) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.F32).isPresent()
                    && !instruction.operands().isEmpty()
                    && instruction.operands().stream().map(IrValue::type).allMatch(type -> type == IrType.F32);
        }
        if (symbol.endsWith("_f64")) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.F64).isPresent()
                    && !instruction.operands().isEmpty()
                    && instruction.operands().stream().map(IrValue::type).allMatch(type -> type == IrType.F64);
        }
        if (symbol.equals("j2ll_rt_integer_value_of")) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.REFERENCE).isPresent()
                    && instruction.operands().size() == 1
                    && instruction.operands().get(0).type() == IrType.I32;
        }
        if (symbol.equals("j2ll_rt_integer_int_value")) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.I32).isPresent()
                    && instruction.operands().size() == 1
                    && instruction.operands().get(0).type() == IrType.REFERENCE;
        }
        if (symbol.equals("j2ll_rt_long_value_of")) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.REFERENCE).isPresent()
                    && instruction.operands().size() == 1
                    && instruction.operands().get(0).type() == IrType.I64;
        }
        if (symbol.equals("j2ll_rt_long_long_value")) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.I64).isPresent()
                    && instruction.operands().size() == 1
                    && instruction.operands().get(0).type() == IrType.REFERENCE;
        }
        if (symbol.equals("j2ll_rt_boolean_value_of")) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.REFERENCE).isPresent()
                    && instruction.operands().size() == 1
                    && instruction.operands().get(0).type() == IrType.I32;
        }
        if (symbol.equals("j2ll_rt_boolean_boolean_value")) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.I32).isPresent()
                    && instruction.operands().size() == 1
                    && instruction.operands().get(0).type() == IrType.REFERENCE;
        }
        if (symbol.equals("j2ll_rt_double_value_of")) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.REFERENCE).isPresent()
                    && instruction.operands().size() == 1
                    && instruction.operands().get(0).type() == IrType.F64;
        }
        if (symbol.equals("j2ll_rt_double_double_value")) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.F64).isPresent()
                    && instruction.operands().size() == 1
                    && instruction.operands().get(0).type() == IrType.REFERENCE;
        }
        if (symbol.equals("j2ll_rt_objects_require_non_null")) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.REFERENCE).isPresent()
                    && instruction.operands().size() == 1
                    && instruction.operands().get(0).type() == IrType.REFERENCE;
        }
        if (symbol.equals("j2ll_rt_objects_equals")) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.I32).isPresent()
                    && instruction.operands().size() == 2
                    && instruction.operands().stream().map(IrValue::type).allMatch(type -> type == IrType.REFERENCE);
        }
        return false;
    }

    private boolean isRuntimeMetadataHelperInstruction(IrInstruction instruction) {
        return instruction.opcode() == IrOpcode.CALL_RUNTIME_HELPER
                && instruction.symbol()
                        .map(symbol -> {
                            String base = runtimeHelperBaseSymbol(symbol);
                            return base.equals("j2ll_rt_class_for_name_static")
                                    || base.equals("j2ll_rt_get_declared_method")
                                    || base.equals("j2ll_rt_get_declared_field")
                                    || base.equals("j2ll_rt_get_declared_constructor")
                                    || base.equals("j2ll_rt_reflect_invoke")
                                    || base.equals("j2ll_rt_reflect_new_instance")
                                    || base.equals("j2ll_rt_reflect_set_accessible")
                                    || base.startsWith("j2ll_rt_reflect_field_");
                        })
                        .orElse(false);
    }

    private boolean supportsRuntimeMetadataHelperInstruction(IrInstruction instruction) {
        String symbol = runtimeHelperBaseSymbol(instruction.symbol().orElseThrow());
        if (symbol.equals("j2ll_rt_class_for_name_static")) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.REFERENCE).isPresent()
                    && instruction.operands().size() == 2
                    && instruction.operands().get(0).type() == IrType.I64
                    && instruction.operands().get(1).type() == IrType.I32
                    && runtimeMetadataKey(instruction).map(key -> key.startsWith("class:")).orElse(false);
        }
        if (symbol.equals("j2ll_rt_get_declared_field")) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.REFERENCE).isPresent()
                    && instruction.operands().size() == 1
                    && instruction.operands().get(0).type() == IrType.I64
                    && runtimeMetadataKey(instruction).map(key -> key.startsWith("field:")).orElse(false);
        }
        if (symbol.equals("j2ll_rt_get_declared_method")
                || symbol.equals("j2ll_rt_get_declared_constructor")) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.REFERENCE).isPresent()
                    && instruction.operands().size() == 1
                    && instruction.operands().get(0).type() == IrType.I64
                    && runtimeMetadataKey(instruction)
                            .map(key -> {
                                if (key.startsWith("method:")) {
                                    int descriptorStart = key.indexOf('!');
                                    return descriptorStart >= 0 && key.substring(descriptorStart + 1).startsWith("(");
                                }
                                if (key.startsWith("constructor:")) {
                                    int descriptorStart = key.indexOf('!');
                                    return descriptorStart >= 0
                                            && key.substring(descriptorStart + 1).startsWith("(")
                                            && key.substring(descriptorStart + 1).endsWith("V");
                                }
                                return false;
                            })
                            .orElse(false);
        }
        if (symbol.equals("j2ll_rt_reflect_invoke")) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.REFERENCE).isPresent()
                    && instruction.operands().size() == 3
                    && instruction.operands().stream().map(IrValue::type).allMatch(type -> type == IrType.REFERENCE);
        }
        if (symbol.equals("j2ll_rt_reflect_new_instance")) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.REFERENCE).isPresent()
                    && instruction.operands().size() == 2
                    && instruction.operands().stream().map(IrValue::type).allMatch(type -> type == IrType.REFERENCE);
        }
        if (symbol.equals("j2ll_rt_reflect_set_accessible")) {
            return instruction.result().isEmpty()
                    && instruction.operands().size() == 2
                    && instruction.operands().get(0).type() == IrType.REFERENCE
                    && instruction.operands().get(1).type() == IrType.I32;
        }
        if (symbol.equals("j2ll_rt_reflect_field_get")) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.REFERENCE).isPresent()
                    && instruction.operands().size() == 2
                    && instruction.operands().stream().map(IrValue::type).allMatch(type -> type == IrType.REFERENCE);
        }
        if (symbol.equals("j2ll_rt_reflect_field_set")) {
            return instruction.result().isEmpty()
                    && instruction.operands().size() == 3
                    && instruction.operands().stream().map(IrValue::type).allMatch(type -> type == IrType.REFERENCE);
        }
        if (symbol.equals("j2ll_rt_reflect_field_get_int")) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.I32).isPresent()
                    && instruction.operands().size() == 2
                    && instruction.operands().stream().map(IrValue::type).allMatch(type -> type == IrType.REFERENCE);
        }
        if (symbol.equals("j2ll_rt_reflect_field_set_int")) {
            return instruction.result().isEmpty()
                    && instruction.operands().size() == 3
                    && instruction.operands().get(0).type() == IrType.REFERENCE
                    && instruction.operands().get(1).type() == IrType.REFERENCE
                    && instruction.operands().get(2).type() == IrType.I32;
        }
        if (symbol.equals("j2ll_rt_reflect_field_get_boolean")) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.I32).isPresent()
                    && instruction.operands().size() == 2
                    && instruction.operands().stream().map(IrValue::type).allMatch(type -> type == IrType.REFERENCE);
        }
        if (symbol.equals("j2ll_rt_reflect_field_set_boolean")) {
            return instruction.result().isEmpty()
                    && instruction.operands().size() == 3
                    && instruction.operands().get(0).type() == IrType.REFERENCE
                    && instruction.operands().get(1).type() == IrType.REFERENCE
                    && instruction.operands().get(2).type() == IrType.I32;
        }
        if (symbol.equals("j2ll_rt_reflect_field_get_long")) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.I64).isPresent()
                    && instruction.operands().size() == 2
                    && instruction.operands().stream().map(IrValue::type).allMatch(type -> type == IrType.REFERENCE);
        }
        if (symbol.equals("j2ll_rt_reflect_field_set_long")) {
            return instruction.result().isEmpty()
                    && instruction.operands().size() == 3
                    && instruction.operands().get(0).type() == IrType.REFERENCE
                    && instruction.operands().get(1).type() == IrType.REFERENCE
                    && instruction.operands().get(2).type() == IrType.I64;
        }
        if (symbol.equals("j2ll_rt_reflect_field_get_double")) {
            return instruction.result().map(IrValue::type).filter(type -> type == IrType.F64).isPresent()
                    && instruction.operands().size() == 2
                    && instruction.operands().stream().map(IrValue::type).allMatch(type -> type == IrType.REFERENCE);
        }
        if (symbol.equals("j2ll_rt_reflect_field_set_double")) {
            return instruction.result().isEmpty()
                    && instruction.operands().size() == 3
                    && instruction.operands().get(0).type() == IrType.REFERENCE
                    && instruction.operands().get(1).type() == IrType.REFERENCE
                    && instruction.operands().get(2).type() == IrType.F64;
        }
        return false;
    }

    private boolean isDispatchHelperInstruction(IrInstruction instruction) {
        return instruction.opcode() == IrOpcode.CALL_VIRTUAL || instruction.opcode() == IrOpcode.CALL_INTERFACE;
    }

    private boolean supportsDispatchHelperInstruction(IrInstruction instruction) {
        if (instruction.operands().isEmpty()
                || instruction.operands().get(0).type() != IrType.REFERENCE) {
            return false;
        }
        String descriptor = instruction.symbol().flatMap(this::methodDescriptor).orElse("");
        if (!descriptor.startsWith("(") || !supportsJvmHostedDescriptor(descriptor)) {
            return false;
        }
        String returnDescriptor = typeMapper.returnDescriptor(descriptor);
        if (returnDescriptor.equals("V")) {
            if (instruction.result().isPresent()) {
                return false;
            }
        } else if (instruction.result().isEmpty()
                || descriptorType(returnDescriptor) != instruction.result().orElseThrow().type()) {
            return false;
        }
        return operandsMatchDescriptor(descriptor, instruction.operands().subList(1, instruction.operands().size()));
    }

    private Optional<String> methodDescriptor(String methodKey) {
        int separator = methodKey.indexOf('!');
        if (separator < 0 || separator == methodKey.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(methodKey.substring(separator + 1));
    }

    private boolean isMonitorHelperInstruction(IrInstruction instruction) {
        return instruction.opcode() == IrOpcode.MONITOR_ENTER
                || instruction.opcode() == IrOpcode.MONITOR_EXIT
                || instruction.opcode() == IrOpcode.MONITOR_EXIT_ON_EXCEPTION;
    }

    private boolean supportsMonitorHelperInstruction(IrInstruction instruction) {
        return instruction.result().isEmpty()
                && instruction.operands().size() == 1
                && instruction.operands().get(0).type() == IrType.REFERENCE;
    }

    private List<String> fieldKeys(IrMethod method) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> isFieldAccess(instruction.opcode()))
                .map(instruction -> (instruction.opcode() == IrOpcode.GET_NATIVE_STATIC
                                || instruction.opcode() == IrOpcode.PUT_NATIVE_STATIC
                                ? "native-slot:"
                                : "")
                        + instruction.symbol().orElseThrow())
                .distinct()
                .sorted()
                .toList();
    }

    private List<String> directCallTargets(IrMethod method, Set<String> supportedLlvmMethods) {
        return sameOwnerDirectCallTargets(method, supportedLlvmMethods).stream()
                .sorted()
                .toList();
    }

    private Set<String> sameOwnerDirectCallTargets(
            IrMethod method,
            Set<String> supportedLlvmMethods) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.opcode() == IrOpcode.CALL_STATIC
                        || isDirectSpecialCallInstruction(instruction))
                .map(instruction -> instruction.symbol().orElseThrow())
                .filter(supportedLlvmMethods::contains)
                .filter(target -> target.startsWith(method.owner() + "#"))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private List<String> allocationKeys(IrMethod method) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(this::supportsAllocationInstruction)
                .map(instruction -> instruction.symbol().orElseThrow())
                .filter(symbol -> symbol.startsWith("object:") || isSupportedReferenceArraySymbol(symbol))
                .distinct()
                .sorted()
                .toList();
    }

    private List<String> typeCheckKeys(IrMethod method) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(this::supportsTypeInstruction)
                .map(instruction -> instruction.symbol().orElseThrow())
                .distinct()
                .sorted()
                .toList();
    }

    private List<String> classObjectKeys(IrMethod method) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.opcode() == IrOpcode.CLASS_OBJECT
                        || instruction.opcode() == IrOpcode.CONST_CLASS)
                .map(instruction -> instruction.symbol().orElseThrow())
                .distinct()
                .sorted()
                .toList();
    }

    private List<String> runtimeMetadataKeys(IrMethod method) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(this::isRuntimeMetadataHelperInstruction)
                .flatMap(instruction -> runtimeMetadataKey(instruction).stream())
                .distinct()
                .sorted()
                .toList();
    }

    private List<String> constructorCallKeys(IrMethod method) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(this::supportsConstructorCallInstruction)
                .map(instruction -> instruction.symbol().orElseThrow())
                .distinct()
                .sorted()
                .toList();
    }

    private List<String> staticCallKeys(
            IrMethod method,
            List<String> directCallTargets,
            Set<String> availableProgramMethods) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.opcode() == IrOpcode.CALL_STATIC)
                .filter(instruction -> instruction.symbol().filter(symbol -> !directCallTargets.contains(symbol)).isPresent())
                .filter(instruction -> supportsStaticCallBridgeInstruction(instruction, availableProgramMethods))
                .map(instruction -> instruction.symbol().orElseThrow())
                .distinct()
                .sorted()
                .toList();
    }

    private List<String> dispatchKeys(IrMethod method) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(this::supportsDispatchHelperInstruction)
                .map(instruction -> instruction.symbol().orElseThrow())
                .distinct()
                .sorted()
                .toList();
    }

    private List<String> stringHelperSymbols(IrMethod method) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> isStringHelperInstruction(instruction) || isStringBuilderHelperInstruction(instruction))
                .map(instruction -> runtimeHelperBaseSymbol(instruction.symbol().orElseThrow()))
                .distinct()
                .sorted()
                .toList();
    }

    private boolean containsJdkScalarHelper(IrMethod method) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(this::isJdkScalarHelperInstruction);
    }

    private boolean containsUnsafeHelper(IrMethod method) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(this::isUnsafeHelperInstruction);
    }

    private boolean containsVarHandleHelper(IrMethod method) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(this::isVarHandleHelperInstruction);
    }

    private boolean containsLambdaHelper(IrMethod method) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(this::isLambdaHelperInstruction);
    }

    private boolean containsArithmeticExceptionHelper(IrMethod method) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(this::isArithmeticExceptionHelperInstruction);
    }

    private boolean containsJvmNumericHelper(IrMethod method) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(this::isJvmNumericHelperInstruction);
    }

    private boolean containsArrayHelper(IrMethod method) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(this::isArrayHelperInstruction);
    }

    private boolean containsArraycopyHelper(IrMethod method) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(this::isArraycopyHelperInstruction);
    }

    private boolean containsAllocationHelper(IrMethod method) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(this::supportsAllocationInstruction);
    }

    private boolean containsTypeHelper(IrMethod method) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(this::isTypeHelperInstruction);
    }

    private boolean containsConstructorCallHelper(IrMethod method) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(this::isConstructorCallHelperInstruction);
    }

    private boolean containsMonitorHelper(IrMethod method) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(this::isMonitorHelperInstruction);
    }

    private boolean containsRuntimeMetadataHelper(IrMethod method) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(this::isRuntimeMetadataHelperInstruction);
    }

    private boolean containsClassInitHelper(IrMethod method) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(this::isClassInitGuardInstruction);
    }

    private boolean needsJniEnv(IrMethod method, List<String> directCallTargets, List<String> staticCallKeys) {
        return method.blocks().stream()
                .anyMatch(block -> block.terminator().kind() == IrTerminatorKind.THROW)
                || method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> isFieldAccess(instruction.opcode())
                        || instruction.opcode() == IrOpcode.CONST_STRING
                        || isArithmeticExceptionHelperInstruction(instruction)
                        || isArrayHelperInstruction(instruction)
                        || isAllocationHelperInstruction(instruction)
                        || isTypeHelperInstruction(instruction)
                        || isMonitorHelperInstruction(instruction)
                        || isClassInitGuardInstruction(instruction)
                        || isConstructorCallHelperInstruction(instruction)
                        || isDispatchHelperInstruction(instruction)
                        || ((instruction.opcode() == IrOpcode.CALL_STATIC
                                        || isDirectSpecialCallInstruction(instruction))
                                && instruction.symbol().filter(directCallTargets::contains).isPresent())
                        || (instruction.opcode() == IrOpcode.CALL_STATIC
                                && instruction.symbol().filter(staticCallKeys::contains).isPresent())
                        || (instruction.opcode() == IrOpcode.CALL_RUNTIME_HELPER
                                && instruction.symbol().map(this::isEnvBackedRuntimeHelperSymbol).orElse(false)));
    }

    private boolean needsOwnerClass(IrMethod method, List<String> directCallTargets) {
        return !directCallTargets.isEmpty()
                || method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> instruction.opcode() == IrOpcode.GET_STATIC
                        || instruction.opcode() == IrOpcode.PUT_STATIC
                        || instruction.opcode() == IrOpcode.GET_NATIVE_STATIC
                        || instruction.opcode() == IrOpcode.PUT_NATIVE_STATIC);
    }

    private boolean isEnvBackedRuntimeHelperSymbol(String symbol) {
        String base = runtimeHelperBaseSymbol(symbol);
        return base.equals("j2ll_rt_string_length")
                || base.equals("j2ll_rt_string_equals")
                || base.equals("j2ll_rt_string_is_empty")
                || base.equals("j2ll_rt_string_char_at")
                || base.equals("j2ll_rt_string_starts_with")
                || base.equals("j2ll_rt_string_ends_with")
                || base.equals("j2ll_rt_string_substring")
                || base.equals("j2ll_rt_string_substring_range")
                || base.equals("j2ll_rt_string_constant")
                || base.startsWith("j2ll_rt_string_builder_")
                || base.equals("j2ll_rt_system_arraycopy")
                || base.startsWith("j2ll_rt_integer_")
                || base.startsWith("j2ll_rt_long_")
                || base.startsWith("j2ll_rt_boolean_")
                || base.startsWith("j2ll_rt_double_")
                || base.startsWith("j2ll_rt_objects_")
                || base.equals("j2ll_rt_lambda_new")
                || base.equals("j2ll_rt_class_for_name_static")
                || base.equals("j2ll_rt_get_declared_method")
                || base.equals("j2ll_rt_get_declared_field")
                || base.equals("j2ll_rt_get_declared_constructor")
                || base.equals("j2ll_rt_reflect_invoke")
                || base.equals("j2ll_rt_reflect_new_instance")
                || base.equals("j2ll_rt_reflect_set_accessible")
                || base.startsWith("j2ll_rt_reflect_field_")
                || base.startsWith("j2ll_rt_unsafe_")
                || base.startsWith("j2ll_rt_var_handle_");
    }

    private boolean containsThrowTerminator(IrMethod method) {
        return method.blocks().stream()
                .anyMatch(block -> block.terminator().kind() == IrTerminatorKind.THROW);
    }

    private boolean isThrowableSemanticUnsupportedCall(IrInstruction instruction) {
        if (instruction.symbol().isEmpty()) {
            return false;
        }
        String symbol = instruction.symbol().orElseThrow();
        return symbol.equals("java/lang/Throwable#getMessage!()Ljava/lang/String;")
                || symbol.equals("java/lang/Throwable#getCause!()Ljava/lang/Throwable;")
                || symbol.equals("java/lang/Throwable#initCause!(Ljava/lang/Throwable;)Ljava/lang/Throwable;");
    }

    private boolean isJdkThrowableFamilyConstructor(String symbol) {
        int separator = symbol.indexOf("#<init>!");
        if (separator < 0) {
            return false;
        }
        String owner = symbol.substring(0, separator);
        return owner.equals("java/lang/Throwable")
                || owner.endsWith("Exception")
                || owner.endsWith("Error");
    }

    private String reasonCode(
            List<String> fieldKeys,
            List<String> directCallTargets,
            List<String> allocationKeys,
            List<String> typeCheckKeys,
            List<String> constructorCallKeys,
            List<String> staticCallKeys,
            List<String> dispatchKeys,
            List<String> stringHelperSymbols,
            boolean jdkScalarHelper,
            boolean allocationHelper,
            boolean typeHelper,
            boolean constructorCallHelper,
            boolean arithmeticExceptionHelper,
            boolean jvmNumericHelper,
            boolean arrayHelper,
            boolean arraycopyHelper,
            boolean varHandleHelper,
            boolean lambdaHelper,
            boolean unsafeHelper,
            boolean monitorHelper,
            boolean exceptionHelper,
            boolean runtimeMetadataHelper,
            boolean synchronizedMethod) {
        if (synchronizedMethod && monitorHelper) {
            return "LLVM_SYNCHRONIZED_METHOD_HELPER_IR";
        }
        if (!directCallTargets.isEmpty()) {
            return "LLVM_DIRECT_CALL_IR";
        }
        if (!staticCallKeys.isEmpty()) {
            return "LLVM_STATIC_CALL_HELPER_IR";
        }
        if (!dispatchKeys.isEmpty()) {
            return "LLVM_DISPATCH_HELPER_IR";
        }
        if (!stringHelperSymbols.isEmpty()) {
            if (stringHelperSymbols.contains("j2ll_rt_string_constant")) {
                return "LLVM_STRING_CONCAT_CONSTANTS_HELPER_IR";
            }
            if (stringHelperSymbols.stream().allMatch(symbol -> symbol.startsWith("j2ll_rt_string_builder_"))) {
                return "LLVM_STRING_BUILDER_HELPER_IR";
            }
            return "LLVM_STRING_HELPER_IR";
        }
        if (runtimeMetadataHelper) {
            return "LLVM_REFLECTION_HELPER_IR";
        }
        if (jdkScalarHelper) {
            return "LLVM_JDK_INTRINSIC_HELPER_IR";
        }
        if (varHandleHelper) {
            return "LLVM_VARHANDLE_HELPER_IR";
        }
        if (lambdaHelper) {
            return "LLVM_LAMBDA_METAFACTORY_HELPER_IR";
        }
        if (unsafeHelper) {
            return "LLVM_UNSAFE_HELPER_IR";
        }
        if (constructorCallHelper) {
            return "LLVM_CONSTRUCTOR_CALL_HELPER_IR";
        }
        if (allocationHelper) {
            return "LLVM_ALLOCATION_HELPER_IR";
        }
        if (typeHelper) {
            return "LLVM_TYPE_HELPER_IR";
        }
        if (arrayHelper) {
            return "LLVM_ARRAY_HELPER_IR";
        }
        if (arraycopyHelper) {
            return "LLVM_ARRAYCOPY_HELPER_IR";
        }
        if (arithmeticExceptionHelper) {
            return "LLVM_DIV_REM_EXCEPTION_HELPER_IR";
        }
        if (jvmNumericHelper) {
            return "LLVM_JVM_NUMERIC_HELPER_IR";
        }
        if (monitorHelper) {
            return "LLVM_MONITOR_HELPER_IR";
        }
        if (exceptionHelper) {
            return "LLVM_EXCEPTION_HELPER_IR";
        }
        if (!fieldKeys.isEmpty()) {
            return "LLVM_FIELD_HELPER_IR";
        }
        return "LLVM_PRIMITIVE_SCALAR_IR";
    }

    private String constructorDescriptor(String methodKey) {
        int descriptorStart = methodKey.indexOf('!');
        if (descriptorStart < 0) {
            return "";
        }
        return methodKey.substring(descriptorStart + 1);
    }

    private Optional<String> runtimeMetadataKey(IrInstruction instruction) {
        return instruction.symbol().flatMap(symbol -> {
            int separator = symbol.indexOf('|');
            if (separator < 0 || separator + 1 >= symbol.length()) {
                return Optional.empty();
            }
            return Optional.of(symbol.substring(separator + 1));
        });
    }

    private String runtimeHelperBaseSymbol(String symbol) {
        int separator = symbol.indexOf('|');
        return separator < 0 ? symbol : symbol.substring(0, separator);
    }
}
