package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class HostWindowsDllEntryRuntimeSourceTest {
    @Test
    void emitsADataFreeEntryPointWithoutAnyCrtCall() {
        String source = new HostWindowsDllEntryRuntimeSource().emit("j2ll_deadbeef");

        assertTrue(source.contains("int j2ll_deadbeef_entry("));
        assertTrue(source.contains("return 1;"));
        assertFalse(NativeLibcRequirementPlan.inspect(source).required());
    }

    @Test
    void rejectsAnEntrySymbolInjection() {
        assertThrows(
                IllegalArgumentException.class,
                () -> HostWindowsDllEntryRuntimeSource.symbol("../entry"));
    }
}
