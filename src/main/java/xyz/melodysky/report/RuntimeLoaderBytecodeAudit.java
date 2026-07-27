package xyz.melodysky.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Rejects class-definition and retired bytecode-carrier surfaces in generated Loader bytecode. */
final class RuntimeLoaderBytecodeAudit {
    Result inspect(byte[] classBytes) {
        Objects.requireNonNull(classBytes, "classBytes");
        ClassReader reader = new ClassReader(classBytes);
        ArrayList<String> forbidden = new ArrayList<>();
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions) {
                if (retiredMethodName(name)) {
                    forbidden.add("method:" + name + descriptor);
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(
                            int opcode,
                            String owner,
                            String invokedName,
                            String invokedDescriptor,
                            boolean isInterface) {
                        if (classDefinitionCall(owner, invokedName)) {
                            forbidden.add(
                                    "call:" + owner + "." + invokedName
                                            + invokedDescriptor);
                        }
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof String text
                                && retiredConstant(text)) {
                            forbidden.add("constant:" + text);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return new Result(
                reader.getClassName(),
                reader.readUnsignedShort(6),
                forbidden.stream().distinct().sorted().toList());
    }

    private boolean retiredMethodName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("fallback")
                || lower.contains("embeddedclassblob")
                || lower.equals("decodeclassblob");
    }

    private boolean classDefinitionCall(String owner, String name) {
        if (name.equals("defineClass")
                || name.startsWith("defineHiddenClass")) {
            return true;
        }
        return owner.equals("java/lang/invoke/MethodHandles$Lookup")
                && name.startsWith("define");
    }

    private boolean retiredConstant(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("nativeembeddedclassblob")
                || lower.contains("fallbackblob")
                || lower.contains("j2llfallback")
                || lower.contains("definehiddenfallback");
    }

    record Result(
            String internalName,
            int majorVersion,
            List<String> forbiddenSurfaces) {
        Result {
            Objects.requireNonNull(internalName, "internalName");
            forbiddenSurfaces =
                    List.copyOf(Objects.requireNonNull(
                            forbiddenSurfaces,
                            "forbiddenSurfaces"));
        }
    }
}
