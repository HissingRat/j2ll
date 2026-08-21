package xyz.melodysky.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import xyz.melodysky.report.ProtectionPassReport;
import xyz.melodysky.report.SensitivePlaintextFact;
import xyz.melodysky.toolchain.NativeImplementationPath;
import xyz.melodysky.toolchain.NativeImplementationPlan;
import xyz.melodysky.toolchain.NativeMethodImplementation;

/** Classifies protection evidence against the final native implementation surface. */
final class MainlineProtectionEvidenceClassifier {
    private static final Pattern INTERNAL_NAME = Pattern.compile(
            "(?:[A-Za-z_$][A-Za-z0-9_$]*/)+[A-Za-z_$][A-Za-z0-9_$]*");

    List<SensitivePlaintextFact> sensitivePlaintextFacts(
            List<ProtectionPassReport> reports,
            NativeImplementationPlan implementationPlan) {
        ClassificationContext context = context(reports, implementationPlan);
        List<SensitivePlaintextFact> protectionFacts = reports.stream()
                .flatMap(report -> report.sensitivePlaintextFacts().stream())
                .map(fact -> classify(fact, context))
                .toList();
        return Stream.concat(
                        protectionFacts.stream(),
                        nativeMetadataFacts(implementationPlan).stream())
                .distinct()
                .sorted(java.util.Comparator
                        .comparing(SensitivePlaintextFact::literalHash)
                        .thenComparing(SensitivePlaintextFact::sourceMethod)
                        .thenComparing(SensitivePlaintextFact::pathKind)
                        .thenComparing(SensitivePlaintextFact::gateMode)
                        .thenComparing(SensitivePlaintextFact::promotionReason))
                .toList();
    }

    List<ProtectionPassReport> classifiedReports(
            List<ProtectionPassReport> reports,
            NativeImplementationPlan implementationPlan) {
        ClassificationContext context = context(reports, implementationPlan);
        return reports.stream()
                .map(report -> new ProtectionPassReport(
                        report.passName(),
                        report.layer(),
                        report.status(),
                        report.reasonCode(),
                        report.affectedMethods(),
                        report.affectedSymbols(),
                        report.seed(),
                        report.sensitivePlaintextFacts().stream()
                                .map(fact -> classify(fact, context))
                                .toList(),
                        report.coverageFacts()))
                .toList();
    }

    private ClassificationContext context(
            List<ProtectionPassReport> reports,
            NativeImplementationPlan implementationPlan) {
        Map<String, NativeMethodImplementation> byMethod =
                implementationPlan.implementations().stream().collect(
                        java.util.stream.Collectors.toMap(
                                NativeMethodImplementation::methodKey,
                                implementation -> implementation,
                                (left, right) -> left,
                                LinkedHashMap::new));
        Set<String> llvmNativeMethods = byMethod.values().stream()
                .filter(implementation ->
                        implementation.path() == NativeImplementationPath.LLVM_NATIVE_PATH)
                .filter(implementation -> implementation.stringHelperSymbols().isEmpty())
                .map(NativeMethodImplementation::methodKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> semanticSensitiveMethods = reports.stream()
                .filter(report -> report.reasonCode()
                        .equals("PROTECTION_SEMANTICALLY_SENSITIVE_METHOD"))
                .flatMap(report -> report.affectedMethods().stream())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return new ClassificationContext(byMethod, llvmNativeMethods, semanticSensitiveMethods);
    }

    private SensitivePlaintextFact classify(
            SensitivePlaintextFact fact,
            ClassificationContext context) {
        NativeMethodImplementation implementation =
                context.implementationsByMethod().get(fact.sourceMethod());
        String path = implementation == null ? "HELPER_PATH" : implementation.path().name();
        if (fact.plaintext() == null || fact.plaintext().length() < 8) {
            return fact.withAuditClassification(
                    path,
                    "observedOnly",
                    "PLAINTEXT_LITERAL_TOO_SHORT_FOR_BLOCKING_GATE",
                    "metadataSensitiveObservedOnly");
        }
        if (isJvmMetadataBridgePlaintext(implementation)) {
            return fact.withAuditClassification(
                    path,
                    "observedOnly",
                    "JVM_METADATA_BRIDGE_PLAINTEXT",
                    "metadataSensitiveObservedOnly");
        }
        if (context.semanticSensitiveMethods().contains(fact.sourceMethod())) {
            return fact.withAuditClassification(
                    path,
                    "observedOnly",
                    "PROTECTION_SEMANTICALLY_SENSITIVE_METHOD",
                    "metadataSensitiveObservedOnly");
        }
        if (implementation != null
                && implementation.reasonCode().equals("LLVM_EXCEPTION_HELPER_IR")) {
            return fact.withAuditClassification(
                    path,
                    "observedOnly",
                    "EXCEPTION_HELPER_PLAINTEXT",
                    "metadataSensitiveObservedOnly");
        }
        if (context.llvmNativeMethods().contains(fact.sourceMethod())) {
            return fact.withAuditClassification(
                    "LLVM_NATIVE_PATH",
                    "blocking",
                    "LLVM_NATIVE_PATH_CONNECTED_SURFACE",
                    "llvmNativeSurface");
        }
        if (implementation != null
                && implementation.path() == NativeImplementationPath.TEMPLATE_JNI_PATH
                && implementation.reasonCode().equals("GENERIC_CONSTRUCTOR_BODY_HELPER")) {
            return fact.withAuditClassification(
                    "TEMPLATE_JNI_PATH_STABLE_SURFACE",
                    "blocking",
                    "TEMPLATE_CONSTRUCTOR_BODY_STABLE_SURFACE",
                    "templateStableSurface");
        }
        if (implementation != null
                && implementation.stringHelperSymbols().stream()
                        .map(this::runtimeHelperBaseSymbol)
                        .anyMatch(symbol -> symbol.equals("j2ll_rt_string_constant")
                                || symbol.startsWith("j2ll_rt_string_constant_"))) {
            return fact.withAuditClassification(
                    "HELPER_PATH_STABLE_GENERATED_C_SURFACE",
                    "blocking",
                    "STRING_CONCAT_CONSTANT_CARRIER_STABLE_SURFACE",
                    "stableGeneratedCSurface");
        }
        return fact.withAuditClassification(
                path,
                "observedOnly",
                "NON_BLOCKING_PATH_KIND_UNTIL_SURFACE_CONNECTED",
                "metadataSensitiveObservedOnly");
    }

    private List<SensitivePlaintextFact> nativeMetadataFacts(
            NativeImplementationPlan implementationPlan) {
        ArrayList<SensitivePlaintextFact> facts = new ArrayList<>();
        for (NativeMethodImplementation implementation : implementationPlan.implementations()) {
            String methodKey = implementation.methodKey();
            addMetadataFact(facts, implementation.entry().registrationOwner(), methodKey);
            if (implementation.entry().methodName().length() >= 8) {
                addMetadataFact(facts, implementation.entry().methodName(), methodKey);
            }
            Stream.of(
                            implementation.fieldKeys(),
                            implementation.allocationKeys(),
                            implementation.typeCheckKeys(),
                            implementation.classObjectKeys(),
                            implementation.runtimeMetadataKeys(),
                            implementation.constructorCallKeys(),
                            implementation.staticCallKeys(),
                            implementation.dispatchKeys())
                    .flatMap(List::stream)
                    .forEach(value -> addInternalNames(facts, value, methodKey));
            for (String fieldKey : implementation.fieldKeys()) {
                int ownerEnd = fieldKey.indexOf('#');
                int descriptorStart = fieldKey.indexOf('!');
                if (ownerEnd >= 0 && descriptorStart > ownerEnd + 1) {
                    String fieldName = fieldKey.substring(ownerEnd + 1, descriptorStart);
                    if (fieldName.length() >= 8) {
                        addMetadataFact(facts, fieldName, methodKey);
                    }
                }
            }
        }
        for (String runtimeMetadata : List.of(
                "java/lang/NoSuchFieldError",
                "unknown j2ll field token",
                "java/lang/NullPointerException",
                "java/lang/ArithmeticException",
                "/ by zero",
                "java/lang/ArrayIndexOutOfBoundsException")) {
            addMetadataFact(facts, runtimeMetadata, "<native-runtime>");
        }
        return facts.stream().distinct().toList();
    }

    private void addInternalNames(
            List<SensitivePlaintextFact> facts,
            String value,
            String methodKey) {
        Matcher matcher = INTERNAL_NAME.matcher(value);
        while (matcher.find()) {
            addMetadataFact(facts, matcher.group(), methodKey);
        }
    }

    private void addMetadataFact(
            List<SensitivePlaintextFact> facts,
            String plaintext,
            String methodKey) {
        if (plaintext == null || plaintext.isBlank()) {
            return;
        }
        facts.add(new SensitivePlaintextFact(
                plaintext,
                null,
                methodKey,
                "nativeMetadataStringEncoding",
                List.of("generated-c", "native-library"),
                "NATIVE_METADATA_STRING",
                "blocking",
                "GENERATED_C",
                "NATIVE_METADATA_PLAINTEXT",
                "nativeMetadataSurface"));
    }

    private boolean isJvmMetadataBridgePlaintext(NativeMethodImplementation implementation) {
        if (implementation == null) {
            return false;
        }
        return Stream.of(
                        implementation.staticCallKeys(),
                        implementation.dispatchKeys(),
                        implementation.stringHelperSymbols())
                .flatMap(java.util.Collection::stream)
                .anyMatch(symbol -> symbol.contains("java/lang/invoke/MethodHandles")
                        || symbol.contains("java/lang/invoke/MethodType")
                        || symbol.contains("java/lang/invoke/MethodHandle")
                        || symbol.contains("java/util/ResourceBundle"));
    }

    private String runtimeHelperBaseSymbol(String symbol) {
        int separator = symbol.indexOf('|');
        return separator < 0 ? symbol : symbol.substring(0, separator);
    }

    private record ClassificationContext(
            Map<String, NativeMethodImplementation> implementationsByMethod,
            Set<String> llvmNativeMethods,
            Set<String> semanticSensitiveMethods) {
        private ClassificationContext {
            implementationsByMethod = Map.copyOf(implementationsByMethod);
            llvmNativeMethods = Set.copyOf(llvmNativeMethods);
            semanticSensitiveMethods = Set.copyOf(semanticSensitiveMethods);
        }
    }
}
