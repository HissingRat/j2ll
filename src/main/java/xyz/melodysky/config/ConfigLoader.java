package xyz.melodysky.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import xyz.melodysky.analysis.hierarchy.AnalysisWorld;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.toolchain.HostPlatform;
import xyz.melodysky.toolchain.TargetTriple;

public final class ConfigLoader {
    private static final SecureRandom PROTECTION_RANDOM = new SecureRandom();
    private static final Set<String> TOP_LEVEL_FIELDS = Set.of(
            "schemaVersion",
            "jarFile",
            "classPath",
            "javaHome",
            "runtimeImage",
            "worldModel",
            "outputDirectory",
            "whiteList",
            "blackList",
            "target",
            "embeddedLibraryDirectory",
            "signaturePolicy",
            "signing",
            "intermediates",
            "protection");

    private static final Set<String> TARGET_FIELDS = Set.of(
            "windowsX64", "windowsArm64", "linuxX64", "linuxArm64", "macosX64", "macosArm64");
    private static final Set<String> SIGNING_FIELDS = Set.of(
            "keystorePath", "storePasswordEnv", "keyAlias", "keyPasswordEnv", "tsaUrl");
    private static final Set<String> INTERMEDIATE_FIELDS = Set.of(
            "enabled", "includeDebugDumps", "includePerClassIr", "includePerClassLlvm", "includePerClassC");
    private static final Set<String> PROTECTION_FIELDS = Set.of("enabled", "seed", "ir", "llvm", "binary");
    private static final Set<String> IR_FIELDS = Set.of(
            "enabled",
            "controlFlowFlattening",
            "fakeBranches",
            "basicBlockSplitting",
            "constantEncryption",
            "stringEncryption",
            "methodInlining",
            "methodSplitting",
            "callIndirection",
            "fieldInternalization",
            "methodInternalization",
            "publicMethodInternalizationAllowList",
            "methodTableHiding",
            "blockNameObfuscation");
    private static final Set<String> LLVM_FIELDS = Set.of(
            "enabled",
            "nameObfuscation",
            "opaquePredicates",
            "blockLayoutPerturbation",
            "indirectCalls",
            "globalLayout");
    private static final Set<String> BINARY_FIELDS = Set.of(
            "enabled",
            "hideInternalSymbols",
            "strip",
            "removePdb",
            "symbolAudit",
            "retainUnwindInfo");

    public ConfigLoadResult load(Path configPath) throws IOException {
        try (Reader reader = Files.newBufferedReader(configPath)) {
            return load(JsonParser.parseReader(reader).getAsJsonObject(), configPath.getParent());
        }
    }

    public ConfigLoadResult load(JsonObject root, Path baseDirectory) {
        Path base = baseDirectory == null ? Path.of(".").toAbsolutePath().normalize() : baseDirectory;
        ArrayList<Diagnostic> diagnostics = new ArrayList<>();
        validateShape(root, diagnostics);
        if (hasErrors(diagnostics)) {
            return new ConfigLoadResult(Optional.empty(), diagnostics);
        }

        int schemaVersion = root.get("schemaVersion").getAsInt();
        if (schemaVersion != 1) {
            diagnostics.add(Diagnostic.error(
                    DiagnosticStage.CONFIG,
                    ConfigDiagnostics.UNSUPPORTED_SCHEMA_VERSION,
                    "unsupported schemaVersion " + schemaVersion));
        }

        AnalysisWorld worldModel = parseEnum(
                root.get("worldModel").getAsString(),
                AnalysisWorld.class,
                "worldModel",
                diagnostics);
        JsonObject protectionObject = root.getAsJsonObject("protection");
        JsonObject irProtectionObject = protectionObject.getAsJsonObject("ir");
        boolean fieldInternalizationEnabled = protectionObject.get("enabled").getAsBoolean()
                && irProtectionObject.get("enabled").getAsBoolean()
                && irProtectionObject
                .get("fieldInternalization")
                .getAsBoolean();
        if (fieldInternalizationEnabled && worldModel != AnalysisWorld.CLOSED_WORLD) {
            diagnostics.add(Diagnostic.warning(
                    DiagnosticStage.CONFIG,
                    ConfigDiagnostics.FIELD_INTERNALIZATION_REQUIRES_CLOSED_WORLD,
                    "protection.ir.fieldInternalization requires worldModel=CLOSED_WORLD; "
                            + "build requires confirmation before using current-input-JAR-only analysis")
                    .withDecision("confirmationRequired"));
        }
        boolean methodInternalizationEnabled = protectionObject.get("enabled").getAsBoolean()
                && irProtectionObject.get("enabled").getAsBoolean()
                && irProtectionObject
                .get("methodInternalization")
                .getAsBoolean();
        if (methodInternalizationEnabled && worldModel != AnalysisWorld.CLOSED_WORLD) {
            diagnostics.add(Diagnostic.warning(
                    DiagnosticStage.CONFIG,
                    ConfigDiagnostics.METHOD_INTERNALIZATION_REQUIRES_CLOSED_WORLD,
                    "protection.ir.methodInternalization requires worldModel=CLOSED_WORLD; "
                            + "build requires confirmation before using current-input-JAR-only analysis")
                    .withDecision("confirmationRequired"));
        }
        SignaturePolicy signaturePolicy = parseSignaturePolicy(root, diagnostics);
        List<Selector> whiteList = parseSelectors("whiteList", root.getAsJsonArray("whiteList"), diagnostics);
        List<Selector> blackList = parseSelectors("blackList", root.getAsJsonArray("blackList"), diagnostics);
        List<Selector> publicMethodInternalizationAllowList = parseExactMethodSelectors(
                "protection.ir.publicMethodInternalizationAllowList",
                irProtectionObject.getAsJsonArray("publicMethodInternalizationAllowList"),
                diagnostics);
        TargetConfig target = parseTarget(root.getAsJsonObject("target"), diagnostics);
        List<TargetTriple> targets = target.enabledTargets();
        if (targets.isEmpty()) {
            diagnostics.add(Diagnostic.error(
                    DiagnosticStage.CONFIG,
                    ConfigDiagnostics.NO_TARGET_SELECTED,
                    "at least one target must be selected"));
        }

        String embeddedLibraryDirectory = root.get("embeddedLibraryDirectory").getAsString();
        if (!isRuntimeClassDirectory(embeddedLibraryDirectory)) {
            diagnostics.add(Diagnostic.error(
                    DiagnosticStage.CONFIG,
                    ConfigDiagnostics.INVALID_EMBEDDED_LIBRARY_DIRECTORY,
                    "embeddedLibraryDirectory must be a relative Java package path "
                            + "such as native0 or xyz/Melody/natives"));
        }
        SigningConfig signing = parseSigning(base, root.get("signing"), diagnostics);
        if (signaturePolicy == SignaturePolicy.RESIGN && signing == null) {
            diagnostics.add(Diagnostic.error(
                    DiagnosticStage.CONFIG,
                    ConfigDiagnostics.INVALID_FIELD_VALUE,
                    "signaturePolicy resign requires signing config"));
        }

        if (hasErrors(diagnostics)) {
            return new ConfigLoadResult(Optional.empty(), diagnostics);
        }

        Path jarFile = resolve(base, root.get("jarFile").getAsString());
        Path outputDirectory = resolve(base, root.get("outputDirectory").getAsString());
        validatePaths(jarFile, outputDirectory, diagnostics);
        List<Path> classPath = classPath(base, root.getAsJsonArray("classPath"));
        String configuredSeed = nullableString(root.getAsJsonObject("protection"), "seed");
        ProtectionSeedMode seedMode = configuredSeed == null
                ? ProtectionSeedMode.RANDOMIZED
                : ProtectionSeedMode.REPRODUCIBLE;
        String seed = configuredSeed == null ? randomSeed() : configuredSeed;

        ResolvedConfig config = new ResolvedConfig(
                schemaVersion,
                jarFile,
                classPath,
                nullablePath(base, root, "javaHome"),
                nullablePath(base, root, "runtimeImage"),
                worldModel,
                outputDirectory,
                whiteList,
                blackList,
                target,
                targets,
                embeddedLibraryDirectory,
                signaturePolicy,
                signing,
                parseIntermediates(root.getAsJsonObject("intermediates")),
                parseProtection(
                        root.getAsJsonObject("protection"),
                        seed,
                        seedMode,
                        publicMethodInternalizationAllowList));
        return new ConfigLoadResult(Optional.of(config), diagnostics);
    }

    private boolean isRuntimeClassDirectory(String value) {
        if (!value.matches("[A-Za-z_$][A-Za-z0-9_$]*(/[A-Za-z_$][A-Za-z0-9_$]*)*")) {
            return false;
        }
        return !value.equals("java")
                && !value.startsWith("java/")
                && !value.equals("META-INF")
                && !value.startsWith("META-INF/");
    }

    private void validateShape(JsonObject root, List<Diagnostic> diagnostics) {
        validateFields(root, TOP_LEVEL_FIELDS, Set.of("target"), "config", diagnostics);
        validateOptionalObject(root, "target", TARGET_FIELDS, "target", diagnostics);
        validateNullableObject(root, "signing", SIGNING_FIELDS, "signing", diagnostics);
        validateObject(root, "intermediates", INTERMEDIATE_FIELDS, "intermediates", diagnostics);
        JsonObject protection = validateObject(root, "protection", PROTECTION_FIELDS, "protection", diagnostics);
        if (protection == null) {
            return;
        }
        JsonObject ir = validateObject(protection, "ir", IR_FIELDS, "protection.ir", diagnostics);
        if (ir != null) {
            validateBoolean(ir, "controlFlowFlattening", "protection.ir.controlFlowFlattening", diagnostics);
            validateBoolean(ir, "fakeBranches", "protection.ir.fakeBranches", diagnostics);
            validateBoolean(ir, "basicBlockSplitting", "protection.ir.basicBlockSplitting", diagnostics);
            validateBoolean(ir, "constantEncryption", "protection.ir.constantEncryption", diagnostics);
            validateBoolean(ir, "stringEncryption", "protection.ir.stringEncryption", diagnostics);
            validateBoolean(ir, "methodInlining", "protection.ir.methodInlining", diagnostics);
            validateBoolean(ir, "methodSplitting", "protection.ir.methodSplitting", diagnostics);
            validateBoolean(ir, "callIndirection", "protection.ir.callIndirection", diagnostics);
            validateBoolean(ir, "fieldInternalization", "protection.ir.fieldInternalization", diagnostics);
            validateBoolean(ir, "methodInternalization", "protection.ir.methodInternalization", diagnostics);
            validateStringArray(
                    ir,
                    "publicMethodInternalizationAllowList",
                    "protection.ir.publicMethodInternalizationAllowList",
                    diagnostics);
            validateBoolean(ir, "methodTableHiding", "protection.ir.methodTableHiding", diagnostics);
            validateBoolean(ir, "blockNameObfuscation", "protection.ir.blockNameObfuscation", diagnostics);
        }
        JsonObject llvm = validateObject(protection, "llvm", LLVM_FIELDS, "protection.llvm", diagnostics);
        if (llvm != null) {
            validateBoolean(llvm, "nameObfuscation", "protection.llvm.nameObfuscation", diagnostics);
            validateBoolean(llvm, "opaquePredicates", "protection.llvm.opaquePredicates", diagnostics);
            validateBoolean(llvm, "blockLayoutPerturbation", "protection.llvm.blockLayoutPerturbation", diagnostics);
            validateBoolean(llvm, "indirectCalls", "protection.llvm.indirectCalls", diagnostics);
            validateBoolean(llvm, "globalLayout", "protection.llvm.globalLayout", diagnostics);
        }
        JsonObject binary = validateObject(
                protection,
                "binary",
                BINARY_FIELDS,
                "protection.binary",
                diagnostics);
        if (binary != null) {
            validateBoolean(binary, "enabled", "protection.binary.enabled", diagnostics);
            validateBoolean(
                    binary,
                    "hideInternalSymbols",
                    "protection.binary.hideInternalSymbols",
                    diagnostics);
            validateBoolean(binary, "strip", "protection.binary.strip", diagnostics);
            validateBoolean(binary, "removePdb", "protection.binary.removePdb", diagnostics);
            validateBoolean(binary, "symbolAudit", "protection.binary.symbolAudit", diagnostics);
            validateBoolean(
                    binary,
                    "retainUnwindInfo",
                    "protection.binary.retainUnwindInfo",
                    diagnostics);
        }
    }

    private void validateFields(JsonObject object, Set<String> expected, String path, List<Diagnostic> diagnostics) {
        validateFields(object, expected, Set.of(), path, diagnostics);
    }

    private void validateFields(
            JsonObject object,
            Set<String> expected,
            Set<String> optional,
            String path,
            List<Diagnostic> diagnostics) {
        for (String field : object.keySet()) {
            if (!expected.contains(field)) {
                diagnostics.add(Diagnostic.warning(
                        DiagnosticStage.CONFIG,
                        ConfigDiagnostics.UNKNOWN_FIELD,
                        "unknown config field ignored: " + path + "." + field));
            }
        }
        for (String field : expected) {
            if (!optional.contains(field) && !object.has(field)) {
                diagnostics.add(Diagnostic.error(
                        DiagnosticStage.CONFIG,
                        ConfigDiagnostics.MISSING_REQUIRED_FIELD,
                        "missing required config field: " + path + "." + field));
            }
        }
    }

    private JsonObject validateObject(
            JsonObject owner,
            String field,
            Set<String> expected,
            String path,
            List<Diagnostic> diagnostics) {
        if (!owner.has(field)) {
            return null;
        }
        JsonElement value = owner.get(field);
        if (!value.isJsonObject()) {
            diagnostics.add(Diagnostic.error(
                    DiagnosticStage.CONFIG,
                    ConfigDiagnostics.INVALID_FIELD_VALUE,
                    "config field must be an object: " + path));
            return null;
        }
        JsonObject object = value.getAsJsonObject();
        validateFields(object, expected, path, diagnostics);
        return object;
    }

    private JsonObject validateOptionalObject(
            JsonObject owner,
            String field,
            Set<String> expected,
            String path,
            List<Diagnostic> diagnostics) {
        if (!owner.has(field)) {
            return null;
        }
        return validateObject(owner, field, expected, path, diagnostics);
    }

    private JsonObject validateNullableObject(
            JsonObject owner,
            String field,
            Set<String> expected,
            String path,
            List<Diagnostic> diagnostics) {
        if (!owner.has(field) || owner.get(field) instanceof JsonNull) {
            return null;
        }
        return validateObject(owner, field, expected, path, diagnostics);
    }

    private void validateBoolean(JsonObject owner, String field, String path, List<Diagnostic> diagnostics) {
        if (!owner.has(field)) {
            return;
        }
        JsonElement value = owner.get(field);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            diagnostics.add(Diagnostic.error(
                    DiagnosticStage.CONFIG,
                    ConfigDiagnostics.INVALID_FIELD_VALUE,
                    "config field must be a boolean: " + path));
        }
    }

    private void validateStringArray(
            JsonObject owner,
            String field,
            String path,
            List<Diagnostic> diagnostics) {
        if (!owner.has(field)) {
            return;
        }
        JsonElement value = owner.get(field);
        if (!value.isJsonArray()) {
            diagnostics.add(Diagnostic.error(
                    DiagnosticStage.CONFIG,
                    ConfigDiagnostics.INVALID_FIELD_VALUE,
                    "config field must be an array: " + path));
            return;
        }
        int index = 0;
        for (JsonElement element : value.getAsJsonArray()) {
            if (!element.isJsonPrimitive()
                    || !element.getAsJsonPrimitive().isString()) {
                diagnostics.add(Diagnostic.error(
                        DiagnosticStage.CONFIG,
                        ConfigDiagnostics.INVALID_FIELD_VALUE,
                        "config field array entry must be a string: "
                                + path
                                + "["
                                + index
                                + "]"));
            }
            index++;
        }
    }

    private SignaturePolicy parseSignaturePolicy(JsonObject root, List<Diagnostic> diagnostics) {
        try {
            return SignaturePolicy.parse(root.get("signaturePolicy").getAsString());
        } catch (IllegalArgumentException exception) {
            diagnostics.add(Diagnostic.error(
                    DiagnosticStage.CONFIG,
                    ConfigDiagnostics.INVALID_FIELD_VALUE,
                    exception.getMessage()));
            return null;
        }
    }

    private <T extends Enum<T>> T parseEnum(
            String value,
            Class<T> enumType,
            String field,
            List<Diagnostic> diagnostics) {
        try {
            return Enum.valueOf(enumType, value);
        } catch (IllegalArgumentException exception) {
            diagnostics.add(Diagnostic.error(
                    DiagnosticStage.CONFIG,
                    ConfigDiagnostics.INVALID_FIELD_VALUE,
                    "unsupported " + field + ": " + value));
            return null;
        }
    }

    private List<Selector> parseSelectors(String field, JsonArray values, List<Diagnostic> diagnostics) {
        SelectorParser parser = new SelectorParser();
        ArrayList<Selector> selectors = new ArrayList<>();
        for (JsonElement value : values) {
            String raw = value.getAsString();
            try {
                selectors.add(parser.parse(raw));
            } catch (IllegalArgumentException exception) {
                diagnostics.add(Diagnostic.error(
                        DiagnosticStage.CONFIG,
                        ConfigDiagnostics.INVALID_SELECTOR,
                        field + " contains invalid selector " + raw + ": " + exception.getMessage()));
            }
        }
        return List.copyOf(selectors);
    }

    private List<Selector> parseExactMethodSelectors(
            String field,
            JsonArray values,
            List<Diagnostic> diagnostics) {
        SelectorParser parser = new SelectorParser();
        ArrayList<Selector> selectors = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (JsonElement value : values) {
            String raw = value.getAsString();
            try {
                Selector selector = parser.parse(raw);
                if (!selector.isMethodSelector()) {
                    throw new IllegalArgumentException(
                            "entry must identify one exact method, not a class");
                }
                if (selector.classPattern().contains("*")) {
                    throw new IllegalArgumentException(
                            "wildcards are not allowed in public method authorization");
                }
                if (!seen.add(selector.raw())) {
                    diagnostics.add(Diagnostic.error(
                            DiagnosticStage.CONFIG,
                            ConfigDiagnostics.INVALID_SELECTOR,
                            field + " contains duplicate selector " + raw));
                    continue;
                }
                selectors.add(selector);
            } catch (IllegalArgumentException exception) {
                diagnostics.add(Diagnostic.error(
                        DiagnosticStage.CONFIG,
                        ConfigDiagnostics.INVALID_SELECTOR,
                        field + " contains invalid selector " + raw + ": " + exception.getMessage()));
            }
        }
        return List.copyOf(selectors);
    }

    private TargetConfig parseTarget(JsonObject target, List<Diagnostic> diagnostics) {
        if (target == null) {
            return HostPlatform.detect()
                    .map(host -> TargetConfig.single(host.target()))
                    .orElseGet(() -> {
                        diagnostics.add(Diagnostic.error(
                                DiagnosticStage.CONFIG,
                                ConfigDiagnostics.HOST_TARGET_UNAVAILABLE,
                                "target is missing and the current host target could not be detected"));
                        return new TargetConfig(false, false, false, false, false, false);
                    });
        }
        return new TargetConfig(
                target.get("windowsX64").getAsBoolean(),
                target.get("windowsArm64").getAsBoolean(),
                target.get("linuxX64").getAsBoolean(),
                target.get("linuxArm64").getAsBoolean(),
                target.get("macosX64").getAsBoolean(),
                target.get("macosArm64").getAsBoolean());
    }

    private void validatePaths(Path jarFile, Path outputDirectory, List<Diagnostic> diagnostics) {
        String jar = jarFile.toString();
        if (jar.isBlank() || !jar.endsWith(".jar")) {
            diagnostics.add(Diagnostic.error(
                    DiagnosticStage.CONFIG,
                    ConfigDiagnostics.INVALID_PATH,
                    "jarFile must resolve to a .jar path: " + jar));
        }
        if (outputDirectory.toString().isBlank()) {
            diagnostics.add(Diagnostic.error(
                    DiagnosticStage.CONFIG,
                    ConfigDiagnostics.INVALID_PATH,
                    "outputDirectory must not be blank"));
        }
    }

    private SigningConfig parseSigning(Path baseDirectory, JsonElement signingElement, List<Diagnostic> diagnostics) {
        if (signingElement == null || signingElement instanceof JsonNull) {
            return null;
        }
        JsonObject signing = signingElement.getAsJsonObject();
        return new SigningConfig(
                resolve(baseDirectory, signing.get("keystorePath").getAsString()),
                signing.get("storePasswordEnv").getAsString(),
                signing.get("keyAlias").getAsString(),
                signing.get("keyPasswordEnv").getAsString(),
                nullableString(signing, "tsaUrl"));
    }

    private IntermediatesConfig parseIntermediates(JsonObject intermediates) {
        return new IntermediatesConfig(
                intermediates.get("enabled").getAsBoolean(),
                intermediates.get("includeDebugDumps").getAsBoolean(),
                intermediates.get("includePerClassIr").getAsBoolean(),
                intermediates.get("includePerClassLlvm").getAsBoolean(),
                intermediates.get("includePerClassC").getAsBoolean());
    }

    private ProtectionConfig parseProtection(
            JsonObject protection,
            String seed,
            ProtectionSeedMode seedMode,
            List<Selector> publicMethodInternalizationAllowList) {
        return new ProtectionConfig(
                protection.get("enabled").getAsBoolean(),
                seed,
                seedMode,
                parseIrProtection(
                        protection.getAsJsonObject("ir"),
                        publicMethodInternalizationAllowList),
                parseLlvmProtection(protection.getAsJsonObject("llvm")),
                parseBinaryProtection(protection.getAsJsonObject("binary")));
    }

    private IrProtectionConfig parseIrProtection(
            JsonObject ir,
            List<Selector> publicMethodInternalizationAllowList) {
        return new IrProtectionConfig(
                ir.get("enabled").getAsBoolean(),
                ir.get("controlFlowFlattening").getAsBoolean(),
                ir.get("fakeBranches").getAsBoolean(),
                ir.get("basicBlockSplitting").getAsBoolean(),
                ir.get("constantEncryption").getAsBoolean(),
                ir.get("stringEncryption").getAsBoolean(),
                ir.get("methodInlining").getAsBoolean(),
                ir.get("methodSplitting").getAsBoolean(),
                ir.get("callIndirection").getAsBoolean(),
                ir.get("fieldInternalization").getAsBoolean(),
                ir.get("methodInternalization").getAsBoolean(),
                publicMethodInternalizationAllowList,
                ir.get("methodTableHiding").getAsBoolean(),
                ir.get("blockNameObfuscation").getAsBoolean());
    }

    private LlvmProtectionConfig parseLlvmProtection(JsonObject llvm) {
        return new LlvmProtectionConfig(
                llvm.get("enabled").getAsBoolean(),
                llvm.get("nameObfuscation").getAsBoolean(),
                llvm.get("opaquePredicates").getAsBoolean(),
                llvm.get("blockLayoutPerturbation").getAsBoolean(),
                llvm.get("indirectCalls").getAsBoolean(),
                llvm.get("globalLayout").getAsBoolean());
    }

    private BinaryProtectionConfig parseBinaryProtection(JsonObject binary) {
        return new BinaryProtectionConfig(
                binary.get("enabled").getAsBoolean(),
                binary.get("hideInternalSymbols").getAsBoolean(),
                binary.get("strip").getAsBoolean(),
                binary.get("removePdb").getAsBoolean(),
                binary.get("symbolAudit").getAsBoolean(),
                binary.get("retainUnwindInfo").getAsBoolean());
    }

    private List<Path> classPath(Path baseDirectory, JsonArray entries) {
        ArrayList<Path> paths = new ArrayList<>();
        for (JsonElement entry : entries) {
            paths.add(resolve(baseDirectory, entry.getAsString()));
        }
        return List.copyOf(paths);
    }

    private Path nullablePath(Path baseDirectory, JsonObject owner, String field) {
        String value = nullableString(owner, field);
        return value == null ? null : resolve(baseDirectory, value);
    }

    private String nullableString(JsonObject owner, String field) {
        JsonElement element = owner.get(field);
        if (element == null || element instanceof JsonNull) {
            return null;
        }
        return element.getAsString();
    }

    private Path resolve(Path baseDirectory, String value) {
        Path path = Path.of(value);
        return path.isAbsolute() ? path.normalize() : baseDirectory.resolve(path).normalize();
    }

    private String randomSeed() {
        byte[] seed = new byte[32];
        PROTECTION_RANDOM.nextBytes(seed);
        return HexFormat.of().formatHex(seed);
    }

    private boolean hasErrors(List<Diagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity().wireName().equals("error"));
    }
}
