package xyz.melodysky.toolchain;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record ManagedZig(
        Path executable,
        Path home,
        String version,
        String verificationPolicy,
        List<ManagedZigBootstrapEvent> bootstrapEvents) {
    public ManagedZig(Path executable, Path home, String version, String verificationPolicy) {
        this(executable, home, version, verificationPolicy, List.of());
    }

    public ManagedZig {
        Objects.requireNonNull(executable, "executable");
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(verificationPolicy, "verificationPolicy");
        bootstrapEvents = List.copyOf(Objects.requireNonNull(bootstrapEvents, "bootstrapEvents"));
    }
}
