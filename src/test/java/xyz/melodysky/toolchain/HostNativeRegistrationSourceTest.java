package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.packaging.MethodTableHidingPlanner;
import xyz.melodysky.packaging.NativeRegistrationEntry;
import xyz.melodysky.packaging.NativeRegistrationPlan;

final class HostNativeRegistrationSourceTest {
    private final NativeRegistrationPlan registrations = new NativeRegistrationPlan(List.of(
            new NativeRegistrationEntry("sample/Owner", "first", "(I)I", "j2ll_fn_a"),
            new NativeRegistrationEntry("sample/Owner", "second", "(J)J", "j2ll_fn_b")));

    @Test
    void hiddenModeBuildsRuntimeTableByOpaqueTokenJoin() {
        String source = new HostNativeRegistrationSource().emit(
                registrations,
                new MethodTableHidingPlanner().plan(registrations, true, 77L));

        assertTrue(source.contains("j2ll_hidden_method_metadata"));
        assertTrue(source.contains("j2ll_hidden_method_function"));
        assertTrue(source.contains("masked_token ^ UINT64_C"));
        assertTrue(source.contains("if (!matched)"));
        assertTrue(source.contains("RegisterNatives(env, owner, methods, count)"));
        assertTrue(source.contains("methods[metadata_index] = (JNINativeMethod){"));
        assertFalse(source.contains(".signature"));
        assertFalse(source.contains("static JNINativeMethod j2ll_natives_"));
    }

    @Test
    void disabledModePreservesOrdinaryRegistrationShape() {
        String source = new HostNativeRegistrationSource().emit(
                registrations,
                new MethodTableHidingPlanner().plan(registrations, false, 77L));

        assertTrue(source.contains("static JNINativeMethod j2ll_natives_"));
        assertFalse(source.contains("j2ll_hidden_method_metadata"));
    }
}
