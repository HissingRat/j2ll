package xyz.melodysky.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import xyz.melodysky.analysis.hierarchy.AnalysisWorld;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.toolchain.HostPlatform;
import xyz.melodysky.toolchain.TargetTriple;

public final class ConfigLoader {
    private static final Set<String> TOP_LEVEL_FIELDS = Set.of(
            "schemaVersion",
            "jarFile",
            "classPath",
            "javaHome",
            "runtimeImage",
            "worldModel",
            "javaSupportTier",
            "fallbackMode",
            "outputDirectory",
            "whiteList",
            "blackList",
            "target",
            "libraryName",
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
            "methodTableHiding");
    private static final Set<String> LLVM_FIELDS = Set.of(
            "enabled",
            "nameObfuscation",
            "opaquePredicates",
            "blockLayoutPerturbation",
            "indirectCalls",
            "globalLayout",
            "visibilityHardening");
    private static final Set<String> BINARY_FIELDS = Set.of(
            "enabled", "hideInternalSymbols", "strip", "removePdb", "symbolAudit");
    private static final Set<String> PASS_FIELDS = Set.of("enabled");
    private static final Set<String> VISIBILITY_HARDENING_FIELDS = Set.of("enabled");

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

        FallbackMode fallbackMode = parseFallbackMode(root, diagnostics);
        AnalysisWorld worldModel = parseEnum(
                root.get("worldModel").getAsString(),
                AnalysisWorld.class,
                "worldModel",
                diagnostics);
        JavaSupportTier javaSupportTier = parseEnum(
                root.get("javaSupportTier").getAsString(),
                JavaSupportTier.class,
                "javaSupportTier",
                diagnostics);
        SignaturePolicy signaturePolicy = parseSignaturePolicy(root, diagnostics);
        List<Selector> whiteList = parseSelectors("whiteList", root.getAsJsonArray("whiteList"), diagnostics);
        List<Selector> blackList = parseSelectors("blackList", root.getAsJsonArray("blackList"), diagnostics);
        TargetConfig target = parseTarget(root.getAsJsonObject("target"), diagnostics);
        List<TargetTriple> targets = target.enabledTargets();
        if (targets.isEmpty()) {
            diagnostics.add(Diagnostic.error(
                    DiagnosticStage.CONFIG,
                    ConfigDiagnostics.NO_TARGET_SELECTED,
                    "at least one target must be selected"));
        }

        String embeddedLibraryDirectory = root.get("embeddedLibraryDirectory").getAsString();
        if (embeddedLibraryDirectory.isBlank() || embeddedLibraryDirectory.startsWith("/")) {
            diagnostics.add(Diagnostic.error(
                    DiagnosticStage.CONFIG,
                    ConfigDiagnostics.INVALID_FIELD_VALUE,
                    "embeddedLibraryDirectory must be a non-empty relative JAR path"));
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
        String seed = nullableString(root.getAsJsonObject("protection"), "seed");
        if (seed == null) {
            seed = deriveSeed(jarFile, outputDirectory, whiteList, blackList);
        }

        ResolvedConfig config = new ResolvedConfig(
                schemaVersion,
                jarFile,
                classPath,
                nullablePath(base, root, "javaHome"),
                nullablePath(base, root, "runtimeImage"),
                worldModel,
                javaSupportTier,
                fallbackMode,
                outputDirectory,
                whiteList,
                blackList,
                target,
                targets,
                nullableString(root, "libraryName"),
                embeddedLibraryDirectory,
                signaturePolicy,
                signing,
                parseIntermediates(root.getAsJsonObject("intermediates")),
                parseProtection(root.getAsJsonObject("protection"), seed));
        return new ConfigLoadResult(Optional.of(config), diagnostics);
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
            validatePass(ir, "controlFlowFlattening", "protection.ir.controlFlowFlattening", diagnostics);
            validatePass(ir, "fakeBranches", "protection.ir.fakeBranches", diagnostics);
            validatePass(ir, "basicBlockSplitting", "protection.ir.basicBlockSplitting", diagnostics);
            validatePass(ir, "constantEncryption", "protection.ir.constantEncryption", diagnostics);
            validatePass(ir, "stringEncryption", "protection.ir.stringEncryption", diagnostics);
            validatePass(ir, "methodInlining", "protection.ir.methodInlining", diagnostics);
            validatePass(ir, "methodSplitting", "protection.ir.methodSplitting", diagnostics);
            validatePass(ir, "callIndirection", "protection.ir.callIndirection", diagnostics);
            validatePass(ir, "methodTableHiding", "protection.ir.methodTableHiding", diagnostics);
        }
        JsonObject llvm = validateObject(protection, "llvm", LLVM_FIELDS, "protection.llvm", diagnostics);
        if (llvm != null) {
            validatePass(llvm, "nameObfuscation", "protection.llvm.nameObfuscation", diagnostics);
            validatePass(llvm, "opaquePredicates", "protection.llvm.opaquePredicates", diagnostics);
            validatePass(llvm, "blockLayoutPerturbation", "protection.llvm.blockLayoutPerturbation", diagnostics);
            validatePass(llvm, "indirectCalls", "protection.llvm.indirectCalls", diagnostics);
            validatePass(llvm, "globalLayout", "protection.llvm.globalLayout", diagnostics);
            validateObject(
                    llvm,
                    "visibilityHardening",
                    VISIBILITY_HARDENING_FIELDS,
                    "protection.llvm.visibilityHardening",
                    diagnostics);
        }
        validateObject(protection, "binary", BINARY_FIELDS, "protection.binary", diagnostics);
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

    private void validatePass(JsonObject owner, String field, String path, List<Diagnostic> diagnostics) {
        validateObject(owner, field, PASS_FIELDS, path, diagnostics);
    }

    private FallbackMode parseFallbackMode(JsonObject root, List<Diagnostic> diagnostics) {
        try {
            return FallbackMode.parse(root.get("fallbackMode").getAsString());
        } catch (IllegalArgumentException exception) {
            diagnostics.add(Diagnostic.error(
                    DiagnosticStage.CONFIG,
                    ConfigDiagnostics.UNSUPPORTED_FALLBACK_MODE,
                    exception.getMessage()));
            return null;
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

    private ProtectionConfig parseProtection(JsonObject protection, String seed) {
        return new ProtectionConfig(
                protection.get("enabled").getAsBoolean(),
                seed,
                parseIrProtection(protection.getAsJsonObject("ir")),
                parseLlvmProtection(protection.getAsJsonObject("llvm")),
                parseBinaryProtection(protection.getAsJsonObject("binary")));
    }

    private IrProtectionConfig parseIrProtection(JsonObject ir) {
        return new IrProtectionConfig(
                ir.get("enabled").getAsBoolean(),
                parsePass(ir.getAsJsonObject("controlFlowFlattening")),
                parsePass(ir.getAsJsonObject("fakeBranches")),
                parsePass(ir.getAsJsonObject("basicBlockSplitting")),
                parsePass(ir.getAsJsonObject("constantEncryption")),
                parsePass(ir.getAsJsonObject("stringEncryption")),
                parsePass(ir.getAsJsonObject("methodInlining")),
                parsePass(ir.getAsJsonObject("methodSplitting")),
                parsePass(ir.getAsJsonObject("callIndirection")),
                parsePass(ir.getAsJsonObject("methodTableHiding")));
    }

    private LlvmProtectionConfig parseLlvmProtection(JsonObject llvm) {
        return new LlvmProtectionConfig(
                llvm.get("enabled").getAsBoolean(),
                parsePass(llvm.getAsJsonObject("nameObfuscation")),
                parsePass(llvm.getAsJsonObject("opaquePredicates")),
                parsePass(llvm.getAsJsonObject("blockLayoutPerturbation")),
                parsePass(llvm.getAsJsonObject("indirectCalls")),
                parsePass(llvm.getAsJsonObject("globalLayout")),
                new VisibilityHardeningConfig(llvm.getAsJsonObject("visibilityHardening")
                        .get("enabled")
                        .getAsBoolean()));
    }

    private BinaryProtectionConfig parseBinaryProtection(JsonObject binary) {
        return new BinaryProtectionConfig(
                binary.get("enabled").getAsBoolean(),
                binary.get("hideInternalSymbols").getAsBoolean(),
                binary.get("strip").getAsBoolean(),
                binary.get("removePdb").getAsBoolean(),
                binary.get("symbolAudit").getAsBoolean());
    }

    private PassConfig parsePass(JsonObject pass) {
        return new PassConfig(pass.get("enabled").getAsBoolean());
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

    private String deriveSeed(Path jarFile, Path outputDirectory, List<Selector> whiteList, List<Selector> blackList) {
        StringBuilder material = new StringBuilder()
                .append(jarFile)
                .append('\n')
                .append(outputDirectory);
        for (Selector selector : whiteList) {
            material.append("\nw:").append(selector.raw());
        }
        for (Selector selector : blackList) {
            material.append("\nb:").append(selector.raw());
        }
        return sha256(material.toString()).substring(0, 16);
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private boolean hasErrors(List<Diagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity().wireName().equals("error"));
    }
}
