package xyz.melodysky.analysis.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PROTECTED;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V17;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import xyz.melodysky.analysis.hierarchy.AnalysisWorld;
import xyz.melodysky.analysis.hierarchy.ClassHierarchy;
import xyz.melodysky.analysis.hierarchy.ClassHierarchyBuilder;
import xyz.melodysky.analysis.reflection.ReflectionMethodKind;
import xyz.melodysky.analysis.reflection.ReflectionMethodTarget;
import xyz.melodysky.analysis.reflection.ReflectionPlan;
import xyz.melodysky.analysis.reflection.ReflectionUnsupportedSite;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.frontend.classfile.ParsedProgram;
import xyz.melodysky.testsupport.AsmFixtureBuilder;

class ProgramEntryPointPlannerTest {
    private static final String OWNER = "fixture/Entries";

    @Test
    void rootsExternalLifecycleAndKnownJvmCallbackEntries() {
        ParsedProgram program = program();

        List<String> roots = plan(program, List.of(), emptyReflection());

        assertEquals(List.of(
                OWNER + "#<clinit>!()V",
                OWNER + "#api!()V",
                OWNER + "#packageApi!()V",
                OWNER + "#protectedApi!()V",
                OWNER + "#readObject!(Ljava/io/ObjectInputStream;)V"), roots);
    }

    @Test
    void rootsSelectedPrivateAndExactReflectiveTargets() {
        ParsedProgram program = program();
        ReflectionPlan reflection = new ReflectionPlan(
                List.of(),
                List.of(new ReflectionMethodTarget(
                        OWNER,
                        "hidden",
                        "()V",
                        ReflectionMethodKind.REFLECTIVE_INVOKE,
                        false,
                        OWNER + "#api!()V@0")),
                List.of(),
                List.of());

        List<String> roots = plan(
                program,
                List.of(method(program, "selected")),
                reflection);

        assertEquals(List.of(
                OWNER + "#<clinit>!()V",
                OWNER + "#api!()V",
                OWNER + "#hidden!()V",
                OWNER + "#packageApi!()V",
                OWNER + "#protectedApi!()V",
                OWNER + "#readObject!(Ljava/io/ObjectInputStream;)V",
                OWNER + "#selected!()V"), roots);
    }

    @Test
    void unsupportedReflectionFallsBackToAllCodeMethods() {
        ParsedProgram program = program();
        ReflectionPlan reflection = new ReflectionPlan(
                List.of(),
                List.of(),
                List.of(),
                List.of(new ReflectionUnsupportedSite(
                        OWNER,
                        "api",
                        "()V",
                        0,
                        "DYNAMIC_REFLECTION",
                        "dynamic target")));

        List<String> roots = plan(program, List.of(), reflection);

        assertEquals(program.classes().stream()
                .flatMap(parsedClass -> parsedClass.methods().stream())
                .filter(ParsedMethod::hasCode)
                .map(ParsedMethod::methodKey)
                .sorted()
                .toList(), roots);
    }

    private List<String> plan(
            ParsedProgram program,
            List<ParsedMethod> selected,
            ReflectionPlan reflectionPlan) {
        ClassHierarchy hierarchy = new ClassHierarchyBuilder()
                .build(program, AnalysisWorld.CLOSED_WORLD)
                .artifact()
                .orElseThrow();
        return new ProgramEntryPointPlanner()
                .plan(program, hierarchy, selected, reflectionPlan)
                .stream()
                .map(ParsedMethod::methodKey)
                .toList();
    }

    private ParsedMethod method(ParsedProgram program, String name) {
        return program.findClass(OWNER).orElseThrow().methods().stream()
                .filter(method -> method.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private ReflectionPlan emptyReflection() {
        return new ReflectionPlan(List.of(), List.of(), List.of(), List.of());
    }

    private ParsedProgram program() {
        AsmClassParser parser = new AsmClassParser();
        return new ParsedProgram(List.of(
                entry("java/lang/Object", AsmFixtureBuilder.classHeader(
                        "java/lang/Object", null, null, ACC_PUBLIC)),
                entry("java/io/Serializable", AsmFixtureBuilder.interfaceHeader(
                        "java/io/Serializable", null)),
                entry(OWNER, entryClass())).stream()
                .map(entry -> parser.parse(entry).artifact().orElseThrow())
                .toList());
    }

    private byte[] entryClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(
                V17,
                ACC_PUBLIC,
                OWNER,
                null,
                "java/lang/Object",
                new String[] {"java/io/Serializable"});
        emitVoid(writer, ACC_STATIC, "<clinit>", "()V");
        emitVoid(writer, ACC_PUBLIC | ACC_STATIC, "api", "()V");
        emitVoid(writer, ACC_PROTECTED | ACC_STATIC, "protectedApi", "()V");
        emitVoid(writer, ACC_STATIC, "packageApi", "()V");
        emitVoid(writer, ACC_PRIVATE | ACC_STATIC, "selected", "()V");
        emitVoid(writer, ACC_PRIVATE | ACC_STATIC, "hidden", "()V");
        emitVoid(
                writer,
                ACC_PRIVATE,
                "readObject",
                "(Ljava/io/ObjectInputStream;)V");
        writer.visitEnd();
        return writer.toByteArray();
    }

    private void emitVoid(
            ClassWriter writer,
            int access,
            String name,
            String descriptor) {
        MethodVisitor method = writer.visitMethod(access, name, descriptor, null, null);
        method.visitCode();
        method.visitInsn(RETURN);
        method.visitMaxs(0, (access & ACC_STATIC) == 0 ? 2 : 0);
        method.visitEnd();
    }

    private ClassFileEntry entry(String owner, byte[] bytes) {
        return new ClassFileEntry(owner + ".class", bytes, "fixture");
    }
}
