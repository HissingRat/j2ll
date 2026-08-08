package xyz.melodysky.analysis.field;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedField;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.frontend.classfile.ParsedProgram;

/** Read-only field catalog used only by dynamic-observer provenance analysis. */
final class FieldObserverDeclarationIndex {
    private final Map<String, ParsedClass> classes;

    private FieldObserverDeclarationIndex(Map<String, ParsedClass> classes) {
        this.classes = Map.copyOf(classes);
    }

    static FieldObserverDeclarationIndex create(
            ParsedProgram inputProgram,
            List<ParsedProgram> classpathPrograms) {
        HashMap<String, ParsedClass> classes = new HashMap<>();
        inputProgram.classes().stream()
                .filter(FieldObserverDeclarationIndex::baseClass)
                .forEach(parsedClass -> classes.putIfAbsent(parsedClass.internalName(), parsedClass));
        for (ParsedProgram program : classpathPrograms) {
            program.classes().stream()
                    .filter(FieldObserverDeclarationIndex::baseClass)
                    .forEach(parsedClass -> classes.putIfAbsent(parsedClass.internalName(), parsedClass));
        }
        return new FieldObserverDeclarationIndex(classes);
    }

    boolean containsOwner(String owner) {
        return classes.containsKey(owner);
    }

    boolean hasScannedStaticMethodBody(String owner, String name, String descriptor) {
        return resolveMethod(owner, name, descriptor, new HashSet<>())
                .filter(method -> method.accessFlags().isStatic())
                .filter(method -> !method.accessFlags().isNative())
                .filter(ParsedMethod::hasCode)
                .isPresent();
    }

    boolean hasScannedMethodBody(HandleTarget target) {
        return resolveMethod(
                        target.owner(),
                        target.name(),
                        target.descriptor(),
                        new HashSet<>())
                .filter(method -> !method.accessFlags().isNative())
                .filter(ParsedMethod::hasCode)
                .filter(method -> staticShapeMatches(target.handleTag(), method))
                .isPresent();
    }

    List<FieldId> declaredByName(String owner, String name) {
        ParsedClass parsedClass = classes.get(owner);
        if (parsedClass == null) {
            return List.of();
        }
        return parsedClass.fields().stream()
                .filter(field -> field.name().equals(name))
                .map(FieldObserverDeclarationIndex::id)
                .sorted()
                .toList();
    }

    List<FieldId> visibleByName(String owner, String name) {
        ArrayList<FieldId> result = new ArrayList<>();
        collectVisibleByName(owner, name, new HashSet<>(), result);
        return result.stream().distinct().sorted().toList();
    }

    Optional<FieldId> resolve(String owner, String name, String descriptor) {
        return resolve(owner, name, descriptor, new HashSet<>());
    }

    private Optional<ParsedMethod> resolveMethod(
            String owner,
            String name,
            String descriptor,
            Set<String> visited) {
        if (!visited.add(owner)) {
            return Optional.empty();
        }
        ParsedClass parsedClass = classes.get(owner);
        if (parsedClass == null) {
            return Optional.empty();
        }
        Optional<ParsedMethod> declared = parsedClass.methods().stream()
                .filter(method -> method.name().equals(name)
                        && method.descriptor().equals(descriptor))
                .findFirst();
        if (declared.isPresent() || name.equals("<init>")) {
            return declared;
        }
        for (String interfaceName : parsedClass.interfaces()) {
            Optional<ParsedMethod> match = resolveMethod(
                    interfaceName,
                    name,
                    descriptor,
                    visited);
            if (match.isPresent()) {
                return match;
            }
        }
        return parsedClass.superName() == null
                ? Optional.empty()
                : resolveMethod(parsedClass.superName(), name, descriptor, visited);
    }

    private boolean staticShapeMatches(int handleTag, ParsedMethod method) {
        boolean staticHandle = handleTag == Opcodes.H_INVOKESTATIC;
        return staticHandle == method.accessFlags().isStatic();
    }

    private Optional<FieldId> resolve(
            String owner,
            String name,
            String descriptor,
            Set<String> visited) {
        if (!visited.add(owner)) {
            return Optional.empty();
        }
        ParsedClass parsedClass = classes.get(owner);
        if (parsedClass == null) {
            return Optional.empty();
        }
        Optional<ParsedField> declared = parsedClass.fields().stream()
                .filter(field -> field.name().equals(name)
                        && field.descriptor().equals(descriptor))
                .findFirst();
        if (declared.isPresent()) {
            return declared.map(FieldObserverDeclarationIndex::id);
        }
        for (String interfaceName : parsedClass.interfaces()) {
            Optional<FieldId> match = resolve(interfaceName, name, descriptor, visited);
            if (match.isPresent()) {
                return match;
            }
        }
        return parsedClass.superName() == null
                ? Optional.empty()
                : resolve(parsedClass.superName(), name, descriptor, visited);
    }

    private void collectVisibleByName(
            String owner,
            String name,
            Set<String> visited,
            List<FieldId> result) {
        if (!visited.add(owner)) {
            return;
        }
        ParsedClass parsedClass = classes.get(owner);
        if (parsedClass == null) {
            return;
        }
        parsedClass.fields().stream()
                .filter(field -> field.name().equals(name))
                .map(FieldObserverDeclarationIndex::id)
                .forEach(result::add);
        for (String interfaceName : parsedClass.interfaces()) {
            collectVisibleByName(interfaceName, name, visited, result);
        }
        if (parsedClass.superName() != null) {
            collectVisibleByName(parsedClass.superName(), name, visited, result);
        }
    }

    private static boolean baseClass(ParsedClass parsedClass) {
        return !parsedClass.sourceEntry().replace('\\', '/').startsWith("META-INF/versions/");
    }

    private static FieldId id(ParsedField field) {
        return new FieldId(field.owner(), field.name(), field.descriptor());
    }

    record HandleTarget(
            int handleTag,
            String owner,
            String name,
            String descriptor) {}
}
