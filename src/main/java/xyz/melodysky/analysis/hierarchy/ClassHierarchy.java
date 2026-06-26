package xyz.melodysky.analysis.hierarchy;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import xyz.melodysky.jvm.MethodSignature;

public final class ClassHierarchy {
    private final AnalysisWorld worldModel;
    private final Map<String, HierarchyClass> classes;
    private final boolean complete;

    public ClassHierarchy(AnalysisWorld worldModel, Collection<HierarchyClass> classes, boolean complete) {
        this.worldModel = Objects.requireNonNull(worldModel, "worldModel");
        LinkedHashMap<String, HierarchyClass> sorted = new LinkedHashMap<>();
        classes.stream()
                .sorted(Comparator.comparing(HierarchyClass::internalName))
                .forEach(hierarchyClass -> sorted.put(hierarchyClass.internalName(), hierarchyClass));
        this.classes = Map.copyOf(sorted);
        this.complete = complete;
    }

    public AnalysisWorld worldModel() {
        return worldModel;
    }

    public boolean isComplete() {
        return complete;
    }

    public List<HierarchyClass> classes() {
        return classes.values().stream()
                .sorted(Comparator.comparing(HierarchyClass::internalName))
                .toList();
    }

    public Optional<HierarchyClass> lookupClass(String internalName) {
        return Optional.ofNullable(classes.get(internalName));
    }

    public Optional<String> superClassOf(String className) {
        return lookupClass(className).map(HierarchyClass::superName);
    }

    public List<String> interfacesOf(String className) {
        return lookupClass(className).map(HierarchyClass::interfaces).orElse(List.of());
    }

    public List<String> subtypesOf(String className) {
        TreeSet<String> subtypes = new TreeSet<>();
        for (HierarchyClass candidate : classes.values()) {
            if (!candidate.internalName().equals(className) && isSubtypeOf(candidate.internalName(), className)) {
                subtypes.add(candidate.internalName());
            }
        }
        return List.copyOf(subtypes);
    }

    public List<String> implementorsOf(String interfaceName) {
        TreeSet<String> implementors = new TreeSet<>();
        for (HierarchyClass candidate : classes.values()) {
            if (!candidate.external() && !candidate.isInterface() && implementsInterface(candidate.internalName(), interfaceName)) {
                implementors.add(candidate.internalName());
            }
        }
        return List.copyOf(implementors);
    }

    public boolean declaresMethod(String className, MethodSignature signature) {
        return lookupClass(className)
                .flatMap(hierarchyClass -> hierarchyClass.declaresMethod(signature))
                .isPresent();
    }

    public Optional<HierarchyMethod> resolveVirtualMethod(String declaredOwner, MethodSignature signature) {
        String current = declaredOwner;
        while (current != null) {
            Optional<HierarchyClass> hierarchyClass = lookupClass(current);
            if (hierarchyClass.isEmpty()) {
                return Optional.empty();
            }
            Optional<HierarchyMethod> declared = hierarchyClass.get().declaresMethod(signature);
            if (declared.isPresent()) {
                return declared;
            }
            current = hierarchyClass.get().superName();
        }
        return resolveInterfaceDefault(declaredOwner, signature);
    }

    public boolean isFinalClass(String className) {
        return lookupClass(className).map(HierarchyClass::isFinal).orElse(false);
    }

    public boolean isFinalMethod(String className, MethodSignature signature) {
        return resolveVirtualMethod(className, signature)
                .map(method -> method.accessFlags().isFinal())
                .orElse(false);
    }

    private Optional<HierarchyMethod> resolveInterfaceDefault(String declaredOwner, MethodSignature signature) {
        ArrayDeque<String> work = new ArrayDeque<>(interfacesOf(declaredOwner));
        while (!work.isEmpty()) {
            String interfaceName = work.removeFirst();
            Optional<HierarchyClass> hierarchyInterface = lookupClass(interfaceName);
            if (hierarchyInterface.isEmpty()) {
                continue;
            }
            Optional<HierarchyMethod> declared = hierarchyInterface.get().declaresMethod(signature)
                    .filter(method -> !method.accessFlags().isAbstract());
            if (declared.isPresent()) {
                return declared;
            }
            work.addAll(hierarchyInterface.get().interfaces());
        }
        return Optional.empty();
    }

    private boolean isSubtypeOf(String candidateName, String targetName) {
        if (candidateName.equals(targetName)) {
            return true;
        }
        Optional<HierarchyClass> candidate = lookupClass(candidateName);
        if (candidate.isEmpty()) {
            return false;
        }
        if (Objects.equals(candidate.get().superName(), targetName) || candidate.get().interfaces().contains(targetName)) {
            return true;
        }
        if (candidate.get().superName() != null && isSubtypeOf(candidate.get().superName(), targetName)) {
            return true;
        }
        return candidate.get().interfaces().stream().anyMatch(interfaceName -> isSubtypeOf(interfaceName, targetName));
    }

    private boolean implementsInterface(String candidateName, String interfaceName) {
        Optional<HierarchyClass> candidate = lookupClass(candidateName);
        if (candidate.isEmpty()) {
            return false;
        }
        if (candidate.get().interfaces().contains(interfaceName)) {
            return true;
        }
        for (String directInterface : candidate.get().interfaces()) {
            if (isSubtypeOf(directInterface, interfaceName)) {
                return true;
            }
        }
        return candidate.get().superName() != null && implementsInterface(candidate.get().superName(), interfaceName);
    }
}
