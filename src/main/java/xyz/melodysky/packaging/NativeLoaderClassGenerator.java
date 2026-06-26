package xyz.melodysky.packaging;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import xyz.melodysky.runtime.loader.J2llNativeLoaderSupport;
import xyz.melodysky.toolchain.NativeLibraryArtifact;

import java.util.List;

public final class NativeLoaderClassGenerator implements Opcodes {
    public byte[] generate(String loaderInternalName, String libraryResourcePath, String expectedSha256) {
        return generate(loaderInternalName, libraryResourcePath, expectedSha256, null);
    }

    public byte[] generate(
            String loaderInternalName,
            String libraryResourcePath,
            String expectedSha256,
            String targetDirectoryName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, loaderInternalName, null, "java/lang/Object", null);
        writer.visitField(ACC_PRIVATE | ACC_STATIC | ACC_VOLATILE, "loaded", "Z", null, null).visitEnd();
        emitConstructor(writer);
        emitEnsureLoaded(writer, loaderInternalName, libraryResourcePath, expectedSha256, targetDirectoryName);
        writer.visitEnd();
        return writer.toByteArray();
    }

    public byte[] generate(String loaderInternalName, List<NativeLibraryArtifact> artifacts) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, loaderInternalName, null, "java/lang/Object", null);
        writer.visitField(ACC_PRIVATE | ACC_STATIC | ACC_VOLATILE, "loaded", "Z", null, null).visitEnd();
        emitConstructor(writer);
        emitEnsureLoadedForTargets(writer, loaderInternalName, artifacts);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private void emitConstructor(ClassWriter writer) {
        MethodVisitor constructor = writer.visitMethod(ACC_PRIVATE, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
    }

    private void emitEnsureLoaded(
            ClassWriter writer,
            String loaderInternalName,
            String libraryResourcePath,
            String expectedSha256,
            String targetDirectoryName) {
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC | ACC_SYNCHRONIZED,
                "ensureLoaded",
                "()V",
                null,
                null);
        Label load = new Label();
        method.visitCode();
        method.visitFieldInsn(GETSTATIC, loaderInternalName, "loaded", "Z");
        method.visitJumpInsn(IFEQ, load);
        method.visitInsn(RETURN);
        method.visitLabel(load);
        method.visitLdcInsn(Type.getObjectType(loaderInternalName));
        method.visitLdcInsn(libraryResourcePath);
        method.visitLdcInsn(expectedSha256);
        String methodName = "load";
        String descriptor = "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;)V";
        if (targetDirectoryName != null) {
            method.visitLdcInsn(targetDirectoryName);
            methodName = "loadHostOnly";
            descriptor = "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V";
        }
        method.visitMethodInsn(
                INVOKESTATIC,
                J2llNativeLoaderSupport.class.getName().replace('.', '/'),
                methodName,
                descriptor,
                false);
        method.visitInsn(ICONST_1);
        method.visitFieldInsn(PUTSTATIC, loaderInternalName, "loaded", "Z");
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private void emitEnsureLoadedForTargets(
            ClassWriter writer,
            String loaderInternalName,
            List<NativeLibraryArtifact> artifacts) {
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC | ACC_SYNCHRONIZED,
                "ensureLoaded",
                "()V",
                null,
                null);
        Label load = new Label();
        method.visitCode();
        method.visitFieldInsn(GETSTATIC, loaderInternalName, "loaded", "Z");
        method.visitJumpInsn(IFEQ, load);
        method.visitInsn(RETURN);
        method.visitLabel(load);
        method.visitLdcInsn(Type.getObjectType(loaderInternalName));
        emitStringArray(method, artifacts.stream().map(artifact -> artifact.target().directoryName()).toList());
        emitStringArray(method, artifacts.stream().map(NativeLibraryArtifact::jarPath).toList());
        emitStringArray(method, artifacts.stream().map(NativeLibraryArtifact::sha256).toList());
        method.visitMethodInsn(
                INVOKESTATIC,
                J2llNativeLoaderSupport.class.getName().replace('.', '/'),
                "loadForCurrentTarget",
                "(Ljava/lang/Class;[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;)V",
                false);
        method.visitInsn(ICONST_1);
        method.visitFieldInsn(PUTSTATIC, loaderInternalName, "loaded", "Z");
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private void emitStringArray(MethodVisitor method, List<String> values) {
        pushInt(method, values.size());
        method.visitTypeInsn(ANEWARRAY, "java/lang/String");
        for (int index = 0; index < values.size(); index++) {
            method.visitInsn(DUP);
            pushInt(method, index);
            method.visitLdcInsn(values.get(index));
            method.visitInsn(AASTORE);
        }
    }

    private void pushInt(MethodVisitor method, int value) {
        if (value >= 0 && value <= 5) {
            method.visitInsn(ICONST_0 + value);
        } else if (value <= Byte.MAX_VALUE) {
            method.visitIntInsn(BIPUSH, value);
        } else {
            method.visitIntInsn(SIPUSH, value);
        }
    }
}
