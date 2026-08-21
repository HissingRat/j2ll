package xyz.melodysky.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import xyz.melodysky.analysis.callgraph.CallGraph;
import xyz.melodysky.analysis.callgraph.DevirtualizationPlan;
import xyz.melodysky.analysis.reflection.ReflectionPlan;
import xyz.melodysky.analysis.runtime.RuntimeTypeResult;
import xyz.melodysky.config.IntermediatesConfig;
import xyz.melodysky.frontend.cfg.MethodCfgResult;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.runtime.metadata.RuntimeMetadataDumpWriter;
import xyz.melodysky.runtime.metadata.RuntimeMetadataIndex;
import xyz.melodysky.toolchain.ClassArtifact;
import xyz.melodysky.toolchain.IntermediateArtifactIndexWriter;
import xyz.melodysky.toolchain.IntermediateArtifactLayout;
import xyz.melodysky.toolchain.MethodArtifact;

/** Owns workspace-private CFG/IR/LLVM/runtime-analysis debug artifacts. */
final class MainlineIntermediateWriter {
    void write(
            Path workspaceRoot,
            IntermediatesConfig intermediates,
            IntermediateArtifactLayout layout,
            Map<String, MethodCfgResult> cfgByMethod,
            Map<String, IrMethod> rawIr,
            Map<String, IrMethod> optimizedIr,
            Map<String, IrMethod> protectedIr,
            Map<String, String> llvmTextByClass,
            CallGraph callGraph,
            RuntimeTypeResult runtimeTypes,
            DevirtualizationPlan devirtualizationPlan,
            boolean rtaApplied,
            RuntimeMetadataIndex metadataIndex,
            ReflectionPlan reflectionPlan) throws IOException {
        IntermediateArtifactIndexWriter indexWriter =
                new IntermediateArtifactIndexWriter();
        Files.createDirectories(workspaceRoot.resolve("intermediates"));
        if (!intermediates.enabled()) {
            writeManifest(workspaceRoot, intermediates, layout, indexWriter);
            return;
        }
        if (intermediates.includeDebugDumps()) {
            Files.createDirectories(
                    workspaceRoot.resolve("intermediates/runtime"));
            Files.writeString(
                    workspaceRoot.resolve(
                            "intermediates/runtime/runtime-metadata.json"),
                    new RuntimeMetadataDumpWriter().write(
                            metadataIndex,
                            reflectionPlan));
        }
        for (ClassArtifact classArtifact : layout.classes()) {
            writeClass(
                    workspaceRoot,
                    intermediates,
                    layout,
                    indexWriter,
                    classArtifact,
                    cfgByMethod,
                    rawIr,
                    optimizedIr,
                    protectedIr,
                    llvmTextByClass,
                    callGraph,
                    runtimeTypes,
                    devirtualizationPlan,
                    rtaApplied);
        }
        writeManifest(workspaceRoot, intermediates, layout, indexWriter);
    }

    private void writeClass(
            Path workspaceRoot,
            IntermediatesConfig intermediates,
            IntermediateArtifactLayout layout,
            IntermediateArtifactIndexWriter indexWriter,
            ClassArtifact classArtifact,
            Map<String, MethodCfgResult> cfgByMethod,
            Map<String, IrMethod> rawIr,
            Map<String, IrMethod> optimizedIr,
            Map<String, IrMethod> protectedIr,
            Map<String, String> llvmTextByClass,
            CallGraph callGraph,
            RuntimeTypeResult runtimeTypes,
            DevirtualizationPlan devirtualizationPlan,
            boolean rtaApplied) throws IOException {
        Path classDir = workspaceRoot.resolve("intermediates/classes")
                .resolve(classArtifact.directory());
        for (String directory : java.util.List.of(
                "cfg", "ir", "llvm", "c", "reports")) {
            Files.createDirectories(classDir.resolve(directory));
        }
        Files.writeString(
                classDir.resolve("class-index.json"),
                indexWriter.classIndexJson(classArtifact));
        Files.writeString(
                classDir.resolve("method-index.json"),
                indexWriter.methodIndexJson(classArtifact, layout));
        Files.writeString(
                classDir.resolve("hierarchy.json"),
                "{\"schemaVersion\":1}\n");
        Files.writeString(
                classDir.resolve("call-sites.json"),
                callSiteSummary(
                        callGraph,
                        runtimeTypes,
                        devirtualizationPlan,
                        rtaApplied));
        for (MethodArtifact method : layout.methodsFor(
                classArtifact.internalName())) {
            String key = method.owner() + "#" + method.name() + "!"
                    + method.descriptor();
            MethodCfgResult cfg = cfgByMethod.get(key);
            if (cfg != null && intermediates.includeDebugDumps()) {
                Files.writeString(
                        classDir.resolve("cfg")
                                .resolve(method.methodId() + ".cfg.txt"),
                        cfg.toString());
                Files.writeString(
                        classDir.resolve("cfg")
                                .resolve(method.methodId() + ".cfg.json"),
                        "{\"schemaVersion\":1,\"method\":\""
                                + method.name()
                                + "\"}\n");
            }
        }
        if (intermediates.includePerClassIr()) {
            Files.writeString(
                    classDir.resolve("ir/raw.ssa.ir"),
                    irDump(rawIr, classArtifact.internalName()));
            Files.writeString(
                    classDir.resolve("ir/optimized.ssa.ir"),
                    irDump(optimizedIr, classArtifact.internalName()));
            Files.writeString(
                    classDir.resolve("ir/protected.ssa.ir"),
                    irDump(protectedIr, classArtifact.internalName()));
        }
        if (intermediates.includePerClassLlvm()) {
            String llvm = llvmTextByClass.getOrDefault(
                    classArtifact.internalName(),
                    "");
            Files.writeString(classDir.resolve("llvm/class.ll"), llvm);
            Files.writeString(classDir.resolve("llvm/protected.class.ll"), llvm);
        }
        if (intermediates.includePerClassC()) {
            Files.writeString(
                    classDir.resolve("c/class.c"),
                    "/* planned C wrapper artifact */\n");
        }
        Files.writeString(
                classDir.resolve("reports/lowering.json"),
                "{\"schemaVersion\":1}\n");
        Files.writeString(
                classDir.resolve("reports/protection.json"),
                "{\"schemaVersion\":1}\n");
    }

    private String callSiteSummary(
            CallGraph callGraph,
            RuntimeTypeResult runtimeTypes,
            DevirtualizationPlan devirtualizationPlan,
            boolean rtaApplied) {
        long directCalls = devirtualizationPlan.decisions().stream()
                .filter(decision -> decision.directTarget().isPresent())
                .count();
        return "{\"schemaVersion\":1,\"callSiteCount\":"
                + callGraph.callSites().size()
                + ",\"instantiatedClassCount\":"
                + runtimeTypes.instantiatedClasses().size()
                + ",\"rtaApplied\":"
                + rtaApplied
                + ",\"directCallSiteCount\":"
                + directCalls
                + "}\n";
    }

    private String irDump(Map<String, IrMethod> methods, String owner) {
        StringBuilder builder = new StringBuilder();
        methods.values().stream()
                .filter(method -> method.owner().equals(owner))
                .forEach(method -> builder.append(method).append('\n'));
        return builder.toString();
    }

    private void writeManifest(
            Path workspaceRoot,
            IntermediatesConfig intermediates,
            IntermediateArtifactLayout layout,
            IntermediateArtifactIndexWriter indexWriter) throws IOException {
        Files.writeString(
                workspaceRoot.resolve(
                        "intermediates/intermediates-manifest.json"),
                indexWriter.manifestJson(
                        workspaceRoot,
                        intermediates,
                        layout));
    }
}
