package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HostJniDefiningOwnerSourceTest {
    @Test
    void resolvesTheDeclaredOwnerInsteadOfTheReceiverRuntimeClass() {
        StringBuilder source = new StringBuilder();

        new HostJniDefiningOwnerSource().appendLookup(source, "pkg/Base");

        assertTrue(source.toString().contains(
                "jclass owner = (*env)->FindClass(env, \"pkg/Base\")"));
        assertFalse(source.toString().contains("GetObjectClass"));
    }

    @Test
    void rejectsNamesThatCouldEscapeTheGeneratedCString() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new HostJniDefiningOwnerSource()
                        .appendLookup(new StringBuilder(), "pkg/Bad\"Name"));
    }

    @Test
    void supportsAValidatedCallerChosenLocalName() {
        StringBuilder source = new StringBuilder();

        new HostJniDefiningOwnerSource()
                .appendLookup(source, "pkg/Base", "cls");

        assertTrue(source.toString().contains(
                "jclass cls = (*env)->FindClass(env, \"pkg/Base\")"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new HostJniDefiningOwnerSource()
                        .appendLookup(
                                new StringBuilder(),
                                "pkg/Base",
                                "bad-name"));
    }
}
