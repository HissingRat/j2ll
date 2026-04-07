package xyz.melodysky.packaging;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LoaderAssemblerTest {

    @Test
    public void testCreatesEmbeddedLoaderWithCustomizedNativeDir() throws IOException {
        byte[] loaderBytes = new LoaderAssembler().createLoaderClass("native0", null, Opcodes.V21);
        ClassNode classNode = readClass(loaderBytes);

        assertEquals("native0/Loader", classNode.name);
        assertEquals(Opcodes.V21, classNode.version);
        MethodNode clinit = classNode.methods.stream()
                .filter(method -> method.name.equals("<clinit>"))
                .findFirst()
                .orElseThrow();
        assertTrue(containsStringConstant(clinit, "native0"));
    }

    @Test
    public void testCreatesPlainLoaderWithCustomizedLibraryName() throws IOException {
        byte[] loaderBytes = new LoaderAssembler().createLoaderClass("native0", "custom-lib", Opcodes.V17);
        ClassNode classNode = readClass(loaderBytes);

        assertEquals("native0/Loader", classNode.name);
        assertEquals(Opcodes.V17, classNode.version);
        MethodNode clinit = classNode.methods.stream()
                .filter(method -> method.name.equals("<clinit>"))
                .findFirst()
                .orElseThrow();
        assertTrue(containsStringConstant(clinit, "custom-lib"));
    }

    private ClassNode readClass(byte[] bytes) {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(classNode, 0);
        return classNode;
    }

    private boolean containsStringConstant(MethodNode methodNode, String expectedValue) {
        for (AbstractInsnNode instruction = methodNode.instructions.getFirst();
             instruction != null;
             instruction = instruction.getNext()) {
            if (instruction instanceof LdcInsnNode ldcInsnNode && expectedValue.equals(ldcInsnNode.cst)) {
                return true;
            }
        }
        return false;
    }
}
