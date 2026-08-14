package xyz.melodysky.toolchain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import xyz.melodysky.backend.llvm.LlvmFunctionAbi;
import xyz.melodysky.backend.llvm.LlvmModuleLowerer;
import xyz.melodysky.backend.llvm.model.LlvmLinkage;
import xyz.melodysky.backend.llvm.model.LlvmModule;
import xyz.melodysky.backend.llvm.model.LlvmModuleEmissionPlan;
import xyz.melodysky.backend.llvm.model.LlvmTextEmitter;
import xyz.melodysky.backend.llvm.model.LlvmUnwindEmissionMode;
import xyz.melodysky.backend.llvm.model.LlvmVisibility;
import xyz.melodysky.backend.llvm.protection.LlvmBlockLayoutPerturbationPass;
import xyz.melodysky.backend.llvm.protection.LlvmBlockLayoutPerturbationResult;
import xyz.melodysky.backend.llvm.protection.LlvmCallIndirectionPass;
import xyz.melodysky.backend.llvm.protection.LlvmCallIndirectionResult;
import xyz.melodysky.backend.llvm.protection.LlvmGlobalLayoutPass;
import xyz.melodysky.backend.llvm.protection.LlvmGlobalLayoutResult;
import xyz.melodysky.backend.llvm.protection.LlvmIrCallIndirectionPass;
import xyz.melodysky.backend.llvm.protection.LlvmIrCallIndirectionResult;
import xyz.melodysky.backend.llvm.protection.LlvmNameObfuscationPass;
import xyz.melodysky.backend.llvm.protection.LlvmOpaquePredicatePass;
import xyz.melodysky.backend.llvm.protection.LlvmOpaquePredicateResult;
import xyz.melodysky.backend.llvm.protection.LlvmProtectionConfig;
import xyz.melodysky.ir.model.IrClass;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.toolchain.localref.NativeLocalReferencePlan;

/**
 * Produces the one authoritative final LLVM compilation.
 *
 * <p>Only final {@link NativeImplementationPath#LLVM_NATIVE_PATH} methods and
 * their explicitly reachable compiler-internal direct targets enter this
 * compilation. Reports, intermediates, and Zig must all consume its result.</p>
 */
public final class NativeLlvmCompiler {
    private final LlvmModuleLowerer lowerer;
    private final LlvmTextEmitter emitter;

    public NativeLlvmCompiler(
            LlvmModuleLowerer lowerer,
            LlvmTextEmitter emitter) {
        this.lowerer = Objects.requireNonNull(lowerer, "lowerer");
        this.emitter = Objects.requireNonNull(emitter, "emitter");
    }

    public NativeLlvmCompilation compile(
            NativeImplementationPlan implementationPlan,
            Map<String, IrMethod> irMethods,
            LlvmProtectionConfig protectionConfig) throws IOException {
        return compile(
                implementationPlan,
                irMethods,
                protectionConfig,
                NativeLlvmCompilationListener.none());
    }

    public NativeLlvmCompilation compile(
            NativeImplementationPlan implementationPlan,
            Map<String, IrMethod> irMethods,
            LlvmProtectionConfig protectionConfig,
            NativeLlvmCompilationListener listener) throws IOException {
        Objects.requireNonNull(implementationPlan, "implementationPlan");
        Objects.requireNonNull(irMethods, "irMethods");
        Objects.requireNonNull(protectionConfig, "protectionConfig");
        Objects.requireNonNull(listener, "listener");

        List<String> directEntryIssues =
                new NativeJniEntryFusionValidator().validate(
                        implementationPlan,
                        irMethods);
        if (!directEntryIssues.isEmpty()) {
            throw new IOException(
                    "LLVM JNI proxy final-plan validation failed before LLVM compilation: "
                            + String.join(",", directEntryIssues));
        }

        CompilationInputs inputs = inputs(implementationPlan, irMethods);
        listener.started(inputs.methodsByOwner().size());
        ArrayList<NativeLlvmModuleCompilation> compiled = new ArrayList<>();
        int completed = 0;
        for (Map.Entry<String, List<IrMethod>> ownerEntry
                : inputs.methodsByOwner().entrySet()) {
            String owner = ownerEntry.getKey();
            List<IrMethod> methods = ownerEntry.getValue();
            listener.moduleStarted(completed + 1, inputs.methodsByOwner().size(), owner);
            LlvmModule lowered = lowerer.lowerClass(
                    new IrClass(owner, methods),
                    LlvmLinkage.EXTERNAL,
                    LlvmVisibility.HIDDEN,
                    inputs.directCallsByMethod(),
                    inputs.staticCallsByMethod(),
                    inputs.functionAbisByMethod(),
                    inputs.localReferencePlansByMethod());
            LlvmModule nameProtected =
                    new LlvmNameObfuscationPass().run(lowered, protectionConfig);
            LlvmBlockLayoutPerturbationResult blockLayout =
                    new LlvmBlockLayoutPerturbationPass()
                            .runDetailed(nameProtected, protectionConfig);
            LlvmOpaquePredicateResult opaquePredicates =
                    new LlvmOpaquePredicatePass()
                            .runDetailed(blockLayout.module(), protectionConfig);
            LlvmIrCallIndirectionResult irCallIndirection =
                    new LlvmIrCallIndirectionPass()
                            .runDetailed(opaquePredicates.module());
            LlvmCallIndirectionResult llvmCallIndirection =
                    new LlvmCallIndirectionPass()
                            .run(irCallIndirection.module(), protectionConfig);
            LlvmGlobalLayoutResult globalLayout =
                    new LlvmGlobalLayoutPass()
                            .runDetailed(llvmCallIndirection.module(), protectionConfig);
            LlvmModule proxyModule;
            try {
                proxyModule = new NativeJniProxySynthesizer().synthesize(
                        owner,
                        globalLayout.module(),
                        implementationPlan);
            } catch (IllegalArgumentException | IllegalStateException exception) {
                throw new IOException(
                        "LLVM JNI proxy synthesis failed for " + owner,
                        exception);
            }
            LlvmGlobalLayoutResult finalGlobalLayout =
                    new LlvmGlobalLayoutResult(
                            proxyModule,
                            globalLayout.affectedGlobals(),
                            globalLayout.validationIssues());
            LlvmModuleEmissionPlan emissionPlan =
                    LlvmModuleEmissionPlan.create(finalGlobalLayout.module());
            String retainedText =
                    emitter.emit(emissionPlan, LlvmUnwindEmissionMode.RETAIN);
            Optional<String> omissionText = emissionPlan.proof().omissionSafe()
                    ? Optional.of(emitter.emit(
                            emissionPlan,
                            LlvmUnwindEmissionMode.OMIT_PROVEN))
                    : Optional.empty();
            List<IrMethod> registeredMethods = methods.stream()
                    .filter(method -> inputs.registeredMethodKeys().contains(method.methodKey()))
                    .toList();
            List<IrMethod> userMethods = methods.stream()
                    .filter(method -> inputs
                            .implementationMethodKeys()
                            .contains(method.methodKey()))
                    .toList();
            compiled.add(new NativeLlvmModuleCompilation(
                    owner,
                    registeredMethods,
                    userMethods,
                    methods,
                    blockLayout,
                    opaquePredicates,
                    irCallIndirection,
                    llvmCallIndirection,
                    finalGlobalLayout,
                    emissionPlan,
                    retainedText,
                    omissionText));
            completed++;
        }
        NativeLlvmCompilation compilation = new NativeLlvmCompilation(
                inputKey(implementationPlan, irMethods, protectionConfig),
                compiled);
        List<String> physicalEntryIssues =
                new NativeJniEntryLlvmVerifier().validate(
                        implementationPlan,
                        compilation);
        if (!physicalEntryIssues.isEmpty()) {
            throw new IOException(
                    "LLVM JNI proxy final LLVM model validation failed: "
                            + String.join(",", physicalEntryIssues));
        }
        listener.completed(inputs.methodsByOwner().size());
        return compilation;
    }

    static String inputKey(
            NativeImplementationPlan implementationPlan,
            Map<String, IrMethod> irMethods,
            LlvmProtectionConfig protectionConfig) {
        StringBuilder canonical = new StringBuilder(protectionConfig.toString()).append('\n');
        implementationPlan.llvmImplementations().forEach(implementation -> canonical
                .append(implementation.methodKey()).append('|')
                .append(implementation.decision().strategy()).append('|')
                .append(implementation.path()).append('|')
                .append(implementation.llvmFunctionSymbol()).append('|')
                .append(implementation.passesJniEnv()).append('|')
                .append(implementation.passesOwnerClass()).append('|')
                .append(implementation.fieldKeys()).append('|')
                .append(implementation.directCallTargets()).append('|')
                .append(implementation.allocationKeys()).append('|')
                .append(implementation.typeCheckKeys()).append('|')
                .append(implementation.classObjectKeys()).append('|')
                .append(implementation.runtimeMetadataKeys()).append('|')
                .append(implementation.constructorCallKeys()).append('|')
                .append(implementation.staticCallKeys()).append('|')
                .append(implementation.dispatchKeys()).append('|')
                .append(implementation.stringHelperSymbols()).append('|')
                .append(implementation.initializerPlan()).append('|')
                .append(implementation.coalescedIntoMethodKey())
                .append('\n'));
        implementationPlan.jniEntryPlans().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> canonical
                        .append("jniEntry|")
                        .append(entry.getKey()).append('|')
                        .append(entry.getValue().kind()).append('|')
                        .append(entry.getValue().functionSymbol()).append('|')
                        .append(entry.getValue().physicalLlvmAbi()).append('|')
                        .append(entry.getValue().semanticBodySymbol()).append('|')
                        .append(entry.getValue().semanticLlvmAbi()).append('|')
                        .append(entry.getValue().topology()).append('|')
                        .append(entry.getValue().reasonCode())
                        .append('\n'));
        implementationPlan.localReferencePlans().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> canonical
                        .append("localRefs|")
                        .append(entry.getKey())
                        .append('|')
                        .append(entry.getValue())
                        .append('\n'));
        irMethods.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> canonical
                        .append(entry.getKey())
                        .append('=')
                        .append(entry.getValue())
                        .append('\n'));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private CompilationInputs inputs(
            NativeImplementationPlan implementationPlan,
            Map<String, IrMethod> irMethods) throws IOException {
        LinkedHashMap<String, Set<String>> directCallsByMethod = new LinkedHashMap<>();
        LinkedHashMap<String, Set<String>> staticCallsByMethod = new LinkedHashMap<>();
        LinkedHashMap<String, LlvmFunctionAbi> functionAbisByMethod =
                new LinkedHashMap<>();
        LinkedHashMap<String, NativeLocalReferencePlan>
                localReferencePlansByMethod = new LinkedHashMap<>();
        LinkedHashSet<String> registeredMethodKeys = implementationPlan
                .registeredImplementations()
                .stream()
                .filter(implementation ->
                        implementation.path()
                                == NativeImplementationPath
                                        .LLVM_NATIVE_PATH)
                .map(NativeMethodImplementation::methodKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> implementationMethodKeys =
                implementationPlan.emittedLlvmImplementations().stream()
                        .map(NativeMethodImplementation::methodKey)
                        .collect(java.util.stream.Collectors
                                .toUnmodifiableSet());
        LinkedHashMap<String, ArrayList<IrMethod>> mutableMethodsByOwner =
                new LinkedHashMap<>();

        validateCoalescedInputs(implementationPlan, irMethods);
        for (NativeMethodImplementation implementation
                : implementationPlan.emittedLlvmImplementations()) {
            directCallsByMethod.put(
                    implementation.methodKey(),
                    Set.copyOf(implementation.directCallTargets()));
            staticCallsByMethod.put(
                    implementation.methodKey(),
                    Set.copyOf(implementation.staticCallKeys()));
            functionAbisByMethod.put(
                    implementation.methodKey(),
                    implementation.llvmFunctionAbi());
            implementationPlan
                    .localReferencePlanFor(implementation.methodKey())
                    .ifPresent(plan -> localReferencePlansByMethod.put(
                            implementation.methodKey(),
                            plan));
            IrMethod method = implementation.initializerPlan()
                    .map(plan -> plan.nativeBody())
                    .orElseGet(() -> irMethods.get(implementation.methodKey()));
            if (method == null) {
                throw new IOException(
                        "LLVM_NATIVE_PATH method has no protected IR: "
                                + implementation.methodKey());
            }
            mutableMethodsByOwner
                    .computeIfAbsent(method.owner(), ignored -> new ArrayList<>())
                    .add(method);
        }
        for (NativeMethodImplementation implementation
                : implementationPlan.emittedLlvmImplementations()) {
            IrMethod caller = implementation.initializerPlan()
                    .map(plan -> plan.nativeBody())
                    .orElseGet(() -> irMethods.get(implementation.methodKey()));
            for (String targetKey : implementation.directCallTargets()) {
                if (implementationMethodKeys.contains(targetKey)) {
                    continue;
                }
                IrMethod helper = irMethods.get(targetKey);
                if (helper == null) {
                    throw new IOException(
                            "compiler-internal direct target has no protected IR: " + targetKey);
                }
                if (!helper.owner().equals(caller.owner())) {
                    throw new IOException(
                            "compiler-internal direct target crosses owner boundary: " + targetKey);
                }
                validateCompilerInternalCalls(helper);
                ArrayList<IrMethod> ownerMethods = mutableMethodsByOwner
                        .computeIfAbsent(helper.owner(), ignored -> new ArrayList<>());
                if (ownerMethods.stream()
                        .noneMatch(existing -> existing.methodKey().equals(targetKey))) {
                    ownerMethods.add(helper);
                }
                directCallsByMethod.putIfAbsent(targetKey, Set.of());
                staticCallsByMethod.putIfAbsent(targetKey, Set.of());
                implementationPlan
                        .localReferencePlanFor(targetKey)
                        .ifPresent(plan ->
                                localReferencePlansByMethod.putIfAbsent(
                                        targetKey,
                                        plan));
            }
        }

        LinkedHashMap<String, List<IrMethod>> methodsByOwner = new LinkedHashMap<>();
        mutableMethodsByOwner.forEach(
                (owner, methods) -> methodsByOwner.put(owner, List.copyOf(methods)));
        return new CompilationInputs(
                java.util.Collections.unmodifiableMap(methodsByOwner),
                java.util.Collections.unmodifiableMap(directCallsByMethod),
                java.util.Collections.unmodifiableMap(staticCallsByMethod),
                java.util.Collections.unmodifiableMap(functionAbisByMethod),
                java.util.Collections.unmodifiableMap(
                        localReferencePlansByMethod),
                java.util.Collections.unmodifiableSet(registeredMethodKeys),
                implementationMethodKeys);
    }

    private void validateCoalescedInputs(
            NativeImplementationPlan implementationPlan,
            Map<String, IrMethod> irMethods) throws IOException {
        for (NativeMethodImplementation implementation
                : implementationPlan.llvmImplementations()) {
            if (implementation.coalescedIntoMethodKey().isEmpty()) {
                continue;
            }
            String calleeKey = implementation.methodKey();
            String callerKey = implementation.coalescedIntoMethodKey()
                    .orElseThrow();
            if (irMethods.containsKey(calleeKey)) {
                throw new IOException(
                        "coalesced native-only method still has standalone IR: "
                                + calleeKey);
            }
            NativeMethodImplementation caller = implementationPlan
                    .implementationFor(callerKey)
                    .orElseThrow(() -> new IOException(
                            "coalesced native-only caller implementation is missing: "
                                    + callerKey));
            if (!caller.emitsStandaloneLlvmBody()
                    || !irMethods.containsKey(callerKey)) {
                throw new IOException(
                        "coalesced native-only caller has no emitted IR body: "
                                + callerKey);
            }
            boolean metadataResidual = implementationPlan
                    .emittedLlvmImplementations()
                    .stream()
                    .anyMatch(candidate -> candidate.directCallTargets()
                                    .contains(calleeKey)
                            || candidate.staticCallKeys().contains(calleeKey)
                            || candidate.dispatchKeys().contains(calleeKey));
            boolean irResidual = irMethods.values().stream()
                    .flatMap(method -> method.blocks().stream())
                    .flatMap(block -> block.instructions().stream())
                    .anyMatch(instruction -> instruction.symbol()
                            .filter(calleeKey::equals)
                            .isPresent());
            if (metadataResidual || irResidual) {
                throw new IOException(
                        "coalesced native-only method retains a native call edge: "
                                + calleeKey);
            }
        }
    }

    private void validateCompilerInternalCalls(IrMethod helper) throws IOException {
        List<String> nestedCalls = helper.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> switch (instruction.opcode()) {
                    case CALL_STATIC,
                            CALL_SPECIAL,
                            CALL_VIRTUAL,
                            CALL_INTERFACE,
                            CALL_DYNAMIC,
                            CALL_RUNTIME_HELPER -> true;
                    default -> false;
                })
                .map(instruction -> instruction.symbol()
                        .orElse(instruction.opcode().name()))
                .distinct()
                .sorted()
                .toList();
        if (!nestedCalls.isEmpty()) {
            throw new IOException(
                    "compiler-internal method contains unsupported nested calls: "
                            + helper.methodKey()
                            + " -> "
                            + nestedCalls);
        }
    }

    private record CompilationInputs(
            Map<String, List<IrMethod>> methodsByOwner,
            Map<String, Set<String>> directCallsByMethod,
            Map<String, Set<String>> staticCallsByMethod,
            Map<String, LlvmFunctionAbi> functionAbisByMethod,
            Map<String, NativeLocalReferencePlan>
                    localReferencePlansByMethod,
            Set<String> registeredMethodKeys,
            Set<String> implementationMethodKeys) {
    }
}
