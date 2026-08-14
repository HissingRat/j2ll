package xyz.melodysky.testsupport.dummy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** Verifies that each selected Dummy method has exactly the declared final outcome. */
public final class DummyMethodOutcomeAsserter {
    private DummyMethodOutcomeAsserter() {}

    public static void assertExactOutcomes(
            Path inputJar,
            Path outputJar,
            Path reports,
            List<DummyMethodExpectation> expectations,
            List<String> failures) {
        try {
            Map<String, DummyMethodExpectation> expectedBySelector = expectations(expectations, failures);
            Map<String, JsonObject> requested = requestedMethods(
                    reports.resolve("lowering-report.json"),
                    failures);
            Map<String, JsonObject> ineligible = ineligibleMethods(
                    reports.resolve("lowering-report.json"),
                    failures);
            Set<String> expectedRequested = selectorsWithStatus(expectedBySelector, false);
            Set<String> expectedIneligible = selectorsWithStatus(expectedBySelector, true);
            assertExactSet(
                    "lowering-report missing selected method",
                    "lowering-report has unexpected requested method",
                    expectedRequested,
                    requested.keySet(),
                    failures);
            assertExactSet(
                    "lowering-report missing ineligible method",
                    "lowering-report has unexpected ineligible method",
                    expectedIneligible,
                    ineligible.keySet(),
                    failures);

            Map<String, JsonObject> skipped = skippedMethods(
                    reports.resolve("skipped-method-report.json"),
                    failures);
            Set<String> expectedSkipped = new LinkedHashSet<>();
            for (DummyMethodExpectation expectation : expectedBySelector.values()) {
                if (expectation.expectedStatus().equals("ineligible")) {
                    assertIneligibleEntry(expectation, ineligible.get(expectation.selector()), failures);
                    if (requested.containsKey(expectation.selector())) {
                        failures.add("reports: ineligible selector appeared in requestedMethods: "
                                + expectation.selector());
                    }
                    if (skipped.containsKey(expectation.selector())) {
                        failures.add("reports: ineligible selector appeared in skipped-method-report: "
                                + expectation.selector());
                    }
                    assertDeclarationPreserved(inputJar, outputJar, expectation, failures);
                    continue;
                }
                if (expectation.expectedStatus().equals("skipped")) {
                    expectedSkipped.add(expectation.selector());
                }
                JsonObject lowering = requested.get(expectation.selector());
                if (lowering == null) {
                    continue;
                }
                assertLoweringEntry(expectation, lowering, failures);
                if (expectation.expectedStatus().equals("skipped")) {
                    assertSkippedEntry(expectation, skipped.get(expectation.selector()), failures);
                    assertSkippedMethodPreserved(inputJar, outputJar, expectation, failures);
                } else if (skipped.containsKey(expectation.selector())) {
                    failures.add("reports: nativeLowered selector appeared in skipped-method-report: "
                            + expectation.selector());
                }
            }
            assertExactSkippedSet(expectedSkipped, skipped.keySet(), failures);
            assertSkippedMethodsNotRegistered(
                    reports.resolve("packaging-report.json"),
                    notRegisteredSelectors(expectedSkipped, expectedIneligible),
                    failures);
            DummyNativeLoweredArtifactAsserter.assertEvidence(
                    inputJar,
                    outputJar,
                    reports,
                    expectations,
                    failures);
        } catch (Exception exception) {
            failures.add("reports: failed to verify exact Dummy method outcomes: " + exception.getMessage());
        }
    }

    private static Map<String, DummyMethodExpectation> expectations(
            List<DummyMethodExpectation> expectations,
            List<String> failures) {
        LinkedHashMap<String, DummyMethodExpectation> indexed = new LinkedHashMap<>();
        for (DummyMethodExpectation expectation : expectations) {
            if (indexed.putIfAbsent(expectation.selector(), expectation) != null) {
                failures.add("expectations: duplicate Dummy selector " + expectation.selector());
            }
        }
        return indexed;
    }

    private static Map<String, JsonObject> requestedMethods(Path report, List<String> failures) throws Exception {
        JsonObject root = reportRoot(report, failures);
        return index(root == null ? null : root.getAsJsonArray("requestedMethods"), false, failures);
    }

    private static Map<String, JsonObject> skippedMethods(Path report, List<String> failures) throws Exception {
        JsonObject root = reportRoot(report, failures);
        return index(root == null ? null : root.getAsJsonArray("entries"), true, failures);
    }

    private static Map<String, JsonObject> ineligibleMethods(Path report, List<String> failures) throws Exception {
        JsonObject root = reportRoot(report, failures);
        return index(root == null ? null : root.getAsJsonArray("ineligible"), true, failures);
    }

    private static JsonObject reportRoot(Path report, List<String> failures) throws Exception {
        if (!Files.isRegularFile(report)) {
            failures.add("reports: missing " + report.getFileName());
            return null;
        }
        return JsonParser.parseString(Files.readString(report)).getAsJsonObject();
    }

    private static Map<String, JsonObject> index(
            JsonArray entries,
            boolean hasSelector,
            List<String> failures) {
        LinkedHashMap<String, JsonObject> indexed = new LinkedHashMap<>();
        if (entries == null) {
            failures.add("reports: method outcome array is missing");
            return indexed;
        }
        for (JsonElement element : entries) {
            JsonObject method = element.getAsJsonObject();
            String selector = hasSelector
                    ? string(method, "selector")
                    : selector(method);
            if (selector == null) {
                failures.add("reports: method outcome entry has no exact selector identity");
            } else if (indexed.putIfAbsent(selector, method) != null) {
                failures.add("reports: duplicate method outcome entry for " + selector);
            }
        }
        return indexed;
    }

    private static void assertExactSkippedSet(
            Set<String> expected,
            Set<String> actual,
            List<String> failures) {
        setDifference("skipped-method-report missing skipped method", expected, actual, failures);
        setDifference("skipped-method-report has unexpected skipped method", actual, expected, failures);
    }

    private static void assertExactSet(
            String missingLabel,
            String unexpectedLabel,
            Set<String> expected,
            Set<String> actual,
            List<String> failures) {
        setDifference(missingLabel, expected, actual, failures);
        setDifference(unexpectedLabel, actual, expected, failures);
    }

    private static Set<String> selectorsWithStatus(
            Map<String, DummyMethodExpectation> expectations,
            boolean ineligible) {
        LinkedHashSet<String> selectors = new LinkedHashSet<>();
        expectations.values().stream()
                .filter(expectation -> expectation.expectedStatus().equals("ineligible") == ineligible)
                .map(DummyMethodExpectation::selector)
                .forEach(selectors::add);
        return selectors;
    }

    private static Set<String> notRegisteredSelectors(Set<String> skipped, Set<String> ineligible) {
        LinkedHashSet<String> selectors = new LinkedHashSet<>(skipped);
        selectors.addAll(ineligible);
        return selectors;
    }

    private static void setDifference(
            String label,
            Set<String> left,
            Set<String> right,
            List<String> failures) {
        left.stream()
                .filter(selector -> !right.contains(selector))
                .forEach(selector -> failures.add("reports: " + label + ": " + selector));
    }

    private static void assertLoweringEntry(
            DummyMethodExpectation expectation,
            JsonObject method,
            List<String> failures) {
        String actualStatus = string(method, "status");
        if (!expectation.expectedStatus().equals(actualStatus)) {
            failures.add("reports: " + expectation.selector() + " expected status "
                    + expectation.expectedStatus() + " but was " + actualStatus);
        }
        String actualReason = string(method, "reasonCode");
        if (!java.util.Objects.equals(expectation.expectedReasonCode(), actualReason)) {
            failures.add("reports: " + expectation.selector() + " expected reason "
                    + expectation.expectedReasonCode() + " but was " + actualReason);
        }
        if (expectation.expectedStatus().equals("nativeLowered")) {
            if (string(method, "rewriteStrategy") == null) {
                failures.add("reports: nativeLowered method has no rewrite strategy: " + expectation.selector());
            }
            if (string(method, "nativeImplementationPath") == null) {
                failures.add("reports: nativeLowered method has no implementation path: " + expectation.selector());
            }
            return;
        }
        assertNullField(method, "rewriteStrategy", expectation.selector(), failures);
        assertNullField(method, "nativeSymbol", expectation.selector(), failures);
        assertNullField(method, "registrationOwner", expectation.selector(), failures);
        assertNullField(method, "nativeImplementationPath", expectation.selector(), failures);
        if (!booleanValue(method, "javaMethodPresent")) {
            failures.add("reports: skipped method was not retained in Java: " + expectation.selector());
        }
        if (booleanValue(method, "registrationPresent")) {
            failures.add("reports: skipped method has registration evidence: " + expectation.selector());
        }
    }

    private static void assertNullField(
            JsonObject object,
            String name,
            String selector,
            List<String> failures) {
        JsonElement value = object.get(name);
        if (value != null && !value.isJsonNull()) {
            failures.add("reports: skipped method has " + name + ": " + selector);
        }
    }

    private static void assertSkippedEntry(
            DummyMethodExpectation expectation,
            JsonObject entry,
            List<String> failures) {
        if (entry == null) {
            return;
        }
        if (!"skipped".equals(string(entry, "status"))) {
            failures.add("reports: skipped entry has wrong status: " + expectation.selector());
        }
        if (!expectation.expectedReasonCode().equals(string(entry, "reasonCode"))) {
            failures.add("reports: skipped entry reason differs from exact expectation: "
                    + expectation.selector());
        }
        if (!booleanValue(entry, "hasCode")) {
            failures.add("reports: skipped entry did not retain Code: " + expectation.selector());
        }
    }

    private static void assertIneligibleEntry(
            DummyMethodExpectation expectation,
            JsonObject entry,
            List<String> failures) {
        if (entry == null) {
            return;
        }
        if (!"ineligible".equals(string(entry, "status"))) {
            failures.add("reports: ineligible entry has wrong status: " + expectation.selector());
        }
        if (!expectation.expectedReasonCode().equals(string(entry, "reasonCode"))) {
            failures.add("reports: ineligible entry reason differs from exact expectation: "
                    + expectation.selector());
        }
    }

    private static void assertSkippedMethodPreserved(
            Path inputJar,
            Path outputJar,
            DummyMethodExpectation expectation,
            List<String> failures) throws Exception {
        DummyMethodExpectation.SelectorParts parts = expectation.parts();
        MethodNode input = jarMethod(inputJar, parts);
        MethodNode output = jarMethod(outputJar, parts);
        if (input == null || output == null) {
            failures.add("jar: skipped method is missing from input or output: " + expectation.selector());
            return;
        }
        if ((output.access & Opcodes.ACC_NATIVE) != 0) {
            failures.add("jar: skipped method was marked native: " + expectation.selector());
        }
        if (input.instructions == null || input.instructions.size() == 0
                || output.instructions == null || output.instructions.size() == 0) {
            failures.add("jar: skipped method does not retain executable Code: " + expectation.selector());
        }
        assertSemanticShapePreserved("skipped", expectation.selector(), input, output, failures);
    }

    private static void assertDeclarationPreserved(
            Path inputJar,
            Path outputJar,
            DummyMethodExpectation expectation,
            List<String> failures) throws Exception {
        DummyMethodExpectation.SelectorParts parts = expectation.parts();
        MethodNode input = jarMethod(inputJar, parts);
        MethodNode output = jarMethod(outputJar, parts);
        if (input == null || output == null) {
            failures.add("jar: ineligible method is missing from input or output: " + expectation.selector());
            return;
        }
        assertSemanticShapePreserved("ineligible", expectation.selector(), input, output, failures);
    }

    private static MethodNode jarMethod(
            Path jarPath,
            DummyMethodExpectation.SelectorParts parts) throws Exception {
        if (jarPath == null || !Files.isRegularFile(jarPath)) {
            return null;
        }
        try (JarFile jar = new JarFile(jarPath.toFile(), false)) {
            var entry = jar.getJarEntry(parts.owner() + ".class");
            if (entry == null) {
                return null;
            }
            ClassNode node = new ClassNode();
            try (var input = jar.getInputStream(entry)) {
                new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
            return node.methods.stream()
                    .filter(method -> method.name.equals(parts.method())
                            && method.desc.equals(parts.descriptor()))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static void assertSemanticShapePreserved(
            String outcome,
            String selector,
            MethodNode input,
            MethodNode output,
            List<String> failures) {
        String inputForm = AsmMethodSemanticFingerprint.canonicalForm(input);
        String outputForm = AsmMethodSemanticFingerprint.canonicalForm(output);
        if (!inputForm.equals(outputForm)) {
            failures.add("jar: " + outcome + " method semantic shape changed: " + selector
                    + " inputSha256=" + AsmMethodSemanticFingerprint.sha256(input)
                    + " outputSha256=" + AsmMethodSemanticFingerprint.sha256(output));
        }
    }

    private static void assertSkippedMethodsNotRegistered(
            Path packagingReport,
            Set<String> skippedSelectors,
            List<String> failures) throws Exception {
        if (skippedSelectors.isEmpty()) {
            return;
        }
        JsonObject root = reportRoot(packagingReport, failures);
        if (root == null) {
            return;
        }
        JsonArray entries = root.getAsJsonArray("registeredNativeMethods");
        if (entries == null) {
            failures.add("reports: packaging report has no registeredNativeMethods");
            return;
        }
        for (JsonElement element : entries) {
            JsonObject method = element.getAsJsonObject();
            String selector = selector(method);
            if (skippedSelectors.contains(selector)) {
                failures.add("reports: skipped method appears in native registration: " + selector);
            }
        }
    }

    private static String selector(JsonObject method) {
        String owner = string(method, "class");
        if (owner == null) {
            owner = string(method, "registrationOwner");
        }
        String name = string(method, "method");
        String descriptor = string(method, "descriptor");
        return owner == null || name == null || descriptor == null
                ? null
                : owner + "#" + name + "!" + descriptor;
    }

    private static String string(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    private static boolean booleanValue(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element != null && !element.isJsonNull() && element.getAsBoolean();
    }
}
