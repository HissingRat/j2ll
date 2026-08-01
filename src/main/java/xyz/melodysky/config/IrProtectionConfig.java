package xyz.melodysky.config;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public record IrProtectionConfig(
        boolean enabled,
        boolean controlFlowFlattening,
        boolean fakeBranches,
        boolean basicBlockSplitting,
        boolean constantEncryption,
        boolean stringEncryption,
        boolean methodInlining,
        boolean methodSplitting,
        boolean callIndirection,
        boolean fieldInternalization,
        boolean methodInternalization,
        List<Selector> publicMethodInternalizationAllowList,
        boolean methodTableHiding,
        boolean blockNameObfuscation) {
    public IrProtectionConfig {
        publicMethodInternalizationAllowList = List.copyOf(Objects.requireNonNull(
                publicMethodInternalizationAllowList,
                "publicMethodInternalizationAllowList"));
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Selector selector : publicMethodInternalizationAllowList) {
            Objects.requireNonNull(
                    selector,
                    "publicMethodInternalizationAllowList entry");
            if (!selector.isMethodSelector()
                    || selector.classPattern().contains("*")) {
                throw new IllegalArgumentException(
                        "publicMethodInternalizationAllowList requires exact method selectors");
            }
            if (!seen.add(selector.raw())) {
                throw new IllegalArgumentException(
                        "duplicate publicMethodInternalizationAllowList selector: "
                                + selector.raw());
            }
        }
    }

    public IrProtectionConfig(
            boolean enabled,
            boolean controlFlowFlattening,
            boolean fakeBranches,
            boolean basicBlockSplitting,
            boolean constantEncryption,
            boolean stringEncryption,
            boolean methodInlining,
            boolean methodSplitting,
            boolean callIndirection,
            boolean fieldInternalization,
            boolean methodInternalization,
            boolean methodTableHiding,
            boolean blockNameObfuscation) {
        this(
                enabled,
                controlFlowFlattening,
                fakeBranches,
                basicBlockSplitting,
                constantEncryption,
                stringEncryption,
                methodInlining,
                methodSplitting,
                callIndirection,
                fieldInternalization,
                methodInternalization,
                List.of(),
                methodTableHiding,
                blockNameObfuscation);
    }

    public IrProtectionConfig(
            boolean enabled,
            boolean controlFlowFlattening,
            boolean fakeBranches,
            boolean basicBlockSplitting,
            boolean constantEncryption,
            boolean stringEncryption,
            boolean methodInlining,
            boolean methodSplitting,
            boolean callIndirection,
            boolean fieldInternalization,
            boolean methodTableHiding,
            boolean blockNameObfuscation) {
        this(
                enabled,
                controlFlowFlattening,
                fakeBranches,
                basicBlockSplitting,
                constantEncryption,
                stringEncryption,
                methodInlining,
                methodSplitting,
                callIndirection,
                fieldInternalization,
                false,
                List.of(),
                methodTableHiding,
                blockNameObfuscation);
    }

    public IrProtectionConfig(
            boolean enabled,
            boolean controlFlowFlattening,
            boolean fakeBranches,
            boolean basicBlockSplitting,
            boolean constantEncryption,
            boolean stringEncryption,
            boolean methodInlining,
            boolean methodSplitting,
            boolean callIndirection,
            boolean methodTableHiding,
            boolean blockNameObfuscation) {
        this(
                enabled,
                controlFlowFlattening,
                fakeBranches,
                basicBlockSplitting,
                constantEncryption,
                stringEncryption,
                methodInlining,
                methodSplitting,
                callIndirection,
                false,
                false,
                List.of(),
                methodTableHiding,
                blockNameObfuscation);
    }
}
