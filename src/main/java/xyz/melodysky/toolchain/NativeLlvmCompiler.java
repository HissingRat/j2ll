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
import java.util.Set;
import xyz.melodysky.backend.llvm.LlvmModuleLowerer;
import xyz.melodysky.backend.llvm.model.LlvmLinkage;
import xyz.melodysky.backend.llvm.model.LlvmModule;
import xyz.melodysky.backend.llvm.model.LlvmTextEmitter;
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
                    inputs.staticCallsByMethod());
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
            List<IrMethod> registeredMethods = methods.stream()
                    .filter(method -> inputs.registeredMethodKeys().contains(method.methodKey()))
                    .toList();
            compiled.add(new NativeLlvmModuleCompilation(
                    owner,
                    registeredMethods,
                    methods,
                    blockLayout,
                    opaquePredicates,
                    irCallIndirection,
                    llvmCallIndirection,
                    globalLayout,
                    emitter.emit(globalLayout.module())));
            completed++;
        }
        listener.completed(inputs.methodsByOwner().size());
        return new NativeLlvmCompilation(
                inputKey(implementationPlan, irMethods, protectionConfig),
                compiled);
    }

    static String inputKey(
            NativeImplementationPlan implementationPlan,
            Map<String, IrMethod> irMethods,
            LlvmProtectionConfig protectionConfig) {
        StringBuilder canonical = new StringBuilder(protectionConfig.toString()).append('\n');
        implementationPlan.llvmImplementations().forEach(implementation -> canonical
                .append(implementation.methodKey()).append('|')
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
                .append(implementation.stringHelperSymbols())
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
        LinkedHashSet<String> registeredMethodKeys = implementationPlan.llvmImplementations().stream()
                .map(NativeMethodImplementation::methodKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        LinkedHashMap<String, ArrayList<IrMethod>> mutableMethodsByOwner =
                new LinkedHashMap<>();

        for (NativeMethodImplementation implementation
                : implementationPlan.llvmImplementations()) {
            directCallsByMethod.put(
                    implementation.methodKey(),
                    Set.copyOf(implementation.directCallTargets()));
            staticCallsByMethod.put(
                    implementation.methodKey(),
                    Set.copyOf(implementation.staticCallKeys()));
            IrMethod method = irMethods.get(implementation.methodKey());
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
                : implementationPlan.llvmImplementations()) {
            IrMethod caller = irMethods.get(implementation.methodKey());
            for (String targetKey : implementation.directCallTargets()) {
                if (registeredMethodKeys.contains(targetKey)) {
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
            }
        }

        LinkedHashMap<String, List<IrMethod>> methodsByOwner = new LinkedHashMap<>();
        mutableMethodsByOwner.forEach(
                (owner, methods) -> methodsByOwner.put(owner, List.copyOf(methods)));
        return new CompilationInputs(
                java.util.Collections.unmodifiableMap(methodsByOwner),
                java.util.Collections.unmodifiableMap(directCallsByMethod),
                java.util.Collections.unmodifiableMap(staticCallsByMethod),
                java.util.Collections.unmodifiableSet(registeredMethodKeys));
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
            Set<String> registeredMethodKeys) {
    }
}
