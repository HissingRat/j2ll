package xyz.melodysky.backend.llvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LlvmNameManglerTest {
    @Test
    void defaultFunctionNamesAreDeterministicHashOnlyIdentifiers() {
        LlvmNameMangler mangler = new LlvmNameMangler();
        String methodKey = "sensitive/acme/PlainOwner#secretMethod!()V";
        String symbol = mangler.functionName(methodKey);

        assertTrue(symbol.matches("j2ll_f_[0-9a-f]{32}"));
        assertFalse(symbol.contains("PlainOwner"));
        assertFalse(symbol.contains("secretMethod"));
        assertEquals(symbol, mangler.functionName(methodKey));
        assertNotEquals(symbol, mangler.functionName("sensitive/acme/PlainOwner#otherMethod!()V"));
    }

    @Test
    void seededFunctionNamesRemainHashOnlyAndSeedDependent() {
        String methodKey = "sensitive/acme/PlainOwner#secretMethod!()V";
        String first = LlvmNameMangler.obfuscating(1L).functionName(methodKey);
        String second = LlvmNameMangler.obfuscating(2L).functionName(methodKey);

        assertTrue(first.matches("j2ll_f_[0-9a-f]{32}"));
        assertFalse(first.contains("PlainOwner"));
        assertNotEquals(first, second);
    }

    @Test
    void rejectsInvalidMethodKeysBeforeHashing() {
        assertThrows(IllegalArgumentException.class, () -> new LlvmNameMangler().functionName("not-a-method-key"));
    }
}
