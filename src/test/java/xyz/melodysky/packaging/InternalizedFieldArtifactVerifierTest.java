package xyz.melodysky.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static xyz.melodysky.testsupport.NativeFieldInternalizationFixtures.nativeStored;
import static xyz.melodysky.testsupport.NativeFieldInternalizationFixtures.plan;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import xyz.melodysky.analysis.field.FieldId;
import xyz.melodysky.analysis.field.ConstantDynamicFieldReferenceResolver;
import xyz.melodysky.analysis.field.NativeFieldInternalizationPlan;

class InternalizedFieldArtifactVerifierTest implements Opcodes {
    private static final String OWNER = "pkg/State";
    private static final FieldId APPROVED = new FieldId(OWNER, "counter", "I");
    private static final Handle FIELD_HANDLE =
            new Handle(H_GETSTATIC, OWNER, APPROVED.name(), APPROVED.descriptor(), false);
    private static final Handle INDY_BOOTSTRAP = new Handle(
            H_INVOKESTATIC,
            "pkg/Bootstrap",
            "indy",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                    + "Ljava/lang/invoke/MethodType;Ljava/lang/Object;)"
                    + "Ljava/lang/invoke/CallSite;",
            false);
    private static final Handle CONDY_BOOTSTRAP = new Handle(
            H_INVOKESTATIC,
            "pkg/Bootstrap",
            "constant",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                    + "Ljava/lang/Class;Ljava/lang/Object;)Ljava/lang/Object;",
            false);
    private static final Handle GET_STATIC_FINAL_SELF_BOOTSTRAP = new Handle(
            H_INVOKESTATIC,
            "java/lang/invoke/ConstantBootstraps",
            "getStaticFinal",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                    + "Ljava/lang/Class;)Ljava/lang/Object;",
            false);
    private static final Handle GET_STATIC_FINAL_OWNER_BOOTSTRAP = new Handle(
            H_INVOKESTATIC,
            "java/lang/invoke/ConstantBootstraps",
            "getStaticFinal",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                    + "Ljava/lang/Class;Ljava/lang/Class;)Ljava/lang/Object;",
            false);
    private static final Handle STATIC_FIELD_VAR_HANDLE_BOOTSTRAP = new Handle(
            H_INVOKESTATIC,
            "java/lang/invoke/ConstantBootstraps",
            "staticFieldVarHandle",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                    + "Ljava/lang/Class;Ljava/lang/Class;Ljava/lang/Class;)"
                    + "Ljava/lang/invoke/VarHandle;",
            false);
    private static final Handle MALFORMED_GET_STATIC_FINAL_BOOTSTRAP = new Handle(
            H_INVOKESTATIC,
            "java/lang/invoke/ConstantBootstraps",
            "getStaticFinal",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                    + "Ljava/lang/Class;Ljava/lang/Object;)Ljava/lang/Object;",
            false);
    private static final Handle FIELD_VAR_HANDLE_BOOTSTRAP = new Handle(
            H_INVOKESTATIC,
            "java/lang/invoke/ConstantBootstraps",
            "fieldVarHandle",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                    + "Ljava/lang/Class;Ljava/lang/Class;Ljava/lang/Class;)"
                    + "Ljava/lang/invoke/VarHandle;",
            false);

    @TempDir
    Path temp;

    private final InternalizedFieldArtifactVerifier verifier =
            new InternalizedFieldArtifactVerifier();

    @Test
    void detectsApprovedFieldDeclaration() throws Exception {
        List<String> residuals = residuals(classWithDeclaration());

        assertEquals(1, residuals.size());
        assertTrue(residuals.get(0).contains("kind=declaration"));
        assertTrue(residuals.get(0).contains(FieldPrivacyHash.sha256(APPROVED.fieldKey())));
        assertTrue(!residuals.get(0).contains(APPROVED.fieldKey()));
    }

    @Test
    void detectsDirectFieldInstruction() throws Exception {
        List<String> residuals = residuals(classWithMethod(method ->
                method.visitFieldInsn(GETSTATIC, OWNER, APPROVED.name(), APPROVED.descriptor())));

        assertEquals(1, residuals.size());
        assertTrue(residuals.get(0).contains("kind=reference"));
    }

    @Test
    void detectsLdcFieldMethodHandle() throws Exception {
        List<String> residuals = residuals(classWithMethod(method ->
                method.visitLdcInsn(FIELD_HANDLE)));

        assertEquals(1, residuals.size());
        assertTrue(residuals.get(0).contains("kind=reference"));
    }

    @Test
    void detectsInvokeDynamicBootstrapArgumentFieldHandle() throws Exception {
        List<String> residuals = residuals(classWithMethod(method ->
                method.visitInvokeDynamicInsn("call", "()V", INDY_BOOTSTRAP, FIELD_HANDLE)));

        assertEquals(1, residuals.size());
        assertTrue(residuals.get(0).contains("kind=reference"));
    }

    @Test
    void detectsConstantDynamicBootstrapArgumentFieldHandle() throws Exception {
        ConstantDynamic dynamic =
                new ConstantDynamic("counter", "I", CONDY_BOOTSTRAP, FIELD_HANDLE);
        List<String> residuals = residuals(classWithMethod(method ->
                method.visitLdcInsn(dynamic)));

        assertEquals(1, residuals.size());
        assertTrue(residuals.get(0).contains("kind=reference"));
    }

    @Test
    void detectsImplicitOwnerGetStaticFinalConstantDynamic() throws Exception {
        ConstantDynamic dynamic = new ConstantDynamic(
                APPROVED.name(),
                APPROVED.descriptor(),
                GET_STATIC_FINAL_SELF_BOOTSTRAP);
        List<String> residuals = residuals(classWithMethod(
                OWNER,
                method -> method.visitLdcInsn(dynamic)));

        assertEquals(1, residuals.size());
        assertTrue(residuals.get(0).contains("kind=reference"));
    }

    @Test
    void detectsExplicitOwnerGetStaticFinalConstantDynamic() throws Exception {
        ConstantDynamic dynamic = new ConstantDynamic(
                APPROVED.name(),
                APPROVED.descriptor(),
                GET_STATIC_FINAL_OWNER_BOOTSTRAP,
                Type.getObjectType(OWNER));
        List<String> residuals = residuals(classWithMethod(method ->
                method.visitLdcInsn(dynamic)));

        assertEquals(1, residuals.size());
        assertTrue(residuals.get(0).contains("kind=reference"));
    }

    @Test
    void detectsStaticFieldVarHandleConstantDynamic() throws Exception {
        ConstantDynamic dynamic = new ConstantDynamic(
                APPROVED.name(),
                "Ljava/lang/invoke/VarHandle;",
                STATIC_FIELD_VAR_HANDLE_BOOTSTRAP,
                Type.getObjectType(OWNER),
                Type.INT_TYPE);
        assertEquals(
                Optional.of(APPROVED),
                new ConstantDynamicFieldReferenceResolver()
                        .resolve("pkg/Carrier", dynamic)
                        .target());
        ConstantDynamic roundTripped = roundTripConstantDynamic(
                classWithMethod(method -> method.visitLdcInsn(dynamic)));
        assertEquals(
                Optional.of(APPROVED),
                new ConstantDynamicFieldReferenceResolver()
                        .resolve("pkg/Carrier", roundTripped)
                        .target());
        List<String> residuals = residuals(classWithMethod(method ->
                method.visitLdcInsn(dynamic)));

        assertEquals(1, residuals.size());
        assertTrue(residuals.get(0).contains("kind=reference"));
    }

    @Test
    void detectsFieldVarHandleConstantDynamic() throws Exception {
        ConstantDynamic dynamic = new ConstantDynamic(
                APPROVED.name(),
                "Ljava/lang/invoke/VarHandle;",
                FIELD_VAR_HANDLE_BOOTSTRAP,
                Type.getObjectType(OWNER),
                Type.INT_TYPE);
        assertEquals(
                Optional.of(APPROVED),
                new ConstantDynamicFieldReferenceResolver()
                        .resolve("pkg/Carrier", dynamic)
                        .target());
        List<String> residuals = residuals(classWithMethod(method ->
                method.visitLdcInsn(dynamic)));

        assertEquals(1, residuals.size());
        assertTrue(residuals.get(0).contains("kind=reference"));
    }

    @Test
    void unresolvedFieldBootstrapFailsClosedWithoutLeakingFieldIdentity() throws Exception {
        ConstantDynamic dynamic = new ConstantDynamic(
                APPROVED.name(),
                APPROVED.descriptor(),
                MALFORMED_GET_STATIC_FINAL_BOOTSTRAP,
                "unknown-owner-shape");
        List<String> residuals = residuals(classWithMethod(method ->
                method.visitLdcInsn(dynamic)));

        assertEquals(1, residuals.size());
        assertTrue(residuals.get(0).contains("kind=unresolved-field-bootstrap"));
        assertTrue(residuals.get(0).contains("fieldIdHash=global"));
        assertTrue(!residuals.get(0).contains(APPROVED.fieldKey()));
    }

    @Test
    void acceptsJarWithNoApprovedDeclarationOrReference() throws Exception {
        Handle unrelated = new Handle(H_GETSTATIC, "pkg/Other", "counter", "I", false);
        byte[] clean = classWithMethod(method -> {
            method.visitLdcInsn(unrelated);
            method.visitInsn(POP);
        });

        assertTrue(residuals(clean).isEmpty());
    }

    private List<String> residuals(byte[] classBytes) throws Exception {
        Path jarPath = temp.resolve("fixture-" + Integer.toUnsignedString(java.util.Arrays.hashCode(classBytes)) + ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jarPath))) {
            JarEntry entry = new JarEntry(OWNER + ".class");
            entry.setTime(0L);
            output.putNextEntry(entry);
            output.write(classBytes);
            output.closeEntry();
        }
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            return verifier.residuals(jar, approvedPlan());
        }
    }

    private byte[] classWithDeclaration() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V17, ACC_PUBLIC, OWNER, null, "java/lang/Object", null);
        writer.visitField(ACC_PRIVATE | ACC_STATIC, APPROVED.name(), APPROVED.descriptor(), null, null)
                .visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] classWithMethod(java.util.function.Consumer<MethodVisitor> body) {
        return classWithMethod("pkg/Carrier", body);
    }

    private byte[] classWithMethod(
            String owner,
            java.util.function.Consumer<MethodVisitor> body) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC, owner, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "()V", null, null);
        method.visitCode();
        body.accept(method);
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private ConstantDynamic roundTripConstantDynamic(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        return node.methods.stream()
                .flatMap(method -> java.util.stream.StreamSupport.stream(
                        method.instructions.spliterator(),
                        false))
                .filter(LdcInsnNode.class::isInstance)
                .map(LdcInsnNode.class::cast)
                .map(ldc -> ldc.cst)
                .filter(ConstantDynamic.class::isInstance)
                .map(ConstantDynamic.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private NativeFieldInternalizationPlan approvedPlan() {
        return plan(List.of(nativeStored(
                APPROVED,
                "j2ll_nfs_00112233445566778899aabbccddeeff",
                List.of())));
    }
}
