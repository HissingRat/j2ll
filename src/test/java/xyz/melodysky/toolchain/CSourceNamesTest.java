package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CSourceNamesTest {
    @Test
    void identityHashSeparatesNamesWithTheSameSanitizedPrefix() {
        assertNotEquals(
                CIdentifier.forIdentity("pkg/A$B"),
                CIdentifier.forIdentity("pkg/A_B"));
    }

    @Test
    void identityIdentifierContainsOnlyAHashAndNeverThePlaintextPrefix() {
        String identifier = CIdentifier.forIdentity("sensitive/AcmeOwner#secretMethod!()V");

        assertTrue(identifier.matches("h_[0-9a-f]{32}"));
        assertFalse(identifier.contains("AcmeOwner"));
        assertFalse(identifier.contains("secretMethod"));
        assertEquals(identifier, CIdentifier.forIdentity("sensitive/AcmeOwner#secretMethod!()V"));
    }

    @Test
    void stringEscaperHandlesQuotesSeparatorsAndControlCharacters() {
        assertEquals(
                "owner\\\\name\\\"line\\nnext\\000",
                CSourceEscaper.stringContents("owner\\name\"line\nnext\u0000"));
    }
}
