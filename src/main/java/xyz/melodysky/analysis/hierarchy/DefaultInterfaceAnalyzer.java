package xyz.melodysky.analysis.hierarchy;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.frontend.classfile.ParsedProgram;

/** Computes default-method ownership and ambiguous inherited signatures. */
public final class DefaultInterfaceAnalyzer {
    public DefaultInterfaceAnalysis analyze(ParsedProgram program) {
        Map<String, ParsedClass> classes = new HashMap<>();
        LinkedHashSet<String> methodKeys = new LinkedHashSet<>();
        for (ParsedClass parsedClass : program.classes()) {
            classes.put(parsedClass.internalName(), parsedClass);
            if (parsedClass.isInterface()) {
                parsedClass.methods().stream()
                        .filter(this::isDefaultInterfaceMethod)
                        .map(ParsedMethod::methodKey)
                        .forEach(methodKeys::add);
            }
        }

        LinkedHashSet<String> conflictSignatures = new LinkedHashSet<>();
        for (ParsedClass parsedClass : program.classes()) {
            if (parsedClass.interfaces().isEmpty()) {
                continue;
            }
            Map<String, LinkedHashSet<String>> providersBySignature = new LinkedHashMap<>();
            for (String interfaceName : parsedClass.interfaces()) {
                collectProviders(interfaceName, classes, providersBySignature, new LinkedHashSet<>());
            }
            providersBySignature.entrySet().stream()
                    .filter(entry -> entry.getValue().size() > 1)
                    .map(Map.Entry::getKey)
                    .filter(signature -> !declaresConcreteMethod(parsedClass, signature))
                    .forEach(conflictSignatures::add);
        }
        return new DefaultInterfaceAnalysis(methodKeys, conflictSignatures);
    }

    private void collectProviders(
            String interfaceName,
            Map<String, ParsedClass> classes,
            Map<String, LinkedHashSet<String>> providersBySignature,
            Set<String> seen) {
        if (!seen.add(interfaceName)) {
            return;
        }
        ParsedClass parsedClass = classes.get(interfaceName);
        if (parsedClass == null) {
            return;
        }
        if (parsedClass.isInterface()) {
            for (ParsedMethod method : parsedClass.methods()) {
                if (isDefaultInterfaceMethod(method)) {
                    providersBySignature
                            .computeIfAbsent(signature(method), ignored -> new LinkedHashSet<>())
                            .add(parsedClass.internalName());
                }
            }
        }
        for (String parent : parsedClass.interfaces()) {
            collectProviders(parent, classes, providersBySignature, seen);
        }
    }

    private boolean isDefaultInterfaceMethod(ParsedMethod method) {
        return method.hasCode()
                && !method.name().startsWith("<")
                && !method.accessFlags().isAbstract()
                && !method.accessFlags().isStatic()
                && !method.accessFlags().isPrivate();
    }

    private boolean declaresConcreteMethod(ParsedClass parsedClass, String signature) {
        return parsedClass.methods().stream()
                .anyMatch(method -> signature(method).equals(signature)
                        && method.hasCode()
                        && !method.accessFlags().isAbstract());
    }

    private String signature(ParsedMethod method) {
        return method.name() + "!" + method.descriptor();
    }
}
