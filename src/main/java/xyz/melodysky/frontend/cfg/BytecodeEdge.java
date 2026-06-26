package xyz.melodysky.frontend.cfg;

import java.util.Objects;

public record BytecodeEdge(int fromBlockId, int toBlockId, BytecodeEdgeKind kind, String detail) {
    public BytecodeEdge {
        Objects.requireNonNull(kind, "kind");
    }
}
