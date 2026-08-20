package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ZigCInputMachinePolicyPlanTest {
    @TempDir
    Path temp;

    @Test
    void registrationWrapperIsTheUniqueNormalizedForbiddenInput() {
        Path wrapper = temp.resolve("native/zig-workspace/jni/wrapper.c");
        Path runtime = temp.resolve("native/zig-workspace/runtime/runtime.c");
        ZigInputSet inputs = inputs(wrapper, runtime);

        ZigCInputMachinePolicyPlan plan =
                ZigCInputMachinePolicyPlan.forRegistrationWrapper(
                        inputs,
                        wrapper.getParent().resolve(".").resolve(wrapper.getFileName()));

        assertEquals(
                ZigCInputMachinePolicyPlan.Mode
                        .REGISTRATION_CONTROL_OUTLINER_FORBIDDEN,
                plan.modeFor(wrapper));
        assertEquals(
                ZigCInputMachinePolicyPlan.Mode.TARGET_DEFAULT,
                plan.modeFor(runtime));
        assertEquals(wrapper.toAbsolutePath().normalize(), plan.registrationControlSource());
        assertEquals(
                List.of(
                        wrapper.toAbsolutePath().normalize(),
                        runtime.toAbsolutePath().normalize()),
                plan.entries().stream()
                        .map(ZigCInputMachinePolicyPlan.Entry::source)
                        .toList());
    }

    @Test
    void registrationWrapperMustMatchExactlyOneNormalizedCInput() {
        Path wrapper = temp.resolve("native/zig-workspace/jni/wrapper.c");
        ZigInputSet unique = inputs(
                wrapper,
                temp.resolve("native/zig-workspace/runtime/runtime.c"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ZigCInputMachinePolicyPlan.forRegistrationWrapper(
                        unique,
                        temp.resolve("missing.c")));

        ZigInputSet ambiguous = inputs(
                wrapper,
                wrapper.getParent().resolve("nested/../wrapper.c"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ZigCInputMachinePolicyPlan.forRegistrationWrapper(
                        ambiguous,
                        wrapper));
        assertThrows(
                IllegalArgumentException.class,
                () -> ZigCInputMachinePolicyPlan.defaults(ambiguous));
    }

    @Test
    void defaultPlanIsTotalButCannotIdentifyARegistrationControlSource() {
        Path wrapper = temp.resolve("native/zig-workspace/jni/wrapper.c");
        Path runtime = temp.resolve("native/zig-workspace/runtime/runtime.c");
        ZigCInputMachinePolicyPlan plan =
                ZigCInputMachinePolicyPlan.defaults(inputs(wrapper, runtime));

        assertEquals(ZigCInputMachinePolicyPlan.Mode.TARGET_DEFAULT, plan.modeFor(wrapper));
        assertEquals(ZigCInputMachinePolicyPlan.Mode.TARGET_DEFAULT, plan.modeFor(runtime));
        assertThrows(
                IllegalArgumentException.class,
                () -> plan.modeFor(temp.resolve("unplanned.c")));
        assertThrows(IllegalStateException.class, plan::registrationControlSource);
    }

    private ZigInputSet inputs(Path... cSources) {
        return new ZigInputSet(new ZigSourceSet(
                List.of(),
                List.of(cSources),
                List.of(),
                List.of()));
    }
}
