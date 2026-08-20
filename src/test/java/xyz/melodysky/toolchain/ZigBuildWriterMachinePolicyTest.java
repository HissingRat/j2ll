package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ZigBuildWriterMachinePolicyTest {
    private static final List<String> COMMON_C_FLAGS = List.of(
            "-std=gnu11",
            "-Oz",
            "-S",
            "-g0",
            "-fPIC",
            "-DNDEBUG",
            "-fvisibility=hidden",
            "-ffunction-sections",
            "-fdata-sections",
            "-ffile-compilation-dir=.",
            "-fdebug-compilation-dir=.",
            "-Werror=implicit-function-declaration");
    private static final List<String> OUTLINER_NEVER =
            List.of("-mllvm", "-enable-machine-outliner=never");
    private static final List<String> OUTLINER_ALWAYS = List.of(
            "-mllvm",
            "-enable-machine-outliner=always",
            "-mllvm",
            "-outliner-benefit-threshold=16");

    @TempDir
    Path temp;

    @Test
    void wrapperAndRuntimeReceiveExactPerInputCommandsAndManifestEvidence()
            throws Exception {
        ZigBuildWorkspace workspace = ZigBuildWorkspace.under(temp);
        Path wrapper = workspace.jniDirectory().resolve("wrapper.c");
        Path runtime = workspace.runtimeDirectory().resolve("j2ll_runtime_helpers.c");
        ZigInputSet inputs = new ZigInputSet(new ZigSourceSet(
                List.of(),
                List.of(wrapper, runtime),
                List.of(),
                List.of()));
        ZigCInputMachinePolicyPlan machinePolicies =
                ZigCInputMachinePolicyPlan.forRegistrationWrapper(inputs, wrapper);
        NativeBuildPlan buildPlan = new NativeBuildPlan(List.of(
                unit(TargetTriple.WINDOWS_X64),
                unit(TargetTriple.LINUX_ARM64),
                unit(TargetTriple.MACOS_X64)));

        new ZigBuildWriter().write(
                workspace,
                "j2lltest",
                buildPlan,
                inputs,
                machinePolicies,
                true,
                NativeUnwindRetentionPolicy.retaining());

        String buildZig = Files.readString(workspace.buildZig());
        JsonObject manifest = JsonParser.parseString(
                        Files.readString(workspace.manifest()))
                .getAsJsonObject();
        for (TargetTriple target : List.of(
                TargetTriple.WINDOWS_X64,
                TargetTriple.LINUX_ARM64,
                TargetTriple.MACOS_X64)) {
            assertCompileCommand(
                    buildZig,
                    target,
                    0,
                    Stream.concat(COMMON_C_FLAGS.stream(), OUTLINER_NEVER.stream())
                            .toList());
            List<String> runtimeOutliner = target.isWindows()
                    ? List.of()
                    : OUTLINER_ALWAYS;
            assertCompileCommand(
                    buildZig,
                    target,
                    1,
                    Stream.concat(COMMON_C_FLAGS.stream(), runtimeOutliner.stream())
                            .toList());
            assertAssemblyIsTheLinkedEvidence(buildZig, target, 0, "jni/wrapper.c");
            assertAssemblyIsTheLinkedEvidence(
                    buildZig,
                    target,
                    1,
                    "runtime/j2ll_runtime_helpers.c");
            assertManifestPolicies(targetManifest(manifest, target), target);
        }
    }

    private void assertCompileCommand(
            String buildZig,
            TargetTriple target,
            int inputIndex,
            List<String> flags) {
        String symbol = commandSymbol(target, inputIndex);
        List<String> allArguments = Stream.concat(
                        Stream.of("-target", target.zigTarget()),
                        flags.stream())
                .toList();
        String expected = "    " + symbol + ".addArgs(&.{ "
                + allArguments.stream()
                        .map(ZigBuildText::quote)
                        .collect(Collectors.joining(", "))
                + " });";
        assertTrue(buildZig.contains(expected), expected);
    }

    private void assertAssemblyIsTheLinkedEvidence(
            String buildZig,
            TargetTriple target,
            int inputIndex,
            String source) {
        String targetSymbol = target.safeSymbol();
        String unitSymbol = targetSymbol + "_c_" + inputIndex;
        String inputSymbol = unitSymbol + "_c_" + inputIndex;
        String command = "compile_assembly_" + inputSymbol;
        String assembly = "optimized_assembly_" + inputSymbol;
        assertTrue(buildZig.contains(
                "    " + command + ".addFileArg(b.path(\"" + source + "\"));"));
        assertTrue(buildZig.contains(
                "    const " + assembly + " = " + command
                        + ".addOutputFileArg(\"c-" + inputIndex + ".s\");"));
        assertTrue(buildZig.contains(
                "    module_" + unitSymbol + ".addAssemblyFile(" + assembly + ");"));
        assertTrue(buildZig.contains(
                "\"native/zig-workspace/evidence/optimized-assembly/"
                        + target.directoryName()
                        + "/c-"
                        + inputIndex
                        + ".s\""));
    }

    private void assertManifestPolicies(JsonObject targetManifest, TargetTriple target) {
        boolean defaultEnabled = !target.isWindows();
        assertEquals(defaultEnabled, targetManifest.get("machineOutlinerEnabled").getAsBoolean());
        assertEquals(
                defaultEnabled ? 16 : 0,
                targetManifest.get("machineOutlinerMinimumBenefitThreshold").getAsInt());
        assertEquals(
                defaultEnabled
                        ? "MACHINE_OUTLINER_ELF_MACHO_ENABLED"
                        : "MACHINE_OUTLINER_WINDOWS_SEH_UNSUPPORTED",
                targetManifest.get("machineOutlinerReason").getAsString());
        assertEquals(
                "PER_C_INPUT",
                targetManifest.get("machineOutlinerPolicyScope").getAsString());

        JsonArray policies = targetManifest.getAsJsonArray("cSourceMachinePolicies");
        assertEquals(2, policies.size());
        assertPolicy(
                policies.get(0).getAsJsonObject(),
                target,
                "jni/wrapper.c",
                "c-0",
                "REGISTRATION_CONTROL_OUTLINER_FORBIDDEN",
                false,
                0,
                "REGISTRATION_MACHINE_TOPOLOGY_OUTLINER_FORBIDDEN",
                OUTLINER_NEVER);
        assertPolicy(
                policies.get(1).getAsJsonObject(),
                target,
                "runtime/j2ll_runtime_helpers.c",
                "c-1",
                "TARGET_DEFAULT",
                defaultEnabled,
                defaultEnabled ? 16 : 0,
                defaultEnabled
                        ? "MACHINE_OUTLINER_ELF_MACHO_ENABLED"
                        : "MACHINE_OUTLINER_WINDOWS_SEH_UNSUPPORTED",
                defaultEnabled ? OUTLINER_ALWAYS : List.of());
    }

    private void assertPolicy(
            JsonObject policy,
            TargetTriple target,
            String source,
            String compileInputId,
            String mode,
            boolean enabled,
            int threshold,
            String reason,
            List<String> flags) {
        assertEquals(source, policy.get("source").getAsString());
        assertEquals(compileInputId, policy.get("compileInputId").getAsString());
        assertEquals(mode, policy.get("mode").getAsString());
        assertEquals(enabled, policy.get("machineOutlinerEnabled").getAsBoolean());
        assertEquals(
                threshold,
                policy.get("machineOutlinerMinimumBenefitThreshold").getAsInt());
        assertEquals(reason, policy.get("machineOutlinerReason").getAsString());
        assertEquals(flags, strings(policy.getAsJsonArray("machineOutlinerCFlags")));
        assertEquals(
                "evidence/optimized-assembly/"
                        + target.directoryName()
                        + "/"
                        + compileInputId
                        + ".s",
                policy.get("optimizedAssemblyEvidence").getAsString());
    }

    private List<String> strings(JsonArray array) {
        ArrayList<String> values = new ArrayList<>();
        for (JsonElement value : array) {
            values.add(value.getAsString());
        }
        return List.copyOf(values);
    }

    private JsonObject targetManifest(JsonObject manifest, TargetTriple target) {
        for (JsonElement value : manifest.getAsJsonArray("targets")) {
            JsonObject candidate = value.getAsJsonObject();
            if (target.directoryName().equals(candidate.get("target").getAsString())) {
                return candidate;
            }
        }
        throw new AssertionError("missing target manifest: " + target);
    }

    private String commandSymbol(TargetTriple target, int inputIndex) {
        return "compile_assembly_"
                + target.safeSymbol()
                + "_c_"
                + inputIndex
                + "_c_"
                + inputIndex;
    }

    private NativeBuildUnit unit(TargetTriple target) {
        return new NativeBuildUnit(
                target,
                temp.resolve("native").resolve(target.libraryFileName()),
                "j2lltest");
    }
}
