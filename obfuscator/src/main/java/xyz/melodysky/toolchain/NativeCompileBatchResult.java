package xyz.melodysky.toolchain;

import java.util.LinkedHashMap;

record NativeCompileBatchResult(LinkedHashMap<String, String> outputByUnit, NativeCompileUnit failedUnit) {
}
