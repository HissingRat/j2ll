package xyz.melodysky.toolchain;

import java.util.List;
import java.util.Objects;

public record ZigTargetMatrix(List<TargetTriple> targets) {
    public ZigTargetMatrix {
        targets = targets.stream().filter(Objects::nonNull).sorted().toList();
    }
}
