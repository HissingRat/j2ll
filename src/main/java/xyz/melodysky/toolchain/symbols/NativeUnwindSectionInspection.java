package xyz.melodysky.toolchain.symbols;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import xyz.melodysky.toolchain.TargetTriple;

/** Target-specific unwind sections observed in one final native library. */
public record NativeUnwindSectionInspection(
        TargetTriple target,
        Map<String, Long> sectionSizes) {
    public NativeUnwindSectionInspection {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(sectionSizes, "sectionSizes");
        Set<String> allowedNames = switch (target) {
            case LINUX_X64, LINUX_ARM64 -> Set.of(".eh_frame", ".eh_frame_hdr");
            case WINDOWS_X64, WINDOWS_ARM64 -> Set.of(".pdata", ".xdata");
            case MACOS_X64, MACOS_ARM64 -> Set.of("__eh_frame", "__unwind_info");
        };
        TreeMap<String, Long> ordered = new TreeMap<>();
        for (Map.Entry<String, Long> entry : sectionSizes.entrySet()) {
            String name = Objects.requireNonNull(entry.getKey(), "section name");
            Long size = Objects.requireNonNull(entry.getValue(), "section size");
            if (name.isBlank()) {
                throw new IllegalArgumentException("unwind section name must not be blank");
            }
            if (!allowedNames.contains(name)) {
                throw new IllegalArgumentException(
                        "unexpected unwind section for " + target.directoryName() + ": " + name);
            }
            if (size < 0) {
                throw new IllegalArgumentException(
                        "unwind section size must not be negative: " + name);
            }
            ordered.put(name, size);
        }
        sectionSizes = Collections.unmodifiableMap(ordered);
    }

    public boolean hasNonEmptyUnwindSection() {
        return sectionSizes.values().stream().anyMatch(size -> size > 0);
    }

    public long totalSize() {
        long total = 0;
        for (long size : sectionSizes.values()) {
            total = Math.addExact(total, size);
        }
        return total;
    }
}
