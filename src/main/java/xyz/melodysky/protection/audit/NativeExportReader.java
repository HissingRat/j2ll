package xyz.melodysky.protection.audit;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import xyz.melodysky.toolchain.TargetTriple;

@FunctionalInterface
interface NativeExportReader {
    List<String> read(TargetTriple target, Path library) throws IOException;
}
