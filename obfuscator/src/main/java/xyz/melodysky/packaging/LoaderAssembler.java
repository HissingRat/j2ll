package xyz.melodysky.packaging;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import xyz.melodysky.compiletime.LoaderPlain;
import xyz.melodysky.compiletime.LoaderUnpack;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

public class LoaderAssembler {

    private static final String NATIVE_DIR_PLACEHOLDER = "%NATIVE_DIR%";
    private static final String LIB_NAME_PLACEHOLDER = "%LIB_NAME%";

    public byte[] createLoaderClass(String nativeDir, String plainLibName, int targetClassVersion) throws IOException {
        ClassNode loaderClass = readLoaderTemplate(plainLibName == null);
        customizeLoaderClass(loaderClass, nativeDir, plainLibName);
        loaderClass.version = targetClassVersion;

        String originalLoaderClassName = loaderClass.name;
        String generatedLoaderClassName = nativeDir + "/Loader";
        ClassNode remappedClass = new ClassNode(Opcodes.ASM9);
        loaderClass.accept(new ClassRemapper(remappedClass, new Remapper() {
            @Override
            public String map(String internalName) {
                return internalName.equals(originalLoaderClassName) ? generatedLoaderClassName : internalName;
            }
        }));

        ClassWriter classWriter = new ClassWriter(0);
        remappedClass.accept(classWriter);
        return classWriter.toByteArray();
    }

    private ClassNode readLoaderTemplate(boolean unpackLoader) throws IOException {
        Class<?> loaderClass = unpackLoader ? LoaderUnpack.class : LoaderPlain.class;
        try (InputStream input = loaderClass.getResourceAsStream(loaderClass.getSimpleName() + ".class")) {
            Objects.requireNonNull(input, "Missing loader template bytes for " + loaderClass.getName());
            ClassNode classNode = new ClassNode(Opcodes.ASM9);
            new ClassReader(input).accept(classNode, 0);
            classNode.sourceFile = "synthetic";
            return classNode;
        }
    }

    static void customizeLoaderClass(ClassNode loaderClass, String nativeDir, String plainLibName) {
        loaderClass.methods.forEach(method -> {
            for (int index = 0; index < method.instructions.size(); index++) {
                AbstractInsnNode instruction = method.instructions.get(index);
                if (!(instruction instanceof LdcInsnNode ldcInsnNode) || !(ldcInsnNode.cst instanceof String value)) {
                    continue;
                }

                if (NATIVE_DIR_PLACEHOLDER.equals(value)) {
                    ldcInsnNode.cst = nativeDir;
                    continue;
                }

                if (plainLibName != null && LIB_NAME_PLACEHOLDER.equals(value)) {
                    ldcInsnNode.cst = plainLibName;
                }
            }
        });
    }
}
