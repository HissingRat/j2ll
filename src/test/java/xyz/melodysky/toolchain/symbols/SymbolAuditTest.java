package xyz.melodysky.toolchain.symbols;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.toolchain.TargetTriple;

class SymbolAuditTest {
    @Test
    void macosAllowlistIncludesOnlyRequiredPlatformRuntimeExportsBeyondBootstrap() {
        ExportList allowlist = new SymbolVisibilityPlanner().loaderExports(TargetTriple.MACOS_ARM64);

        assertEquals(
                List.of("JNI_OnLoad", "__dso_handle", "_mh_dylib_header"),
                allowlist.symbols().stream().map(ExportedSymbol::name).toList());
        assertTrue(new SymbolAudit().audit(
                allowlist,
                List.of("JNI_OnLoad", "__dso_handle", "_mh_dylib_header")).passed());
    }

    @Test
    void passesWhenActualExportsMatchAllowlist() {
        ExportList allowlist = new SymbolVisibilityPlanner().defaultLoaderExports();

        SymbolAuditResult result = new SymbolAudit().audit(allowlist, List.of("JNI_OnLoad"));

        assertTrue(result.passed());
        assertEquals(List.of(), result.unexpectedExports());
        assertEquals(List.of(), result.missingExports());
    }

    @Test
    void reportsUnexpectedAndMissingExports() {
        ExportList allowlist = new SymbolVisibilityPlanner().defaultLoaderExports();

        SymbolAuditResult result = new SymbolAudit().audit(allowlist, List.of("JNI_OnLoad", "Java_pkg_Foo_run"));

        assertFalse(result.passed());
        assertEquals(List.of("Java_pkg_Foo_run"), result.unexpectedExports());
        assertEquals(List.of(), result.missingExports());
    }

    @Test
    void jniAllowlistExportsOnlyOnLoadAndRejectsInternalRegistrationRoots() {
        ExportList allowlist = new SymbolVisibilityPlanner().defaultLoaderExports();
        SymbolAuditResult result = new SymbolAudit().audit(allowlist, List.of(
                "JNI_OnLoad",
                "abcdefghijklmnopabcdefghijklmnop",
                "Java_pkg_Foo_run"));

        assertFalse(result.passed());
        assertEquals(
                List.of("Java_pkg_Foo_run", "abcdefghijklmnopabcdefghijklmnop"),
                result.unexpectedExports());
        assertEquals(List.of(), result.missingExports());
    }
}
