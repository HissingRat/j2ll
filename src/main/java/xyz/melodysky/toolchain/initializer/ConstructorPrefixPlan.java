package xyz.melodysky.toolchain.initializer;

import java.util.Objects;

/**
 * Identifies the verifier-required Java prefix of a constructor stub.
 *
 * <p>The opcode index counts only real bytecode instructions. Labels, frames,
 * and line-number nodes do not affect the index.</p>
 */
public record ConstructorPrefixPlan(
        int initializationOpcodeIndex,
        String targetOwner,
        String targetDescriptor,
        boolean interfaceTarget) {
    public ConstructorPrefixPlan {
        if (initializationOpcodeIndex < 0) {
            throw new IllegalArgumentException("constructor initialization opcode index must be non-negative");
        }
        Objects.requireNonNull(targetOwner, "targetOwner");
        Objects.requireNonNull(targetDescriptor, "targetDescriptor");
        if (targetOwner.isBlank()) {
            throw new IllegalArgumentException("constructor initialization owner must not be blank");
        }
        if (!targetDescriptor.startsWith("(") || !targetDescriptor.endsWith("V")) {
            throw new IllegalArgumentException(
                    "constructor initialization descriptor must return void: " + targetDescriptor);
        }
    }
}
