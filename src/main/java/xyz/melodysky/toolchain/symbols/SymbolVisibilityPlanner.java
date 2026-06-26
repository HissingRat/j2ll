package xyz.melodysky.toolchain.symbols;

import java.util.ArrayList;
import java.util.List;
import xyz.melodysky.packaging.BootstrapWrapperPlan;
import xyz.melodysky.packaging.JniOnLoadPlan;

public final class SymbolVisibilityPlanner {
    public ExportList defaultLoaderExports() {
        return new ExportList(List.of(
                new ExportedSymbol("JNI_OnLoad"),
                new ExportedSymbol("j2ll_register")));
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
