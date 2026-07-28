package xyz.melodysky.protection.audit;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import xyz.melodysky.toolchain.TargetTriple;

public record AttackerAuditRequest(
        TargetTriple target,
        Path nativeLibrary,
        Path generatedC,
        List<String> sensitivePlaintexts) {
    public AttackerAuditRequest {
        Objects.requireNonNull(target, "target");
        nativeLibrary = Objects.requireNonNull(
                        nativeLibrary,
                        "nativeLibrary")
                .toAbsolutePath()
                .normalize();
        generatedC = Objects.requireNonNull(
                        generatedC,
                        "generatedC")
                .toAbsolutePath()
                .normalize();
        sensitivePlaintexts = List.copyOf(Objects.requireNonNull(
                sensitivePlaintexts,
                "sensitivePlaintexts"));
    }
}
