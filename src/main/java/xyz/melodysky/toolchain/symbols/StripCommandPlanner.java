package xyz.melodysky.toolchain.symbols;

import java.nio.file.Path;
import java.util.List;
import xyz.melodysky.toolchain.TargetTriple;

public final class StripCommandPlanner {
    public StripPlan plan(TargetTriple target, Path libraryPath, boolean release) {
        if (!release) {
            return new StripPlan(target, false, false, List.of());
        }
        if (target.isWindows()) {
            return new StripPlan(target, false, true, List.of());
        }
        return new StripPlan(target, true, false, List.of("strip", "-x", libraryPath.toString()));
    }
}
