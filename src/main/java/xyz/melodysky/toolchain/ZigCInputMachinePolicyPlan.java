package xyz.melodysky.toolchain;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable, total machine policy over the exact generated C input set. */
final class ZigCInputMachinePolicyPlan {
    enum Mode {
        TARGET_DEFAULT,
        REGISTRATION_CONTROL_OUTLINER_FORBIDDEN
    }

    record Entry(Path source, Mode mode) {
        Entry {
            source = normalize(Objects.requireNonNull(source, "source"));
            Objects.requireNonNull(mode, "mode");
        }
    }

    private final List<Entry> entries;
    private final Map<Path, Mode> modeBySource;

    private ZigCInputMachinePolicyPlan(List<Entry> entries) {
        this.entries = List.copyOf(entries);
        LinkedHashMap<Path, Mode> indexed = new LinkedHashMap<>();
        for (Entry entry : entries) {
            if (indexed.put(entry.source(), entry.mode()) != null) {
                throw new IllegalArgumentException(
                        "C input machine policy is ambiguous after path normalization: "
                                + entry.source());
            }
        }
        modeBySource = Map.copyOf(indexed);
    }

    static ZigCInputMachinePolicyPlan defaults(ZigInputSet inputs) {
        Objects.requireNonNull(inputs, "inputs");
        return new ZigCInputMachinePolicyPlan(inputs.sources().cSources().stream()
                .map(source -> new Entry(source, Mode.TARGET_DEFAULT))
                .toList());
    }

    static ZigCInputMachinePolicyPlan forRegistrationWrapper(
            ZigInputSet inputs,
            Path wrapper) {
        Objects.requireNonNull(inputs, "inputs");
        Path normalizedWrapper = normalize(Objects.requireNonNull(wrapper, "wrapper"));
        long matches = inputs.sources().cSources().stream()
                .map(ZigCInputMachinePolicyPlan::normalize)
                .filter(normalizedWrapper::equals)
                .count();
        if (matches != 1) {
            throw new IllegalArgumentException(
                    "registration wrapper must match exactly one configured C input");
        }
        return new ZigCInputMachinePolicyPlan(inputs.sources().cSources().stream()
                .map(source -> new Entry(
                        source,
                        normalize(source).equals(normalizedWrapper)
                                ? Mode.REGISTRATION_CONTROL_OUTLINER_FORBIDDEN
                                : Mode.TARGET_DEFAULT))
                .toList());
    }

    List<Entry> entries() {
        return entries;
    }

    Mode modeFor(Path source) {
        Path normalized = normalize(Objects.requireNonNull(source, "source"));
        Mode mode = modeBySource.get(normalized);
        if (mode == null) {
            throw new IllegalArgumentException(
                    "missing machine policy for configured C input: " + normalized);
        }
        return mode;
    }

    Path registrationControlSource() {
        List<Path> matches = entries.stream()
                .filter(entry -> entry.mode()
                        == Mode.REGISTRATION_CONTROL_OUTLINER_FORBIDDEN)
                .map(Entry::source)
                .toList();
        if (matches.size() != 1) {
            throw new IllegalStateException(
                    "exactly one registration-control C input is required");
        }
        return matches.get(0);
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
