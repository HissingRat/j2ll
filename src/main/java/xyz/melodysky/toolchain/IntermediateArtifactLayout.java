package xyz.melodysky.toolchain;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record IntermediateArtifactLayout(
        List<ClassArtifact> classes,
        Map<String, List<MethodArtifact>> methodsByClass) {
    public IntermediateArtifactLayout {
        classes = List.copyOf(Objects.requireNonNull(classes, "classes"));
        methodsByClass = Map.copyOf(Objects.requireNonNull(methodsByClass, "methodsByClass"));
    }

    public List<MethodArtifact> methodsFor(String internalName) {
        return methodsByClass.getOrDefault(internalName, List.of());
    }
}
