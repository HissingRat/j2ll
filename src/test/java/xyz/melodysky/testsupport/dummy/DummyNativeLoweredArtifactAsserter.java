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
import java.util.regex.Pattern;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Proves that every Dummy nativeLowered report entry has a real rewritten JAR carrier. */
final class DummyNativeLoweredArtifactAsserter {
    private static final Pattern INTERFACE_HELPER_OWNER =
            Pattern.compile("j2ll/generated/i_[0-9a-f]{32}");
    private static final Pattern INTERFACE_HELPER_METHOD =
            Pattern.compile("j2ll_m_[0-9a-f]{32}");

    private DummyNativeLoweredArtifactAsserter() {}

    static void assertEvidence(
            Path inputJar,
            Path outputJar,
            Path reports,
            List<DummyMethodExpectation> expectations,
            List<String> failures) {
        try {
            Set<String> expectedNative = new LinkedHashSet<>();
            for (DummyMethodExpectation expectation : expectations) {
                if (expectation.expectedStatus().equals("nativeLowered")) {
                    expectedNative.add(expectation.selector());
                }
            }

            JsonObject loweringRoot = report(reports.resolve("lowering-report.json"));
            JsonObject packagingRoot = report(reports.resolve("packaging-report.json"));
            Map<String, JsonObject> lowering = indexLowering(loweringRoot);
            Map<String, JsonObject> rewritten = indexRewritten(packagingRoot, failures);
            Map<String, JsonObject> registrations = indexRegistrations(packagingRoot, failures);

            assertExactSet(
                    "packaging rewrite missing nativeLowered selector",
                    "packaging rewrote unexpected selector",
                    expectedNative,
                    rewritten.keySet(),
                    failures);

            LinkedHashSet<String> expectedSymbols = new LinkedHashSet<>();
            for (String selector : expectedNative) {
                JsonObject loweringEntry = lowering.get(selector);
                JsonObject rewriteEntry = rewritten.get(selector);
                if (loweringEntry == null || rewriteEntry == null) {
                    continue;
                }
                assertOne(
                        inputJar,
                        outputJar,
                        DummyMethodExpectation.SelectorParts.parse(selector),
                        loweringEntry,
                        rewriteEntry,
                        registrations,
                        expectedSymbols,
                        failures);
            }
            assertExactSet(
                    "packaging registration missing native symbol",
                    "packaging has unexpected native registration symbol",
                    expectedSymbols,
                    registrations.keySet(),
                    failures);
        } catch (Exception exception) {
            failures.add("jar: failed to verify nativeLowered artifacts: " + exception.getMessage());
        }
    }

    private static void assertOne(
            Path inputJar,
            Path outputJar,
            DummyMethodExpectation.SelectorParts selector,
            JsonObject lowering,
            JsonObject rewrite,
            Map<String, JsonObject> registrations,
            Set<String> expectedSymbols,
            List<String> failures) throws Exception {
        String identity = selector.selector();
        String strategy = string(lowering, "rewriteStrategy");
        String symbol = string(lowering, "nativeSymbol");
        String registrationOwner = string(lowering, "registrationOwner");
        if (!booleanValue(lowering, "javaMethodPresent")
                || !booleanValue(lowering, "registrationPresent")
                || !"registeredNative".equals(string(lowering, "retentionMode"))) {
            failures.add("reports: nativeLowered method lacks registered-Java retention evidence: " + identity);
        }
        if (strategy == null || symbol == null || registrationOwner == null) {
            failures.add("reports: nativeLowered method lacks strategy/symbol/owner evidence: " + identity);
            return;
        }
        if (!expectedSymbols.add(symbol)) {
            failures.add("reports: native symbol is shared by multiple Dummy expectations: " + symbol);
        }
        assertEqual("rewrite strategy", identity, strategy, string(rewrite, "rewriteStrategy"), failures);
        assertEqual("registration owner", identity, registrationOwner, string(rewrite, "registrationOwner"), failures);
        if (!booleanValue(rewrite, "javaMethodPresent") || !booleanValue(rewrite, "registrationPresent")) {
            failures.add("reports: packaging rewrite lacks Java/registration evidence: " + identity);
        }

        JsonObject registration = registrations.get(symbol);
        if (registration == null) {
            return;
        }
        String carrierOwner = string(registration, "registrationOwner");
        String carrierMethod = string(registration, "method");
        String carrierDescriptor = string(registration, "descriptor");
        assertEqual("carrier owner", identity, registrationOwner, carrierOwner, failures);
        if (carrierOwner == null || carrierMethod == null || carrierDescriptor == null) {
            failures.add("reports: registration carrier is incomplete: " + identity);
            return;
        }

        DummyMethodExpectation.SelectorParts carrier = new DummyMethodExpectation.SelectorParts(
                carrierOwner + "#" + carrierMethod + "!" + carrierDescriptor,
                carrierOwner,
                carrierMethod,
                carrierDescriptor);
        MethodNode carrierMethodNode = jarMethod(outputJar, carrier);
        if (carrierMethodNode == null) {
            failures.add("jar: registered native carrier is missing: " + carrier.selector());
        } else if ((carrierMethodNode.access & Opcodes.ACC_NATIVE) == 0 || hasCode(carrierMethodNode)) {
            failures.add("jar: registration carrier is not ACC_NATIVE without Code: " + carrier.selector());
        }

        MethodNode input = jarMethod(inputJar, selector);
        MethodNode output = jarMethod(outputJar, selector);
        if (input == null || output == null) {
            failures.add("jar: nativeLowered original selector is missing: " + identity);
            return;
        }
        switch (strategy) {
            case "nativeOriginal" -> assertNativeOriginal(selector, output, carrier, failures);
            case "constructorStub", "classInitializerStub", "interfaceMethodStub" ->
                    assertStub(outputJar, selector, input, output, carrier, strategy, failures);
            default -> failures.add("jar: unsupported nativeLowered rewrite strategy in Dummy: "
                    + strategy + " for " + identity);
        }
    }

    private static void assertNativeOriginal(
            DummyMethodExpectation.SelectorParts selector,
            MethodNode output,
            DummyMethodExpectation.SelectorParts carrier,
            List<String> failures) {
        if ((output.access & Opcodes.ACC_NATIVE) == 0 || hasCode(output)) {
            failures.add("jar: nativeOriginal is not ACC_NATIVE without Code: " + selector.selector());
        }
        if (!selector.owner().equals(carrier.owner())
                || !selector.method().equals(carrier.method())
                || !selector.descriptor().equals(carrier.descriptor())) {
            failures.add("jar: nativeOriginal registration carrier differs from original selector: "
                    + selector.selector());
        }
    }

    private static void assertStub(
            Path outputJar,
            DummyMethodExpectation.SelectorParts selector,
            MethodNode input,
            MethodNode output,
            DummyMethodExpectation.SelectorParts carrier,
            String strategy,
            List<String> failures) {
        if ((output.access & Opcodes.ACC_NATIVE) != 0 || !hasCode(output)) {
            failures.add("jar: " + strategy + " is not a Java Code stub: " + selector.selector());
            return;
        }
        if (AsmMethodSemanticFingerprint.canonicalForm(input)
                .equals(AsmMethodSemanticFingerprint.canonicalForm(output))) {
            failures.add("jar: " + strategy + " retained the original Java body: " + selector.selector());
        }

        int loaderCall = -1;
        int carrierCall = -1;
        for (int index = 0; index < output.instructions.size(); index++) {
            if (!(output.instructions.get(index) instanceof MethodInsnNode call)) {
                continue;
            }
            if (call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("native0/Loader")
                    && call.name.equals("ensureLoaded")
                    && call.desc.equals("()V")) {
                loaderCall = index;
            }
            if (call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals(carrier.owner())
                    && call.name.equals(carrier.method())
                    && call.desc.equals(carrier.descriptor())) {
                carrierCall = index;
            }
        }
        if (carrierCall < 0) {
            failures.add("jar: " + strategy + " does not call its exact carrier: " + selector.selector());
        }
        if (strategy.equals("constructorStub") && loaderCall < 0) {
            assertClassInitializerLoadsBeforeConstructor(outputJar, selector, failures);
        } else if (loaderCall < 0 || loaderCall >= carrierCall) {
            failures.add("jar: " + strategy + " does not call Loader before its exact carrier: "
                    + selector.selector());
        }
        if (strategy.equals("interfaceMethodStub")
                && (!INTERFACE_HELPER_OWNER.matcher(carrier.owner()).matches()
                        || !INTERFACE_HELPER_METHOD.matcher(carrier.method()).matches())) {
            failures.add("jar: interface helper carrier is not build-scoped hash-only: " + carrier.selector());
        }
    }

    private static void assertClassInitializerLoadsBeforeConstructor(
            Path outputJar,
            DummyMethodExpectation.SelectorParts constructor,
            List<String> failures) {
        try {
            DummyMethodExpectation.SelectorParts classInitializer =
                    new DummyMethodExpectation.SelectorParts(
                            constructor.owner() + "#<clinit>!()V",
                            constructor.owner(),
                            "<clinit>",
                            "()V");
            MethodNode method = jarMethod(outputJar, classInitializer);
            if (method == null || loaderCall(method) < 0) {
                failures.add("jar: constructorStub has no class-initialization Loader guard: "
                        + constructor.selector());
            }
        } catch (Exception exception) {
            failures.add("jar: failed to inspect constructor Loader guard: "
                    + constructor.selector() + " (" + exception.getMessage() + ")");
        }
    }

    private static int loaderCall(MethodNode method) {
        for (int index = 0; index < method.instructions.size(); index++) {
            if (method.instructions.get(index) instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("native0/Loader")
                    && call.name.equals("ensureLoaded")
                    && call.desc.equals("()V")) {
                return index;
            }
        }
        return -1;
    }

    private static Map<String, JsonObject> indexLowering(JsonObject root) {
        LinkedHashMap<String, JsonObject> indexed = new LinkedHashMap<>();
        for (JsonElement element : root.getAsJsonArray("requestedMethods")) {
            JsonObject method = element.getAsJsonObject();
            indexed.put(selector(method, "class"), method);
        }
        return indexed;
    }

    private static Map<String, JsonObject> indexRewritten(JsonObject root, List<String> failures) {
        LinkedHashMap<String, JsonObject> indexed = new LinkedHashMap<>();
        JsonArray classes = root.getAsJsonArray("rewrittenClasses");
        for (JsonElement classElement : classes) {
            JsonObject rewrittenClass = classElement.getAsJsonObject();
            String owner = string(rewrittenClass, "class");
            for (JsonElement methodElement : rewrittenClass.getAsJsonArray("methods")) {
                JsonObject method = methodElement.getAsJsonObject();
                String selector = selector(method, owner);
                if (indexed.putIfAbsent(selector, method) != null) {
                    failures.add("reports: duplicate packaging rewrite entry: " + selector);
                }
            }
        }
        return indexed;
    }

    private static Map<String, JsonObject> indexRegistrations(JsonObject root, List<String> failures) {
        LinkedHashMap<String, JsonObject> indexed = new LinkedHashMap<>();
        for (JsonElement element : root.getAsJsonArray("registeredNativeMethods")) {
            JsonObject registration = element.getAsJsonObject();
            String symbol = string(registration, "nativeSymbol");
            if (symbol == null || indexed.putIfAbsent(symbol, registration) != null) {
                failures.add("reports: missing or duplicate native registration symbol: " + symbol);
            }
        }
        return indexed;
    }

    private static JsonObject report(Path path) throws Exception {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("missing " + path.getFileName());
        }
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    private static MethodNode jarMethod(
            Path jarPath,
            DummyMethodExpectation.SelectorParts selector) throws Exception {
        try (JarFile jar = new JarFile(jarPath.toFile(), false)) {
            var entry = jar.getJarEntry(selector.owner() + ".class");
            if (entry == null) {
                return null;
            }
            ClassNode node = new ClassNode();
            try (var input = jar.getInputStream(entry)) {
                new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
            return node.methods.stream()
                    .filter(method -> method.name.equals(selector.method())
                            && method.desc.equals(selector.descriptor()))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static boolean hasCode(MethodNode method) {
        return method.instructions != null && method.instructions.size() > 0;
    }

    private static String selector(JsonObject method, String ownerFieldOrValue) {
        String owner = ownerFieldOrValue.contains("/") ? ownerFieldOrValue : string(method, ownerFieldOrValue);
        String name = string(method, "method");
        String descriptor = string(method, "descriptor");
        return owner + "#" + name + "!" + descriptor;
    }

    private static void assertExactSet(
            String missingLabel,
            String extraLabel,
            Set<String> expected,
            Set<String> actual,
            List<String> failures) {
        expected.stream()
                .filter(value -> !actual.contains(value))
                .forEach(value -> failures.add("reports: " + missingLabel + ": " + value));
        actual.stream()
                .filter(value -> !expected.contains(value))
                .forEach(value -> failures.add("reports: " + extraLabel + ": " + value));
    }

    private static void assertEqual(
            String label,
            String selector,
            String expected,
            String actual,
            List<String> failures) {
        if (!java.util.Objects.equals(expected, actual)) {
            failures.add("reports: " + label + " differs for " + selector
                    + " expected=" + expected + " actual=" + actual);
        }
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
