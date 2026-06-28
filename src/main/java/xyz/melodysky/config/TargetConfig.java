package xyz.melodysky.config;

import java.util.ArrayList;
import java.util.List;
import xyz.melodysky.toolchain.TargetTriple;

public record TargetConfig(
        boolean windowsX64,
        boolean windowsArm64,
        boolean linuxX64,
        boolean linuxArm64,
        boolean macosX64,
        boolean macosArm64) {
    public static TargetConfig single(TargetTriple target) {
        return new TargetConfig(
                target == TargetTriple.WINDOWS_X64,
                target == TargetTriple.WINDOWS_ARM64,
                target == TargetTriple.LINUX_X64,
                target == TargetTriple.LINUX_ARM64,
                target == TargetTriple.MACOS_X64,
                target == TargetTriple.MACOS_ARM64);
    }

    public List<TargetTriple> enabledTargets() {
        ArrayList<TargetTriple> targets = new ArrayList<>();
        addIf(targets, windowsX64, TargetTriple.WINDOWS_X64);
        addIf(targets, windowsArm64, TargetTriple.WINDOWS_ARM64);
        addIf(targets, linuxX64, TargetTriple.LINUX_X64);
        addIf(targets, linuxArm64, TargetTriple.LINUX_ARM64);
        addIf(targets, macosX64, TargetTriple.MACOS_X64);
        addIf(targets, macosArm64, TargetTriple.MACOS_ARM64);
        return List.copyOf(targets);
    }

    private void addIf(List<TargetTriple> targets, boolean enabled, TargetTriple target) {
        if (enabled) {
            targets.add(target);
        }
    }
}
