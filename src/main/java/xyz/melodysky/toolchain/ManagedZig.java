package xyz.melodysky.toolchain;

import java.nio.file.Path;
import java.util.Objects;

public record ManagedZig(Path executable, Path home, String version, String verificationPolicy) {
    public ManagedZig {
        Objects.requireNonNull(executable, "executable");
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(verificationPolicy, "verificationPolicy");
    }
}
