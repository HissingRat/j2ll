package xyz.melodysky.toolchain.symbols;

import java.util.List;
import xyz.melodysky.toolchain.TargetTriple;

public record StripPlan(TargetTriple target, boolean strip, boolean removePdb, List<String> command) {
}
