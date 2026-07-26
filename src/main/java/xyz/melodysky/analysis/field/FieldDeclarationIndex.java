package xyz.melodysky.analysis.field;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedField;
import xyz.melodysky.frontend.classfile.ParsedProgram;

final class FieldDeclarationIndex {
    private final List<ClassFact> allClasses;
    private final Map<String, List<ClassFact>> baseClassesByName;

    private FieldDeclarationIndex(List<ClassFact> allClasses) {
        this.allClasses = allClasses.stream()
                .sorted(Comparator.comparing((ClassFact fact) -> fact.parsedClass().internalName())
                        .thenComparing(ClassFact::origin)
                        .thenComparing(fact -> fact.parsedClass().sourceEntry()))
                .toList();
        LinkedHashMap<String, List<ClassFact>> classesByName = new LinkedHashMap<>();
        this.allClasses.stream()
                .filter(fact -> !fact.versioned())
                .forEach(fact -> classesByName
                        .computeIfAbsent(fact.parsedClass().internalName(), ignored -> new ArrayList<>())
                        .add(fact));
        this.baseClassesByName = Map.copyOf(classesByName);
    }

    static FieldDeclarationIndex create(ParsedProgram inputProgram, List<ParsedProgram> classpathPrograms) {
        ArrayList<ClassFact> classes = new ArrayList<>();
        for (ParsedClass parsedClass : inputProgram.classes()) {
            classes.add(new ClassFact(parsedClass, FieldCodeOrigin.INPUT, isVersioned(parsedClass)));
        }
        for (ParsedProgram classpathProgram : classpathPrograms) {
            for (ParsedClass parsedClass : classpathProgram.classes()) {
                classes.add(new ClassFact(parsedClass, FieldCodeOrigin.CLASSPATH, isVersioned(parsedClass)));
            }
        }
        return new FieldDeclarationIndex(classes);
    }

    List<ClassFact> allClasses() {
        return allClasses;
    }

    List<ParsedField> inputBaseFields() {
        return allClasses.stream()
                .filter(fact -> fact.origin() == FieldCodeOrigin.INPUT && !fact.versioned())
                .flatMap(fact -> fact.parsedClass().fields().stream())
                .toList();
    }

    Set<FieldId> ambiguousInputBaseFields() {
        HashMap<FieldId, Integer> counts = new HashMap<>();
        for (ParsedField field : inputBaseFields()) {
            counts.merge(new FieldId(field.owner(), field.name(), field.descriptor()), 1, Integer::sum);
        }
        HashSet<FieldId> ambiguous = new HashSet<>();
        counts.forEach((field, count) -> {
            if (count > 1) {
                ambiguous.add(field);
            }
        });
        return Set.copyOf(ambiguous);
    }

    Set<String> inputMultiReleaseOwners() {
        HashSet<String> owners = new HashSet<>();
        for (ClassFact fact : allClasses) {
            if (fact.origin() == FieldCodeOrigin.INPUT && fact.versioned()) {
                owners.add(fact.parsedClass().internalName());
            }
        }
        return Set.copyOf(owners);
    }

    Set<String> ownersWithClassInitializer() {
        HashSet<String> owners = new HashSet<>();
        for (ClassFact fact : allClasses) {
            if (fact.versioned()) {
                continue;
            }
            boolean hasClassInitializer = fact.parsedClass().methods().stream()
                    .anyMatch(method -> method.name().equals("<clinit>"));
            if (hasClassInitializer) {
                owners.add(fact.parsedClass().internalName());
            }
        }
        return Set.copyOf(owners);
    }

    Set<String> serializableOwners() {
        HashSet<String> result = new HashSet<>();
        for (ClassFact fact : allClasses) {
            if (!fact.versioned()
                    && implementsType(fact.parsedClass().internalName(), "java/io/Serializable", new HashSet<>())) {
                result.add(fact.parsedClass().internalName());
            }
        }
        return Set.copyOf(result);
    }

    Optional<ParsedField> resolve(String symbolicOwner, String name, String descriptor) {
        return resolve(symbolicOwner, name, descriptor, new HashSet<>());
    }

    private Optional<ParsedField> resolve(
            String owner,
            String name,
            String descriptor,
            Set<String> visited) {
        if (!visited.add(owner)) {
            return Optional.empty();
        }
        Optional<ParsedClass> ownerClass = preferredBaseClass(owner);
        if (ownerClass.isEmpty()) {
            return Optional.empty();
        }
        ParsedClass parsedClass = ownerClass.orElseThrow();
        Optional<ParsedField> declared = parsedClass.fields().stream()
                .filter(field -> field.name().equals(name) && field.descriptor().equals(descriptor))
                .findFirst();
        if (declared.isPresent()) {
            return declared;
        }
        for (String interfaceName : parsedClass.interfaces()) {
            Optional<ParsedField> interfaceField = resolve(interfaceName, name, descriptor, visited);
            if (interfaceField.isPresent()) {
                return interfaceField;
            }
        }
        if (parsedClass.superName() != null) {
            return resolve(parsedClass.superName(), name, descriptor, visited);
        }
        return Optional.empty();
    }

    private boolean implementsType(String owner, String target, Set<String> visited) {
        if (owner.equals(target)) {
            return true;
        }
        if (!visited.add(owner)) {
            return false;
        }
        Optional<ParsedClass> ownerClass = preferredBaseClass(owner);
        if (ownerClass.isEmpty()) {
            return false;
        }
        ParsedClass parsedClass = ownerClass.orElseThrow();
        for (String interfaceName : parsedClass.interfaces()) {
            if (interfaceName.equals(target) || implementsType(interfaceName, target, visited)) {
                return true;
            }
        }
        return parsedClass.superName() != null && implementsType(parsedClass.superName(), target, visited);
    }

    private Optional<ParsedClass> preferredBaseClass(String owner) {
        List<ClassFact> candidates = baseClassesByName.getOrDefault(owner, List.of());
        return candidates.stream()
                .min(Comparator.comparing(ClassFact::origin)
                        .thenComparing(fact -> fact.parsedClass().sourceEntry()))
                .map(ClassFact::parsedClass);
    }

    private static boolean isVersioned(ParsedClass parsedClass) {
        return parsedClass.sourceEntry().replace('\\', '/').startsWith("META-INF/versions/");
    }

    record ClassFact(ParsedClass parsedClass, FieldCodeOrigin origin, boolean versioned) {}
}
