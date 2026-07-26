package xyz.melodysky.toolchain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import xyz.melodysky.backend.llvm.model.LlvmModule;

/** Final, ordered LLVM compilation shared by reports and the Zig build. */
public record NativeLlvmCompilation(
        String inputKey,
        List<NativeLlvmModuleCompilation> modules) {
    public NativeLlvmCompilation {
        Objects.requireNonNull(inputKey, "inputKey");
        modules = List.copyOf(Objects.requireNonNull(modules, "modules"));
    }

    public Map<String, LlvmModule> modulesByOwner() {
        LinkedHashMap<String, LlvmModule> result = new LinkedHashMap<>();
        for (NativeLlvmModuleCompilation module : modules) {
            if (result.put(module.owner(), module.module()) != null) {
                throw new IllegalStateException("duplicate final LLVM owner " + module.owner());
            }
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    public Map<String, String> textByOwner() {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (NativeLlvmModuleCompilation module : modules) {
            if (result.put(module.owner(), module.llvmText()) != null) {
                throw new IllegalStateException("duplicate final LLVM owner " + module.owner());
            }
        }
        return java.util.Collections.unmodifiableMap(result);
    }
}
