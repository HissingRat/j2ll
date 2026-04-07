package xyz.melodysky.packaging;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record NativeRegistrationPlan(List<ClassRegistration> classes) {

    public static NativeRegistrationPlan empty() {
        return new NativeRegistrationPlan(List.of());
    }

    public Map<String, Set<NativeMethodClassRewriter.MethodKey>> nativeMethodsByClass() {
        LinkedHashMap<String, Set<NativeMethodClassRewriter.MethodKey>> methodsByClass = new LinkedHashMap<>();
        for (ClassRegistration registration : classes) {
            methodsByClass.put(
                    registration.internalName(),
                    registration.methods().stream()
                            .map(method -> new NativeMethodClassRewriter.MethodKey(method.name(), method.descriptor()))
                            .collect(java.util.stream.Collectors.toUnmodifiableSet())
            );
        }
        return Map.copyOf(methodsByClass);
    }

    public Map<String, Integer> classIndexByInternalName() {
        HashMap<String, Integer> classIndexes = new HashMap<>();
        for (ClassRegistration registration : classes) {
            classIndexes.put(registration.internalName(), registration.index());
        }
        return Map.copyOf(classIndexes);
    }

    public Set<String> loaderHookClasses() {
        return classes.stream()
                .map(ClassRegistration::internalName)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public record ClassRegistration(int index, String internalName, List<MethodRegistration> methods) {
    }

    public record MethodRegistration(String name, String descriptor, String bridgeSymbol) {
    }
}
