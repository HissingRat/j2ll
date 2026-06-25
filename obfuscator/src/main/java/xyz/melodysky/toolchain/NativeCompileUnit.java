package xyz.melodysky.toolchain;

import java.nio.file.Path;
import java.util.List;

record NativeCompileUnit(String label, Path objectFile, List<String> command) {
}
