package xyz.melodysky.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import xyz.melodysky.analysis.field.FieldAccessImplementationPath;
import xyz.melodysky.analysis.field.FieldUseAnalyzer;
import xyz.melodysky.analysis.field.FieldUseIndex;
import xyz.melodysky.analysis.field.NativeFieldInternalizationDecision;
import xyz.melodysky.analysis.field.NativeFieldInternalizationPlan;
import xyz.melodysky.analysis.field.NativeFieldInternalizationPlanner;
import xyz.melodysky.analysis.world.WholeProgramAnalysisDiagnostics;
import xyz.melodysky.analysis.world.WholeProgramAnalysisFeature;
import xyz.melodysky.analysis.world.WholeProgramAnalysisPolicy;
import xyz.melodysky.analysis.world.WholeProgramAnalysisScope;
import xyz.melodysky.config.ResolvedConfig;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassParseResult;
import xyz.melodysky.frontend.classfile.DirectoryClassFileSource;
import xyz.melodysky.frontend.classfile.JarClassFileSource;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.frontend.classfile.ParsedProgram;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.pass.protection.NativeFieldIrRewriter;
import xyz.melodysky.report.ProtectionPassReport;
import xyz.melodysky.toolchain.NativeImplementationPath;
import xyz.melodysky.toolchain.NativeImplementationPlan;

/**
 * Strict field-internalization analysis and IR rewrite boundary.
 */
public final class FieldInternalizationPipeline {
    public FieldInternalizationPipelineResult run(
            ResolvedConfig config,
            ParsedProgram inputProgram,
            Map<String, IrMethod> methods,
            NativeImplementationPlan preliminaryImplementationPlan,
            long seed) {
        return run(
                config,
                inputProgram,
                methods,
                preliminaryImplementationPlan,
                seed,
                WholeProgramAnalysisPolicy.strict());
    }

    public FieldInternalizationPipelineResult run(
            ResolvedConfig config,
            ParsedProgram inputProgram,
            Map<String, IrMethod> methods,
            NativeImplementationPlan preliminaryImplementationPlan,
            long seed,
            WholeProgramAnalysisPolicy wholeProgramPolicy) {
        boolean enabled = config.protection().enabled()
                && config.protection().ir().enabled()
                && config.protection().ir().fieldInternalization();
        if (!enabled) {
            return new FieldInternalizationPipelineResult(
                    new NativeFieldInternalizationPlan(List.of()),
                    methods,
                    report(false, new NativeFieldInternalizationPlan(List.of()), seed),
                    List.of(),
                    WholeProgramAnalysisScope.NOT_REQUIRED,
                    false);
        }

        WholeProgramAnalysisScope analysisScope = wholeProgramPolicy.scopeFor(
                WholeProgramAnalysisFeature.FIELD_INTERNALIZATION,
                config.worldModel());
        ClasspathPrograms classpath = analysisScope.analyzesClasspath()
                ? parseClasspath(config.classPath())
                : new ClasspathPrograms(List.of(), true, List.of());
        FieldUseIndex useIndex = new FieldUseAnalyzer()
                .analyze(inputProgram, classpath.programs())
                .withAdditionalMultiReleaseOwners(multiReleaseOwners(config.jarFile()));
        Map<String, ParsedMethod> parsedMethods = inputProgram.classes().stream()
                .flatMap(parsedClass -> parsedClass.methods().stream())
                .collect(java.util.stream.Collectors.toMap(
                        ParsedMethod::methodKey,
                        method -> method,
                        (left, right) -> left,
                        LinkedHashMap::new));
        Map<String, NativeImplementationPath> paths =
                preliminaryImplementationPlan.implementations().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                implementation -> implementation.methodKey(),
                                implementation -> implementation.path(),
                                (left, right) -> left,
                                LinkedHashMap::new));
        NativeFieldInternalizationPlan plan = new NativeFieldInternalizationPlanner().plan(
                useIndex,
                analysisScope,
                classpath.complete(),
                seed,
                methodKey -> {
                    ParsedMethod method = parsedMethods.get(methodKey);
                    if (method == null) {
                        return FieldAccessImplementationPath.UNKNOWN;
                    }
                    return paths.get(methodKey) == NativeImplementationPath.LLVM_NATIVE_PATH
                            ? FieldAccessImplementationPath.LLVM_NATIVE_PATH
                            : FieldAccessImplementationPath.NON_LLVM_PATH;
                });
        Set<String> llvmMethodKeys = paths.entrySet().stream()
                .filter(entry -> entry.getValue()
                        == NativeImplementationPath.LLVM_NATIVE_PATH)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var rewrite = new NativeFieldIrRewriter().rewrite(
                methods,
                plan,
                llvmMethodKeys);
        ArrayList<Diagnostic> diagnostics = new ArrayList<>(classpath.diagnostics());
        if (analysisScope == WholeProgramAnalysisScope.CURRENT_JAR_ONLY_USER_APPROVED) {
            diagnostics.add(Diagnostic.warning(
                            DiagnosticStage.PROTECTION,
                            WholeProgramAnalysisDiagnostics.CURRENT_JAR_ONLY_USER_APPROVED,
                            "fieldInternalization is using user-approved current-input-JAR-only "
                                    + "reference analysis; classPath and external observers were not analyzed")
                    .withDecision(analysisScope.wireName()));
        }
        diagnostics.addAll(rewrite.diagnostics());
        return new FieldInternalizationPipelineResult(
                plan,
                rewrite.methods(),
                report(true, plan, seed),
                diagnostics,
                analysisScope,
                analysisScope.analyzesClasspath());
    }

    private ProtectionPassReport report(
            boolean enabled,
            NativeFieldInternalizationPlan plan,
            long seed) {
        if (!enabled) {
            return new ProtectionPassReport(
                    "FIELD_INTERNALIZATION",
                    "PROGRAM_IR",
                    "SKIPPED",
                    "PROTECTION_PASS_DISABLED",
                    List.of(),
                    List.of(),
                    Long.toString(seed));
        }
        List<NativeFieldInternalizationDecision> internalized = plan.internalizedFields();
        if (internalized.isEmpty()) {
            String reason = plan.decisions().stream()
                    .flatMap(decision -> decision.reasons().stream())
                    .map(Enum::name)
                    .sorted()
                    .findFirst()
                    .orElse("FIELD_INTERNALIZATION_NO_CANDIDATE");
            return new ProtectionPassReport(
                    "FIELD_INTERNALIZATION",
                    "PROGRAM_IR",
                    "SKIPPED",
                    reason,
                    plan.decisions().stream()
                            .flatMap(decision -> decision.accesses().stream())
                            .map(access -> access.methodKey())
                            .toList(),
                    List.of(),
                    Long.toString(seed));
        }
        return new ProtectionPassReport(
                "FIELD_INTERNALIZATION",
                "PROGRAM_IR",
                "RAN",
                "FIELD_INTERNALIZATION",
                internalized.stream()
                        .flatMap(decision -> decision.accesses().stream())
                        .map(access -> access.methodKey())
                        .toList(),
                internalized.stream()
                        .map(decision -> decision.nativeSlotId().orElseThrow())
                        .toList(),
                Long.toString(seed));
    }

    private ClasspathPrograms parseClasspath(List<Path> classPath) {
        ArrayList<ParsedProgram> programs = new ArrayList<>();
        ArrayList<Diagnostic> diagnostics = new ArrayList<>();
        boolean complete = true;
        AsmClassParser parser = new AsmClassParser();
        for (Path entry : classPath.stream().sorted().toList()) {
            var result = Files.isDirectory(entry)
                    ? parser.parseAll(new DirectoryClassFileSource(entry))
                    : parser.parseAll(new JarClassFileSource(entry));
            diagnostics.addAll(result.diagnostics());
            ClassParseResult parsed = result.artifact().orElse(null);
            if (parsed == null) {
                complete = false;
            } else {
                programs.add(parsed.program());
            }
        }
        return new ClasspathPrograms(programs, complete, diagnostics);
    }

    private Set<String> multiReleaseOwners(Path jarPath) {
        LinkedHashSet<String> owners = new LinkedHashSet<>();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            jar.stream()
                    .map(entry -> entry.getName().replace('\\', '/'))
                    .filter(name -> name.startsWith("META-INF/versions/") && name.endsWith(".class"))
                    .forEach(name -> {
                        String remainder = name.substring("META-INF/versions/".length());
                        int versionEnd = remainder.indexOf('/');
                        if (versionEnd > 0 && versionEnd + 1 < remainder.length()) {
                            owners.add(remainder.substring(versionEnd + 1, remainder.length() - ".class".length()));
                        }
                    });
        } catch (IOException exception) {
            // Input discovery already reports unreadable JARs. Conservatively
            // returning no additional owners cannot approve a field unless all
            // other strict gates pass.
        }
        return Set.copyOf(owners);
    }

    private record ClasspathPrograms(
            List<ParsedProgram> programs,
            boolean complete,
            List<Diagnostic> diagnostics) {
        private ClasspathPrograms {
            programs = List.copyOf(programs);
            diagnostics = List.copyOf(diagnostics);
        }
    }
}
