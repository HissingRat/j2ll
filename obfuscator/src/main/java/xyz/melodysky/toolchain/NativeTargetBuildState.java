package xyz.melodysky.toolchain;

import xyz.melodysky.config.BuildTarget;

import java.nio.file.Path;
import java.util.List;

record NativeTargetBuildState(BuildTarget target,
                              Path libraryFile,
                              Path logFile,
                              List<NativeCompileUnit> compileUnits,
                              NativeCompileBatchResult compileBatchResult,
                              int llvmShardCount,
                              int runtimeSourceCount,
                              long compileMillis) {
}
