package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class HostWindowsDllEntryRuntimeSourceTest {
    @Test
    void emitsADataFreeEntryPointWithoutAnyCrtCall() {
        String libraryName = "408cc4b89702abf5";
        String symbol = HostWindowsDllEntryRuntimeSource.symbol(libraryName);
        String source = new HostWindowsDllEntryRuntimeSource().emit(libraryName);

        assertTrue(symbol.matches("[a-p]{32}"), symbol);
        assertTrue(symbol.equals(HostWindowsDllEntryRuntimeSource.symbol(libraryName)));
        assertFalse(symbol.equals(HostWindowsDllEntryRuntimeSource.symbol("008cc4b89702abf5")));
        assertTrue(source.contains("int " + symbol + "("));
        assertFalse(source.contains("j2ll"), source);
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
