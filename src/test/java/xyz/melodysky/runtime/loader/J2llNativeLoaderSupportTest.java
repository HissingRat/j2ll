package xyz.melodysky.runtime.loader;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class J2llNativeLoaderSupportTest {
    @Test
    void missingNativeLibraryResourceThrowsClearUnsatisfiedLinkError() {
        UnsatisfiedLinkError error = assertThrows(UnsatisfiedLinkError.class, () ->
                J2llNativeLoaderSupport.load(
                        J2llNativeLoaderSupportTest.class,
                        "native0/missing-library.dylib",
                        "0".repeat(64)));

        assertTrue(error.getMessage().contains("resource not found"), error.getMessage());
        assertTrue(error.getMessage().contains("native0/missing-library.dylib"), error.getMessage());
    }

    @Test
    void hashMismatchThrowsClearUnsatisfiedLinkErrorBeforeSystemLoad() {
        UnsatisfiedLinkError error = assertThrows(UnsatisfiedLinkError.class, () ->
                J2llNativeLoaderSupport.load(
                        J2llNativeLoaderSupport.class,
                        "xyz/melodysky/runtime/loader/J2llNativeLoaderSupport.class",
                        "0".repeat(64)));

        assertTrue(error.getMessage().contains("hash mismatch"), error.getMessage());
        assertTrue(error.getMessage().contains("expected"), error.getMessage());
        assertTrue(error.getMessage().contains("but found"), error.getMessage());
    }

    @Test
    void unsupportedTargetThrowsClearUnsatisfiedLinkErrorBeforeResourceLookup() {
        UnsatisfiedLinkError error = assertThrows(UnsatisfiedLinkError.class, () ->
                J2llNativeLoaderSupport.loadHostOnly(
                        J2llNativeLoaderSupportTest.class,
                        "native0/anything.dylib",
                        "0".repeat(64),
                        "unsupported-os-arch"));

        assertTrue(error.getMessage().contains("unsupported OS/arch"), error.getMessage());
        assertTrue(error.getMessage().contains("unsupported-os-arch"), error.getMessage());
    }
}
