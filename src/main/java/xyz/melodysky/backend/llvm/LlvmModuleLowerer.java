package xyz.melodysky.backend.llvm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import xyz.melodysky.backend.llvm.model.LlvmBasicBlock;
import xyz.melodysky.backend.llvm.model.LlvmDeclaration;
import xyz.melodysky.backend.llvm.model.LlvmFunction;
import xyz.melodysky.backend.llvm.model.LlvmInstruction;
import xyz.melodysky.backend.llvm.model.LlvmIrCallIndirectionRef;
import xyz.melodysky.backend.llvm.model.LlvmLinkage;
import xyz.melodysky.backend.llvm.model.LlvmModule;
import xyz.melodysky.backend.llvm.model.LlvmNativeUnwindSemantics;
import xyz.melodysky.backend.llvm.model.LlvmParameter;
import xyz.melodysky.backend.llvm.model.LlvmSwitchCase;
import xyz.melodysky.backend.llvm.model.LlvmTerminator;
import xyz.melodysky.backend.llvm.model.LlvmType;
import xyz.melodysky.backend.llvm.model.LlvmVisibility;
import xyz.melodysky.ir.model.BusinessStringConstantRef;
import xyz.melodysky.ir.model.BusinessStringSymbolMapper;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrClass;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminatorKind;
import xyz.melodysky.ir.model.IrValue;
import xyz.melodysky.runtime.PureNativeJdkRuntimeHelpers;
import xyz.melodysky.runtime.RuntimeHelperCatalog;
import xyz.melodysky.runtime.RuntimeHelperKind;
import xyz.melodysky.runtime.RuntimeTokenDomain;
import xyz.melodysky.runtime.RuntimeTokenMapper;
import xyz.melodysky.runtime.jni.RuntimeLocalAbiDomain;
import xyz.melodysky.runtime.jni.RuntimeLocalAbiPlan;
import xyz.melodysky.runtime.jni.RuntimeLocalAbiPlanner;
import xyz.melodysky.toolchain.localref.NativeLocalReferencePlan;
import xyz.melodysky.toolchain.localref.NativeLocalReferenceNormalEdge;

public final class LlvmModuleLowerer {
    private final LlvmNameMangler nameMangler;
    private final LlvmTypeLowerer typeLowerer = new LlvmTypeLowerer();
    private final RuntimeHelperCatalog runtimeHelpers = RuntimeHelperCatalog.defaultCatalog();
    private final NativeFieldLlvmLowering nativeFields;
    private final BusinessStringSymbolMapper businessStringSymbols;
    private final RuntimeTokenMapper runtimeTokens;
    private final RuntimeLocalAbiPlanner runtimeLocalAbi =
            new RuntimeLocalAbiPlanner();

    public LlvmModuleLowerer(
            LlvmNameMangler nameMangler,
            BusinessStringSymbolMapper businessStringSymbols,
            RuntimeTokenMapper runtimeTokens) {
        this.nameMangler = java.util.Objects.requireNonNull(
                nameMangler,
                "nameMangler");
        this.businessStringSymbols = java.util.Objects.requireNonNull(
                businessStringSymbols,
                "businessStringSymbols");
        this.runtimeTokens = java.util.Objects.requireNonNull(
                runtimeTokens,
                "runtimeTokens");
        this.nativeFields = new NativeFieldLlvmLowering(runtimeTokens);
    }

    public LlvmModule lowerClass(IrClass irClass) {
        return lowerClass(irClass, LlvmLinkage.EXTERNAL, LlvmVisibility.HIDDEN);
    }

    public LlvmModule lowerClass(IrClass irClass, LlvmLinkage linkage, LlvmVisibility visibility) {
        return lowerClass(irClass, linkage, visibility, Set.of());
    }

    public LlvmModule lowerClass(
            IrClass irClass,
            LlvmLinkage linkage,
            LlvmVisibility visibility,
            Set<String> directCallMethodKeys) {
        return lowerClass(irClass, linkage, visibility, directCallMethodKeys, Set.of());
    }

    public LlvmModule lowerClass(
            IrClass irClass,
            LlvmLinkage linkage,
            LlvmVisibility visibility,
            Set<String> directCallMethodKeys,
            Set<String> staticCallMethodKeys) {
        Map<String, Set<String>> directCallsByMethod = new HashMap<>();
        Map<String, Set<String>> staticCallsByMethod = new HashMap<>();
        for (IrMethod method : irClass.methods()) {
            directCallsByMethod.put(
                    method.methodKey(),
                    configuredDirectCalls(method, directCallMethodKeys));
            staticCallsByMethod.put(
                    method.methodKey(),
                    configuredStaticCalls(method, staticCallMethodKeys));
        }
        return lowerClass(
                irClass,
                linkage,
                visibility,
                directCallsByMethod,
                staticCallsByMethod);
    }

    private Set<String> configuredDirectCalls(IrMethod method, Set<String> configuredTargets) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.opcode() == IrOpcode.CALL_STATIC
                        || isDirectSpecialCallInstruction(instruction))
                .flatMap(instruction -> instruction.symbol().stream())
                .filter(configuredTargets::contains)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private Set<String> configuredStaticCalls(IrMethod method, Set<String> configuredTargets) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.opcode() == IrOpcode.CALL_STATIC)
                .flatMap(instruction -> instruction.symbol().stream())
                .filter(configuredTargets::contains)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public LlvmModule lowerClass(
            IrClass irClass,
            LlvmLinkage linkage,
            LlvmVisibility visibility,
            Map<String, Set<String>> directCallsByMethod,
            Map<String, Set<String>> staticCallsByMethod) {
        return lowerClass(
                irClass,
                linkage,
                visibility,
                directCallsByMethod,
                staticCallsByMethod,
                Map.of());
    }

    public LlvmModule lowerClass(
            IrClass irClass,
            LlvmLinkage linkage,
            LlvmVisibility visibility,
            Map<String, Set<String>> directCallsByMethod,
            Map<String, Set<String>> staticCallsByMethod,
            Map<String, LlvmFunctionAbi> plannedFunctionAbis) {
        return lowerClass(
                irClass,
                linkage,
                visibility,
                directCallsByMethod,
                staticCallsByMethod,
                plannedFunctionAbis,
                Map.of());
    }

    public LlvmModule lowerClass(
            IrClass irClass,
            LlvmLinkage linkage,
            LlvmVisibility visibility,
            Map<String, Set<String>> directCallsByMethod,
            Map<String, Set<String>> staticCallsByMethod,
            Map<String, LlvmFunctionAbi> plannedFunctionAbis,
            Map<String, NativeLocalReferencePlan>
                    localReferencePlansByMethod) {
        java.util.Objects.requireNonNull(
                localReferencePlansByMethod,
                "localReferencePlansByMethod");
        Map<String, LlvmFunctionAbi> functionAbis = functionAbis(
                irClass,
                directCallsByMethod,
                staticCallsByMethod,
                plannedFunctionAbis);
        return new LlvmModule(
                irClass.internalName(),
                runtimeHelperDeclarations(
                        irClass,
                        localReferencePlansByMethod),
                irClass.methods().stream()
                        .map(method -> lowerMethod(
                                method,
                                linkage,
                                visibility,
                                directCallsByMethod.getOrDefault(method.methodKey(), Set.of()),
                                staticCallsByMethod.getOrDefault(method.methodKey(), Set.of()),
                                functionAbis,
                                Optional.ofNullable(localReferencePlansByMethod
                                        .get(method.methodKey()))))
                        .toList());
    }

    private Map<String, LlvmFunctionAbi> functionAbis(
            IrClass irClass,
            Map<String, Set<String>> directCallsByMethod,
            Map<String, Set<String>> staticCallsByMethod,
            Map<String, LlvmFunctionAbi> plannedFunctionAbis) {
        java.util.Objects.requireNonNull(
                plannedFunctionAbis,
                "plannedFunctionAbis");
        Map<String, LlvmFunctionAbi> result = new HashMap<>();
        for (IrMethod method : irClass.methods()) {
            Set<String> directCalls = directCallsByMethod.getOrDefault(method.methodKey(), Set.of());
            Set<String> staticCalls = staticCallsByMethod.getOrDefault(method.methodKey(), Set.of());
            LlvmFunctionAbi inferred = inferFunctionAbi(
                    method,
                    directCalls,
                    staticCalls);
            LlvmFunctionAbi planned =
                    plannedFunctionAbis.get(method.methodKey());
            if (planned != null && !planned.equals(inferred)) {
                throw new IllegalArgumentException(
                        "planned LLVM function ABI does not match lowering "
                                + "requirements for "
                                + method.methodKey()
                                + ": planned="
                                + planned
                                + ", inferred="
                                + inferred);
            }
            result.put(
                    method.methodKey(),
                    planned == null ? inferred : planned);
        }
        return Map.copyOf(result);
    }

    /** Shared exact ABI inference for final-plan IR transformations. */
    public LlvmFunctionAbi inferFunctionAbi(
            IrMethod method,
            Set<String> directCallMethodKeys,
            Set<String> staticCallMethodKeys) {
        java.util.Objects.requireNonNull(method, "method");
        java.util.Objects.requireNonNull(
                directCallMethodKeys,
                "directCallMethodKeys");
        java.util.Objects.requireNonNull(
                staticCallMethodKeys,
                "staticCallMethodKeys");
        return LlvmFunctionAbi.semanticInternal(
                methodNeedsJniEnv(
                        method,
                        directCallMethodKeys,
                        staticCallMethodKeys),
                methodNeedsOwnerClass(method, directCallMethodKeys));
    }

    private List<LlvmDeclaration> runtimeHelperDeclarations(
            IrClass irClass,
            Map<String, NativeLocalReferencePlan>
                    localReferencePlansByMethod) {
        ArrayList<LlvmDeclaration> declarations = new ArrayList<>(runtimeHelpers.helpers().stream()
                .filter(helper -> helper.kind() != RuntimeHelperKind.STRING_CONSTANT)
                .map(helper -> new LlvmDeclaration(
                        helper.llvmSymbol(),
                        helper.llvmReturnType(),
                        helper.llvmParameterTypes(),
                        helper.name()))
                .toList());
        TreeMap<String, BusinessStringConstantRef> businessStrings = new TreeMap<>();
        irClass.methods().stream()
                .flatMap(method -> method.blocks().stream())
                .flatMap(block -> block.instructions().stream())
                .flatMap(instruction ->
                        BusinessStringConstantRef.fromInstruction(instruction).stream())
                .forEach(constant -> {
                    BusinessStringConstantRef existing =
                            businessStrings.putIfAbsent(
                                    constant.helperSymbol(businessStringSymbols),
                                    constant);
                    if (existing != null && !existing.value().equals(constant.value())) {
                        throw new IllegalArgumentException(
                                "business string helper symbol collision");
                    }
                });
        businessStrings.values().forEach(constant -> declarations.add(
                new LlvmDeclaration(
                        constant.helperSymbol(businessStringSymbols),
                        "ptr",
                        List.of("ptr"),
                        "businessStringConstantLocal")));
        addLocalizedRuntimeBindingDeclarations(irClass, declarations);
        declarations.addAll(nativeFields.declarations());
        if (localReferencePlansByMethod.values().stream()
                .anyMatch(NativeLocalReferencePlan::emitsReleases)) {
            declarations.add(LlvmLocalReferenceLowering.declaration());
        }
        return List.copyOf(declarations);
    }

    private void addLocalizedRuntimeBindingDeclarations(
            IrClass irClass,
            List<LlvmDeclaration> declarations) {
        TreeMap<String, LlvmDeclaration> localized = new TreeMap<>();
        irClass.methods().stream()
                .flatMap(method -> method.blocks().stream())
                .flatMap(block -> block.instructions().stream())
                .forEach(instruction -> localizedRuntimeDeclaration(instruction)
                        .ifPresent(declaration -> localized.putIfAbsent(
                                declaration.name(),
                                declaration)));
        irClass.methods().stream()
                .flatMap(method -> method.blocks().stream())
                .forEach(block -> {
                    block.instructions().stream()
                            .flatMap(instruction ->
                                    instruction.exceptionSites().stream())
                            .flatMap(site -> site.handlers().stream())
                            .forEach(edge -> addCatchDeclaration(
                                    localized,
                                    edge.catchType()));
                    block.exceptionEdges().forEach(edge -> addCatchDeclaration(
                            localized,
                            edge.catchType()));
                });
        declarations.addAll(localized.values());
    }

    private Optional<LlvmDeclaration> localizedRuntimeDeclaration(
            xyz.melodysky.ir.model.IrInstruction instruction) {
        if (instruction.opcode() == IrOpcode.NEW_OBJECT) {
            String key = instruction.symbol().orElseThrow();
            if (key.startsWith("object:")) {
                return Optional.of(new LlvmDeclaration(
                        runtimeTokens.helperSymbol(
                                RuntimeTokenDomain.CLASS_RUNTIME,
                                "alloc_object",
                                key),
                        "ptr",
                        List.of("ptr"),
                        "localizedObjectAllocation"));
            }
        }
        if (instruction.opcode() == IrOpcode.NEW_ARRAY
                && instruction.symbol().orElse("").startsWith(
                        "referenceArray:")) {
            String key = instruction.symbol().orElseThrow();
            return Optional.of(new LlvmDeclaration(
                    runtimeTokens.helperSymbol(
                            RuntimeTokenDomain.CLASS_RUNTIME,
                            "new_object_array",
                            key),
                    "ptr",
                    List.of("ptr", "i32"),
                    "localizedReferenceArrayAllocation"));
        }
        if (instruction.opcode() == IrOpcode.CHECKCAST
                || instruction.opcode() == IrOpcode.INSTANCEOF) {
            String key = instruction.symbol().orElseThrow();
            String operation = instruction.opcode() == IrOpcode.CHECKCAST
                    ? "checkcast"
                    : "instanceof";
            return Optional.of(new LlvmDeclaration(
                    runtimeTokens.helperSymbol(
                            RuntimeTokenDomain.CLASS_RUNTIME,
                            operation,
                            key),
                    instruction.opcode() == IrOpcode.CHECKCAST ? "ptr" : "i32",
                    List.of("ptr", "ptr"),
                    "localizedTypeCheck"));
        }
        if (instruction.opcode() == IrOpcode.CLASS_OBJECT
                || instruction.opcode() == IrOpcode.CONST_CLASS) {
            String identity = classIdentityFromConstSymbol(
                    instruction.symbol().orElseThrow());
            return Optional.of(new LlvmDeclaration(
                    runtimeTokens.helperSymbol(
                            RuntimeTokenDomain.CLASS_OBJECT,
                            "class_object",
                            identity),
                    "ptr",
                    List.of("ptr"),
                    "localizedClassObject"));
        }
        if (instruction.opcode() == IrOpcode.CALL_RUNTIME_HELPER
                && instruction.symbol().orElse("").startsWith(
                        "j2ll_rt_class_for_name_static|class:")) {
            String identity = instruction.symbol().orElseThrow()
                    .substring("j2ll_rt_class_for_name_static|class:".length());
            return Optional.of(new LlvmDeclaration(
                    runtimeTokens.helperSymbol(
                            RuntimeTokenDomain.CLASS_OBJECT,
                            "class_for_name",
                            identity),
                    "ptr",
                    List.of("ptr", "i32"),
                    "localizedClassForName"));
        }
        if (instruction.opcode() == IrOpcode.CALL_RUNTIME_HELPER
                && runtimeMetadataKey(instruction).isPresent()) {
            String key = runtimeMetadataKey(instruction).orElseThrow();
            String base = runtimeHelperBaseSymbol(
                    instruction.symbol().orElseThrow());
            String operation;
            RuntimeTokenDomain domain;
            if (base.equals("j2ll_rt_get_declared_method")
                    && key.startsWith("method:")) {
                operation = "reflection_lookup_method";
                domain = RuntimeTokenDomain.REFLECTION_METHOD;
            } else if (base.equals("j2ll_rt_get_declared_constructor")
                    && key.startsWith("constructor:")) {
                operation = "reflection_lookup_constructor";
                domain = RuntimeTokenDomain.REFLECTION_METHOD;
            } else if (base.equals("j2ll_rt_get_declared_field")
                    && key.startsWith("field:")) {
                operation = "reflection_lookup_field";
                domain = RuntimeTokenDomain.REFLECTION_FIELD;
            } else {
                operation = null;
                domain = null;
            }
            if (operation != null) {
                return Optional.of(new LlvmDeclaration(
                        runtimeTokens.helperSymbol(domain, operation, key),
                        "ptr",
                        localAbiParameterTypes(
                                RuntimeLocalAbiDomain.REFLECTION,
                                operation,
                                key,
                                List.of("ptr")),
                        "localizedReflectionLookup"));
            }
        }
        if (instruction.opcode() == IrOpcode.CALL_RUNTIME_HELPER
                && instruction.symbol().orElse("").startsWith(
                        "j2ll_rt_lambda_new|lambda:")) {
            String identity = runtimeMetadataKey(instruction)
                    .orElseThrow();
            return Optional.of(new LlvmDeclaration(
                    runtimeTokens.helperSymbol(
                            RuntimeTokenDomain.LAMBDA,
                            "lambda_new",
                            identity),
                    "ptr",
                    List.of("ptr", "ptr"),
                    "localizedLambdaFactory"));
        }
        if (instruction.opcode() == IrOpcode.GET_STATIC
                || instruction.opcode() == IrOpcode.PUT_STATIC
                || instruction.opcode() == IrOpcode.GET_FIELD
                || instruction.opcode() == IrOpcode.PUT_FIELD) {
            String symbol = localizedFieldHelper(instruction);
            String operation = localizedFieldOperation(instruction);
            String fieldKey = instruction.symbol().orElseThrow();
            boolean write = instruction.opcode() == IrOpcode.PUT_STATIC
                    || instruction.opcode() == IrOpcode.PUT_FIELD;
            String valueType = fieldLlvmType(
                    fieldKey);
            List<String> logicalParameters = write
                    ? List.of("ptr", "ptr", valueType)
                    : List.of("ptr", "ptr");
            return Optional.of(new LlvmDeclaration(
                    symbol,
                    write ? "void" : valueType,
                    localAbiParameterTypes(
                            RuntimeLocalAbiDomain.FIELD,
                            operation,
                            fieldKey,
                            logicalParameters),
                    "localizedFieldBinding"));
        }
        if (isConstructorCallHelperInstruction(instruction)) {
            String methodKey = instruction.symbol().orElseThrow();
            String operation = "constructor_call";
            return Optional.of(new LlvmDeclaration(
                    localizedDispatchHelper(
                            operation,
                            methodKey),
                    "void",
                    localAbiParameterTypes(
                            RuntimeLocalAbiDomain.DISPATCH,
                            operation,
                            methodKey,
                            List.of("ptr", "ptr", "ptr")),
                    "localizedConstructorDispatch"));
        }
        if (instruction.opcode() == IrOpcode.CALL_STATIC) {
            String methodKey = instruction.symbol().orElseThrow();
            String descriptor = methodDescriptor(methodKey).orElseThrow();
            String returnType = staticCallBridgeReturnType(descriptor);
            String operation = "static_call_"
                    + dispatchDescriptorSuffix(descriptor);
            return Optional.of(new LlvmDeclaration(
                    localizedDispatchHelper(
                            operation,
                            methodKey),
                    returnType,
                    localAbiParameterTypes(
                            RuntimeLocalAbiDomain.DISPATCH,
                            operation,
                            methodKey,
                            List.of("ptr", "ptr")),
                    "localizedStaticDispatch"));
        }
        if (isDispatchHelperInstruction(instruction)) {
            String methodKey = instruction.symbol().orElseThrow();
            String descriptor = methodDescriptor(methodKey).orElseThrow();
            String returnType = staticCallBridgeReturnType(descriptor);
            String operation = dispatchOperationPrefix(instruction)
                    + dispatchDescriptorSuffix(descriptor);
            return Optional.of(new LlvmDeclaration(
                    localizedDispatchHelper(
                            operation,
                            methodKey),
                    returnType,
                    localAbiParameterTypes(
                            RuntimeLocalAbiDomain.DISPATCH,
                            operation,
                            methodKey,
                            List.of("ptr", "ptr", "ptr")),
                    "localizedVirtualDispatch"));
        }
        return Optional.empty();
    }

    private void addCatchDeclaration(
            Map<String, LlvmDeclaration> declarations,
            String catchType) {
        if (catchType.equals("<any>")) {
            return;
        }
        String key = "instanceof:" + catchType;
        String symbol = runtimeTokens.helperSymbol(
                RuntimeTokenDomain.CLASS_RUNTIME,
                "instanceof",
                key);
        declarations.putIfAbsent(
                symbol,
                new LlvmDeclaration(
                        symbol,
                        "i32",
                        List.of("ptr", "ptr"),
                        "localizedCatchTypeCheck"));
    }

    private LlvmFunction lowerMethod(
            IrMethod method,
            LlvmLinkage linkage,
            LlvmVisibility visibility,
            Set<String> directCallMethodKeys,
            Set<String> staticCallMethodKeys,
            Map<String, LlvmFunctionAbi> functionAbis,
            Optional<NativeLocalReferencePlan> localReferencePlan) {
        localReferencePlan.ifPresent(plan -> {
            if (!plan.methodKey().equals(method.methodKey())) {
                throw new IllegalArgumentException(
                        "local-reference plan belongs to another method: "
                                + plan.methodKey());
            }
        });
        Optional<LlvmLocalReferenceLowering> localReferences =
                localReferencePlan.map(LlvmLocalReferenceLowering::new);
        ArrayList<LlvmParameter> parameters = new ArrayList<>();
        LlvmFunctionAbi functionAbi = functionAbis.get(method.methodKey());
        LlvmReferenceIdentityLowering referenceIdentity =
                new LlvmReferenceIdentityLowering(
                        method,
                        functionAbi,
                        runtimeHelpers.helper(RuntimeHelperKind.IS_SAME_OBJECT)
                                .orElseThrow()
                                .llvmSymbol());
        if (functionAbi.passesJniEnv()) {
            parameters.add(new LlvmParameter(LlvmType.PTR, "%j2ll_env"));
        }
        if (functionAbi.passesOwnerClass()) {
            parameters.add(new LlvmParameter(LlvmType.PTR, "%j2ll_owner"));
        }
        method.parameters().stream()
                .map(parameter -> new LlvmParameter(typeLowerer.lower(parameter.type()), parameter.name()))
                .forEach(parameters::add);
        boolean cacheReferenceSidecar = nativeFields.usesReferenceSidecar(method);
        LlvmJvalueScratchPlan jvalueScratch =
                new LlvmJvalueScratchPlanner().plan(
                        method,
                        instruction -> jvalueScratchArguments(
                                instruction,
                                staticCallMethodKeys));
        LlvmType functionReturnType = typeLowerer.lower(method.returnType());
        LlvmExceptionFlowLowerer exceptionFlowLowerer = new LlvmExceptionFlowLowerer(
                method.blocks().stream()
                        .map(IrBlock::name)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                runtimeTokens);
        ArrayList<LlvmExceptionFlowLowerer.BlockResult> loweredBlocks = new ArrayList<>();
        HashMap<String, String> normalExitBlocks = new HashMap<>();
        ArrayList<LlvmExceptionFlowLowerer.ExceptionalIncoming> exceptionalIncoming =
                new ArrayList<>();
        List<LlvmInstruction> exceptionalExitCleanup = cacheReferenceSidecar
                ? List.of(nativeFields.referenceSidecarCleanup())
                : List.of();
        for (IrBlock block : method.blocks()) {
            ArrayList<LlvmExceptionFlowLowerer.InstructionChunk> chunks = new ArrayList<>();
            for (int instructionIndex = 0;
                    instructionIndex < block.instructions().size();
                    instructionIndex++) {
                int localInstructionIndex = instructionIndex;
                xyz.melodysky.ir.model.IrInstruction instruction =
                        block.instructions().get(instructionIndex);
                xyz.melodysky.toolchain.localref
                                .NativeLocalReferenceReleaseSchedule
                        releases = localReferencePlan
                                .map(plan -> plan.releasesAfter(
                                        block.name(),
                                        localInstructionIndex))
                                .orElseGet(() -> new xyz.melodysky.toolchain
                                        .localref
                                        .NativeLocalReferenceReleaseSchedule(
                                        List.of(),
                                        List.of()));
                chunks.add(new LlvmExceptionFlowLowerer.InstructionChunk(
                        instruction,
                        lowerInstructions(
                                instruction,
                                directCallMethodKeys,
                                staticCallMethodKeys,
                                functionAbis,
                                block.name(),
                                instructionIndex,
                                jvalueScratch,
                                referenceIdentity),
                        localReferences
                                .map(lowering -> lowering.releases(
                                        releases.normalPath()))
                                .orElse(List.of()),
                        localReferences
                                .map(lowering -> lowering.releases(
                                        releases.exceptionalPath()))
                                .orElse(List.of())));
            }
            LlvmExceptionFlowLowerer.BlockResult lowered = exceptionFlowLowerer.lower(
                    block,
                    chunks,
                    lowerTerminator(block),
                    functionReturnType,
                    exceptionalExitCleanup,
                    localReferences
                            .map(lowering -> lowering.releases(
                                    localReferencePlan
                                            .orElseThrow()
                                            .releasesBeforeTerminator(
                                                    block.name())))
                            .orElse(List.of()));
            loweredBlocks.add(lowered);
            normalExitBlocks.put(block.name(), lowered.normalExitBlock());
            exceptionalIncoming.addAll(lowered.exceptionalIncoming());
        }

        NormalEdgeLowering normalEdges = lowerNormalEdges(
                method,
                loweredBlocks,
                localReferencePlan,
                localReferences);
        Map<String, List<PhiIncoming>> phiIncoming =
                phiIncoming(
                        method,
                        normalExitBlocks,
                        exceptionalIncoming,
                        normalEdges.predecessors());
        ArrayList<LlvmBasicBlock> blocks = new ArrayList<>();
        if (cacheReferenceSidecar || jvalueScratch.required()) {
            blocks.add(activationPrologue(
                    method,
                    cacheReferenceSidecar,
                    jvalueScratch));
        }
        for (List<LlvmBasicBlock> lowered : normalEdges.blocks()) {
            for (LlvmBasicBlock block : lowered) {
                blocks.add(withPhiInstructions(
                        block,
                        method,
                        phiIncoming.getOrDefault(block.name(), List.of()),
                        localReferences));
            }
        }
        return new LlvmFunction(
                nameMangler.functionName(method),
                linkage,
                visibility,
                functionReturnType,
                parameters,
                blocks,
                LlvmNativeUnwindSemantics.PROVEN_ABSENT);
    }

    private NormalEdgeLowering lowerNormalEdges(
            IrMethod method,
            List<LlvmExceptionFlowLowerer.BlockResult> loweredBlocks,
            Optional<NativeLocalReferencePlan> localReferencePlan,
            Optional<LlvmLocalReferenceLowering> localReferences) {
        Set<String> usedNames = loweredBlocks.stream()
                .flatMap(result -> result.blocks().stream())
                .map(LlvmBasicBlock::name)
                .collect(java.util.stream.Collectors.toCollection(
                        java.util.LinkedHashSet::new));
        ArrayList<List<LlvmBasicBlock>> result = new ArrayList<>();
        LinkedHashMap<NativeLocalReferenceNormalEdge, String>
                predecessors = new LinkedHashMap<>();
        LlvmLocalReferenceEdgeLowerer edgeLowerer =
                new LlvmLocalReferenceEdgeLowerer();
        for (int index = 0; index < method.blocks().size(); index++) {
            LlvmLocalReferenceEdgeLowerer.EdgeResult lowered =
                    localReferencePlan.isPresent()
                            ? edgeLowerer.lower(
                                    method.blocks().get(index),
                                    loweredBlocks.get(index),
                                    localReferencePlan.orElseThrow(),
                                    localReferences.orElseThrow(),
                                    usedNames)
                            : edgeLowerer.splitParallelEdges(
                                    method.blocks().get(index),
                                    loweredBlocks.get(index),
                                    method.methodKey(),
                                    usedNames);
            result.add(lowered.blocks());
            predecessors.putAll(lowered.predecessorByEdge());
        }
        return new NormalEdgeLowering(result, predecessors);
    }

    private LlvmBasicBlock activationPrologue(
            IrMethod method,
            boolean cacheReferenceSidecar,
            LlvmJvalueScratchPlan jvalueScratch) {
        if (method.blocks().isEmpty()) {
            throw new IllegalArgumentException(
                    "activation prologue requires a method entry block");
        }
        if (!method.blocks().get(0).parameters().isEmpty()) {
            throw new IllegalArgumentException(
                    "activation prologue requires a parameter-free method entry block");
        }
        Set<String> names = method.blocks().stream()
                .map(IrBlock::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        String base = "j2ll.activation.prologue."
                + stableHash(method.methodKey());
        String name = base;
        int suffix = 1;
        while (names.contains(name)) {
            name = base + "." + suffix++;
        }
        ArrayList<LlvmInstruction> instructions = new ArrayList<>();
        if (cacheReferenceSidecar) {
            instructions.addAll(
                    nativeFields.referenceSidecarCacheInitialization());
        }
        if (jvalueScratch.required()) {
            instructions.add(jvalueScratch.allocation());
        }
        return new LlvmBasicBlock(
                name,
                instructions,
                LlvmTerminator.gotoBlock(method.blocks().get(0).name()));
    }

    private LlvmBasicBlock withPhiInstructions(
            LlvmBasicBlock block,
            IrMethod method,
            List<PhiIncoming> phiIncoming,
            Optional<LlvmLocalReferenceLowering> localReferences) {
        IrBlock source = method.blocks().stream()
                .filter(candidate -> candidate.name().equals(block.name()))
                .findFirst()
                .orElse(null);
        if (source == null || source.parameters().isEmpty()) {
            return block;
        }
        ArrayList<LlvmInstruction> instructions = new ArrayList<>();
        for (int index = 0; index < source.parameters().size(); index++) {
            int parameterIndex = index;
            IrValue parameter = source.parameters().get(index);
            ArrayList<String> incoming = new ArrayList<>();
            for (PhiIncoming predecessor : phiIncoming) {
                if (predecessor.arguments().size() != source.parameters().size()) {
                    throw new IllegalArgumentException(
                            "LLVM phi incoming argument count does not match target block parameters");
                }
                incoming.add("[ " + predecessor.arguments().get(index).name()
                        + ", %" + predecessor.predecessorBlock() + " ]");
            }
            instructions.add(LlvmInstruction.rawProvenNoNativeUnwind(
                    Optional.of(parameter.name()),
                    "phi " + typeLowerer.lower(parameter.type()).text() + " "
                            + String.join(", ", incoming)));
            if (parameter.type()
                    == xyz.melodysky.ir.model.IrType.REFERENCE) {
                localReferences.flatMap(lowering ->
                                lowering.ownershipPhi(
                                        parameter,
                                        phiIncoming.stream()
                                                .map(predecessor ->
                                                        new LlvmLocalReferenceLowering
                                                                .OwnershipIncoming(
                                                                predecessor
                                                                        .predecessorBlock(),
                                                                        predecessor
                                                                        .arguments()
                                                                        .get(parameterIndex)))
                                                .toList()))
                        .ifPresent(instructions::add);
            }
        }
        instructions.addAll(block.instructions());
        return new LlvmBasicBlock(block.name(), instructions, block.terminator());
    }

    private Map<String, List<PhiIncoming>> phiIncoming(
            IrMethod method,
            Map<String, String> normalExitBlocks,
            List<LlvmExceptionFlowLowerer.ExceptionalIncoming> exceptionalIncoming,
            Map<NativeLocalReferenceNormalEdge, String>
                    normalEdgePredecessors) {
        HashMap<String, ArrayList<PhiIncoming>> incoming = new HashMap<>();
        for (IrBlock block : method.blocks()) {
            String predecessor = normalExitBlocks.getOrDefault(block.name(), block.name());
            switch (block.terminator().kind()) {
                case GOTO -> addPhiIncoming(
                        incoming,
                        block.terminator().target().orElseThrow(),
                        normalPredecessor(
                                block,
                                0,
                                block.terminator().target().orElseThrow(),
                                predecessor,
                                normalEdgePredecessors),
                        block.terminator().targetArguments());
                case THROW -> {
                }
                case BRANCH -> {
                    addPhiIncoming(
                            incoming,
                            block.terminator().trueTarget().orElseThrow(),
                            normalPredecessor(
                                    block,
                                    0,
                                    block.terminator()
                                            .trueTarget()
                                            .orElseThrow(),
                                    predecessor,
                                    normalEdgePredecessors),
                            block.terminator().trueTargetArguments());
                    addPhiIncoming(
                            incoming,
                            block.terminator().falseTarget().orElseThrow(),
                            normalPredecessor(
                                    block,
                                    1,
                                    block.terminator()
                                            .falseTarget()
                                            .orElseThrow(),
                                    predecessor,
                                    normalEdgePredecessors),
                            block.terminator().falseTargetArguments());
                }
                case SWITCH -> {
                    addPhiIncoming(
                            incoming,
                            block.terminator().defaultTarget().orElseThrow(),
                            normalPredecessor(
                                    block,
                                    0,
                                    block.terminator()
                                            .defaultTarget()
                                            .orElseThrow(),
                                    predecessor,
                                    normalEdgePredecessors),
                            block.terminator().defaultTargetArguments());
                    for (int index = 0;
                            index < block.terminator().switchCases().size();
                            index++) {
                        var switchCase =
                                block.terminator()
                                        .switchCases()
                                        .get(index);
                        addPhiIncoming(
                                incoming,
                                switchCase.target(),
                                normalPredecessor(
                                        block,
                                        index + 1,
                                        switchCase.target(),
                                        predecessor,
                                        normalEdgePredecessors),
                                switchCase.arguments());
                    }
                }
                case RETURN -> {
                }
            }
        }
        for (LlvmExceptionFlowLowerer.ExceptionalIncoming exceptional : exceptionalIncoming) {
            addPhiIncoming(
                    incoming,
                    exceptional.target(),
                    exceptional.predecessorBlock(),
                    exceptional.arguments());
        }
        return incoming.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> List.copyOf(entry.getValue())));
    }

    private String normalPredecessor(
            IrBlock block,
            int ordinal,
            String target,
            String defaultPredecessor,
            Map<NativeLocalReferenceNormalEdge, String>
                    normalEdgePredecessors) {
        return normalEdgePredecessors.getOrDefault(
                new NativeLocalReferenceNormalEdge(
                        block.name(),
                        ordinal,
                        target),
                defaultPredecessor);
    }

    private void addPhiIncoming(
            Map<String, ArrayList<PhiIncoming>> incoming,
            String target,
            String predecessor,
            List<IrValue> arguments) {
        if (!arguments.isEmpty()) {
            incoming.computeIfAbsent(target, ignored -> new ArrayList<>())
                    .add(new PhiIncoming(predecessor, arguments));
        }
    }

    private LlvmTerminator lowerTerminator(IrBlock block) {
        if (block.terminator().kind() == IrTerminatorKind.GOTO) {
            return LlvmTerminator.gotoBlock(block.terminator().target().orElseThrow());
        }
        if (block.terminator().kind() == IrTerminatorKind.BRANCH) {
            return LlvmTerminator.branch(
                    block.terminator().condition().orElseThrow().name(),
                    block.terminator().trueTarget().orElseThrow(),
                    block.terminator().falseTarget().orElseThrow());
        }
        if (block.terminator().kind() == IrTerminatorKind.SWITCH) {
            return LlvmTerminator.switchOn(
                    block.terminator().switchValue().orElseThrow().name(),
                    block.terminator().defaultTarget().orElseThrow(),
                    block.terminator().switchCases().stream()
                            .map(switchCase -> new LlvmSwitchCase(switchCase.key(), switchCase.target()))
                            .toList());
        }
        if (block.terminator().kind() == IrTerminatorKind.THROW) {
            return LlvmTerminator.throwValue(block.terminator().value().orElseThrow().name());
        }
        LlvmType returnType = block.terminator().value()
                .map(value -> typeLowerer.lower(value.type()))
                .orElse(LlvmType.VOID);
        return new LlvmTerminator(returnType, block.terminator().value().map(IrValue::name));
    }

    private List<LlvmInstruction> lowerInstructions(
            xyz.melodysky.ir.model.IrInstruction instruction,
            Set<String> directCallMethodKeys,
            Set<String> staticCallMethodKeys,
            Map<String, LlvmFunctionAbi> functionAbis,
            String blockName,
            int instructionIndex,
            LlvmJvalueScratchPlan jvalueScratch,
            LlvmReferenceIdentityLowering referenceIdentity) {
        Optional<BusinessStringConstantRef> businessString =
                BusinessStringConstantRef.fromInstruction(instruction);
        if (businessString.isPresent()) {
            return List.of(lowerBusinessStringConstant(
                    instruction,
                    businessString.orElseThrow()));
        }
        if (isCall(instruction.opcode())) {
            return lowerCallInstructions(
                    instruction,
                    directCallMethodKeys,
                    staticCallMethodKeys,
                    functionAbis,
                    stableHash(blockName + ":" + instructionIndex + ":" + instruction.symbol().orElse("")),
                    jvalueScratch);
        }
        if (instruction.opcode() == IrOpcode.GET_NATIVE_STATIC
                || instruction.opcode() == IrOpcode.PUT_NATIVE_STATIC) {
            return nativeFields.lower(
                    instruction,
                    stableHash(blockName + ":" + instructionIndex + ":" + instruction.symbol().orElse("")));
        }
        if (isReferenceCompare(instruction.opcode())) {
            return referenceIdentity.lower(instruction);
        }
        return List.of(lowerInstruction(instruction, directCallMethodKeys, functionAbis));
    }

    private LlvmInstruction lowerInstruction(
            xyz.melodysky.ir.model.IrInstruction instruction,
            Set<String> directCallMethodKeys,
            Map<String, LlvmFunctionAbi> functionAbis) {
        if (isConversion(instruction.opcode())) {
            return lowerConversion(instruction);
        }
        if (instruction.opcode() == IrOpcode.CONST_NULL) {
            return LlvmInstruction.rawProvenNoNativeUnwind(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "inttoptr i64 0 to ptr");
        }
        if (isSymbolicConstant(instruction.opcode())) {
            return lowerSymbolicConstant(instruction);
        }
        if (isPrimitiveCompare(instruction.opcode())) {
            return LlvmInstruction.rawProvenNoNativeUnwind(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "icmp " + comparePredicate(instruction.opcode()) + " i32 "
                            + instruction.operands().get(0).name() + ", "
                            + instruction.operands().get(1).name());
        }
        if (isJvmComparisonHelper(instruction.opcode())) {
            return lowerHelperCall(instruction, helperName(instruction.opcode()));
        }
        if (isArithmeticExceptionHelper(instruction.opcode())) {
            return lowerEnvBackedHelperCall(instruction, helperName(instruction.opcode()));
        }
        if (isMemoryFence(instruction.opcode())) {
            return lowerMemoryFence(instruction);
        }
        if (instruction.opcode() == IrOpcode.BITCAST_I32_TO_F32) {
            return LlvmInstruction.rawProvenNoNativeUnwind(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "bitcast i32 " + instruction.operands().get(0).name() + " to float");
        }
        if (instruction.opcode() == IrOpcode.BITCAST_I64_TO_F64) {
            return LlvmInstruction.rawProvenNoNativeUnwind(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "bitcast i64 " + instruction.operands().get(0).name() + " to double");
        }
        if (instruction.opcode() == IrOpcode.CONST_FLOAT) {
            int rawBits = Float.floatToRawIntBits(
                    instruction.floatLiteral().orElseThrow());
            return LlvmInstruction.rawProvenNoNativeUnwind(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "bitcast i32 " + rawBits + " to float");
        }
        if (instruction.opcode() == IrOpcode.CONST_DOUBLE) {
            long rawBits = Double.doubleToRawLongBits(
                    instruction.doubleLiteral().orElseThrow());
            return LlvmInstruction.rawProvenNoNativeUnwind(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "bitcast i64 " + rawBits + " to double");
        }
        if (isArrayHelper(instruction)) {
            return lowerEnvBackedHelperCall(instruction, arrayHelperName(instruction));
        }
        if (isAllocationHelper(instruction)) {
            return lowerAllocationHelper(instruction);
        }
        if (isTypeHelper(instruction)) {
            return lowerTypeHelper(instruction);
        }
        if (isRuntimeModelHelper(instruction.opcode())) {
            return lowerRuntimeModelHelper(instruction);
        }
        if (isFieldAccess(instruction.opcode())) {
            return lowerFieldAccess(instruction);
        }
        if (isCall(instruction.opcode())) {
            return lowerCall(instruction, directCallMethodKeys, functionAbis);
        }
        String opcode = switch (instruction.opcode()) {
            case CONST_INT -> "add";
            case CONST_LONG -> "add";
            case CONST_FLOAT, CONST_DOUBLE ->
                    throw new IllegalStateException("handled earlier");
            case CONST_STRING, CONST_CLASS, CONST_METHOD_TYPE, CONST_METHOD_HANDLE ->
                    throw new IllegalStateException("handled earlier");
            case CLASS_OBJECT, CLASS_INIT_GUARD, CLASS_INIT_BEGIN, CLASS_INIT_END, CLASS_INIT_FAILED ->
                    throw new IllegalStateException("handled earlier");
            case CLASS_INIT_HAPPENS_BEFORE, CLASS_INIT_ACTIVE_USE ->
                    throw new IllegalStateException("handled earlier");
            case ADD_I32 -> "add";
            case SUB_I32 -> "sub";
            case MUL_I32 -> "mul";
            case DIV_I32, REM_I32 -> throw new IllegalStateException("handled earlier");
            case NEG_I32 -> "sub";
            case SHL_I32 -> "shl";
            case SHR_I32 -> "ashr";
            case USHR_I32 -> "lshr";
            case AND_I32 -> "and";
            case OR_I32 -> "or";
            case XOR_I32 -> "xor";
            case BITCAST_I32_TO_F32 -> throw new IllegalStateException("handled earlier");
            case CMP_EQ_I32, CMP_NE_I32, CMP_LT_I32, CMP_LE_I32, CMP_GT_I32, CMP_GE_I32,
                    CMP_EQ_REF, CMP_NE_REF ->
                    throw new IllegalStateException("handled earlier");
            case ADD_I64 -> "add";
            case SUB_I64 -> "sub";
            case MUL_I64 -> "mul";
            case DIV_I64, REM_I64 -> throw new IllegalStateException("handled earlier");
            case NEG_I64 -> "sub";
            case SHL_I64 -> "shl";
            case SHR_I64 -> "ashr";
            case USHR_I64 -> "lshr";
            case AND_I64 -> "and";
            case OR_I64 -> "or";
            case XOR_I64 -> "xor";
            case BITCAST_I64_TO_F64 -> throw new IllegalStateException("handled earlier");
            case ADD_F32, ADD_F64 -> "fadd";
            case SUB_F32, SUB_F64 -> "fsub";
            case MUL_F32, MUL_F64 -> "fmul";
            case DIV_F32, DIV_F64 -> "fdiv";
            case REM_F32, REM_F64 -> "frem";
            case NEG_F32, NEG_F64 -> "fsub";
            case LCMP, FCMPL, FCMPG, DCMPL, DCMPG -> throw new IllegalStateException("handled earlier");
            case CONST_NULL, I2L, I2F, I2D, I2B, I2C, I2S, L2I, L2F, L2D,
                    F2I, F2L, F2D, D2I, D2L, D2F -> throw new IllegalStateException("handled earlier");
            case NEW_OBJECT, NEW_ARRAY, NEW_MULTI_ARRAY, ARRAY_LENGTH,
                    ARRAY_LOAD_I32, ARRAY_LOAD_I64, ARRAY_LOAD_F32, ARRAY_LOAD_F64, ARRAY_LOAD_REF,
                    ARRAY_STORE_I32, ARRAY_STORE_I64, ARRAY_STORE_F32, ARRAY_STORE_F64, ARRAY_STORE_REF,
                    CHECKCAST, INSTANCEOF -> throw new IllegalStateException("handled earlier");
            case GET_STATIC, PUT_STATIC, GET_NATIVE_STATIC, PUT_NATIVE_STATIC, GET_FIELD, PUT_FIELD,
                    CALL_STATIC, CALL_SPECIAL, CALL_DIRECT, CALL_VIRTUAL, CALL_INTERFACE, CALL_DYNAMIC,
                    CALL_RUNTIME_HELPER ->
                    throw new IllegalStateException("handled earlier");
            case MONITOR_ENTER, MONITOR_EXIT, MONITOR_EXIT_ON_EXCEPTION -> throw new IllegalStateException("handled earlier");
            case VOLATILE_READ_BARRIER, VOLATILE_WRITE_BARRIER, FINAL_FIELD_PUBLICATION,
                    MONITOR_HAPPENS_BEFORE, THREAD_START_HAPPENS_BEFORE, THREAD_JOIN_HAPPENS_BEFORE ->
                    throw new IllegalStateException("handled earlier");
        };
        List<String> operands = operands(instruction);
        return LlvmInstruction.provenNoNativeUnwind(
                Optional.of(instruction.result().orElseThrow().name()),
                typeLowerer.lower(instruction.result().orElseThrow().type()),
                opcode,
                operands);
    }

    private List<String> operands(xyz.melodysky.ir.model.IrInstruction instruction) {
        return switch (instruction.opcode()) {
            case CONST_INT -> List.of("0", Integer.toString(instruction.intLiteral().orElseThrow()));
            case CONST_LONG -> List.of("0", Long.toString(instruction.longLiteral().orElseThrow()));
            case CONST_FLOAT, CONST_DOUBLE ->
                    throw new IllegalStateException("handled earlier");
            case NEG_I32, NEG_I64 -> List.of("0", instruction.operands().get(0).name());
            case NEG_F32, NEG_F64 -> List.of("-0.0", instruction.operands().get(0).name());
            default -> instruction.operands().stream().map(IrValue::name).toList();
        };
    }

    private boolean isPrimitiveCompare(IrOpcode opcode) {
        return opcode == IrOpcode.CMP_EQ_I32
                || opcode == IrOpcode.CMP_NE_I32
                || opcode == IrOpcode.CMP_LT_I32
                || opcode == IrOpcode.CMP_LE_I32
                || opcode == IrOpcode.CMP_GT_I32
                || opcode == IrOpcode.CMP_GE_I32;
    }

    private boolean isReferenceCompare(IrOpcode opcode) {
        return opcode == IrOpcode.CMP_EQ_REF || opcode == IrOpcode.CMP_NE_REF;
    }

    private boolean isJvmComparisonHelper(IrOpcode opcode) {
        return opcode == IrOpcode.LCMP
                || opcode == IrOpcode.FCMPL
                || opcode == IrOpcode.FCMPG
                || opcode == IrOpcode.DCMPL
                || opcode == IrOpcode.DCMPG;
    }

    private boolean isArithmeticExceptionHelper(IrOpcode opcode) {
        return opcode == IrOpcode.DIV_I32
                || opcode == IrOpcode.REM_I32
                || opcode == IrOpcode.DIV_I64
                || opcode == IrOpcode.REM_I64;
    }

    private boolean isArrayHelper(xyz.melodysky.ir.model.IrInstruction instruction) {
        if (instruction.opcode() == IrOpcode.ARRAY_LENGTH
                || instruction.opcode() == IrOpcode.ARRAY_LOAD_REF
                || instruction.opcode() == IrOpcode.ARRAY_STORE_REF
                || instruction.opcode() == IrOpcode.ARRAY_LOAD_I64
                || instruction.opcode() == IrOpcode.ARRAY_STORE_I64
                || instruction.opcode() == IrOpcode.ARRAY_LOAD_F32
                || instruction.opcode() == IrOpcode.ARRAY_STORE_F32
                || instruction.opcode() == IrOpcode.ARRAY_LOAD_F64
                || instruction.opcode() == IrOpcode.ARRAY_STORE_F64) {
            return true;
        }
        if (instruction.opcode() != IrOpcode.ARRAY_LOAD_I32
                && instruction.opcode() != IrOpcode.ARRAY_STORE_I32) {
            return false;
        }
        return instruction.symbol()
                .map(symbol -> symbol.equals("int")
                        || symbol.equals("byteOrBoolean")
                        || symbol.equals("short")
                        || symbol.equals("char"))
                .orElse(false);
    }

    private boolean isAllocationHelper(xyz.melodysky.ir.model.IrInstruction instruction) {
        if (instruction.opcode() == IrOpcode.NEW_OBJECT) {
            return true;
        }
        if (instruction.opcode() != IrOpcode.NEW_ARRAY) {
            return false;
        }
        return instruction.symbol()
                .map(symbol -> symbol.startsWith("primitiveArray:") || symbol.startsWith("referenceArray:"))
                .orElse(false);
    }

    private boolean isSymbolicConstant(IrOpcode opcode) {
        return opcode == IrOpcode.CONST_STRING
                || opcode == IrOpcode.CONST_CLASS
                || opcode == IrOpcode.CONST_METHOD_TYPE
                || opcode == IrOpcode.CONST_METHOD_HANDLE;
    }

    private boolean isConversion(IrOpcode opcode) {
        return opcode == IrOpcode.I2L
                || opcode == IrOpcode.I2F
                || opcode == IrOpcode.I2D
                || opcode == IrOpcode.I2B
                || opcode == IrOpcode.I2C
                || opcode == IrOpcode.I2S
                || opcode == IrOpcode.L2I
                || opcode == IrOpcode.L2F
                || opcode == IrOpcode.L2D
                || opcode == IrOpcode.F2I
                || opcode == IrOpcode.F2L
                || opcode == IrOpcode.F2D
                || opcode == IrOpcode.D2I
                || opcode == IrOpcode.D2L
                || opcode == IrOpcode.D2F;
    }

    private boolean isRuntimeModelHelper(IrOpcode opcode) {
        return opcode == IrOpcode.CLASS_OBJECT
                || opcode == IrOpcode.CLASS_INIT_GUARD
                || opcode == IrOpcode.CLASS_INIT_BEGIN
                || opcode == IrOpcode.CLASS_INIT_END
                || opcode == IrOpcode.CLASS_INIT_FAILED
                || opcode == IrOpcode.NEW_MULTI_ARRAY
                || opcode == IrOpcode.ARRAY_LENGTH
                || opcode == IrOpcode.ARRAY_LOAD_I32
                || opcode == IrOpcode.ARRAY_LOAD_I64
                || opcode == IrOpcode.ARRAY_LOAD_F32
                || opcode == IrOpcode.ARRAY_LOAD_F64
                || opcode == IrOpcode.ARRAY_LOAD_REF
                || opcode == IrOpcode.ARRAY_STORE_I32
                || opcode == IrOpcode.ARRAY_STORE_I64
                || opcode == IrOpcode.ARRAY_STORE_F32
                || opcode == IrOpcode.ARRAY_STORE_F64
                || opcode == IrOpcode.ARRAY_STORE_REF
                || opcode == IrOpcode.CHECKCAST
                || opcode == IrOpcode.INSTANCEOF
                || opcode == IrOpcode.MONITOR_ENTER
                || opcode == IrOpcode.MONITOR_EXIT
                || opcode == IrOpcode.MONITOR_EXIT_ON_EXCEPTION
                || opcode == IrOpcode.THREAD_START_HAPPENS_BEFORE
                || opcode == IrOpcode.THREAD_JOIN_HAPPENS_BEFORE;
    }

    private boolean isMemoryFence(IrOpcode opcode) {
        return opcode == IrOpcode.VOLATILE_READ_BARRIER
                || opcode == IrOpcode.VOLATILE_WRITE_BARRIER
                || opcode == IrOpcode.FINAL_FIELD_PUBLICATION
                || opcode == IrOpcode.MONITOR_HAPPENS_BEFORE
                || opcode == IrOpcode.CLASS_INIT_HAPPENS_BEFORE
                || opcode == IrOpcode.CLASS_INIT_ACTIVE_USE;
    }

    private boolean isMonitorHelper(IrOpcode opcode) {
        return opcode == IrOpcode.MONITOR_ENTER
                || opcode == IrOpcode.MONITOR_EXIT
                || opcode == IrOpcode.MONITOR_EXIT_ON_EXCEPTION;
    }

    private boolean isClassInitHelper(IrOpcode opcode) {
        return opcode == IrOpcode.CLASS_OBJECT
                || opcode == IrOpcode.CLASS_INIT_GUARD
                || opcode == IrOpcode.CLASS_INIT_BEGIN
                || opcode == IrOpcode.CLASS_INIT_END
                || opcode == IrOpcode.CLASS_INIT_FAILED;
    }

    private boolean isEnvBackedRuntimeModelHelper(IrOpcode opcode) {
        return isMonitorHelper(opcode) || isClassInitHelper(opcode);
    }

    private boolean isCall(IrOpcode opcode) {
        return opcode == IrOpcode.CALL_STATIC
                || opcode == IrOpcode.CALL_SPECIAL
                || opcode == IrOpcode.CALL_DIRECT
                || opcode == IrOpcode.CALL_VIRTUAL
                || opcode == IrOpcode.CALL_INTERFACE
                || opcode == IrOpcode.CALL_DYNAMIC
                || opcode == IrOpcode.CALL_RUNTIME_HELPER;
    }

    private boolean isFieldAccess(IrOpcode opcode) {
        return opcode == IrOpcode.GET_STATIC
                || opcode == IrOpcode.PUT_STATIC
                || opcode == IrOpcode.GET_NATIVE_STATIC
                || opcode == IrOpcode.PUT_NATIVE_STATIC
                || opcode == IrOpcode.GET_FIELD
                || opcode == IrOpcode.PUT_FIELD;
    }

    private LlvmInstruction lowerFieldAccess(xyz.melodysky.ir.model.IrInstruction instruction) {
        String helper = localizedFieldHelper(instruction);
        String operation = localizedFieldOperation(instruction);
        String fieldKey = instruction.symbol().orElseThrow();
        if (instruction.opcode() == IrOpcode.GET_STATIC) {
            String type = typeLowerer.lower(instruction.result().orElseThrow().type()).text();
            return LlvmInstruction.rawProvenNoNativeUnwind(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "call " + type + " @" + helper
                            + "("
                            + localAbiArguments(
                                    RuntimeLocalAbiDomain.FIELD,
                                    operation,
                                    fieldKey,
                                    List.of(
                                            "ptr %j2ll_env",
                                            "ptr %j2ll_owner"))
                            + ")");
        }
        if (instruction.opcode() == IrOpcode.PUT_STATIC) {
            IrValue value = instruction.operands().get(0);
            return LlvmInstruction.rawProvenNoNativeUnwind(
                    Optional.empty(),
                    "call void @" + helper
                            + "("
                            + localAbiArguments(
                                    RuntimeLocalAbiDomain.FIELD,
                                    operation,
                                    fieldKey,
                                    List.of(
                                            "ptr %j2ll_env",
                                            "ptr %j2ll_owner",
                                            typedOperand(value)))
                            + ")");
        }
        if (instruction.opcode() == IrOpcode.GET_FIELD) {
            String type = typeLowerer.lower(instruction.result().orElseThrow().type()).text();
            return LlvmInstruction.rawProvenNoNativeUnwind(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "call " + type + " @" + helper + "("
                            + localAbiArguments(
                                    RuntimeLocalAbiDomain.FIELD,
                                    operation,
                                    fieldKey,
                                    List.of(
                                            "ptr %j2ll_env",
                                            typedOperand(
                                                    instruction.operands()
                                                            .get(0))))
                            + ")");
        }
        IrValue receiver = instruction.operands().get(0);
        IrValue value = instruction.operands().get(1);
        return LlvmInstruction.rawProvenNoNativeUnwind(
                Optional.empty(),
                "call void @" + helper + "("
                        + localAbiArguments(
                                RuntimeLocalAbiDomain.FIELD,
                                operation,
                                fieldKey,
                                List.of(
                                        "ptr %j2ll_env",
                                        typedOperand(receiver),
                                        typedOperand(value)))
                        + ")");
    }

    private String localizedFieldHelper(
            xyz.melodysky.ir.model.IrInstruction instruction) {
        String fieldKey = instruction.symbol().orElseThrow();
        return runtimeTokens.helperSymbol(
                RuntimeTokenDomain.FIELD_RUNTIME,
                localizedFieldOperation(instruction),
                fieldKey);
    }

    private String localizedFieldOperation(
            xyz.melodysky.ir.model.IrInstruction instruction) {
        String fieldKey = instruction.symbol().orElseThrow();
        int descriptor = fieldKey.indexOf('!');
        if (descriptor < 0 || descriptor == fieldKey.length() - 1) {
            throw new IllegalArgumentException("invalid field key: " + fieldKey);
        }
        String suffix = switch (fieldKey.charAt(descriptor + 1)) {
            case 'J' -> "i64";
            case 'F' -> "f32";
            case 'D' -> "f64";
            case 'L', '[' -> "ref";
            default -> "i32";
        };
        String operation = switch (instruction.opcode()) {
            case GET_STATIC -> "field_get_static_" + suffix;
            case PUT_STATIC -> "field_put_static_" + suffix;
            case GET_FIELD -> "field_get_instance_" + suffix;
            case PUT_FIELD -> "field_put_instance_" + suffix;
            default -> throw new IllegalArgumentException(
                    "not a JVM field access: " + instruction.opcode());
        };
        return operation;
    }

    private String fieldLlvmType(String fieldKey) {
        int descriptor = fieldKey.indexOf('!');
        if (descriptor < 0 || descriptor == fieldKey.length() - 1) {
            throw new IllegalArgumentException("invalid field key: " + fieldKey);
        }
        return switch (fieldKey.charAt(descriptor + 1)) {
            case 'J' -> "i64";
            case 'F' -> "float";
            case 'D' -> "double";
            case 'L', '[' -> "ptr";
            default -> "i32";
        };
    }

    private LlvmInstruction lowerCall(
            xyz.melodysky.ir.model.IrInstruction instruction,
            Set<String> directCallMethodKeys,
            Map<String, LlvmFunctionAbi> functionAbis) {
        if ((instruction.opcode() == IrOpcode.CALL_STATIC
                        || instruction.opcode() == IrOpcode.CALL_DIRECT
                        || isDirectSpecialCallInstruction(instruction))
                && instruction.symbol().filter(directCallMethodKeys::contains).isPresent()) {
            String methodKey = instruction.symbol().orElseThrow();
            String target = nameMangler.functionName(methodKey);
            LlvmFunctionAbi targetAbi = Optional.ofNullable(functionAbis.get(methodKey))
                    .orElseThrow(() -> new IllegalArgumentException(
                            "direct LLVM call target is outside the current class module: " + methodKey));
            String args = directCallArguments(targetAbi, instruction.operands());
            if (instruction.result().isPresent()) {
                String type = typeLowerer.lower(instruction.result().orElseThrow().type()).text();
                return preserveIrCallIndirection(instruction, LlvmInstruction.rawProvenNoNativeUnwind(
                        Optional.of(instruction.result().orElseThrow().name()),
                        "call " + type + " @" + target + "(" + args + ")"));
            }
            return preserveIrCallIndirection(
                    instruction,
                    LlvmInstruction.rawProvenNoNativeUnwind(Optional.empty(), "call void @" + target + "(" + args + ")"));
        }
        if (isConstructorCallHelperInstruction(instruction)) {
            String token = dispatchToken(instruction.symbol().orElseThrow());
            if (instruction.symbol().orElseThrow().endsWith("!()V")) {
                return LlvmInstruction.rawProvenNoNativeUnwind(
                        Optional.empty(),
                        "call void @j2ll_rt_call_constructor_void(ptr %j2ll_env, "
                                + typedOperand(instruction.operands().get(0)) + ", " + token + ")");
            }
            return LlvmInstruction.rawProvenNoNativeUnwind(
                    Optional.empty(),
                    "call void @j2ll_rt_call_constructor_void_i32_i32(ptr %j2ll_env, "
                            + typedOperand(instruction.operands().get(0)) + ", " + token + ", "
                            + typedOperand(instruction.operands().get(1)) + ", "
                            + typedOperand(instruction.operands().get(2)) + ")");
        }
        if (isDispatchHelperInstruction(instruction)) {
            String helper = dispatchHelperName(instruction);
            String receiver = typedOperand(instruction.operands().get(0));
            String token = dispatchToken(instruction.symbol().orElseThrow());
            String arguments = dispatchHelperArguments(instruction, receiver, token);
            String resultType = typeLowerer.lower(instruction.result().orElseThrow().type()).text();
            return LlvmInstruction.rawProvenNoNativeUnwind(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "call " + resultType + " @" + helper + "(ptr %j2ll_env, " + arguments + ")");
        }
        String symbol = instruction.opcode() == IrOpcode.CALL_DYNAMIC
                ? stableHash(instruction.symbol().orElseThrow())
                : safeSymbol(instruction.symbol().orElseThrow());
        if (instruction.opcode() == IrOpcode.CALL_RUNTIME_HELPER) {
            symbol = runtimeHelperBaseSymbol(instruction.symbol().orElseThrow());
        }
        if (instruction.opcode() == IrOpcode.CALL_RUNTIME_HELPER
                && isEnvBackedRuntimeHelperSymbol(symbol)) {
            return lowerEnvBackedRuntimeCall(instruction, symbol);
        }
        String args = typedOperands(instruction.operands());
        String prefix = callPrefix(instruction.opcode());
        if (instruction.result().isPresent()) {
            String type = typeLowerer.lower(instruction.result().orElseThrow().type()).text();
            return LlvmInstruction.rawProvenNoNativeUnwind(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "call " + type + " @" + prefix + symbol + "(" + args + ")");
        }
        return LlvmInstruction.rawProvenNoNativeUnwind(Optional.empty(), "call void @" + prefix + symbol + "(" + args + ")");
    }

    private LlvmInstruction preserveIrCallIndirection(
            xyz.melodysky.ir.model.IrInstruction instruction,
            LlvmInstruction lowered) {
        return instruction.callIndirection()
                .map(reference -> lowered.withIrCallIndirection(new LlvmIrCallIndirectionRef(
                        reference.groupId(),
                        reference.entryId(),
                        reference.mode())))
                .orElse(lowered);
    }

    private String directCallArguments(LlvmFunctionAbi targetAbi, List<IrValue> operands) {
        ArrayList<String> arguments = new ArrayList<>();
        if (targetAbi.passesJniEnv()) {
            arguments.add("ptr %j2ll_env");
        }
        if (targetAbi.passesOwnerClass()) {
            arguments.add("ptr %j2ll_owner");
        }
        arguments.addAll(operands.stream().map(this::typedOperand).toList());
        return String.join(", ", arguments);
    }

    private List<LlvmInstruction> lowerCallInstructions(
            xyz.melodysky.ir.model.IrInstruction instruction,
            Set<String> directCallMethodKeys,
            Set<String> staticCallMethodKeys,
            Map<String, LlvmFunctionAbi> functionAbis,
            String scratchSuffix,
            LlvmJvalueScratchPlan jvalueScratch) {
        if (isConstructorCallHelperInstruction(instruction)) {
            return lowerConstructorCallBridge(
                    instruction,
                    scratchSuffix,
                    jvalueScratch);
        }
        if (isStaticCallBridgeInstruction(instruction, staticCallMethodKeys)) {
            return lowerStaticCallBridge(
                    instruction,
                    scratchSuffix,
                    jvalueScratch);
        }
        if (isDispatchHelperInstruction(instruction)) {
            return lowerDispatchCallBridge(
                    instruction,
                    scratchSuffix,
                    jvalueScratch);
        }
        return List.of(lowerCall(instruction, directCallMethodKeys, functionAbis));
    }

    private List<IrValue> jvalueScratchArguments(
            xyz.melodysky.ir.model.IrInstruction instruction,
            Set<String> staticCallMethodKeys) {
        if (isConstructorCallHelperInstruction(instruction)
                || isDispatchHelperInstruction(instruction)) {
            return instruction.operands().subList(
                    1,
                    instruction.operands().size());
        }
        if (isStaticCallBridgeInstruction(
                instruction,
                staticCallMethodKeys)) {
            return instruction.operands();
        }
        return List.of();
    }

    private List<LlvmInstruction> lowerConstructorCallBridge(
            xyz.melodysky.ir.model.IrInstruction instruction,
            String scratchSuffix,
            LlvmJvalueScratchPlan jvalueScratch) {
        ArrayList<LlvmInstruction> lowered = new ArrayList<>();
        LlvmJvalueScratchPlan.ScratchUse scratch = jvalueScratch.use(
                instruction.operands().subList(1, instruction.operands().size()),
                scratchSuffix,
                this::typedOperand,
                this::jvalueStoreAlign);
        lowered.addAll(scratch.instructions());
        String argsPointer = scratch.pointerOperand();
        String methodKey = instruction.symbol().orElseThrow();
        String operation = "constructor_call";
        String helper = localizedDispatchHelper(
                operation,
                methodKey);
        lowered.add(LlvmInstruction.rawProvenNoNativeUnwind(
                Optional.empty(),
                "call void @" + helper + "("
                        + localAbiArguments(
                                RuntimeLocalAbiDomain.DISPATCH,
                                operation,
                                methodKey,
                                List.of(
                                        "ptr %j2ll_env",
                                        typedOperand(
                                                instruction.operands().get(0)),
                                        argsPointer))
                        + ")"));
        return List.copyOf(lowered);
    }

    private List<LlvmInstruction> lowerStaticCallBridge(
            xyz.melodysky.ir.model.IrInstruction instruction,
            String scratchSuffix,
            LlvmJvalueScratchPlan jvalueScratch) {
        ArrayList<LlvmInstruction> lowered = new ArrayList<>();
        LlvmJvalueScratchPlan.ScratchUse scratch = jvalueScratch.use(
                instruction.operands(),
                scratchSuffix,
                this::typedOperand,
                this::jvalueStoreAlign);
        lowered.addAll(scratch.instructions());
        String argsPointer = scratch.pointerOperand();
        String methodKey = instruction.symbol().orElseThrow();
        String descriptor = methodDescriptor(methodKey).orElseThrow();
        String returnType = staticCallBridgeReturnType(descriptor);
        String operation = "static_call_"
                + dispatchDescriptorSuffix(descriptor);
        String helper = localizedDispatchHelper(
                operation,
                methodKey);
        String call = "call " + returnType + " @" + helper
                + "("
                + localAbiArguments(
                        RuntimeLocalAbiDomain.DISPATCH,
                        operation,
                        methodKey,
                        List.of("ptr %j2ll_env", argsPointer))
                + ")";
        lowered.add(LlvmInstruction.rawProvenNoNativeUnwind(
                instruction.result().map(IrValue::name),
                call));
        return List.copyOf(lowered);
    }

    private List<LlvmInstruction> lowerDispatchCallBridge(
            xyz.melodysky.ir.model.IrInstruction instruction,
            String scratchSuffix,
            LlvmJvalueScratchPlan jvalueScratch) {
        ArrayList<LlvmInstruction> lowered = new ArrayList<>();
        LlvmJvalueScratchPlan.ScratchUse scratch = jvalueScratch.use(
                instruction.operands().subList(1, instruction.operands().size()),
                scratchSuffix,
                this::typedOperand,
                this::jvalueStoreAlign);
        lowered.addAll(scratch.instructions());
        String argsPointer = scratch.pointerOperand();
        String methodKey = instruction.symbol().orElseThrow();
        String descriptor = methodDescriptor(methodKey).orElseThrow();
        String returnType = staticCallBridgeReturnType(descriptor);
        String operation = dispatchOperationPrefix(instruction)
                + dispatchDescriptorSuffix(descriptor);
        String helper = localizedDispatchHelper(
                operation,
                methodKey);
        String receiver = typedOperand(instruction.operands().get(0));
        String call = "call " + returnType + " @" + helper + "("
                + localAbiArguments(
                        RuntimeLocalAbiDomain.DISPATCH,
                        operation,
                        methodKey,
                        List.of(
                                "ptr %j2ll_env",
                                receiver,
                                argsPointer))
                + ")";
        lowered.add(LlvmInstruction.rawProvenNoNativeUnwind(instruction.result().map(IrValue::name), call));
        return List.copyOf(lowered);
    }

    private int jvalueStoreAlign(IrValue argument) {
        return switch (argument.type()) {
            case I64, F64, REFERENCE -> 8;
            case I32, F32 -> 4;
            case I1 -> 1;
            case VOID -> throw new IllegalArgumentException("void cannot be a jvalue argument");
        };
    }

    private LlvmInstruction lowerAllocationHelper(xyz.melodysky.ir.model.IrInstruction instruction) {
        String symbol = instruction.symbol().orElseThrow();
        if (instruction.opcode() == IrOpcode.NEW_OBJECT) {
            String helper = runtimeTokens.helperSymbol(
                    RuntimeTokenDomain.CLASS_RUNTIME,
                    "alloc_object",
                    symbol);
            return LlvmInstruction.rawProvenNoNativeUnwind(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "call ptr @" + helper + "(ptr %j2ll_env)");
        }
        if (symbol.equals("primitiveArray:byte") || symbol.equals("primitiveArray:boolean")) {
            return LlvmInstruction.rawProvenNoNativeUnwind(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "call ptr @j2ll_rt_new_byte_array(ptr %j2ll_env, " + typedOperand(instruction.operands().get(0)) + ")");
        }
        if (symbol.equals("primitiveArray:short")) {
            return LlvmInstruction.rawProvenNoNativeUnwind(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "call ptr @j2ll_rt_new_short_array(ptr %j2ll_env, " + typedOperand(instruction.operands().get(0)) + ")");
        }
        if (symbol.equals("primitiveArray:char")) {
            return LlvmInstruction.rawProvenNoNativeUnwind(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "call ptr @j2ll_rt_new_char_array(ptr %j2ll_env, " + typedOperand(instruction.operands().get(0)) + ")");
        }
        if (symbol.equals("primitiveArray:int")) {
            return LlvmInstruction.rawProvenNoNativeUnwind(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "call ptr @j2ll_rt_new_int_array(ptr %j2ll_env, " + typedOperand(instruction.operands().get(0)) + ")");
        }
        if (symbol.equals("primitiveArray:long")) {
            return LlvmInstruction.rawProvenNoNativeUnwind(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "call ptr @j2ll_rt_new_long_array(ptr %j2ll_env, " + typedOperand(instruction.operands().get(0)) + ")");
        }
        if (symbol.equals("primitiveArray:float")) {
            return LlvmInstruction.rawProvenNoNativeUnwind(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "call ptr @j2ll_rt_new_float_array(ptr %j2ll_env, " + typedOperand(instruction.operands().get(0)) + ")");
        }
        if (symbol.equals("primitiveArray:double")) {
            return LlvmInstruction.rawProvenNoNativeUnwind(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "call ptr @j2ll_rt_new_double_array(ptr %j2ll_env, " + typedOperand(instruction.operands().get(0)) + ")");
        }
        String helper = runtimeTokens.helperSymbol(
                RuntimeTokenDomain.CLASS_RUNTIME,
                "new_object_array",
                symbol);
        return LlvmInstruction.rawProvenNoNativeUnwind(
                Optional.of(instruction.result().orElseThrow().name()),
                "call ptr @" + helper + "(ptr %j2ll_env, "
                        + typedOperand(instruction.operands().get(0)) + ")");
    }

    private LlvmInstruction lowerTypeHelper(xyz.melodysky.ir.model.IrInstruction instruction) {
        String key = instruction.symbol().orElseThrow();
        String operation = instruction.opcode() == IrOpcode.CHECKCAST
                ? "checkcast"
                : "instanceof";
        String helper = runtimeTokens.helperSymbol(
                RuntimeTokenDomain.CLASS_RUNTIME,
                operation,
                key);
        if (instruction.opcode() == IrOpcode.CHECKCAST) {
            return LlvmInstruction.rawProvenNoNativeUnwind(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "call ptr @" + helper + "(ptr %j2ll_env, "
                            + typedOperand(instruction.operands().get(0)) + ")");
        }
        return LlvmInstruction.rawProvenNoNativeUnwind(
                Optional.of(instruction.result().orElseThrow().name()),
                "call i32 @" + helper + "(ptr %j2ll_env, "
                        + typedOperand(instruction.operands().get(0)) + ")");
    }

    private LlvmInstruction lowerEnvBackedRuntimeCall(
            xyz.melodysky.ir.model.IrInstruction instruction,
            String symbol) {
        if (instruction.symbol().orElse("").startsWith(
                "j2ll_rt_class_for_name_static|class:")) {
            String identity = instruction.symbol().orElseThrow()
                    .substring("j2ll_rt_class_for_name_static|class:".length());
            String helper = runtimeTokens.helperSymbol(
                    RuntimeTokenDomain.CLASS_OBJECT,
                    "class_for_name",
                    identity);
            return LlvmInstruction.rawProvenNoNativeUnwind(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "call ptr @" + helper + "(ptr %j2ll_env, "
                            + typedOperand(instruction.operands().get(1))
                            + ")");
        }
        Optional<String> metadataKey = runtimeMetadataKey(instruction);
        if (metadataKey.isPresent()) {
            String key = metadataKey.orElseThrow();
            String base = runtimeHelperBaseSymbol(
                    instruction.symbol().orElseThrow());
            String operation;
            RuntimeTokenDomain domain;
            if (base.equals("j2ll_rt_get_declared_method")
                    && key.startsWith("method:")) {
                operation = "reflection_lookup_method";
                domain = RuntimeTokenDomain.REFLECTION_METHOD;
            } else if (base.equals("j2ll_rt_get_declared_constructor")
                    && key.startsWith("constructor:")) {
                operation = "reflection_lookup_constructor";
                domain = RuntimeTokenDomain.REFLECTION_METHOD;
            } else if (base.equals("j2ll_rt_get_declared_field")
                    && key.startsWith("field:")) {
                operation = "reflection_lookup_field";
                domain = RuntimeTokenDomain.REFLECTION_FIELD;
            } else {
                operation = null;
                domain = null;
            }
            if (operation != null) {
                String helper = runtimeTokens.helperSymbol(
                        domain,
                        operation,
                        key);
                return LlvmInstruction.rawProvenNoNativeUnwind(
                        Optional.of(instruction.result().orElseThrow().name()),
                        "call ptr @" + helper + "("
                                + localAbiArguments(
                                        RuntimeLocalAbiDomain.REFLECTION,
                                        operation,
                                        key,
                                        List.of("ptr %j2ll_env"))
                                + ")");
            }
        }
        if (instruction.symbol().orElse("").startsWith(
                "j2ll_rt_lambda_new|lambda:")) {
            String identity = runtimeMetadataKey(instruction)
                    .orElseThrow();
            String helper = runtimeTokens.helperSymbol(
                    RuntimeTokenDomain.LAMBDA,
                    "lambda_new",
                    identity);
            return LlvmInstruction.rawProvenNoNativeUnwind(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "call ptr @" + helper + "(ptr %j2ll_env, "
                            + typedOperand(
                                    instruction.operands().get(1))
                            + ")");
        }
        String args = typedOperands(instruction.operands());
        String arguments = args.isEmpty() ? "ptr %j2ll_env" : "ptr %j2ll_env, " + args;
        if (instruction.result().isPresent()) {
            String type = typeLowerer.lower(instruction.result().orElseThrow().type()).text();
            return LlvmInstruction.rawProvenNoNativeUnwind(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "call " + type + " @" + symbol + "(" + arguments + ")");
        }
        return LlvmInstruction.rawProvenNoNativeUnwind(Optional.empty(), "call void @" + symbol + "(" + arguments + ")");
    }

    private LlvmInstruction lowerConversion(xyz.melodysky.ir.model.IrInstruction instruction) {
        IrValue operand = instruction.operands().get(0);
        String operandType = typeLowerer.lower(operand.type()).text();
        String resultName = instruction.result().orElseThrow().name();
        return switch (instruction.opcode()) {
            case I2L -> LlvmInstruction.rawProvenNoNativeUnwind(Optional.of(resultName), "sext i32 " + operand.name() + " to i64");
            case I2F -> LlvmInstruction.rawProvenNoNativeUnwind(Optional.of(resultName), "sitofp i32 " + operand.name() + " to float");
            case I2D -> LlvmInstruction.rawProvenNoNativeUnwind(Optional.of(resultName), "sitofp i32 " + operand.name() + " to double");
            case L2I -> LlvmInstruction.rawProvenNoNativeUnwind(Optional.of(resultName), "trunc i64 " + operand.name() + " to i32");
            case L2F -> LlvmInstruction.rawProvenNoNativeUnwind(Optional.of(resultName), "sitofp i64 " + operand.name() + " to float");
            case L2D -> LlvmInstruction.rawProvenNoNativeUnwind(Optional.of(resultName), "sitofp i64 " + operand.name() + " to double");
            case F2D -> LlvmInstruction.rawProvenNoNativeUnwind(Optional.of(resultName), "fpext float " + operand.name() + " to double");
            case D2F -> LlvmInstruction.rawProvenNoNativeUnwind(Optional.of(resultName), "fptrunc double " + operand.name() + " to float");
            case I2B, I2C, I2S, F2I, F2L, D2I, D2L -> LlvmInstruction.rawProvenNoNativeUnwind(
                    Optional.of(resultName),
                    "call " + typeLowerer.lower(instruction.result().orElseThrow().type()).text()
                            + " @" + helperName(instruction.opcode()) + "(" + operandType + " " + operand.name() + ")");
            default -> throw new IllegalArgumentException("not a conversion opcode: " + instruction.opcode());
        };
    }

    private LlvmInstruction lowerSymbolicConstant(xyz.melodysky.ir.model.IrInstruction instruction) {
        if (instruction.opcode() == IrOpcode.CONST_STRING) {
            return lowerBusinessStringConstant(
                    instruction,
                    BusinessStringConstantRef.fromInstruction(instruction)
                            .orElseThrow());
        }
        if (instruction.opcode() == IrOpcode.CONST_CLASS) {
            String identity = classIdentityFromConstSymbol(instruction.symbol().orElseThrow());
            String helper = runtimeTokens.helperSymbol(
                    RuntimeTokenDomain.CLASS_OBJECT,
                    "class_object",
                    identity);
            return LlvmInstruction.rawProvenNoNativeUnwind(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "call ptr @" + helper + "(ptr %j2ll_env)");
        }
        String helper = "j2ll_const_" + constantKind(instruction.opcode()) + "_" + stableHash(instruction.symbol().orElseThrow());
        return LlvmInstruction.rawProvenNoNativeUnwind(
                Optional.of(instruction.result().orElseThrow().name()),
                "call ptr @" + helper + "()");
    }

    private LlvmInstruction lowerBusinessStringConstant(
            xyz.melodysky.ir.model.IrInstruction instruction,
            BusinessStringConstantRef constant) {
        return LlvmInstruction.rawProvenNoNativeUnwind(
                Optional.of(instruction.result().orElseThrow().name()),
                "call ptr @"
                        + constant.helperSymbol(businessStringSymbols)
                        + "(ptr %j2ll_env)");
    }

    private LlvmInstruction lowerRuntimeModelHelper(xyz.melodysky.ir.model.IrInstruction instruction) {
        if (instruction.opcode() == IrOpcode.CLASS_OBJECT) {
            String identity = classIdentityFromConstSymbol(
                    instruction.symbol().orElseThrow());
            String helper = runtimeTokens.helperSymbol(
                    RuntimeTokenDomain.CLASS_OBJECT,
                    "class_object",
                    identity);
            return LlvmInstruction.rawProvenNoNativeUnwind(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "call ptr @" + helper + "(ptr %j2ll_env)");
        }
        String helper = runtimeModelHelperName(instruction);
        String args = typedOperands(instruction.operands());
        if (isEnvBackedRuntimeModelHelper(instruction.opcode())) {
            String arguments = args.isEmpty() ? "ptr %j2ll_env" : "ptr %j2ll_env, " + args;
            if (instruction.result().isPresent()) {
                String type = typeLowerer.lower(instruction.result().orElseThrow().type()).text();
                return LlvmInstruction.rawProvenNoNativeUnwind(
                        Optional.of(instruction.result().orElseThrow().name()),
                        "call " + type + " @" + helper + "(" + arguments + ")");
            }
            return LlvmInstruction.rawProvenNoNativeUnwind(Optional.empty(), "call void @" + helper + "(" + arguments + ")");
        }
        if (instruction.result().isPresent()) {
            String type = typeLowerer.lower(instruction.result().orElseThrow().type()).text();
            return LlvmInstruction.rawProvenNoNativeUnwind(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "call " + type + " @" + helper + "(" + args + ")");
        }
        return LlvmInstruction.rawProvenNoNativeUnwind(Optional.empty(), "call void @" + helper + "(" + args + ")");
    }

    private LlvmInstruction lowerMemoryFence(xyz.melodysky.ir.model.IrInstruction instruction) {
        String ordering = switch (instruction.opcode()) {
            case VOLATILE_READ_BARRIER -> "acquire";
            case VOLATILE_WRITE_BARRIER, FINAL_FIELD_PUBLICATION -> "release";
            case MONITOR_HAPPENS_BEFORE -> instruction.symbol().orElse("").equals("monitorEnter")
                    ? "acquire"
                    : "release";
            case CLASS_INIT_HAPPENS_BEFORE -> (instruction.symbol().orElse("").equals("classInitEnd")
                            || instruction.symbol().orElse("").equals("classInitFailed"))
                    ? "release"
                    : "acquire";
            case CLASS_INIT_ACTIVE_USE -> "acquire";
            default -> throw new IllegalArgumentException("not a memory fence opcode: " + instruction.opcode());
        };
        return LlvmInstruction.rawProvenNoNativeUnwind(Optional.empty(), "fence " + ordering);
    }

    private LlvmInstruction lowerHelperCall(xyz.melodysky.ir.model.IrInstruction instruction, String helperName) {
        String resultType = typeLowerer.lower(instruction.result().orElseThrow().type()).text();
        return LlvmInstruction.rawProvenNoNativeUnwind(
                Optional.of(instruction.result().orElseThrow().name()),
                "call " + resultType + " @" + helperName + "(" + typedOperands(instruction.operands()) + ")");
    }

    private LlvmInstruction lowerEnvBackedHelperCall(
            xyz.melodysky.ir.model.IrInstruction instruction,
            String helperName) {
        String args = typedOperands(instruction.operands());
        String arguments = args.isEmpty() ? "ptr %j2ll_env" : "ptr %j2ll_env, " + args;
        if (instruction.result().isPresent()) {
            String resultType = typeLowerer.lower(instruction.result().orElseThrow().type()).text();
            return LlvmInstruction.rawProvenNoNativeUnwind(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "call " + resultType + " @" + helperName + "(" + arguments + ")");
        }
        return LlvmInstruction.rawProvenNoNativeUnwind(Optional.empty(), "call void @" + helperName + "(" + arguments + ")");
    }

    private String typedOperands(List<IrValue> operands) {
        return operands.stream()
                .map(this::typedOperand)
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private String typedOperand(IrValue operand) {
        return typeLowerer.lower(operand.type()).text() + " " + operand.name();
    }

    private String safeSymbol(String value) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if ((ch >= 'a' && ch <= 'z')
                    || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9')) {
                result.append(ch);
            } else {
                result.append('_');
            }
        }
        return result.toString();
    }

    private String comparePredicate(IrOpcode opcode) {
        return switch (opcode) {
            case CMP_EQ_I32 -> "eq";
            case CMP_NE_I32 -> "ne";
            case CMP_LT_I32 -> "slt";
            case CMP_LE_I32 -> "sle";
            case CMP_GT_I32 -> "sgt";
            case CMP_GE_I32 -> "sge";
            default -> throw new IllegalArgumentException("not a primitive compare opcode: " + opcode);
        };
    }

    private String helperName(IrOpcode opcode) {
        return switch (opcode) {
            case I2B -> "j2ll_rt_i2b";
            case I2C -> "j2ll_rt_i2c";
            case I2S -> "j2ll_rt_i2s";
            case F2I -> "j2ll_rt_f2i";
            case F2L -> "j2ll_rt_f2l";
            case D2I -> "j2ll_rt_d2i";
            case D2L -> "j2ll_rt_d2l";
            case LCMP -> "j2ll_rt_lcmp";
            case FCMPL -> "j2ll_rt_fcmpl";
            case FCMPG -> "j2ll_rt_fcmpg";
            case DCMPL -> "j2ll_rt_dcmpl";
            case DCMPG -> "j2ll_rt_dcmpg";
            case DIV_I32 -> "j2ll_rt_div_i32";
            case REM_I32 -> "j2ll_rt_rem_i32";
            case DIV_I64 -> "j2ll_rt_div_i64";
            case REM_I64 -> "j2ll_rt_rem_i64";
            case ARRAY_LENGTH -> "j2ll_rt_array_length_i32";
            case ARRAY_LOAD_I32 -> "j2ll_rt_array_load_i32";
            case ARRAY_STORE_I32 -> "j2ll_rt_array_store_i32";
            default -> throw new IllegalArgumentException("opcode has no runtime helper: " + opcode);
        };
    }

    private String arrayHelperName(xyz.melodysky.ir.model.IrInstruction instruction) {
        return switch (instruction.opcode()) {
            case ARRAY_LENGTH -> "j2ll_rt_array_length_i32";
            case ARRAY_LOAD_REF -> "j2ll_rt_array_load_ref";
            case ARRAY_STORE_REF -> "j2ll_rt_array_store_ref";
            case ARRAY_LOAD_I32 -> instruction.symbol().orElse("").equals("byteOrBoolean")
                    ? "j2ll_rt_array_load_i8"
                    : instruction.symbol().orElse("").equals("short")
                            ? "j2ll_rt_array_load_i16"
                            : instruction.symbol().orElse("").equals("char")
                                    ? "j2ll_rt_array_load_u16"
                                    : "j2ll_rt_array_load_i32";
            case ARRAY_STORE_I32 -> instruction.symbol().orElse("").equals("byteOrBoolean")
                    ? "j2ll_rt_array_store_i8"
                    : instruction.symbol().orElse("").equals("short")
                            ? "j2ll_rt_array_store_i16"
                            : instruction.symbol().orElse("").equals("char")
                                    ? "j2ll_rt_array_store_u16"
                                    : "j2ll_rt_array_store_i32";
            case ARRAY_LOAD_I64 -> "j2ll_rt_array_load_i64";
            case ARRAY_STORE_I64 -> "j2ll_rt_array_store_i64";
            case ARRAY_LOAD_F32 -> "j2ll_rt_array_load_f32";
            case ARRAY_STORE_F32 -> "j2ll_rt_array_store_f32";
            case ARRAY_LOAD_F64 -> "j2ll_rt_array_load_f64";
            case ARRAY_STORE_F64 -> "j2ll_rt_array_store_f64";
            default -> throw new IllegalArgumentException("not an array helper opcode: " + instruction.opcode());
        };
    }

    private String runtimeModelHelperName(xyz.melodysky.ir.model.IrInstruction instruction) {
        IrOpcode opcode = instruction.opcode();
        if (opcode == IrOpcode.ARRAY_LENGTH) {
            return "j2ll_rt_array_length";
        }
        if (opcode == IrOpcode.CLASS_OBJECT) {
            return "j2ll_rt_class_object";
        }
        if (opcode == IrOpcode.CLASS_INIT_GUARD) {
            return "j2ll_rt_class_init_guard";
        }
        if (opcode == IrOpcode.CLASS_INIT_BEGIN) {
            return "j2ll_rt_class_init_begin";
        }
        if (opcode == IrOpcode.CLASS_INIT_END) {
            return "j2ll_rt_class_init_end";
        }
        if (opcode == IrOpcode.CLASS_INIT_FAILED) {
            return "j2ll_rt_class_init_failed";
        }
        if (opcode == IrOpcode.MONITOR_ENTER) {
            return "j2ll_rt_monitor_enter";
        }
        if (opcode == IrOpcode.MONITOR_EXIT) {
            return "j2ll_rt_monitor_exit";
        }
        if (opcode == IrOpcode.MONITOR_EXIT_ON_EXCEPTION) {
            return "j2ll_rt_monitor_exit_on_exception";
        }
        if (opcode == IrOpcode.THREAD_START_HAPPENS_BEFORE) {
            return "j2ll_rt_thread_start_happens_before";
        }
        if (opcode == IrOpcode.THREAD_JOIN_HAPPENS_BEFORE) {
            return "j2ll_rt_thread_join_happens_before";
        }
        return "j2ll_rt_" + runtimeModelKind(opcode) + "_" + stableHash(instruction.symbol().orElseThrow());
    }

    private String runtimeModelKind(IrOpcode opcode) {
        return switch (opcode) {
            case NEW_OBJECT -> "new_object";
            case NEW_ARRAY -> "new_array";
            case NEW_MULTI_ARRAY -> "new_multi_array";
            case ARRAY_LOAD_I32 -> "array_load_i32";
            case ARRAY_LOAD_I64 -> "array_load_i64";
            case ARRAY_LOAD_F32 -> "array_load_f32";
            case ARRAY_LOAD_F64 -> "array_load_f64";
            case ARRAY_LOAD_REF -> "array_load_ref";
            case ARRAY_STORE_I32 -> "array_store_i32";
            case ARRAY_STORE_I64 -> "array_store_i64";
            case ARRAY_STORE_F32 -> "array_store_f32";
            case ARRAY_STORE_F64 -> "array_store_f64";
            case ARRAY_STORE_REF -> "array_store_ref";
            case CHECKCAST -> "checkcast";
            case INSTANCEOF -> "instanceof";
            case MONITOR_ENTER, MONITOR_EXIT, MONITOR_EXIT_ON_EXCEPTION,
                    THREAD_START_HAPPENS_BEFORE, THREAD_JOIN_HAPPENS_BEFORE ->
                    throw new IllegalStateException("fixed JMM helper handled earlier");
            default -> throw new IllegalArgumentException("not a runtime model helper opcode: " + opcode);
        };
    }

    private String callPrefix(IrOpcode opcode) {
        return switch (opcode) {
            case CALL_STATIC, CALL_SPECIAL -> "j2ll_call_";
            case CALL_DIRECT, CALL_VIRTUAL -> "j2ll_call_virtual_";
            case CALL_INTERFACE -> "j2ll_call_interface_";
            case CALL_DYNAMIC -> "j2ll_call_dynamic_";
            case CALL_RUNTIME_HELPER -> "";
            default -> throw new IllegalArgumentException("not a call opcode: " + opcode);
        };
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
                || base.equals("j2ll_rt_object_get_class")
                || base.equals("j2ll_rt_class_get_class_loader")
                || base.equals("j2ll_rt_is_same_object")
                || base.equals("j2ll_rt_thread_sleep")
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
                || base.startsWith("j2ll_rt_var_handle_")
                || PureNativeJdkRuntimeHelpers
                        .isI32BigEndianFrameHelper(base);
    }

    private String runtimeHelperBaseSymbol(String symbol) {
        int separator = symbol.indexOf('|');
        return separator < 0 ? symbol : symbol.substring(0, separator);
    }

    private Optional<String> runtimeMetadataKey(
            xyz.melodysky.ir.model.IrInstruction instruction) {
        return instruction.symbol().flatMap(symbol -> {
            int separator = symbol.indexOf('|');
            if (separator < 0 || separator == symbol.length() - 1) {
                return Optional.empty();
            }
            return Optional.of(symbol.substring(separator + 1));
        });
    }

    private boolean isDispatchHelperInstruction(xyz.melodysky.ir.model.IrInstruction instruction) {
        return (instruction.opcode() == IrOpcode.CALL_DIRECT
                        || instruction.opcode() == IrOpcode.CALL_VIRTUAL
                        || instruction.opcode() == IrOpcode.CALL_INTERFACE)
                && dispatchHelperName(instruction) != null;
    }

    private String dispatchHelperName(xyz.melodysky.ir.model.IrInstruction instruction) {
        if (instruction.operands().isEmpty()
                || instruction.operands().get(0).type() != xyz.melodysky.ir.model.IrType.REFERENCE) {
            return null;
        }
        String descriptor = instruction.symbol()
                .flatMap(this::methodDescriptor)
                .orElse("");
        if (!descriptor.startsWith("(")
                || !operandsMatchDescriptor(descriptor, instruction.operands().subList(1, instruction.operands().size()))) {
            return null;
        }
        boolean iface = instruction.opcode() == IrOpcode.CALL_INTERFACE;
        return switch (descriptorReturnChar(descriptor)) {
            case 'V' -> instruction.result().isEmpty()
                    ? (iface ? "j2ll_rt_call_interface_void_a" : "j2ll_rt_call_virtual_void_a")
                    : null;
            case 'Z', 'B', 'C', 'S', 'I' -> instruction.result()
                    .filter(result -> result.type() == xyz.melodysky.ir.model.IrType.I32)
                    .map(_result -> iface ? "j2ll_rt_call_interface_i32_a" : "j2ll_rt_call_virtual_i32_a")
                    .orElse(null);
            case 'J' -> instruction.result()
                    .filter(result -> result.type() == xyz.melodysky.ir.model.IrType.I64)
                    .map(_result -> iface ? "j2ll_rt_call_interface_i64_a" : "j2ll_rt_call_virtual_i64_a")
                    .orElse(null);
            case 'F' -> instruction.result()
                    .filter(result -> result.type() == xyz.melodysky.ir.model.IrType.F32)
                    .map(_result -> iface ? "j2ll_rt_call_interface_f32_a" : "j2ll_rt_call_virtual_f32_a")
                    .orElse(null);
            case 'D' -> instruction.result()
                    .filter(result -> result.type() == xyz.melodysky.ir.model.IrType.F64)
                    .map(_result -> iface ? "j2ll_rt_call_interface_f64_a" : "j2ll_rt_call_virtual_f64_a")
                    .orElse(null);
            case 'L', '[' -> instruction.result()
                    .filter(result -> result.type() == xyz.melodysky.ir.model.IrType.REFERENCE)
                    .map(_result -> iface ? "j2ll_rt_call_interface_ref_a" : "j2ll_rt_call_virtual_ref_a")
                    .orElse(null);
            default -> null;
        };
    }

    private String dispatchHelperArguments(
            xyz.melodysky.ir.model.IrInstruction instruction,
            String receiver,
            String token) {
        if (instruction.operands().size() == 1) {
            return receiver + ", " + token + ", ptr null";
        }
        return receiver + ", " + token + ", " + typedOperand(instruction.operands().get(1));
    }

    private Optional<String> methodDescriptor(String methodKey) {
        int separator = methodKey.indexOf('!');
        if (separator < 0 || separator == methodKey.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(methodKey.substring(separator + 1));
    }

    private boolean isConstructorCallHelperInstruction(xyz.melodysky.ir.model.IrInstruction instruction) {
        if (instruction.opcode() != IrOpcode.CALL_SPECIAL
                || instruction.symbol().map(symbol -> !symbol.contains("#<init>!")).orElse(true)
                || instruction.operands().isEmpty()) {
            return false;
        }
        String descriptor = methodDescriptor(instruction.symbol().orElseThrow()).orElse("");
        return descriptor.endsWith("V")
                && instruction.operands().get(0).type() == xyz.melodysky.ir.model.IrType.REFERENCE
                && operandsMatchDescriptor(descriptor, instruction.operands().subList(1, instruction.operands().size()));
    }

    private boolean isStaticCallBridgeInstruction(
            xyz.melodysky.ir.model.IrInstruction instruction,
            Set<String> staticCallMethodKeys) {
        return instruction.opcode() == IrOpcode.CALL_STATIC
                && instruction.symbol().filter(staticCallMethodKeys::contains).isPresent()
                && instruction.symbol()
                        .flatMap(this::methodDescriptor)
                        .map(descriptor -> operandsMatchDescriptor(descriptor, instruction.operands()))
                        .orElse(false);
    }

    private boolean isDirectSpecialCallInstruction(xyz.melodysky.ir.model.IrInstruction instruction) {
        return instruction.opcode() == IrOpcode.CALL_SPECIAL
                && instruction.symbol().map(symbol -> !symbol.contains("#<init>!")).orElse(false);
    }

    private boolean operandsMatchDescriptor(String descriptor, List<IrValue> operands) {
        if (!descriptor.startsWith("(")) {
            return false;
        }
        ArrayList<xyz.melodysky.ir.model.IrType> parameterTypes = new ArrayList<>();
        int index = 1;
        while (index < descriptor.length() && descriptor.charAt(index) != ')') {
            ParseResult parsed = parseDescriptorType(descriptor, index);
            if (parsed == null || parsed.type() == xyz.melodysky.ir.model.IrType.VOID) {
                return false;
            }
            parameterTypes.add(parsed.type());
            index = parsed.nextIndex();
        }
        if (index >= descriptor.length() || descriptor.charAt(index) != ')' || parameterTypes.size() != operands.size()) {
            return false;
        }
        ParseResult returnType = parseDescriptorType(descriptor, index + 1);
        if (returnType == null || returnType.nextIndex() != descriptor.length()) {
            return false;
        }
        for (int operandIndex = 0; operandIndex < operands.size(); operandIndex++) {
            if (operands.get(operandIndex).type() != parameterTypes.get(operandIndex)) {
                return false;
            }
        }
        return true;
    }

    private ParseResult parseDescriptorType(String descriptor, int start) {
        if (start >= descriptor.length()) {
            return null;
        }
        char type = descriptor.charAt(start);
        return switch (type) {
            case 'V' -> new ParseResult(xyz.melodysky.ir.model.IrType.VOID, start + 1);
            case 'Z', 'B', 'C', 'S', 'I' -> new ParseResult(xyz.melodysky.ir.model.IrType.I32, start + 1);
            case 'J' -> new ParseResult(xyz.melodysky.ir.model.IrType.I64, start + 1);
            case 'F' -> new ParseResult(xyz.melodysky.ir.model.IrType.F32, start + 1);
            case 'D' -> new ParseResult(xyz.melodysky.ir.model.IrType.F64, start + 1);
            case 'L' -> {
                int end = descriptor.indexOf(';', start);
                yield end < 0 ? null : new ParseResult(xyz.melodysky.ir.model.IrType.REFERENCE, end + 1);
            }
            case '[' -> {
                int index = start;
                while (index < descriptor.length() && descriptor.charAt(index) == '[') {
                    index++;
                }
                ParseResult component = parseDescriptorType(descriptor, index);
                yield component == null || component.type() == xyz.melodysky.ir.model.IrType.VOID
                        ? null
                        : new ParseResult(xyz.melodysky.ir.model.IrType.REFERENCE, component.nextIndex());
            }
            default -> null;
        };
    }

    private String staticCallBridgeHelper(String descriptor) {
        return switch (descriptorReturnChar(descriptor)) {
            case 'V' -> "j2ll_rt_call_static_void_a";
            case 'Z', 'B', 'C', 'S', 'I' -> "j2ll_rt_call_static_i32_a";
            case 'J' -> "j2ll_rt_call_static_i64_a";
            case 'F' -> "j2ll_rt_call_static_f32_a";
            case 'D' -> "j2ll_rt_call_static_f64_a";
            case 'L', '[' -> "j2ll_rt_call_static_ref_a";
            default -> throw new IllegalArgumentException("unsupported static call return descriptor: " + descriptor);
        };
    }

    private String staticCallBridgeReturnType(String descriptor) {
        return switch (descriptorReturnChar(descriptor)) {
            case 'V' -> "void";
            case 'Z', 'B', 'C', 'S', 'I' -> "i32";
            case 'J' -> "i64";
            case 'F' -> "float";
            case 'D' -> "double";
            case 'L', '[' -> "ptr";
            default -> throw new IllegalArgumentException("unsupported static call return descriptor: " + descriptor);
        };
    }

    private char descriptorReturnChar(String descriptor) {
        int close = descriptor.indexOf(')');
        if (close < 0 || close == descriptor.length() - 1) {
            throw new IllegalArgumentException("invalid method descriptor: " + descriptor);
        }
        return descriptor.charAt(close + 1);
    }

    private record ParseResult(xyz.melodysky.ir.model.IrType type, int nextIndex) {
    }

    private boolean isTypeHelper(xyz.melodysky.ir.model.IrInstruction instruction) {
        return instruction.opcode() == IrOpcode.CHECKCAST || instruction.opcode() == IrOpcode.INSTANCEOF;
    }

    private String allocationClassIdentity(String symbol) {
        if (symbol.startsWith("object:")) {
            return "L" + symbol.substring("object:".length()) + ";";
        }
        if (symbol.startsWith("referenceArray:")) {
            String component = symbol.substring("referenceArray:".length());
            return component.startsWith("[") ? component : "L" + component + ";";
        }
        throw new IllegalArgumentException("not an allocation class symbol: " + symbol);
    }

    private String typeClassIdentity(String symbol) {
        if (symbol.startsWith("checkcast:")) {
            return classIdentity(symbol.substring("checkcast:".length()));
        }
        if (symbol.startsWith("instanceof:")) {
            return classIdentity(symbol.substring("instanceof:".length()));
        }
        throw new IllegalArgumentException("unsupported type helper symbol " + symbol);
    }

    private String classIdentity(String internalOrDescriptor) {
        if (internalOrDescriptor.startsWith("[")) {
            return internalOrDescriptor;
        }
        if (internalOrDescriptor.startsWith("L") && internalOrDescriptor.endsWith(";")) {
            return internalOrDescriptor;
        }
        return "L" + internalOrDescriptor + ";";
    }

    private String classIdentityFromConstSymbol(String symbol) {
        String identity = symbol.startsWith("class:") ? symbol.substring("class:".length()) : symbol;
        return classIdentity(identity);
    }

    private String dispatchToken(String methodKey) {
        return "i64 " + runtimeTokens.token(
                RuntimeTokenDomain.DISPATCH_METHOD,
                methodKey);
    }

    private String localizedDispatchHelper(
            String operation,
            String methodKey) {
        return runtimeTokens.helperSymbol(
                RuntimeTokenDomain.DISPATCH_METHOD,
                operation,
                methodKey);
    }

    private List<String> localAbiParameterTypes(
            RuntimeLocalAbiDomain domain,
            String operation,
            String identity,
            List<String> logicalTypes) {
        RuntimeLocalAbiPlan plan = runtimeLocalAbi.plan(
                runtimeTokens,
                domain,
                operation,
                identity,
                logicalTypes.size());
        return plan.arrange(logicalTypes);
    }

    private String localAbiArguments(
            RuntimeLocalAbiDomain domain,
            String operation,
            String identity,
            List<String> logicalArguments) {
        RuntimeLocalAbiPlan plan = runtimeLocalAbi.plan(
                runtimeTokens,
                domain,
                operation,
                identity,
                logicalArguments.size());
        return String.join(
                ", ",
                plan.arrange(logicalArguments));
    }

    private String dispatchDescriptorSuffix(String descriptor) {
        int close = descriptor.indexOf(')');
        if (close < 0 || close == descriptor.length() - 1) {
            throw new IllegalArgumentException(
                    "invalid method descriptor: " + descriptor);
        }
        return switch (descriptor.charAt(close + 1)) {
            case 'V' -> "void";
            case 'Z' -> "boolean";
            case 'B' -> "byte";
            case 'C' -> "char";
            case 'S' -> "short";
            case 'I' -> "i32";
            case 'J' -> "i64";
            case 'F' -> "f32";
            case 'D' -> "f64";
            case 'L', '[' -> "ref";
            default -> throw new IllegalArgumentException(
                    "unsupported dispatch return descriptor "
                            + descriptor);
        };
    }

    private String dispatchOperationPrefix(
            xyz.melodysky.ir.model.IrInstruction instruction) {
        return instruction.opcode() == IrOpcode.CALL_INTERFACE
                ? "interface_dispatch_"
                : "virtual_dispatch_";
    }

    private String constantKind(IrOpcode opcode) {
        return switch (opcode) {
            case CONST_STRING -> "string";
            case CONST_CLASS -> "class";
            case CONST_METHOD_TYPE -> "method_type";
            case CONST_METHOD_HANDLE -> "method_handle";
            default -> throw new IllegalArgumentException("not a symbolic constant opcode: " + opcode);
        };
    }

    private String stableHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (int index = 0; index < 8; index++) {
                result.append(String.format("%02x", hash[index]));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is unavailable", exception);
        }
    }

    private boolean methodNeedsJniEnv(
            IrMethod method,
            Set<String> directCallMethodKeys,
            Set<String> staticCallMethodKeys) {
        return LlvmFunctionAbiPolicy.referenceComparisonsRequireJniEnv(method)
                || method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> !instruction.exceptionSites().isEmpty())
                || method.blocks().stream()
                .anyMatch(block -> block.terminator().kind() == IrTerminatorKind.THROW)
                || method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> isFieldAccess(instruction.opcode())
                        || isArithmeticExceptionHelper(instruction.opcode())
                        || isArrayHelper(instruction)
                        || isAllocationHelper(instruction)
                        || isTypeHelper(instruction)
                        || isMonitorHelper(instruction.opcode())
                        || isClassInitHelper(instruction.opcode())
                        || LlvmFunctionAbiPolicy
                                .literalOrClassObjectRequiresJniEnv(
                                        instruction.opcode())
                        || isConstructorCallHelperInstruction(instruction)
                        || isStaticCallBridgeInstruction(instruction, staticCallMethodKeys)
                        || isDispatchHelperInstruction(instruction)
                        || ((instruction.opcode() == IrOpcode.CALL_STATIC
                                        || instruction.opcode() == IrOpcode.CALL_DIRECT
                                        || isDirectSpecialCallInstruction(instruction))
                                && instruction.symbol().filter(directCallMethodKeys::contains).isPresent())
                        || (instruction.opcode() == IrOpcode.CALL_RUNTIME_HELPER
                                && instruction.symbol().map(this::isEnvBackedRuntimeHelperSymbol).orElse(false)));
    }

    private boolean methodNeedsOwnerClass(IrMethod method, Set<String> directCallMethodKeys) {
        return !directCallMethodKeys.isEmpty()
                || method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> instruction.opcode() == IrOpcode.GET_STATIC
                        || instruction.opcode() == IrOpcode.PUT_STATIC
                        || instruction.opcode() == IrOpcode.GET_NATIVE_STATIC
                        || instruction.opcode() == IrOpcode.PUT_NATIVE_STATIC);
    }

    private String fieldHelper(xyz.melodysky.ir.model.IrInstruction instruction) {
        RuntimeHelperKind kind = switch (instruction.opcode()) {
            case GET_STATIC -> switch (instruction.result().orElseThrow().type()) {
                case I32 -> RuntimeHelperKind.FIELD_GET_STATIC_I32;
                case I64 -> RuntimeHelperKind.FIELD_GET_STATIC_I64;
                case F32 -> RuntimeHelperKind.FIELD_GET_STATIC_F32;
                case F64 -> RuntimeHelperKind.FIELD_GET_STATIC_F64;
                case REFERENCE -> RuntimeHelperKind.FIELD_GET_STATIC_REF;
                default -> throw new IllegalArgumentException("unsupported static field get type "
                        + instruction.result().orElseThrow().type());
            };
            case PUT_STATIC -> switch (instruction.operands().get(0).type()) {
                case I32 -> RuntimeHelperKind.FIELD_PUT_STATIC_I32;
                case I64 -> RuntimeHelperKind.FIELD_PUT_STATIC_I64;
                case F32 -> RuntimeHelperKind.FIELD_PUT_STATIC_F32;
                case F64 -> RuntimeHelperKind.FIELD_PUT_STATIC_F64;
                case REFERENCE -> RuntimeHelperKind.FIELD_PUT_STATIC_REF;
                default -> throw new IllegalArgumentException("unsupported static field put type "
                        + instruction.operands().get(0).type());
            };
            case GET_FIELD -> switch (instruction.result().orElseThrow().type()) {
                case I32 -> RuntimeHelperKind.FIELD_GET_FIELD_I32;
                case I64 -> RuntimeHelperKind.FIELD_GET_FIELD_I64;
                case F32 -> RuntimeHelperKind.FIELD_GET_FIELD_F32;
                case F64 -> RuntimeHelperKind.FIELD_GET_FIELD_F64;
                case REFERENCE -> RuntimeHelperKind.FIELD_GET_FIELD_REF;
                default -> throw new IllegalArgumentException("unsupported field get type "
                        + instruction.result().orElseThrow().type());
            };
            case PUT_FIELD -> switch (instruction.operands().get(1).type()) {
                case I32 -> RuntimeHelperKind.FIELD_PUT_FIELD_I32;
                case I64 -> RuntimeHelperKind.FIELD_PUT_FIELD_I64;
                case F32 -> RuntimeHelperKind.FIELD_PUT_FIELD_F32;
                case F64 -> RuntimeHelperKind.FIELD_PUT_FIELD_F64;
                case REFERENCE -> RuntimeHelperKind.FIELD_PUT_FIELD_REF;
                default -> throw new IllegalArgumentException("unsupported field put type "
                        + instruction.operands().get(1).type());
            };
            default -> throw new IllegalArgumentException("not a field opcode: " + instruction.opcode());
        };
        return runtimeHelpers.helper(kind).orElseThrow().llvmSymbol();
    }

    private record PhiIncoming(String predecessorBlock, List<IrValue> arguments) {
        private PhiIncoming {
            arguments = List.copyOf(arguments);
        }
    }

    private record NormalEdgeLowering(
            List<List<LlvmBasicBlock>> blocks,
            Map<NativeLocalReferenceNormalEdge, String> predecessors) {
        private NormalEdgeLowering {
            blocks = blocks.stream().map(List::copyOf).toList();
            predecessors = Map.copyOf(predecessors);
        }
    }
}
