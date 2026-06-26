package xyz.melodysky.runtime.metadata;

import java.util.List;
import java.util.Objects;

public record NestMetadata(
        String nestHost,
        List<String> nestMembers,
        String outerClass,
        String outerMethodName,
        String outerMethodDescriptor) {
    public NestMetadata {
        nestMembers = nestMembers.stream().filter(Objects::nonNull).sorted().distinct().toList();
    }

    public static NestMetadata empty() {
        return new NestMetadata(null, List.of(), null, null, null);
    }
}
