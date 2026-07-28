package xyz.melodysky.toolchain.symbols;

import java.util.ArrayList;
import java.util.List;
import xyz.melodysky.packaging.JniOnLoadPlan;
import xyz.melodysky.toolchain.TargetTriple;

public final class SymbolVisibilityPlanner {
    public ExportList defaultLoaderExports() {
        return new ExportList(List.of(new ExportedSymbol("JNI_OnLoad")));
    }

    public ExportList loaderExports(TargetTriple target) {
        ArrayList<ExportedSymbol> exports = new ArrayList<>(defaultLoaderExports().symbols());
        if (target == TargetTriple.MACOS_X64 || target == TargetTriple.MACOS_ARM64) {
            exports.add(new ExportedSymbol("__dso_handle"));
            exports.add(new ExportedSymbol("_mh_dylib_header"));
        }
        return new ExportList(exports);
    }

    public ExportList jniExports(JniOnLoadPlan plan) {
        return new ExportList(List.of(new ExportedSymbol(plan.onLoadSymbol())));
    }
}
