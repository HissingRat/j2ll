package xyz.melodysky.packaging;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodNode;
import xyz.melodysky.toolchain.NativeLibraryArtifact;

public final class NativeLoaderClassGenerator implements Opcodes {
    private static final String TEMPLATE_INTERNAL_NAME =
            "xyz/melodysky/runtime/loader/LoaderTemplate";
    private static final String TEMPLATE_RESOURCE = TEMPLATE_INTERNAL_NAME + ".class";
    public byte[] generate(
            RuntimeLoaderPlan plan,
            List<NativeLibraryArtifact> artifacts) throws IOException {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(artifacts, "artifacts");
        ClassNode loader = relocateTemplate(plan.internalName());
        replaceEnsureLoaded(loader, plan.internalName(), artifacts);
        new LoaderClassValueSidecarInjector().inject(
                loader,
                plan.referenceSidecarSize());
        stripDebugMetadata(loader);
        loader.version = V17;

        ClassWriter writer = new LoaderClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS,
                plan.internalName());
        loader.accept(writer);
        byte[] bytes = writer.toByteArray();
        ClassReader result = new ClassReader(bytes);
        if (result.readUnsignedShort(6) != V17 || !result.getClassName().equals(plan.internalName())) {
            throw new IOException("generated Loader.class identity/version mismatch");
        }
        return bytes;
    }

    private ClassNode relocateTemplate(String loaderInternalName) throws IOException {
        ClassReader reader;
        ClassLoader classLoader = NativeLoaderClassGenerator.class.getClassLoader();
        try (InputStream input = classLoader.getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (input == null) {
                throw new IOException("missing Java 17 Loader template: " + TEMPLATE_RESOURCE);
            }
            reader = new ClassReader(input);
        }
        if (reader.readUnsignedShort(6) != V17) {
            throw new IOException("Loader template must be Java 17 classfile");
        }
        ClassNode relocated = new ClassNode();
        reader.accept(
                new ClassRemapper(
                        relocated,
                        new SimpleRemapper(Map.of(TEMPLATE_INTERNAL_NAME, loaderInternalName))),
                ClassReader.EXPAND_FRAMES);
        return relocated;
    }

    private void replaceEnsureLoaded(
            ClassNode loader,
            String loaderInternalName,
            List<NativeLibraryArtifact> artifacts) throws IOException {
        MethodNode method = loader.methods.stream()
                .filter(candidate -> candidate.name.equals("ensureLoaded")
                        && candidate.desc.equals("()V"))
                .findFirst()
                .orElseThrow(() -> new IOException("Loader template has no ensureLoaded()V"));
        method.access = ACC_PUBLIC | ACC_STATIC | ACC_SYNCHRONIZED;
        method.instructions.clear();
        method.tryCatchBlocks.clear();
        method.localVariables = null;
        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;

        Label load = new Label();
        method.visitCode();
        method.visitFieldInsn(GETSTATIC, loaderInternalName, "loaded", "Z");
        method.visitJumpInsn(IFEQ, load);
        method.visitInsn(RETURN);
        method.visitLabel(load);
        method.visitLdcInsn(Type.getObjectType(loaderInternalName));
        emitStringArray(method, artifacts.stream()
                .map(artifact -> artifact.target().directoryName())
                .toList());
        emitStringArray(method, artifacts.stream()
                .map(NativeLibraryArtifact::jarPath)
                .toList());
        emitStringArray(method, artifacts.stream()
                .map(NativeLibraryArtifact::sha256)
                .toList());
        method.visitMethodInsn(
                INVOKESTATIC,
                loaderInternalName,
                "loadForCurrentTarget",
                "(Ljava/lang/Class;[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;)V",
                false);
        method.visitInsn(ICONST_1);
        method.visitFieldInsn(PUTSTATIC, loaderInternalName, "loaded", "Z");
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private void stripDebugMetadata(ClassNode loader) {
        loader.sourceFile = null;
        loader.sourceDebug = null;
        for (MethodNode method : loader.methods) {
            method.localVariables = null;
            method.visibleLocalVariableAnnotations = null;
            method.invisibleLocalVariableAnnotations = null;
            List<AbstractInsnNode> lineNumbers = new ArrayList<>();
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                    instruction != null;
                    instruction = instruction.getNext()) {
                if (instruction instanceof LineNumberNode) {
                    lineNumbers.add(instruction);
                }
            }
            lineNumbers.forEach(method.instructions::remove);
        }
    }

    private void emitStringArray(MethodNode method, List<String> values) {
        pushInt(method, values.size());
        method.visitTypeInsn(ANEWARRAY, "java/lang/String");
        for (int index = 0; index < values.size(); index++) {
            method.visitInsn(DUP);
            pushInt(method, index);
            method.visitLdcInsn(values.get(index));
            method.visitInsn(AASTORE);
        }
    }

    private void pushInt(MethodNode method, int value) {
        if (value >= 0 && value <= 5) {
            method.visitInsn(ICONST_0 + value);
        } else if (value <= Byte.MAX_VALUE) {
            method.visitIntInsn(BIPUSH, value);
        } else {
            method.visitIntInsn(SIPUSH, value);
        }
    }

    private static final class LoaderClassWriter extends ClassWriter {
        private final String loaderInternalName;

        private LoaderClassWriter(int flags, String loaderInternalName) {
            super(flags);
            this.loaderInternalName = loaderInternalName;
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            if (type1.equals(type2)) {
                return type1;
            }
            if (type1.equals(loaderInternalName) || type2.equals(loaderInternalName)) {
                return "java/lang/Object";
            }
            try {
                return super.getCommonSuperClass(type1, type2);
            } catch (TypeNotPresentException | LinkageError exception) {
                return "java/lang/Object";
            }
        }
    }
}
