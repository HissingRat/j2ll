package xyz.melodysky.runtime.metadata;

import java.util.List;
import java.util.Objects;

public record RecordMetadata(boolean recordClass, List<RecordComponentMetadata> components) {
    public RecordMetadata {
        components = components.stream().filter(Objects::nonNull).sorted().toList();
    }

    public static RecordMetadata nonRecord() {
        return new RecordMetadata(false, List.of());
    }
}
