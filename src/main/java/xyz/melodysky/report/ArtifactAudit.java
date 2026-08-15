package xyz.melodysky.report;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.Manifest;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.objectweb.asm.Opcodes;
import xyz.melodysky.analysis.field.NativeFieldInternalizationPlan;
import xyz.melodysky.analysis.method.NativeMethodInternalizationPlan;
import xyz.melodysky.analysis.method.NativeOnlyMethodCoalescingPlan;
import xyz.melodysky.packaging.FinalJarMetadataPolicy;
import xyz.melodysky.packaging.InternalizedFieldArtifactVerifier;
import xyz.melodysky.packaging.InternalizedMethodArtifactVerifier;
import xyz.melodysky.toolchain.ClassArtifactPath;
import xyz.melodysky.toolchain.NativeImplementationPlan;
import xyz.melodysky.toolchain.NativeLlvmCompilation;
import xyz.melodysky.toolchain.NativeOnlyMethodCoalescingEmissionVerifier;
import xyz.melodysky.toolchain.NativeSourceName;

public class ArtifactAudit {
    public ArtifactAuditResult audit(
            Path workspaceRoot,
            Path outputJar,
            String embeddedLibraryDirectory,
            List<EmbeddedLibraryReport> embeddedLibraries,
            List<String> exportedSymbols,
            List<SensitivePlaintextFact> sensitivePlaintextFacts) throws IOException {
        ArrayList<ArtifactAuditCheck> checks = new ArrayList<>();
        List<SensitivePlaintextFact> checkedSensitiveFacts = sensitivePlaintextFacts.stream()
                .filter(fact -> fact.gateMode().equals("blocking"))
                .sorted(factComparator())
                .toList();
        List<SensitivePlaintextFact> observedOnlySensitiveFacts = sensitivePlaintextFacts.stream()
                .filter(fact -> fact.gateMode().equals("observedOnly"))
                .sorted(factComparator())
                .toList();
        List<SensitivePlaintextFact> skippedSensitiveFacts = sensitivePlaintextFacts.stream()
                .filter(fact -> !fact.gateMode().equals("blocking") && !fact.gateMode().equals("observedOnly"))
                .map(fact -> fact.withAuditClassification(
                        fact.pathKind(),
                        fact.gateMode(),
                        "SENSITIVE_FACT_GATE_MODE_UNSUPPORTED",
                        "unsupportedGateMode"))
                .sorted(factComparator())
                .toList();
        if (!Files.isRegularFile(outputJar)) {
            checks.add(ArtifactAuditCheck.failed(
                    "outputJar.exists",
                    "FINAL_ARTIFACT_MISSING",
                    "output JAR is not present for artifact audit: " + outputJar));
            return result(checks, checkedSensitiveFacts, observedOnlySensitiveFacts, skippedSensitiveFacts);
        }

        try (JarFile jar = new JarFile(outputJar.toFile())) {
            Set<String> allEntries = new HashSet<>();
            jar.stream().forEach(entry -> allEntries.add(entry.getName()));
            Set<String> entries = new HashSet<>();
            jar.stream().filter(entry -> !entry.isDirectory()).forEach(entry -> entries.add(entry.getName()));
            checkAuditSurfaces(checks, workspaceRoot, entries, embeddedLibraries, exportedSymbols);
            checkNoEmbeddedBytecodeEntries(checks, entries);
            checkNoLegacyEntries(checks, entries);
            checkNoPdbEntries(checks, entries);
            checkNativeResourcePaths(checks, entries, embeddedLibraryDirectory, embeddedLibraries);
            checkRuntimeLoader(
                    checks,
                    jar,
                    entries,
                    embeddedLibraryDirectory,
                    !embeddedLibraries.isEmpty());
            checkEmbeddedLibraryHashes(checks, jar, embeddedLibraries);
            checkFinalJarMetadataPolicy(checks, workspaceRoot, jar, allEntries, embeddedLibraries);
            checkPlaintextsInJar(workspaceRoot, jar, checks, checkedSensitiveFacts);
        }
        checkNoLegacyFallbackWorkspaceSurfaces(checks, workspaceRoot);
        checkExportedSymbols(checks, exportedSymbols, !embeddedLibraries.isEmpty());
        checkNoWorkspacePdb(checks, workspaceRoot, embeddedLibraries);
        checkPlaintexts(workspaceRoot, checks, checkedSensitiveFacts);
        checkLoweringReportHelperPrivacy(checks, workspaceRoot);
        checks.add(ArtifactAuditCheck.passed(
                "plaintext.observedOnlyFacts",
                observedOnlySensitiveFacts.isEmpty() ? "NO_OBSERVED_ONLY_SENSITIVE_FACTS" : "OBSERVED_ONLY_SENSITIVE_FACTS_REPORTED",
                "observed-only sensitive facts: " + observedOnlySensitiveFacts.size()));
        checks.add(ArtifactAuditCheck.passed(
                "plaintext.skippedFacts",
                skippedSensitiveFacts.isEmpty() ? "NO_SKIPPED_SENSITIVE_FACTS" : "SKIPPED_SENSITIVE_FACTS_REPORTED",
                "skipped sensitive facts: " + skippedSensitiveFacts.size()));
        return result(checks, checkedSensitiveFacts, observedOnlySensitiveFacts, skippedSensitiveFacts);
    }

    public ArtifactAuditResult audit(
            Path workspaceRoot,
            Path outputJar,
            String embeddedLibraryDirectory,
            List<EmbeddedLibraryReport> embeddedLibraries,
            List<String> exportedSymbols,
            List<SensitivePlaintextFact> sensitivePlaintextFacts,
            NativeFieldInternalizationPlan fieldInternalizationPlan) throws IOException {
        ArtifactAuditResult base = audit(
                workspaceRoot,
                outputJar,
                embeddedLibraryDirectory,
                embeddedLibraries,
                exportedSymbols,
                sensitivePlaintextFacts);
        if (!base.passed()) {
            return base;
        }
        List<String> residuals;
        try (JarFile jar = new JarFile(outputJar.toFile())) {
            residuals = new InternalizedFieldArtifactVerifier()
                    .residuals(jar, fieldInternalizationPlan);
        }
        ArtifactAuditCheck fieldCheck = residuals.isEmpty()
                ? ArtifactAuditCheck.passed(
                        "jar.internalizedFieldResiduals",
                        fieldInternalizationPlan.approvedFieldIds().isEmpty()
                                ? "NO_INTERNALIZED_FIELDS"
                                : "INTERNALIZED_FIELDS_REMOVED",
                        fieldInternalizationPlan.approvedFieldIds().isEmpty()
                                ? "no native-internalized fields were planned"
                                : "approved field declarations and references are absent from the final JAR")
                : ArtifactAuditCheck.failed(
                        "jar.internalizedFieldResiduals",
                        "INTERNALIZED_FIELD_RESIDUAL",
                        "residual approved field surfaces: " + residuals);
        ArrayList<ArtifactAuditCheck> checks = new ArrayList<>(base.checks());
        checks.add(fieldCheck);
        return new ArtifactAuditResult(
                residuals.isEmpty(),
                checks,
                base.checkedSensitiveFacts(),
                base.observedOnlySensitiveFacts(),
                base.skippedSensitiveFacts());
    }

    public ArtifactAuditResult audit(
            Path workspaceRoot,
            Path outputJar,
            String embeddedLibraryDirectory,
            List<EmbeddedLibraryReport> embeddedLibraries,
            List<String> exportedSymbols,
            List<SensitivePlaintextFact> sensitivePlaintextFacts,
            NativeFieldInternalizationPlan fieldInternalizationPlan,
            NativeMethodInternalizationPlan methodInternalizationPlan)
            throws IOException {
        ArtifactAuditResult fieldResult = audit(
                workspaceRoot,
                outputJar,
                embeddedLibraryDirectory,
                embeddedLibraries,
                exportedSymbols,
                sensitivePlaintextFacts,
                fieldInternalizationPlan);
        if (!fieldResult.passed()) {
            return fieldResult;
        }
        List<String> residuals;
        try (JarFile jar = new JarFile(outputJar.toFile())) {
            residuals = new InternalizedMethodArtifactVerifier()
                    .residuals(jar, methodInternalizationPlan);
        }
        boolean noInternalizedMethods =
                methodInternalizationPlan.internalizedMethodKeys().isEmpty();
        ArtifactAuditCheck methodCheck = residuals.isEmpty()
                ? ArtifactAuditCheck.passed(
                        "jar.internalizedMethodResiduals",
                        noInternalizedMethods
                                ? "NO_INTERNALIZED_METHODS"
                                : "INTERNALIZED_METHODS_REMOVED",
                        noInternalizedMethods
                                ? "no native-internalized methods were planned"
                                : "approved method declarations and references are absent from the final JAR")
                : ArtifactAuditCheck.failed(
                        "jar.internalizedMethodResiduals",
                        "INTERNALIZED_METHOD_RESIDUAL",
                        "residual approved method surfaces: " + residuals);
        ArrayList<ArtifactAuditCheck> checks =
                new ArrayList<>(fieldResult.checks());
        checks.add(methodCheck);
        return new ArtifactAuditResult(
                residuals.isEmpty(),
                checks,
                fieldResult.checkedSensitiveFacts(),
                fieldResult.observedOnlySensitiveFacts(),
                fieldResult.skippedSensitiveFacts());
    }

    public ArtifactAuditResult audit(
            Path workspaceRoot,
            Path outputJar,
            String embeddedLibraryDirectory,
            List<EmbeddedLibraryReport> embeddedLibraries,
            List<String> exportedSymbols,
            List<SensitivePlaintextFact> sensitivePlaintextFacts,
            NativeFieldInternalizationPlan fieldInternalizationPlan,
            NativeMethodInternalizationPlan methodInternalizationPlan,
            NativeOnlyMethodCoalescingPlan methodCoalescingPlan,
            NativeImplementationPlan implementationPlan,
            NativeLlvmCompilation llvmCompilation)
            throws IOException {
        ArtifactAuditResult methodResult = audit(
                workspaceRoot,
                outputJar,
                embeddedLibraryDirectory,
                embeddedLibraries,
                exportedSymbols,
                sensitivePlaintextFacts,
                fieldInternalizationPlan,
                methodInternalizationPlan);
        if (!methodResult.passed()) {
            return methodResult;
        }
        List<String> residuals =
                new NativeOnlyMethodCoalescingEmissionVerifier()
                        .workspaceResiduals(
                                workspaceRoot,
                                methodCoalescingPlan,
                                implementationPlan,
                                llvmCompilation);
        boolean noCoalescedMethods =
                methodCoalescingPlan.coalescedCount() == 0;
        ArtifactAuditCheck coalescingCheck = residuals.isEmpty()
                ? ArtifactAuditCheck.passed(
                        "native.coalescedMethodStandaloneBodies",
                        noCoalescedMethods
                                ? "NO_COALESCED_NATIVE_METHODS"
                                : "COALESCED_NATIVE_STANDALONE_BODIES_ABSENT",
                        noCoalescedMethods
                                ? "no native-only methods were physically coalesced"
                                : "coalesced methods have no LLVM function, declaration, call reference, or generated-C wrapper")
                : ArtifactAuditCheck.failed(
                        "native.coalescedMethodStandaloneBodies",
                        "COALESCED_NATIVE_STANDALONE_BODY_RESIDUAL",
                        "coalesced native method residuals: " + residuals);
        ArrayList<ArtifactAuditCheck> checks =
                new ArrayList<>(methodResult.checks());
        checks.add(coalescingCheck);
        return new ArtifactAuditResult(
                residuals.isEmpty(),
                checks,
                methodResult.checkedSensitiveFacts(),
                methodResult.observedOnlySensitiveFacts(),
                methodResult.skippedSensitiveFacts());
    }

    public ArtifactAuditResult skipped(String reasonCode, String message) {
        return new ArtifactAuditResult(false, List.of(ArtifactAuditCheck.failed(
                "artifactAudit.skipped",
                reasonCode,
                message)));
    }

    private void checkNoEmbeddedBytecodeEntries(List<ArtifactAuditCheck> checks, Set<String> entries) {
        List<String> forbidden = entries.stream()
                .filter(this::isEmbeddedBytecodeEntry)
                .sorted()
                .toList();
        checks.add(forbidden.isEmpty()
                ? ArtifactAuditCheck.passed(
                        "jar.noEmbeddedBytecodeCopies",
                        "NO_EMBEDDED_METHOD_BYTECODE",
                        "output JAR has no generated fallback class or embedded-bytecode manifest entries")
                : ArtifactAuditCheck.failed(
                        "jar.noEmbeddedBytecodeCopies",
                        "EMBEDDED_METHOD_BYTECODE_ENTRY",
                        "forbidden embedded-bytecode entries: " + forbidden));
    }

    private boolean isEmbeddedBytecodeEntry(String entry) {
        String lower = entry.toLowerCase(java.util.Locale.ROOT);
        return (lower.startsWith("j2ll/generated/fallback/") && lower.endsWith(".class"))
                || (lower.endsWith(".class")
                        && lower.substring(lower.lastIndexOf('/') + 1).startsWith("j2llfallback$"))
                || lower.contains("/fallback-blobs/")
                || lower.endsWith("/fallback-blob-manifest.json")
                || lower.endsWith("/fallback_blob_manifest.json");
    }

    private void checkNoLegacyEntries(List<ArtifactAuditCheck> checks, Set<String> entries) {
        List<String> legacy = entries.stream()
                .filter(entry -> entry.startsWith("obfuscator/") || entry.contains("/obfuscator/bench/"))
                .sorted()
                .toList();
        checks.add(legacy.isEmpty()
                ? ArtifactAuditCheck.passed("jar.noLegacyEntries", "NO_LEGACY_OUTPUT_PATHS", "no legacy output paths in JAR")
                : ArtifactAuditCheck.failed("jar.noLegacyEntries", "LEGACY_OUTPUT_PATH_FOUND", "legacy entries: " + legacy));
    }

    private void checkNoPdbEntries(List<ArtifactAuditCheck> checks, Set<String> entries) {
        List<String> pdb = entries.stream()
                .filter(entry -> entry.toLowerCase(java.util.Locale.ROOT).endsWith(".pdb"))
                .sorted()
                .toList();
        checks.add(pdb.isEmpty()
                ? ArtifactAuditCheck.passed("jar.noPdb", "WINDOWS_PDB_EXCLUDED", "output JAR has no PDB entries")
                : ArtifactAuditCheck.failed("jar.noPdb", "WINDOWS_PDB_PACKAGED", "PDB entries: " + pdb));
    }

    private void checkNativeResourcePaths(
            List<ArtifactAuditCheck> checks,
            Set<String> entries,
            String embeddedLibraryDirectory,
            List<EmbeddedLibraryReport> embeddedLibraries) {
        String prefix = embeddedLibraryDirectory.endsWith("/") ? embeddedLibraryDirectory : embeddedLibraryDirectory + "/";
        List<String> wrong = embeddedLibraries.stream()
                .map(EmbeddedLibraryReport::jarPath)
                .filter(path -> !path.startsWith(prefix))
                .sorted()
                .toList();
        List<String> missing = embeddedLibraries.stream()
                .map(EmbeddedLibraryReport::jarPath)
                .filter(path -> !entries.contains(path))
                .sorted()
                .toList();
        Set<String> expected = embeddedLibraries.stream()
                .map(EmbeddedLibraryReport::jarPath)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<String> unexpected = entries.stream()
                .filter(path -> path.startsWith(prefix))
                .filter(this::isNativeLibraryPath)
                .filter(path -> !expected.contains(path))
                .sorted()
                .toList();
        checks.add(wrong.isEmpty() && missing.isEmpty() && unexpected.isEmpty()
                ? ArtifactAuditCheck.passed(
                        "jar.nativeResourcePaths",
                        "NATIVE_RESOURCES_UNDER_CONFIGURED_DIRECTORY",
                        "native resources are under " + prefix)
                : ArtifactAuditCheck.failed(
                        "jar.nativeResourcePaths",
                        "NATIVE_RESOURCE_PATH_INVALID",
                        "wrong directory: " + wrong + ", missing: " + missing
                                + ", unexpected: " + unexpected));
    }

    private boolean isNativeLibraryPath(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".dll") || lower.endsWith(".so") || lower.endsWith(".dylib");
    }

    private void checkRuntimeLoader(
            List<ArtifactAuditCheck> checks,
            JarFile jar,
            Set<String> entries,
            String embeddedLibraryDirectory,
            boolean required) throws IOException {
        String directory = embeddedLibraryDirectory.endsWith("/")
                ? embeddedLibraryDirectory.substring(0, embeddedLibraryDirectory.length() - 1)
                : embeddedLibraryDirectory;
        String internalName = directory + "/Loader";
        String entryName = internalName + ".class";
        List<String> forbiddenSupportEntries = List.of(
                "xyz/melodysky/runtime/fallback/J2llFallbackSupport.class",
                "xyz/melodysky/runtime/loader/J2llNativeLoaderSupport.class");
        List<String> presentForbiddenSupport = forbiddenSupportEntries.stream()
                .filter(entries::contains)
                .toList();
        List<String> oldGeneratedLoaders = entries.stream()
                .filter(entry -> entry.startsWith("j2ll/generated/"))
                .filter(entry -> entry.endsWith("/NativeLoader.class"))
                .sorted()
                .toList();
        checks.add(presentForbiddenSupport.isEmpty() && oldGeneratedLoaders.isEmpty()
                ? ArtifactAuditCheck.passed(
                        "jar.noLegacyRuntimeSupportClasses",
                        "NO_LEGACY_RUNTIME_SUPPORT_CLASSES",
                        "output JAR has no split or old generated runtime loader classes")
                : ArtifactAuditCheck.failed(
                        "jar.noLegacyRuntimeSupportClasses",
                        "LEGACY_RUNTIME_SUPPORT_CLASS_FOUND",
                        "legacy runtime support entries: " + presentForbiddenSupport
                                + ", old generated loaders: " + oldGeneratedLoaders));

        if (!required) {
            checks.add(ArtifactAuditCheck.passed(
                    "jar.runtimeLoader",
                    "RUNTIME_LOADER_NOT_REQUIRED",
                    "no native library was embedded, so Loader.class is not required"));
            return;
        }
        long entryCount = jar.stream()
                .filter(entry -> !entry.isDirectory())
                .filter(entry -> entry.getName().equals(entryName))
                .count();
        if (entryCount != 1) {
            checks.add(ArtifactAuditCheck.failed(
                    "jar.runtimeLoader",
                    "RUNTIME_LOADER_ENTRY_INVALID",
                    "expected exactly one " + entryName + " but found " + entryCount));
            return;
        }
        try (InputStream input = jar.getInputStream(jar.getJarEntry(entryName))) {
            RuntimeLoaderBytecodeAudit.Result loaderAudit =
                    new RuntimeLoaderBytecodeAudit().inspect(
                            input.readAllBytes());
            boolean identityMatches =
                    loaderAudit.internalName().equals(internalName);
            boolean versionMatches =
                    loaderAudit.majorVersion() == Opcodes.V17;
            checks.add(identityMatches
                            && versionMatches
                            && loaderAudit.forbiddenSurfaces().isEmpty()
                    ? ArtifactAuditCheck.passed(
                            "jar.runtimeLoader",
                            "RUNTIME_LOADER_PRESENT",
                            "single minimal Java 17 runtime Loader is present at " + entryName)
                    : ArtifactAuditCheck.failed(
                            "jar.runtimeLoader",
                            "RUNTIME_LOADER_CLASS_INVALID",
                            "Loader identity/version mismatch or forbidden bytecode surface: internalName="
                                    + loaderAudit.internalName()
                                    + ", major="
                                    + loaderAudit.majorVersion()
                                    + ", forbiddenSurfaces="
                                    + loaderAudit.forbiddenSurfaces()));
        }
    }

    private void checkNoWorkspacePdb(
            List<ArtifactAuditCheck> checks,
            Path workspaceRoot,
            List<EmbeddedLibraryReport> embeddedLibraries) throws IOException {
        ArrayList<String> pdb = new ArrayList<>();
        Path nativeDirectory = workspaceRoot.resolve("native");
        if (!embeddedLibraries.isEmpty() && Files.isDirectory(nativeDirectory)) {
            try (Stream<Path> paths = Files.list(nativeDirectory)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString()
                                .toLowerCase(java.util.Locale.ROOT)
                                .endsWith(".pdb"))
                        .map(path -> displayPath(workspaceRoot, path))
                        .forEach(pdb::add);
            }
        }
        pdb.sort(String::compareTo);
        checks.add(pdb.isEmpty()
                ? ArtifactAuditCheck.passed(
                        "workspace.noPdb",
                        "WINDOWS_PDB_WORKSPACE_EXCLUDED",
                        "flat native output directory has no PDB files")
                : ArtifactAuditCheck.failed(
                        "workspace.noPdb",
                        "WINDOWS_PDB_WORKSPACE_FOUND",
                        "PDB files in flat native output directory: " + pdb));
    }

    private void checkEmbeddedLibraryHashes(
            List<ArtifactAuditCheck> checks,
            JarFile jar,
            List<EmbeddedLibraryReport> embeddedLibraries) throws IOException {
        ArrayList<String> mismatches = new ArrayList<>();
        for (EmbeddedLibraryReport library : embeddedLibraries) {
            var entry = jar.getJarEntry(library.jarPath());
            if (entry == null) {
                mismatches.add(library.jarPath() + ":missing");
                continue;
            }
            try (InputStream input = jar.getInputStream(entry)) {
                String actual = sha256(input.readAllBytes());
                if (!actual.equals(library.sha256())) {
                    mismatches.add(library.jarPath() + ":sha256");
                }
            }
        }
        checks.add(mismatches.isEmpty()
                ? ArtifactAuditCheck.passed(
                        "jar.nativeSha256",
                        "NATIVE_LIBRARY_SHA256_MATCH",
                        "embedded native library hashes match packaging report")
                : ArtifactAuditCheck.failed(
                        "jar.nativeSha256",
                        "NATIVE_LIBRARY_SHA256_MISMATCH",
                        "native library hash mismatches: " + mismatches));
    }

    private void checkFinalJarMetadataPolicy(
            List<ArtifactAuditCheck> checks,
            Path workspaceRoot,
            JarFile jar,
            Set<String> entries,
            List<EmbeddedLibraryReport> embeddedLibraries) throws IOException {
        List<String> forbidden = entries.stream()
                .filter(FinalJarMetadataPolicy::isPrivateJ2llEntry)
                .sorted()
                .toList();
        List<String> manifestReferences = privateManifestReferences(jar);
        checks.add(forbidden.isEmpty() && manifestReferences.isEmpty()
                ? ArtifactAuditCheck.passed(
                        "metadata.privateJ2llEntries",
                        "PRIVATE_J2LL_METADATA_ABSENT",
                        "final JAR contains no META-INF/j2ll private metadata")
                : ArtifactAuditCheck.failed(
                        "metadata.privateJ2llEntries",
                        "PRIVATE_J2LL_METADATA_PRESENT",
                        "forbidden final-JAR private metadata entries: " + forbidden
                                + "; manifest references: " + manifestReferences));
        java.util.Map<String, String> embeddedLibraryEvidence = embeddedLibraries.stream()
                .collect(java.util.stream.Collectors.toMap(
                        EmbeddedLibraryReport::jarPath,
                        EmbeddedLibraryReport::sha256,
                        (left, right) -> left,
                        java.util.TreeMap::new));
        checkNativeLibrariesMatchTargetArtifacts(checks, workspaceRoot, embeddedLibraryEvidence);
    }

    private List<String> privateManifestReferences(JarFile jar) throws IOException {
        ArrayList<String> references = new ArrayList<>();
        for (var entry : jar.stream()
                .filter(candidate -> candidate.getName().equalsIgnoreCase("META-INF/MANIFEST.MF"))
                .toList()) {
            try (InputStream input = jar.getInputStream(entry)) {
                Manifest manifest = new Manifest(input);
                FinalJarMetadataPolicy.privateManifestSections(manifest).stream()
                        .map(section -> entry.getName() + ":" + section)
                        .forEach(references::add);
            }
        }
        references.sort(String::compareTo);
        return List.copyOf(references);
    }

    private void checkNativeLibrariesMatchTargetArtifacts(
            List<ArtifactAuditCheck> checks,
            Path workspaceRoot,
            java.util.Map<String, String> embeddedLibraryEvidence) throws IOException {
        Path packagingReport = workspaceRoot.resolve("reports/packaging-report.json");
        if (!Files.isRegularFile(packagingReport)) {
            checks.add(ArtifactAuditCheck.skipped(
                    "metadata.nativeLibrariesTargetArtifacts",
                    "surfaceNotGenerated",
                    "packaging report was not available for native library metadata consistency"));
            return;
        }
        JsonObject root = JsonParser.parseString(Files.readString(packagingReport)).getAsJsonObject();
        JsonObject zig = root.has("zigToolchain") && root.get("zigToolchain").isJsonObject()
                ? root.getAsJsonObject("zigToolchain")
                : new JsonObject();
        if (!zig.has("targetArtifacts") || !zig.get("targetArtifacts").isJsonArray()) {
            checks.add(ArtifactAuditCheck.failed(
                    "metadata.nativeLibrariesTargetArtifacts",
                    "METADATA_CONSISTENCY_FAILED",
                    "packaging report has no zigToolchain.targetArtifacts array"));
            return;
        }
        java.util.Map<String, String> targetArtifacts = new java.util.TreeMap<>();
        ArrayList<String> failedRequiredWithMetadata = new ArrayList<>();
        ArrayList<String> invalidLibraryNames = new ArrayList<>();
        java.util.TreeSet<String> builtLibraryNames = new java.util.TreeSet<>();
        for (com.google.gson.JsonElement element : zig.getAsJsonArray("targetArtifacts")) {
            JsonObject target = element.getAsJsonObject();
            boolean built = "built".equals(textOrEmpty(target, "status"))
                    && hasText(target, "actualJarPath")
                    && hasText(target, "actualSha256");
            if (built) {
                targetArtifacts.put(target.get("actualJarPath").getAsString(), target.get("actualSha256").getAsString());
                String libraryName = textOrEmpty(target, "libraryName");
                if (!libraryName.matches("[0-9a-f]{16}")) {
                    invalidLibraryNames.add(textOrEmpty(target, "target") + ":" + libraryName);
                } else {
                    builtLibraryNames.add(libraryName);
                }
            }
            boolean failedRequired = target.has("required")
                    && target.get("required").getAsBoolean()
                    && target.has("buildable")
                    && !target.get("buildable").getAsBoolean();
            if (failedRequired && hasText(target, "actualJarPath")) {
                failedRequiredWithMetadata.add(textOrEmpty(target, "target"));
            }
        }
        boolean libraryNamesValid = invalidLibraryNames.isEmpty()
                && (targetArtifacts.isEmpty() || builtLibraryNames.size() == 1);
        checks.add(libraryNamesValid
                ? ArtifactAuditCheck.passed(
                        "metadata.nativeLibraryName",
                        "NATIVE_LIBRARY_NAME_HASH_ONLY",
                        targetArtifacts.isEmpty()
                                ? "no built native target required a logical library name"
                                : "all built targets use one pure 16-hex logical library name")
                : ArtifactAuditCheck.failed(
                        "metadata.nativeLibraryName",
                        "NATIVE_LIBRARY_NAME_NOT_HASH_ONLY",
                        "invalid or inconsistent logical native library names: "
                                + invalidLibraryNames + "; valid names: " + builtLibraryNames));
        boolean matches = embeddedLibraryEvidence.equals(targetArtifacts) && failedRequiredWithMetadata.isEmpty();
        checks.add(matches
                ? ArtifactAuditCheck.passed(
                        "metadata.nativeLibrariesTargetArtifacts",
                        "WORKSPACE_NATIVE_EVIDENCE_MATCH",
                        "packaging embedded-library evidence matches built target artifacts")
                : ArtifactAuditCheck.failed(
                        "metadata.nativeLibrariesTargetArtifacts",
                        "METADATA_CONSISTENCY_FAILED",
                        "embedded-library evidence mismatch with targetArtifacts; failed required metadata: "
                                + failedRequiredWithMetadata));
    }

    private void checkNoLegacyFallbackWorkspaceSurfaces(
            List<ArtifactAuditCheck> checks,
            Path workspaceRoot) throws IOException {
        ArrayList<String> forbidden = new ArrayList<>();
        for (Path root : List.of(
                workspaceRoot.resolve("native/zig-workspace"),
                workspaceRoot.resolve("intermediates/runtime"))) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(root)) {
                paths.filter(path -> !path.equals(root))
                        .filter(path -> isLegacyFallbackWorkspacePath(root.relativize(path)))
                        .map(path -> displayPath(workspaceRoot, path))
                        .forEach(forbidden::add);
            }
        }
        Path packagingReport = workspaceRoot.resolve("reports/packaging-report.json");
        if (Files.isRegularFile(packagingReport)) {
            String report = Files.readString(packagingReport);
            if (report.contains("\"fallbackBlobs\"")
                    || report.contains("nativeEmbeddedClassBlob")
                    || report.contains("fallbackBlobEncodingV1")) {
                forbidden.add("reports/packaging-report.json:legacyFallbackMetadata");
            }
        }
        forbidden.sort(String::compareTo);
        checks.add(forbidden.isEmpty()
                ? ArtifactAuditCheck.passed(
                        "workspace.noEmbeddedBytecodeSurfaces",
                        "NO_EMBEDDED_BYTECODE_WORKSPACE_SURFACES",
                        "workspace has no fallback blob carrier, manifest, directory or packaging metadata")
                : ArtifactAuditCheck.failed(
                        "workspace.noEmbeddedBytecodeSurfaces",
                        "EMBEDDED_BYTECODE_WORKSPACE_SURFACE",
                        "forbidden embedded-bytecode workspace surfaces: " + forbidden));
    }

    private boolean isLegacyFallbackWorkspacePath(Path relativePath) {
        String relative = relativePath.toString()
                .replace('\\', '/')
                .toLowerCase(java.util.Locale.ROOT);
        String fileName = relative.substring(relative.lastIndexOf('/') + 1);
        return relative.equals("fallback")
                || relative.startsWith("fallback/")
                || relative.contains("/fallback/")
                || fileName.contains("fallback")
                || relative.contains("fallback-blob")
                || relative.contains("fallback_blob")
                || relative.contains("nativeembeddedfallbackblob");
    }

    private String textOrEmpty(JsonObject object, String field) {
        return hasText(object, field) ? object.get(field).getAsString() : "";
    }

    private boolean hasText(JsonObject object, String field) {
        return object.has(field) && !object.get(field).isJsonNull() && !object.get(field).getAsString().isBlank();
    }

    private void checkExportedSymbols(
            List<ArtifactAuditCheck> checks,
            List<String> exportedSymbols,
            boolean nativeLibrariesPresent) {
        List<String> allowed = List.of("JNI_OnLoad", "__dso_handle", "_mh_dylib_header");
        List<String> unexpected = exportedSymbols.stream()
                .filter(symbol -> !allowed.contains(symbol))
                .sorted()
                .toList();
        List<String> missing = nativeLibrariesPresent
                ? java.util.stream.Stream.of("JNI_OnLoad")
                        .filter(symbol -> !exportedSymbols.contains(symbol))
                        .toList()
                : List.of();
        checks.add(unexpected.isEmpty() && missing.isEmpty()
                ? ArtifactAuditCheck.passed(
                        "symbols.noHiddenExports",
                        "NATIVE_EXPORT_ALLOWLIST_PASSED",
                        nativeLibrariesPresent
                                ? "native exports match the loader/platform allowlist"
                                : "no native library export surface is required")
                : ArtifactAuditCheck.failed(
                        "symbols.noHiddenExports",
                        "NATIVE_EXPORT_ALLOWLIST_FAILED",
                        "missing exports: " + missing + ", unexpected exports: " + unexpected));
    }

    private void checkPlaintexts(
            Path workspaceRoot,
            List<ArtifactAuditCheck> checks,
            List<SensitivePlaintextFact> checkedSensitiveFacts)
            throws IOException {
        List<Path> roots = List.of(
                workspaceRoot.resolve("intermediates"),
                workspaceRoot.resolve("native"),
                workspaceRoot.resolve("reports"));
        if (checkedSensitiveFacts.isEmpty() || roots.stream().noneMatch(Files::isDirectory)) {
            checks.add(ArtifactAuditCheck.passed(
                    "plaintext.forbiddenStrings",
                    "PLAINTEXT_AUDIT_NOT_APPLICABLE",
                    "no forbidden plaintext literals were provided for this audit"));
            return;
        }
        ArrayList<String> hits = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                    if (!isPlaintextAuditSurface(workspaceRoot, path)) {
                        continue;
                    }
                    List<String> forbiddenPlaintexts = checkedSensitiveFacts.stream()
                            .filter(fact -> appliesToWorkspacePath(workspaceRoot, fact, path))
                            .map(SensitivePlaintextFact::plaintext)
                            .filter(value -> !value.isBlank())
                            .sorted()
                            .distinct()
                            .toList();
                    String text = Files.readString(path, StandardCharsets.ISO_8859_1);
                    collectPlaintextHits(workspaceRoot, path, text, forbiddenPlaintexts, hits);
                }
            }
        }
        checks.add(hits.isEmpty()
                ? ArtifactAuditCheck.passed(
                        "plaintext.forbiddenStrings",
                        "FORBIDDEN_PLAINTEXT_ABSENT",
                        "forbidden plaintext literals are absent from generated C/LLVM/native/report artifacts")
                : ArtifactAuditCheck.failed(
                        "plaintext.forbiddenStrings",
                        "FORBIDDEN_PLAINTEXT_FOUND",
                        "forbidden plaintext hits: " + hits));
    }

    private void checkLoweringReportHelperPrivacy(
            List<ArtifactAuditCheck> checks,
            Path workspaceRoot) {
        Path report = workspaceRoot.resolve(
                "reports/lowering-report.json");
        if (!Files.isRegularFile(report)) {
            checks.add(ArtifactAuditCheck.skipped(
                    "reports.loweringHelperPrivacy",
                    "LOWERING_REPORT_NOT_AVAILABLE",
                    "lowering report was not available during audit"));
            return;
        }
        ArrayList<String> violations = new ArrayList<>();
        try {
            JsonObject root = JsonParser.parseString(
                            Files.readString(
                                    report,
                                    StandardCharsets.UTF_8))
                    .getAsJsonObject();
            var methods = root.getAsJsonArray("requestedMethods");
            if (methods == null) {
                violations.add("requestedMethodsMissing");
            } else {
                for (int methodIndex = 0;
                        methodIndex < methods.size();
                        methodIndex++) {
                    JsonObject method = methods.get(methodIndex)
                            .getAsJsonObject();
                    var sites = method.getAsJsonArray(
                            "helperBackedSites");
                    if (sites == null) {
                        continue;
                    }
                    for (int siteIndex = 0;
                            siteIndex < sites.size();
                            siteIndex++) {
                        JsonObject site = sites.get(siteIndex)
                                .getAsJsonObject();
                        String location = "method["
                                + methodIndex
                                + "].site["
                                + siteIndex
                                + "]";
                        if (site.has("helper")) {
                            violations.add(location
                                    + ":legacyHelperField");
                        }
                        if (!hasNonBlankString(
                                site,
                                "helperKind")) {
                            violations.add(location
                                    + ":helperKind");
                        }
                        if (!hasSha256(
                                site,
                                "helperIdentityHash")) {
                            violations.add(location
                                    + ":helperIdentityHash");
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException exception) {
            violations.add("parseFailure:"
                    + exception.getClass().getSimpleName());
        }
        checks.add(violations.isEmpty()
                ? ArtifactAuditCheck.passed(
                        "reports.loweringHelperPrivacy",
                        "LOWERING_HELPER_EVIDENCE_HASH_ONLY",
                        "lowering helper evidence contains only non-sensitive kind and identity hash")
                : ArtifactAuditCheck.failed(
                        "reports.loweringHelperPrivacy",
                        "LOWERING_HELPER_EVIDENCE_PLAINTEXT_SURFACE",
                        "lowering helper evidence privacy violations: "
                                + violations));
    }

    private boolean hasNonBlankString(
            JsonObject object,
            String name) {
        return object.has(name)
                && object.get(name).isJsonPrimitive()
                && object.get(name).getAsJsonPrimitive().isString()
                && !object.get(name).getAsString().isBlank();
    }

    private boolean hasSha256(JsonObject object, String name) {
        return hasNonBlankString(object, name)
                && object.get(name)
                        .getAsString()
                        .matches("[0-9a-f]{64}");
    }

    private void checkPlaintextsInJar(
            Path workspaceRoot,
            JarFile jar,
            List<ArtifactAuditCheck> checks,
            List<SensitivePlaintextFact> checkedSensitiveFacts) throws IOException {
        List<SensitivePlaintextFact> jarFacts = checkedSensitiveFacts.stream()
                .filter(fact -> !isNativeMetadataFact(fact))
                .toList();
        if (jarFacts.isEmpty()) {
            checks.add(ArtifactAuditCheck.passed(
                    "plaintext.jarEntries",
                    "PLAINTEXT_JAR_AUDIT_NOT_APPLICABLE",
                    "no blocking plaintext facts were provided for JAR entry audit"));
            return;
        }
        ArrayList<String> hits = new ArrayList<>();
        ArrayList<String> classParseFailures = new ArrayList<>();
        ClassPlaintextSurfaceScanner classScanner = new ClassPlaintextSurfaceScanner();
        for (var entry : jar.stream()
                .filter(entry -> !entry.isDirectory())
                .filter(entry -> isJarPlaintextAuditSurface(entry.getName()))
                .sorted(java.util.Comparator.comparing(java.util.jar.JarEntry::getName))
                .toList()) {
            List<String> entryForbiddenPlaintexts = jarFacts.stream()
                    .filter(fact -> appliesToJarEntry(fact, entry.getName()))
                    .map(SensitivePlaintextFact::plaintext)
                    .filter(value -> !value.isBlank())
                    .sorted()
                    .distinct()
                    .toList();
            if (entryForbiddenPlaintexts.isEmpty()) {
                continue;
            }
            byte[] outputBytes;
            try (InputStream input = jar.getInputStream(entry)) {
                outputBytes = input.readAllBytes();
            }
            Path displayPath = Path.of("jar").resolve(entry.getName());
            if (entry.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".class")) {
                ClassPlaintextSurfaceScanner.ScanResult scanResult =
                        classScanner.scan(outputBytes, entryForbiddenPlaintexts);
                if (scanResult.rawFallback()) {
                    classParseFailures.add(displayPath(workspaceRoot, displayPath));
                }
                collectPlaintextHits(
                        workspaceRoot,
                        displayPath,
                        scanResult.matches(),
                        hits);
            } else {
                String text = new String(outputBytes, StandardCharsets.ISO_8859_1);
                collectPlaintextHits(
                        workspaceRoot,
                        displayPath,
                        text,
                        entryForbiddenPlaintexts,
                        hits);
            }
        }
        checks.add(hits.isEmpty()
                ? ArtifactAuditCheck.passed(
                        "plaintext.jarEntries",
                        "FORBIDDEN_PLAINTEXT_ABSENT_FROM_JAR",
                        "blocking plaintext literals are absent from applicable executable, constant-value, annotation, and non-class-resource JAR surfaces")
                : ArtifactAuditCheck.failed(
                        "plaintext.jarEntries",
                        "FORBIDDEN_PLAINTEXT_JAR_ENTRY",
                        "forbidden plaintext JAR hits: " + hits));
        checks.add(classParseFailures.isEmpty()
                ? ArtifactAuditCheck.passed(
                        "plaintext.jarClassSemanticSurfaces",
                        "PLAINTEXT_CLASS_SEMANTIC_SCAN_PASSED",
                        "applicable class entries were parsed for semantic plaintext carriers")
                : ArtifactAuditCheck.failed(
                        "plaintext.jarClassSemanticSurfaces",
                        "PLAINTEXT_CLASS_PARSE_FAILED",
                        "class semantic plaintext scan failed closed; raw fallback was used for: "
                                + classParseFailures.stream().sorted().toList()));
    }

    private boolean appliesToJarEntry(
            SensitivePlaintextFact fact,
            String entryName) {
        if (fact.artifactSurfaces().stream()
                .anyMatch(surface -> surface.equalsIgnoreCase("jar-entry"))) {
            return true;
        }
        if (!entryName.toLowerCase(java.util.Locale.ROOT).endsWith(".class")) {
            return false;
        }
        int memberSeparator = fact.sourceMethod().indexOf('#');
        if (memberSeparator <= 0) {
            return false;
        }
        String sourceOwnerEntry =
                fact.sourceMethod().substring(0, memberSeparator) + ".class";
        return entryName.equals(sourceOwnerEntry);
    }

    private void collectPlaintextHits(
            Path workspaceRoot,
            Path path,
            Set<String> matchedPlaintexts,
            List<String> hits) {
        for (String plaintext : matchedPlaintexts.stream().sorted().toList()) {
            hits.add(displayPath(workspaceRoot, path)
                    + ":sha256="
                    + sha256(plaintext.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private void collectPlaintextHits(
            Path workspaceRoot,
            Path path,
            String text,
            List<String> forbiddenPlaintexts,
            List<String> hits) {
        for (String forbidden : forbiddenPlaintexts) {
            if (!forbidden.isEmpty()
                    && containsWorkspacePlaintext(path, text, forbidden)) {
                hits.add(displayPath(workspaceRoot, path)
                        + ":sha256="
                        + sha256(forbidden.getBytes(StandardCharsets.UTF_8)));
            }
        }
    }

    private boolean containsWorkspacePlaintext(
            Path path,
            String text,
            String forbidden) {
        int fromIndex = 0;
        while (fromIndex < text.length()) {
            int match = text.indexOf(forbidden, fromIndex);
            if (match < 0) {
                return false;
            }
            if (!isJniNativeMethodSignatureMember(path, text, forbidden, match)
                    && !isSourceIdentifierSubstring(path, text, match, forbidden.length())) {
                return true;
            }
            fromIndex = match + forbidden.length();
        }
        return false;
    }

    /**
     * Generated source is an evidence surface, but an arbitrary substring of
     * a C/LLVM identifier is not a plaintext carrier. For example, the
     * business literal {@code instance} must not match the runtime declaration
     * {@code j2ll_rt_instanceof}. Exact identifiers and quoted/data literals
     * remain blocking.
     */
    private boolean isSourceIdentifierSubstring(
            Path path,
            String text,
            int match,
            int length) {
        String lower = path.getFileName().toString()
                .toLowerCase(java.util.Locale.ROOT);
        if (!lower.endsWith(".c")
                && !lower.endsWith(".ll")
                && !lower.endsWith(".zig")) {
            return false;
        }
        if (isInsideDataLiteral(path, text, match)) {
            return false;
        }
        boolean joinedLeft = match > 0
                && isSourceIdentifierPart(text.charAt(match - 1));
        int after = match + length;
        boolean joinedRight = after < text.length()
                && isSourceIdentifierPart(text.charAt(after));
        return joinedLeft || joinedRight;
    }

    private boolean isInsideDataLiteral(
            Path path,
            String text,
            int position) {
        int lineStart = text.lastIndexOf('\n', position - 1) + 1;
        char quote = 0;
        int quoteStart = -1;
        boolean escaped = false;
        for (int index = lineStart; index < position; index++) {
            char ch = text.charAt(index);
            if (quote == 0) {
                if (ch == '\'' || ch == '"') {
                    quote = ch;
                    quoteStart = index;
                }
                continue;
            }
            if (escaped) {
                escaped = false;
            } else if (ch == '\\') {
                escaped = true;
            } else if (ch == quote) {
                quote = 0;
                quoteStart = -1;
            }
        }
        if (quote == 0) {
            return false;
        }
        String lower = path.getFileName().toString()
                .toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".ll")
                && quote == '"'
                && quoteStart > 0
                && (text.charAt(quoteStart - 1) == '@'
                        || text.charAt(quoteStart - 1) == '%')) {
            return false;
        }
        return true;
    }

    private boolean isSourceIdentifierPart(char ch) {
        return ch >= 'a' && ch <= 'z'
                || ch >= 'A' && ch <= 'Z'
                || ch >= '0' && ch <= '9'
                || ch == '_'
                || ch == '$'
                || ch == '.';
    }

    private boolean isJniNativeMethodSignatureMember(
            Path path,
            String text,
            String forbidden,
            int match) {
        if (!forbidden.equals("signature")
                || !path.getFileName().toString()
                        .toLowerCase(java.util.Locale.ROOT)
                        .endsWith(".c")) {
            return false;
        }
        int lineStart = text.lastIndexOf('\n', match);
        String prefix = text.substring(lineStart + 1, match);
        return prefix.matches("\\s*methods\\[\\d+\\]\\.");
    }

    private String displayPath(Path workspaceRoot, Path path) {
        try {
            return workspaceRoot.relativize(path).toString().replace('\\', '/');
        } catch (IllegalArgumentException exception) {
            return path.toString().replace('\\', '/');
        }
    }

    private void checkAuditSurfaces(
            List<ArtifactAuditCheck> checks,
            Path workspaceRoot,
            Set<String> jarEntries,
            List<EmbeddedLibraryReport> embeddedLibraries,
            List<String> exportedSymbols) throws IOException {
        checks.add(surfaceCheck(
                "surface.generatedC",
                "GENERATED_C_SURFACE_CHECKED",
                "surfaceNotGenerated",
                workspaceRoot.resolve("intermediates/classes"),
                path -> path.toString().endsWith(".c")));
        checks.add(surfaceCheck(
                "surface.perClassLlvm",
                "PER_CLASS_LLVM_SURFACE_CHECKED",
                "surfaceNotGenerated",
                workspaceRoot.resolve("intermediates/classes"),
                path -> path.toString().endsWith(".ll")));
        checks.add(Files.isRegularFile(workspaceRoot.resolve("native/zig-workspace/build.zig"))
                ? ArtifactAuditCheck.passed("surface.buildZig", "BUILD_ZIG_SURFACE_CHECKED", "build.zig workspace manifest checked")
                : ArtifactAuditCheck.skipped("surface.buildZig", "surfaceNotGenerated", "build.zig was not generated"));
        checks.add(embeddedLibraries.isEmpty()
                ? ArtifactAuditCheck.skipped("surface.nativeLibraryResources", "surfaceNotGenerated", "no native resources were embedded")
                : ArtifactAuditCheck.passed(
                        "surface.nativeLibraryResources",
                        "NATIVE_RESOURCE_SURFACE_CHECKED",
                        "native resource entries checked: " + embeddedLibraries.size()));
        checks.add(jarEntries.isEmpty()
                ? ArtifactAuditCheck.skipped("surface.outputJarEntries", "surfaceNotGenerated", "output JAR has no file entries")
                : ArtifactAuditCheck.passed(
                        "surface.outputJarEntries",
                        "OUTPUT_JAR_ENTRY_SURFACE_CHECKED",
                        "output JAR entries checked: " + jarEntries.size()));
        checks.add(exportedSymbols.isEmpty()
                ? ArtifactAuditCheck.skipped("surface.symbolAuditOutput", "unavailableOnTarget", "no exported symbol list was available")
                : ArtifactAuditCheck.passed(
                        "surface.symbolAuditOutput",
                        "SYMBOL_AUDIT_SURFACE_CHECKED",
                        "exported symbol entries checked: " + exportedSymbols.size()));
        checks.add(Files.isRegularFile(workspaceRoot.resolve("reports/packaging-report.json"))
                ? ArtifactAuditCheck.passed(
                        "surface.packagingReportPaths",
                        "PACKAGING_REPORT_PATH_SURFACE_CHECKED",
                        "packaging report paths checked")
                : ArtifactAuditCheck.skipped(
                        "surface.packagingReportPaths",
                        "surfaceNotGenerated",
                        "packaging report was not available during audit"));
    }

    private ArtifactAuditCheck surfaceCheck(
            String name,
            String checkedReason,
            String skippedReason,
            Path root,
            java.util.function.Predicate<Path> matcher) throws IOException {
        if (!Files.isDirectory(root)) {
            return ArtifactAuditCheck.skipped(name, skippedReason, root + " was not generated");
        }
        try (Stream<Path> paths = Files.walk(root)) {
            long count = paths.filter(Files::isRegularFile).filter(matcher).count();
            return count == 0
                    ? ArtifactAuditCheck.skipped(name, skippedReason, "matching files were not generated")
                    : ArtifactAuditCheck.passed(name, checkedReason, "surface files checked: " + count);
        }
    }

    private boolean isPlaintextAuditSurface(Path workspaceRoot, Path path) {
        String relative = workspaceRoot.relativize(path).toString().replace('\\', '/');
        if (relative.startsWith("intermediates/classes/")) {
            return (relative.contains("/c/") && relative.endsWith(".c"))
                    || (relative.contains("/llvm/") && relative.endsWith(".ll"));
        }
        if (relative.startsWith("native/zig-cache/")) {
            return false;
        }
        if (relative.startsWith("native/")) {
            return relative.endsWith(".c")
                    || relative.endsWith(".ll")
                    || relative.endsWith(".zig")
                    || relative.endsWith(".dylib")
                    || relative.endsWith(".so")
                    || relative.endsWith(".dll");
        }
        if (relative.equals("reports/lowering-report.json")
                || relative.equals("reports/packaging-report.json")
                || relative.equals("reports/symbol-audit.json")) {
            return true;
        }
        return false;
    }

    private boolean appliesToWorkspacePath(
            Path workspaceRoot,
            SensitivePlaintextFact fact,
            Path path) {
        if (!isNativeMetadataFact(fact)) {
            return appliesToMethodWorkspaceSurface(workspaceRoot, fact, path);
        }
        String lower = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".c")
                || lower.endsWith(".dll")
                || lower.endsWith(".so")
                || lower.endsWith(".dylib");
    }

    private boolean appliesToMethodWorkspaceSurface(
            Path workspaceRoot,
            SensitivePlaintextFact fact,
            Path path) {
        String relative = displayPath(workspaceRoot, path);
        String lower = path.getFileName().toString()
                .toLowerCase(java.util.Locale.ROOT);
        if (relative.startsWith("reports/")) {
            return true;
        }
        if (lower.endsWith(".dll")
                || lower.endsWith(".so")
                || lower.endsWith(".dylib")) {
            return fact.artifactSurfaces().stream()
                    .anyMatch(surface -> surface.equalsIgnoreCase("native-library"));
        }
        if (lower.endsWith(".ll")) {
            return fact.artifactSurfaces().stream()
                            .anyMatch(surface -> surface.equalsIgnoreCase("llvm-ir"))
                    && isOwningClassSource(relative, fact.sourceMethod(), true);
        }
        if (lower.endsWith(".c")) {
            if (fact.artifactSurfaces().stream()
                    .noneMatch(surface -> surface.equalsIgnoreCase("generated-c"))) {
                return false;
            }
            if (relative.startsWith("native/zig-workspace/jni/")) {
                // The JNI translation unit is the consolidated carrier for
                // per-method wrappers and business-string helper bodies.
                return true;
            }
            return isOwningClassSource(relative, fact.sourceMethod(), false);
        }
        return false;
    }

    private boolean isOwningClassSource(
            String relative,
            String sourceMethod,
            boolean llvm) {
        String owner = sourceOwner(sourceMethod);
        if (owner.isEmpty()) {
            return false;
        }
        if (relative.startsWith("native/zig-workspace/llvm/")) {
            return relative.endsWith("/" + NativeSourceName.llvmFileName(owner))
                    || relative.equals("native/zig-workspace/llvm/"
                            + NativeSourceName.llvmFileName(owner));
        }
        if (!relative.startsWith("intermediates/classes/")) {
            return false;
        }
        String classRoot = "intermediates/classes/"
                + new ClassArtifactPath().safeInternalName(owner);
        String suffix = llvm ? "/llvm/" : "/c/";
        if (relative.startsWith(classRoot + suffix)) {
            return true;
        }
        if (!relative.startsWith(classRoot + "__")) {
            return false;
        }
        int suffixStart = relative.indexOf(suffix, classRoot.length() + 2);
        if (suffixStart < 0) {
            return false;
        }
        String hashPrefix = relative.substring(classRoot.length() + 2, suffixStart);
        String fullHash = new ClassArtifactPath().fullHash(owner);
        return hashPrefix.length() >= 16 && fullHash.startsWith(hashPrefix);
    }

    private String sourceOwner(String sourceMethod) {
        int memberSeparator = sourceMethod.indexOf('#');
        return memberSeparator <= 0
                ? ""
                : sourceMethod.substring(0, memberSeparator);
    }

    private boolean isNativeMetadataFact(SensitivePlaintextFact fact) {
        return fact.pathKind().equals("NATIVE_METADATA_STRING");
    }

    private boolean isJarPlaintextAuditSurface(String entryName) {
        String lower = entryName.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".dylib")
                || lower.endsWith(".so")
                || lower.endsWith(".dll")
                || lower.endsWith(".pdb")) {
            return false;
        }
        return lower.endsWith(".class")
                || lower.endsWith(".json")
                || lower.endsWith(".properties")
                || lower.endsWith(".txt")
                || lower.endsWith(".xml")
                || lower.equals("meta-inf/manifest.mf");
    }

    private ArtifactAuditResult result(
            List<ArtifactAuditCheck> checks,
            List<SensitivePlaintextFact> checkedSensitiveFacts,
            List<SensitivePlaintextFact> observedOnlySensitiveFacts,
            List<SensitivePlaintextFact> skippedSensitiveFacts) {
        return new ArtifactAuditResult(
                checks.stream().noneMatch(check -> check.status().equals("failed")),
                checks,
                checkedSensitiveFacts,
                observedOnlySensitiveFacts,
                skippedSensitiveFacts);
    }

    private java.util.Comparator<SensitivePlaintextFact> factComparator() {
        return java.util.Comparator
                .comparing(SensitivePlaintextFact::literalHash)
                .thenComparing(SensitivePlaintextFact::sourceMethod)
                .thenComparing(SensitivePlaintextFact::pathKind)
                .thenComparing(SensitivePlaintextFact::gateMode)
                .thenComparing(SensitivePlaintextFact::promotionReason);
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
