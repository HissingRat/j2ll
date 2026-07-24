package xyz.melodysky.toolchain.symbols;

import java.util.ArrayList;
import java.util.List;
import xyz.melodysky.packaging.BootstrapWrapperPlan;
import xyz.melodysky.packaging.JniOnLoadPlan;
import xyz.melodysky.toolchain.TargetTriple;

public final class SymbolVisibilityPlanner {
    public ExportList defaultLoaderExports() {
        return new ExportList(List.of(
                new ExportedSymbol("JNI_OnLoad"),
                new ExportedSymbol("j2ll_register")));
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
        ArrayList<ExportedSymbol> exports = new ArrayList<>();
        exports.add(new ExportedSymbol(plan.onLoadSymbol()));
        exports.add(new ExportedSymbol(plan.aggregateRegisterSymbol()));
        for (BootstrapWrapperPlan wrapper : plan.bootstrapWrappers()) {
            exports.add(new ExportedSymbol(wrapper.wrapperSymbol()));
            exports.add(new ExportedSymbol(wrapper.registerSymbol()));
        }
        return new ExportList(exports);
    }
}
