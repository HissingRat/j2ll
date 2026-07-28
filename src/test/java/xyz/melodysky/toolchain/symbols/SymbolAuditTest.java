package xyz.melodysky.toolchain.symbols;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.packaging.JniOnLoadPlan;
import xyz.melodysky.packaging.JniOnLoadPlanner;
import xyz.melodysky.packaging.NativeRegistrationEntry;
import xyz.melodysky.packaging.NativeRegistrationPlan;
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
        NativeRegistrationPlan registrationPlan = new NativeRegistrationPlan(List.of(
                new NativeRegistrationEntry("pkg/Foo", "run", "()V", "j2ll_pkg_Foo_run")));
        JniOnLoadPlan onLoadPlan = new JniOnLoadPlanner().plan(registrationPlan);
        ExportList allowlist = new SymbolVisibilityPlanner().jniExports(onLoadPlan);
        SymbolAuditResult result = new SymbolAudit().audit(allowlist, List.of(
                "JNI_OnLoad",
                "j2ll_register",
                "Java_pkg_Foo_run"));

        assertFalse(result.passed());
        assertEquals(List.of("Java_pkg_Foo_run", "j2ll_register"), result.unexpectedExports());
        assertEquals(List.of(), result.missingExports());
    }

    @Test
    void windowsReleasePlansPdbRemoval() {
        StripPlan plan = new StripCommandPlanner().plan(
                TargetTriple.WINDOWS_X64,
                Path.of("native/x64-windows.dll"),
                true);

        assertTrue(plan.removePdb());
        assertFalse(plan.strip());
    }
}
