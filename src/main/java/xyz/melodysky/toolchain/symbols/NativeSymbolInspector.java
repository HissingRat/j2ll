package xyz.melodysky.toolchain.symbols;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import xyz.melodysky.toolchain.TargetTriple;

public final class NativeSymbolInspector {
    public List<String> exportedSymbols(TargetTriple target, Path libraryPath) throws IOException {
        BinaryData data = new BinaryData(Files.readAllBytes(libraryPath));
        return switch (target) {
            case WINDOWS_X64, WINDOWS_ARM64 -> new PeExportTable().read(data, target);
            case LINUX_X64, LINUX_ARM64 -> new ElfExportTable().read(data, target);
            case MACOS_X64, MACOS_ARM64 -> new MachOExportTable().read(data, target);
        };
    }
}
