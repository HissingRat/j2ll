package xyz.melodysky.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import xyz.melodysky.analysis.hierarchy.DefaultInterfaceAnalysis;
import xyz.melodysky.analysis.hierarchy.DefaultInterfaceAnalyzer;
import xyz.melodysky.analysis.runtime.RuntimeHelperSiteAnalyzer;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.frontend.classfile.ParsedProgram;
import xyz.melodysky.ir.ssa.SsaMethodResult;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.MethodRewriteStrategy;
import xyz.melodysky.packaging.NativeRegistrationEntry;
import xyz.melodysky.packaging.NativeRegistrationPlan;
import xyz.melodysky.toolchain.IntermediateArtifactLayout;
import xyz.melodysky.toolchain.MethodArtifact;
import xyz.melodysky.toolchain.NativeImplementationPlan;

/** Assembles final method outcome facts without making lowering policy decisions. */
public final class LoweringReportAssembler {
    private final DefaultInterfaceAnalyzer defaultInterfaceAnalyzer = new DefaultInterfaceAnalyzer();
    private final RuntimeHelperSiteAnalyzer helperSiteAnalyzer = new RuntimeHelperSiteAnalyzer();
    private final HelperBackedSiteReportFactory helperReportFactory =
            new HelperBackedSiteReportFactory();

    public List<LoweringReportMethod> assemble(
            ParsedProgram program,
            IntermediateArtifactLayout layout,
            List<SsaMethodResult> ssaResults,
            List<MethodRewriteDecision> rewriteDecisions,
            NativeRegistrationPlan registrationPlan,
            NativeImplementationPlan implementationPlan) {
        DefaultInterfaceAnalysis defaultInterfaces = defaultInterfaceAnalyzer.analyze(program);
        Map<String, MethodRewriteDecision> rewritesByMethod = indexRewrites(rewriteDecisions);
        Map<String, MethodArtifact> artifactsByMethod = indexArtifacts(layout);
        ArrayList<LoweringReportMethod> methods = new ArrayList<>();
        for (SsaMethodResult result : ssaResults) {
            ParsedMethod source = result.sourceMethod();
            MethodArtifact methodArtifact = requireArtifact(artifactsByMethod, source.methodKey());
            MethodRewriteDecision rewrite = rewritesByMethod.get(source.methodKey());
            Optional<NativeRegistrationEntry> registration = registrationFor(source, rewrite, registrationPlan);
            var implementation = implementationPlan.implementationFor(source.methodKey());
            methods.add(new LoweringReportMethod(
                    source.owner(),
                    source.name(),
                    source.descriptor(),
                    methodArtifact.methodId(),
                    result.status(),
                    rewrite == null ? null : rewrite.strategy(),
                    retentionMode(result, rewrite, registration),
                    rewrite == null
                            || rewrite.strategy()
                                    != MethodRewriteStrategy
                                            .INTERNAL_NATIVE_ONLY,
                    registration.isPresent(),
                    source.accessFlags().names(),
                    compilerFlags(source),
                    registration.map(NativeRegistrationEntry::nativeSymbol).orElse(null),
                    registration.map(NativeRegistrationEntry::registrationOwner).orElse(null),
                    implementation.map(item -> item.path().wireName()).orElse(null),
                    helperSiteAnalyzer.analyze(result, registration, implementation, defaultInterfaces).stream()
                            .map(helperReportFactory::create)
                            .toList(),
                    result.reasonCode(),
                    result.reason()));
        }
        return List.copyOf(methods);
    }

    private NativeMethodRetentionMode retentionMode(
            SsaMethodResult result,
            MethodRewriteDecision rewrite,
            Optional<NativeRegistrationEntry> registration) {
        if (rewrite != null
                && rewrite.strategy()
                        == MethodRewriteStrategy
                                .INTERNAL_NATIVE_ONLY) {
            return NativeMethodRetentionMode.INTERNAL_NATIVE_ONLY;
        }
        if (registration.isPresent()) {
            return NativeMethodRetentionMode.REGISTERED_NATIVE;
        }
        return NativeMethodRetentionMode.JAVA_BYTECODE;
    }

    private Map<String, MethodRewriteDecision> indexRewrites(List<MethodRewriteDecision> rewriteDecisions) {
        LinkedHashMap<String, MethodRewriteDecision> indexed = new LinkedHashMap<>();
        for (MethodRewriteDecision decision : rewriteDecisions) {
            indexed.putIfAbsent(decision.method().methodKey(), decision);
        }
        return indexed;
    }

    private Map<String, MethodArtifact> indexArtifacts(IntermediateArtifactLayout layout) {
        LinkedHashMap<String, MethodArtifact> indexed = new LinkedHashMap<>();
        layout.classes().stream()
                .flatMap(classArtifact -> layout.methodsFor(classArtifact.internalName()).stream())
                .forEach(method -> indexed.putIfAbsent(methodKey(method), method));
        return indexed;
    }

    private MethodArtifact requireArtifact(Map<String, MethodArtifact> artifactsByMethod, String methodKey) {
        MethodArtifact artifact = artifactsByMethod.get(methodKey);
        if (artifact == null) {
            throw new IllegalStateException("missing method artifact for " + methodKey);
        }
        return artifact;
    }

    private String methodKey(MethodArtifact method) {
        return method.owner() + "#" + method.name() + "!" + method.descriptor();
    }

    private Optional<NativeRegistrationEntry> registrationFor(
            ParsedMethod source,
            MethodRewriteDecision rewrite,
            NativeRegistrationPlan registrationPlan) {
        if (rewrite == null) {
            return Optional.empty();
        }
        String methodName = rewrite.generatedHelperName().orElse(source.name());
        String descriptor = registeredDescriptor(rewrite);
        return registrationPlan.entries().stream()
                .filter(entry -> entry.registrationOwner().equals(rewrite.registrationOwner()))
                .filter(entry -> entry.methodName().equals(methodName))
                .filter(entry -> entry.descriptor().equals(descriptor))
                .findFirst();
    }

    private String registeredDescriptor(MethodRewriteDecision rewrite) {
        if (rewrite.strategy() == MethodRewriteStrategy.CONSTRUCTOR_STUB) {
            String descriptor = rewrite.method().descriptor();
            int close = descriptor.indexOf(')');
            return "(L" + rewrite.method().owner() + ";" + descriptor.substring(1, close) + ")V";
        }
        if (rewrite.strategy() == MethodRewriteStrategy.CLASS_INITIALIZER_STUB) {
            return "()V";
        }
        return rewrite.method().descriptor();
    }

    private List<String> compilerFlags(ParsedMethod method) {
        ArrayList<String> flags = new ArrayList<>();
        if (method.accessFlags().isSynthetic()) {
            flags.add("synthetic");
        }
        if (method.accessFlags().has(xyz.melodysky.jvm.AccessFlags.BRIDGE)) {
            flags.add("bridge");
        }
        return List.copyOf(flags);
    }
}
