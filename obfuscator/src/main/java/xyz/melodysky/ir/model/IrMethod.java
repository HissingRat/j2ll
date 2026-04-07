package xyz.melodysky.ir.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record IrMethod(
        String name,
        IrType returnType,
        List<IrType> parameterTypes,
        int maxLocals,
        boolean isStatic,
        boolean isPrivate,
        boolean isFinal,
        String entryBlock,
        List<IrBlock> blocks
) {

    public IrMethod(
            String name,
            IrType returnType,
            List<IrType> parameterTypes,
            int maxLocals,
            String entryBlock,
            List<IrBlock> blocks
    ) {
        this(name, returnType, parameterTypes, maxLocals, true, false, false, entryBlock, blocks);
    }

    public IrMethod(
            String name,
            IrType returnType,
            List<IrType> parameterTypes,
            int maxLocals,
            boolean isStatic,
            String entryBlock,
            List<IrBlock> blocks
    ) {
        this(name, returnType, parameterTypes, maxLocals, isStatic, false, false, entryBlock, blocks);
    }

    public IrMethod(
            String name,
            IrType returnType,
            List<IrType> parameterTypes,
            int maxLocals,
            boolean isStatic,
            boolean isPrivate,
            boolean isFinal,
            String entryBlock,
            List<IrBlock> blocks
    ) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(returnType, "returnType");
        Objects.requireNonNull(parameterTypes, "parameterTypes");
        Objects.requireNonNull(entryBlock, "entryBlock");
        Objects.requireNonNull(blocks, "blocks");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (maxLocals < 0) {
            throw new IllegalArgumentException("maxLocals must be non-negative");
        }
        parameterTypes = List.copyOf(parameterTypes);
        blocks = List.copyOf(blocks);
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("blocks must not be empty");
        }

        Map<String, IrBlock> blockByLabel = new LinkedHashMap<>();
        for (IrBlock block : blocks) {
            IrBlock previous = blockByLabel.put(block.label(), block);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate block label: " + block.label());
            }
        }
        if (!blockByLabel.containsKey(entryBlock)) {
            throw new IllegalArgumentException("entryBlock does not exist: " + entryBlock);
        }
        this.name = name;
        this.returnType = returnType;
        this.parameterTypes = parameterTypes;
        this.maxLocals = maxLocals;
        this.isStatic = isStatic;
        this.isPrivate = isPrivate;
        this.isFinal = isFinal;
        this.entryBlock = entryBlock;
        this.blocks = blocks;
    }
}
