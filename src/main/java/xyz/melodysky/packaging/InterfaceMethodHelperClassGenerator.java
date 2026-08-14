package xyz.melodysky.packaging;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/** Emits the separate native-method carrier used by Code-bearing interface stubs. */
public final class InterfaceMethodHelperClassGenerator implements Opcodes {
    public Map<String, byte[]> generate(List<MethodRewriteDecision> decisions) {
        Map<String, List<MethodRewriteDecision>> byOwner = decisions.stream()
                .filter(decision -> decision.strategy() == MethodRewriteStrategy.INTERFACE_METHOD_STUB)
                .collect(java.util.stream.Collectors.groupingBy(
                        MethodRewriteDecision::registrationOwner,
                        LinkedHashMap::new,
                        java.util.stream.Collectors.toList()));
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        byOwner.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> entries.put(
                        entry.getKey() + ".class",
                        generateClass(entry.getKey(), entry.getValue())));
        return Map.copyOf(entries);
    }

    private byte[] generateClass(
            String internalName,
            List<MethodRewriteDecision> decisions) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(
                V17,
                ACC_PUBLIC | ACC_FINAL | ACC_SUPER | ACC_SYNTHETIC,
                internalName,
                null,
                "java/lang/Object",
                null);
        decisions.stream()
                .sorted(java.util.Comparator.comparing(
                        decision -> decision.generatedHelperName().orElseThrow()))
                .forEach(decision -> writer.visitMethod(
                                ACC_PUBLIC | ACC_STATIC | ACC_NATIVE | ACC_SYNTHETIC,
                                decision.generatedHelperName().orElseThrow(),
                                NativeHelperDescriptor.forDecision(decision),
                                null,
                                null)
                        .visitEnd());
        writer.visitEnd();
        return writer.toByteArray();
    }
}
