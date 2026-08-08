package xyz.melodysky.toolchain;

import java.util.List;
import java.util.Objects;

/** Per-target build policy kept separate from Zig source rendering. */
public record ZigTargetBuildPolicy(
        NativeUnwindRetentionDecision unwindRetention,
        NativeMachineOutlinerPolicy machineOutliner) {
    public ZigTargetBuildPolicy {
        Objects.requireNonNull(unwindRetention, "unwindRetention");
        Objects.requireNonNull(machineOutliner, "machineOutliner");
    }

    public static ZigTargetBuildPolicy resolve(
            TargetTriple target,
            NativeUnwindRetentionPolicy unwindRetentionPolicy) {
        return new ZigTargetBuildPolicy(
                unwindRetentionPolicy.resolve(target),
                NativeMachineOutlinerPolicy.forTarget(target));
    }

    /** Flags that apply only to generated C compile units, never to LLVM object inputs. */
    public List<String> generatedCCompilerFlags() {
        java.util.ArrayList<String> flags = new java.util.ArrayList<>();
        flags.add("-Werror=implicit-function-declaration");
        if (!unwindRetention.effective()) {
            flags.add("-fno-unwind-tables");
            flags.add("-fno-asynchronous-unwind-tables");
        }
        flags.addAll(machineOutliner.cFlags());
        return List.copyOf(flags);
    }
}
