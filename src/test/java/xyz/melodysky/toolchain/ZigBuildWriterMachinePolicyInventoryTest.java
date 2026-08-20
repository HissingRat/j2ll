package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ZigBuildWriterMachinePolicyInventoryTest {
    @TempDir
    Path temp;

    @Test
    void manifestConsumesTheActualCompileInputInventoryAcrossBatching()
            throws Exception {
        ZigBuildWorkspace workspace = ZigBuildWorkspace.under(temp);
        ArrayList<Path> cSources = new ArrayList<>();
        for (int index = 0; index < 65; index++) {
            cSources.add(workspace.jniDirectory().resolve(
                    "input-" + String.format("%02d", index) + ".c"));
        }
        Path wrapper = workspace.jniDirectory().resolve("input-31.c");
        cSources.add(workspace.runtimeDirectory().resolve("runtime.c"));
        ZigSourceSet sources = new ZigSourceSet(
                List.of(), cSources, List.of(), List.of());
        ZigInputSet inputs = new ZigInputSet(sources);
        NativeBuildPlan buildPlan = new NativeBuildPlan(List.of(new NativeBuildUnit(
                TargetTriple.LINUX_X64,
                temp.resolve("native").resolve(TargetTriple.LINUX_X64.libraryFileName()),
                "j2lltest")));
        ZigCInputMachinePolicyPlan policies =
                ZigCInputMachinePolicyPlan.forRegistrationWrapper(inputs, wrapper);

        new ZigBuildWriter().write(
                workspace,
                "j2lltest",
                buildPlan,
                inputs,
                policies,
                true,
                NativeUnwindRetentionPolicy.retaining());

        ZigBuildProgressPlan.TargetPlan targetPlan =
                ZigBuildProgressPlan.forSources(buildPlan, sources).targets().get(0);
        assertEquals(ZigBuildProgressPlan.MAX_COMPILE_UNITS, targetPlan.compileUnits().size());
        assertTrue(targetPlan.compileUnits().stream()
                .anyMatch(unit -> unit.inputs().size() > 1));
        List<ZigBuildProgressPlan.CompileInput> actualInputs =
                targetPlan.compileUnits().stream()
                        .flatMap(unit -> unit.inputs().stream())
                        .filter(input -> input.kind()
                                == ZigBuildProgressPlan.CompileInputKind.C)
                        .toList();
        JsonObject manifest = JsonParser.parseString(
                        Files.readString(workspace.manifest()))
                .getAsJsonObject();
        JsonArray rows = manifest.getAsJsonArray("targets")
                .get(0).getAsJsonObject()
                .getAsJsonArray("cSourceMachinePolicies");
        assertEquals(actualInputs.size(), rows.size());
        for (int index = 0; index < actualInputs.size(); index++) {
            ZigBuildProgressPlan.CompileInput input = actualInputs.get(index);
            JsonObject row = rows.get(index).getAsJsonObject();
            assertEquals(input.id(), row.get("compileInputId").getAsString());
            assertTrue(row.get("optimizedAssemblyEvidence").getAsString()
                    .endsWith("/" + input.id() + ".s"));
            boolean registration = input.source().toAbsolutePath().normalize().equals(
                    wrapper.toAbsolutePath().normalize());
            assertEquals(
                    registration
                            ? "REGISTRATION_CONTROL_OUTLINER_FORBIDDEN"
                            : "TARGET_DEFAULT",
                    row.get("mode").getAsString());
        }
    }
}
