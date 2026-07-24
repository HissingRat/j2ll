package xyz.melodysky.toolchain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import xyz.melodysky.backend.llvm.LlvmModuleLowerer;
import xyz.melodysky.backend.llvm.LlvmNameMangler;
import xyz.melodysky.backend.llvm.model.LlvmModule;
import xyz.melodysky.backend.llvm.model.LlvmLinkage;
import xyz.melodysky.backend.llvm.model.LlvmTextEmitter;
import xyz.melodysky.backend.llvm.model.LlvmVisibility;
import xyz.melodysky.backend.llvm.protection.LlvmCallIndirectionPass;
import xyz.melodysky.backend.llvm.protection.LlvmProtectionConfig;
import xyz.melodysky.ir.model.IrClass;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.packaging.EmbeddedLibraryLayout;
import xyz.melodysky.packaging.RuntimeLoaderPlan;
import xyz.melodysky.toolchain.symbols.NativeSymbolInspector;

public final class ZigNativeLibraryBuilder {
    private final HostJniCSourceGenerator sourceGenerator;
    private final LlvmModuleLowerer llvmLowerer;
    private final LlvmTextEmitter llvmEmitter;
    private final ManagedZigLocator zigLocator;
    private final ZigBuildWriter buildWriter;
    private final ZigBuildInvoker buildInvoker;
    private final NativeSymbolInspector symbolInspector;
    private final J2llHomeResolver homeResolver;
    private final boolean callIndirectionEnabled;
    private final long protectionSeed;
    private final boolean strip;

    public ZigNativeLibraryBuilder() {
        this(new LlvmNameMangler());
    }

    public ZigNativeLibraryBuilder(LlvmNameMangler llvmNameMangler) {
        this(llvmNameMangler, false, 0L);
    }

    public ZigNativeLibraryBuilder(
            LlvmNameMangler llvmNameMangler,
            boolean callIndirectionEnabled,
            long protectionSeed) {
        this(llvmNameMangler, callIndirectionEnabled, protectionSeed, true);
    }

    public ZigNativeLibraryBuilder(
            LlvmNameMangler llvmNameMangler,
            boolean callIndirectionEnabled,
            long protectionSeed,
            boolean strip) {
        this(
                new HostJniCSourceGenerator(),
                new LlvmModuleLowerer(llvmNameMangler),
                new LlvmTextEmitter(),
                new ManagedZigLocator(),
                new ZigBuildWriter(),
                new ZigBuildInvoker(),
                new NativeSymbolInspector(),
                new J2llHomeResolver(),
                callIndirectionEnabled,
                protectionSeed,
                strip);
    }

    public ZigNativeLibraryBuilder(
            HostJniCSourceGenerator sourceGenerator,
            LlvmModuleLowerer llvmLowerer,
            LlvmTextEmitter llvmEmitter,
            ManagedZigLocator zigLocator,
            ZigBuildWriter buildWriter,
            ZigBuildInvoker buildInvoker,
            NativeSymbolInspector symbolInspector,
            J2llHomeResolver homeResolver) {
        this(
                sourceGenerator,
                llvmLowerer,
                llvmEmitter,
                zigLocator,
                buildWriter,
                buildInvoker,
                symbolInspector,
                homeResolver,
                false,
                0L,
                true);
    }

    public ZigNativeLibraryBuilder(
            HostJniCSourceGenerator sourceGenerator,
            LlvmModuleLowerer llvmLowerer,
            LlvmTextEmitter llvmEmitter,
            ManagedZigLocator zigLocator,
            ZigBuildWriter buildWriter,
            ZigBuildInvoker buildInvoker,
            NativeSymbolInspector symbolInspector,
            J2llHomeResolver homeResolver,
            boolean callIndirectionEnabled,
            long protectionSeed) {
        this(
                sourceGenerator,
                llvmLowerer,
                llvmEmitter,
                zigLocator,
                buildWriter,
                buildInvoker,
                symbolInspector,
                homeResolver,
                callIndirectionEnabled,
                protectionSeed,
                true);
    }

    public ZigNativeLibraryBuilder(
            HostJniCSourceGenerator sourceGenerator,
            LlvmModuleLowerer llvmLowerer,
            LlvmTextEmitter llvmEmitter,
            ManagedZigLocator zigLocator,
            ZigBuildWriter buildWriter,
            ZigBuildInvoker buildInvoker,
            NativeSymbolInspector symbolInspector,
            J2llHomeResolver homeResolver,
            boolean callIndirectionEnabled,
            long protectionSeed,
            boolean strip) {
        this.sourceGenerator = sourceGenerator;
        this.llvmLowerer = llvmLowerer;
        this.llvmEmitter = llvmEmitter;
        this.zigLocator = zigLocator;
        this.buildWriter = buildWriter;
        this.buildInvoker = buildInvoker;
        this.symbolInspector = symbolInspector;
        this.homeResolver = homeResolver;
        this.callIndirectionEnabled = callIndirectionEnabled;
        this.protectionSeed = protectionSeed;
        this.strip = strip;
    }

    public Optional<ZigNativeBuildResult> build(
            Path workspaceRoot,
            RuntimeLoaderPlan runtimeLoaderPlan,
            NativeBuildPlan buildPlan,
            NativeImplementationPlan implementationPlan,
            Map<String, IrMethod> irMethods) throws IOException {
        return build(
                workspaceRoot,
                runtimeLoaderPlan,
                buildPlan,
                implementationPlan,
                irMethods,
                NativeBuildProgressListener.none());
    }

    public Optional<ZigNativeBuildResult> build(
            Path workspaceRoot,
            RuntimeLoaderPlan runtimeLoaderPlan,
            NativeBuildPlan buildPlan,
            NativeImplementationPlan implementationPlan,
            Map<String, IrMethod> irMethods,
            NativeBuildProgressListener progressListener) throws IOException {
        Objects.requireNonNull(progressListener, "progressListener");
        if (implementationPlan.implementations().isEmpty() || buildPlan.units().isEmpty()) {
            return Optional.empty();
        }
        ManagedZig zig = zigLocator.ensure(homeResolver.resolve());
        ZigBuildWorkspace workspace = ZigBuildWorkspace.under(workspaceRoot);
        prepareDirectories(workspace);
        String libraryName = buildPlan.units().get(0).libraryName();
        if (!NativeLibraryName.isSafe(libraryName)) {
            throw new IOException("unsafe native library name in build plan: " + libraryName);
        }
        Path jniDirectory = workspace.jniDirectory().toAbsolutePath().normalize();
        Path wrapper = jniDirectory.resolve(libraryName + ".c").normalize();
        if (!wrapper.startsWith(jniDirectory)) {
            throw new IOException("native wrapper path escapes the Zig JNI workspace: " + wrapper);
        }
        Files.writeString(
                wrapper,
                sourceGenerator.generate(implementationPlan, runtimeLoaderPlan),
                StandardCharsets.UTF_8);
        Path runtime = workspace.runtimeDirectory().resolve("j2ll_runtime_helpers.c");
        Files.writeString(runtime, "/* runtime helper C inputs are helper-backed skeletons in this slice */\n", StandardCharsets.UTF_8);
        Path fallback = workspace.fallbackDirectory().resolve("j2ll_fallback_blobs.c");
        Files.writeString(fallback, "/* fallback blob carrier C inputs are embedded by JNI source in this slice */\n", StandardCharsets.UTF_8);
        List<Path> llvmSources = writeLlvmSources(workspace, implementationPlan, irMethods);
        ZigSourceSet sources = new ZigSourceSet(
                llvmSources,
                List.of(wrapper, runtime, fallback),
                List.of(),
                new ZigJniHeaderSet().prepare(workspace));
        buildWriter.write(
                workspace,
                libraryName,
                buildPlan,
                new ZigInputSet(sources),
                strip);
        ZigBuildInvocation invocation = buildInvoker.invocation(zig, workspace);
        try {
            buildInvoker.invoke(zig, workspace, buildPlan, progressListener);
        } catch (IOException exception) {
            throw ZigBuildException.from(buildPlan, workspace, exception);
        }
        List<NativeLibraryArtifact> artifacts = collectArtifacts(
                runtimeLoaderPlan.embeddedLibraryDirectory(),
                buildPlan,
                wrapper);
        return Optional.of(new ZigNativeBuildResult(
                zig,
                workspace.buildZig(),
                workspace.manifest(),
                wrapper,
                artifacts,
                invocation));
    }

    private void prepareDirectories(ZigBuildWorkspace workspace) throws IOException {
        Files.createDirectories(workspace.llvmDirectory());
        Files.createDirectories(workspace.jniDirectory());
        Files.createDirectories(workspace.runtimeDirectory());
        Files.createDirectories(workspace.fallbackDirectory());
        Files.createDirectories(workspace.logsDirectory());
    }

    private List<Path> writeLlvmSources(
            ZigBuildWorkspace workspace,
            NativeImplementationPlan implementationPlan,
            Map<String, IrMethod> irMethods) throws IOException {
        if (implementationPlan.llvmImplementations().isEmpty()) {
            return List.of();
        }
        Map<String, Set<String>> directCallsByMethod = new LinkedHashMap<>();
        Map<String, Set<String>> staticCallsByMethod = new LinkedHashMap<>();
        for (NativeMethodImplementation implementation : implementationPlan.llvmImplementations()) {
            directCallsByMethod.put(
                    implementation.methodKey(),
                    Set.copyOf(implementation.directCallTargets()));
            staticCallsByMethod.put(
                    implementation.methodKey(),
                    Set.copyOf(implementation.staticCallKeys()));
        }
        Map<String, ArrayList<IrMethod>> methodsByOwner = new LinkedHashMap<>();
        for (NativeMethodImplementation implementation : implementationPlan.llvmImplementations()) {
            IrMethod method = irMethods.get(implementation.methodKey());
            if (method == null) {
                throw new IOException("LLVM_NATIVE_PATH method has no protected IR: " + implementation.methodKey());
            }
            methodsByOwner.computeIfAbsent(method.owner(), ignored -> new ArrayList<>()).add(method);
        }
        ArrayList<Path> sources = new ArrayList<>();
        for (Map.Entry<String, ArrayList<IrMethod>> entry : methodsByOwner.entrySet()) {
            Path llvmPath = workspace.llvmDirectory().resolve(NativeSourceName.llvmFileName(entry.getKey()));
            LlvmModule module = llvmLowerer.lowerClass(
                    new IrClass(entry.getKey(), entry.getValue()),
                    LlvmLinkage.EXTERNAL,
                    LlvmVisibility.HIDDEN,
                    directCallsByMethod,
                    staticCallsByMethod);
            module = new LlvmCallIndirectionPass()
                    .run(module, callIndirectionEnabled
                            ? LlvmProtectionConfig.enabled(protectionSeed)
                            : LlvmProtectionConfig.disabled(protectionSeed))
                    .module();
            String text = llvmEmitter.emit(module);
            Files.writeString(llvmPath, text, StandardCharsets.UTF_8);
            sources.add(llvmPath);
        }
        return List.copyOf(sources);
    }

    private List<NativeLibraryArtifact> collectArtifacts(
            String embeddedLibraryDirectory,
            NativeBuildPlan buildPlan,
            Path wrapper) throws IOException {
        EmbeddedLibraryLayout layout = new EmbeddedLibraryLayout();
        ArrayList<NativeLibraryArtifact> artifacts = new ArrayList<>();
        for (NativeBuildUnit unit : buildPlan.units()) {
            if (!Files.exists(unit.outputPath())) {
                throw new IOException("managed Zig did not produce selected target artifact: " + unit.outputPath());
            }
            artifacts.add(new NativeLibraryArtifact(
                    unit.target(),
                    unit.outputPath(),
                    wrapper,
                    layout.jarPath(embeddedLibraryDirectory, unit.target()),
                    sha256(unit.outputPath()),
                    symbolInspector.exportedSymbols(unit.target(), unit.outputPath())));
        }
        return List.copyOf(artifacts);
    }

    private String sha256(Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is not available", exception);
        }
    }
}
