package xyz.melodysky.ir.pass.protection;

import java.util.Objects;

record MethodInliningSite(
        int blockIndex,
        String blockName,
        int instructionIndex,
        MethodInliningCandidate candidate) {
    MethodInliningSite {
        Objects.requireNonNull(blockName, "blockName");
        Objects.requireNonNull(candidate, "candidate");
    }

    String displayName() {
        return String.format("%04d:%s@%04d", blockIndex, blockName, instructionIndex);
    }
}
