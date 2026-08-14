package xyz.melodysky.testsupport.dummy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.objectweb.asm.Attribute;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LocalVariableAnnotationNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.ParameterNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeAnnotationNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Canonical, constant-pool-independent representation of an ASM method's semantic shape.
 *
 * <p>Line numbers, local-variable debug tables and stack-map frames are deliberately ignored. Labels are represented
 * by the executable-instruction boundary at which they occur, so equivalent methods do not depend on label object
 * identity or on redundant co-located labels.
 */
public final class AsmMethodSemanticFingerprint {
    private AsmMethodSemanticFingerprint() {}

    public static String canonicalForm(MethodNode method) {
        Objects.requireNonNull(method, "method");
        CanonicalWriter writer = new CanonicalWriter();
        Map<LabelNode, Integer> labelBoundaries = labelBoundaries(method);

        writer.section("method");
        writer.integer(method.access);
        writer.text(method.name);
        writer.text(method.desc);
        writer.text(method.signature);
        writeStrings(writer, method.exceptions);
        writeParameters(writer, method.parameters);
        writeAnnotationValue(writer, method.annotationDefault);
        writeAnnotations(writer, method.visibleAnnotations);
        writeAnnotations(writer, method.invisibleAnnotations);
        writeTypeAnnotations(writer, method.visibleTypeAnnotations);
        writeTypeAnnotations(writer, method.invisibleTypeAnnotations);
        writer.integer(method.visibleAnnotableParameterCount);
        writer.integer(method.invisibleAnnotableParameterCount);
        writeParameterAnnotations(writer, method.visibleParameterAnnotations);
        writeParameterAnnotations(writer, method.invisibleParameterAnnotations);
        writeAttributes(writer, method.attrs);

        boolean hasCode = hasCode(method);
        writer.section("code");
        writer.bool(hasCode);
        if (hasCode) {
            writeInstructions(writer, method, labelBoundaries);
            writeTryCatchBlocks(writer, method.tryCatchBlocks, labelBoundaries);
            writeLocalVariableAnnotations(writer, method.visibleLocalVariableAnnotations, labelBoundaries);
            writeLocalVariableAnnotations(writer, method.invisibleLocalVariableAnnotations, labelBoundaries);
            writer.integer(method.maxStack);
            writer.integer(method.maxLocals);
        }
        return writer.toString();
    }

    public static String sha256(MethodNode method) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalForm(method).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static boolean hasCode(MethodNode method) {
        if (method.instructions != null) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof LabelNode)
                        && !(instruction instanceof LineNumberNode)
                        && !(instruction instanceof FrameNode)) {
                    return true;
                }
            }
        }
        return method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty();
    }

    private static Map<LabelNode, Integer> labelBoundaries(MethodNode method) {
        IdentityHashMap<LabelNode, Integer> boundaries = new IdentityHashMap<>();
        if (method.instructions == null) {
            return boundaries;
        }
        int executableIndex = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof LabelNode label) {
                boundaries.put(label, executableIndex);
            } else if (!(instruction instanceof LineNumberNode) && !(instruction instanceof FrameNode)) {
                executableIndex++;
            }
        }
        return boundaries;
    }

    private static void writeInstructions(
            CanonicalWriter writer,
            MethodNode method,
            Map<LabelNode, Integer> labelBoundaries) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof LabelNode)
                    && !(instruction instanceof LineNumberNode)
                    && !(instruction instanceof FrameNode)) {
                count++;
            }
        }
        writer.section("instructions");
        writer.integer(count);
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof LabelNode
                    || instruction instanceof LineNumberNode
                    || instruction instanceof FrameNode) {
                continue;
            }
            writeInstruction(writer, instruction, labelBoundaries);
            writeTypeAnnotations(writer, instruction.visibleTypeAnnotations);
            writeTypeAnnotations(writer, instruction.invisibleTypeAnnotations);
        }
    }

    private static void writeInstruction(
            CanonicalWriter writer,
            AbstractInsnNode instruction,
            Map<LabelNode, Integer> labelBoundaries) {
        writer.section("instruction");
        writer.integer(instruction.getOpcode());
        if (instruction instanceof InsnNode) {
            writer.section("no-operand");
        } else if (instruction instanceof IntInsnNode node) {
            writer.section("int");
            writer.integer(node.operand);
        } else if (instruction instanceof VarInsnNode node) {
            writer.section("var");
            writer.integer(node.var);
        } else if (instruction instanceof TypeInsnNode node) {
            writer.section("type");
            writer.text(node.desc);
        } else if (instruction instanceof FieldInsnNode node) {
            writer.section("field");
            writer.text(node.owner);
            writer.text(node.name);
            writer.text(node.desc);
        } else if (instruction instanceof MethodInsnNode node) {
            writer.section("method-call");
            writer.text(node.owner);
            writer.text(node.name);
            writer.text(node.desc);
            writer.bool(node.itf);
        } else if (instruction instanceof InvokeDynamicInsnNode node) {
            writer.section("invokedynamic");
            writer.text(node.name);
            writer.text(node.desc);
            writeConstant(writer, node.bsm);
            writer.integer(node.bsmArgs.length);
            for (Object argument : node.bsmArgs) {
                writeConstant(writer, argument);
            }
        } else if (instruction instanceof JumpInsnNode node) {
            writer.section("jump");
            writer.integer(labelBoundary(node.label, labelBoundaries));
        } else if (instruction instanceof LdcInsnNode node) {
            writer.section("ldc");
            writeConstant(writer, node.cst);
        } else if (instruction instanceof IincInsnNode node) {
            writer.section("iinc");
            writer.integer(node.var);
            writer.integer(node.incr);
        } else if (instruction instanceof TableSwitchInsnNode node) {
            writer.section("table-switch");
            writer.integer(node.min);
            writer.integer(node.max);
            writer.integer(labelBoundary(node.dflt, labelBoundaries));
            writeLabels(writer, node.labels, labelBoundaries);
        } else if (instruction instanceof LookupSwitchInsnNode node) {
            writer.section("lookup-switch");
            writer.integer(labelBoundary(node.dflt, labelBoundaries));
            writer.integer(node.keys.size());
            for (Integer key : node.keys) {
                writer.integer(key);
            }
            writeLabels(writer, node.labels, labelBoundaries);
        } else if (instruction instanceof MultiANewArrayInsnNode node) {
            writer.section("multianewarray");
            writer.text(node.desc);
            writer.integer(node.dims);
        } else {
            throw new IllegalArgumentException(
                    "unsupported ASM instruction node in semantic fingerprint: " + instruction.getClass().getName());
        }
    }

    private static void writeTryCatchBlocks(
            CanonicalWriter writer,
            List<TryCatchBlockNode> blocks,
            Map<LabelNode, Integer> labelBoundaries) {
        writer.section("try-catch");
        writer.integer(blocks == null ? 0 : blocks.size());
        if (blocks == null) {
            return;
        }
        for (TryCatchBlockNode block : blocks) {
            writer.integer(labelBoundary(block.start, labelBoundaries));
            writer.integer(labelBoundary(block.end, labelBoundaries));
            writer.integer(labelBoundary(block.handler, labelBoundaries));
            writer.text(block.type);
            writeTypeAnnotations(writer, block.visibleTypeAnnotations);
            writeTypeAnnotations(writer, block.invisibleTypeAnnotations);
        }
    }

    private static void writeLocalVariableAnnotations(
            CanonicalWriter writer,
            List<LocalVariableAnnotationNode> annotations,
            Map<LabelNode, Integer> labelBoundaries) {
        writer.section("local-variable-type-annotations");
        writer.integer(annotations == null ? 0 : annotations.size());
        if (annotations == null) {
            return;
        }
        for (LocalVariableAnnotationNode annotation : annotations) {
            writeTypeAnnotation(writer, annotation);
            writer.integer(annotation.start.size());
            for (int index = 0; index < annotation.start.size(); index++) {
                writer.integer(labelBoundary(annotation.start.get(index), labelBoundaries));
                writer.integer(labelBoundary(annotation.end.get(index), labelBoundaries));
                writer.integer(annotation.index.get(index));
            }
        }
    }

    private static void writeLabels(
            CanonicalWriter writer,
            List<LabelNode> labels,
            Map<LabelNode, Integer> labelBoundaries) {
        writer.integer(labels.size());
        for (LabelNode label : labels) {
            writer.integer(labelBoundary(label, labelBoundaries));
        }
    }

    private static int labelBoundary(LabelNode label, Map<LabelNode, Integer> labelBoundaries) {
        Integer boundary = labelBoundaries.get(label);
        if (boundary == null) {
            throw new IllegalArgumentException("semantic label is not present in the method instruction list");
        }
        return boundary;
    }

    private static void writeParameters(CanonicalWriter writer, List<ParameterNode> parameters) {
        writer.section("parameters");
        writer.integer(parameters == null ? 0 : parameters.size());
        if (parameters == null) {
            return;
        }
        for (ParameterNode parameter : parameters) {
            writer.text(parameter.name);
            writer.integer(parameter.access);
        }
    }

    private static void writeStrings(CanonicalWriter writer, List<String> values) {
        writer.integer(values == null ? 0 : values.size());
        if (values != null) {
            values.forEach(writer::text);
        }
    }

    private static void writeAnnotations(CanonicalWriter writer, List<AnnotationNode> annotations) {
        writer.section("annotations");
        writer.integer(annotations == null ? 0 : annotations.size());
        if (annotations != null) {
            annotations.forEach(annotation -> writeAnnotation(writer, annotation));
        }
    }

    private static void writeAnnotation(CanonicalWriter writer, AnnotationNode annotation) {
        writer.text(annotation.desc);
        writer.integer(annotation.values == null ? 0 : annotation.values.size());
        if (annotation.values != null) {
            for (Object value : annotation.values) {
                writeAnnotationValue(writer, value);
            }
        }
    }

    private static void writeTypeAnnotations(CanonicalWriter writer, List<TypeAnnotationNode> annotations) {
        writer.section("type-annotations");
        writer.integer(annotations == null ? 0 : annotations.size());
        if (annotations != null) {
            annotations.forEach(annotation -> writeTypeAnnotation(writer, annotation));
        }
    }

    private static void writeTypeAnnotation(CanonicalWriter writer, TypeAnnotationNode annotation) {
        writer.integer(annotation.typeRef);
        TypePath path = annotation.typePath;
        writer.text(path == null ? null : path.toString());
        writeAnnotation(writer, annotation);
    }

    private static void writeParameterAnnotations(CanonicalWriter writer, List<AnnotationNode>[] annotations) {
        writer.section("parameter-annotations");
        writer.integer(annotations == null ? 0 : annotations.length);
        if (annotations != null) {
            for (List<AnnotationNode> parameterAnnotations : annotations) {
                writeAnnotations(writer, parameterAnnotations);
            }
        }
    }

    private static void writeAttributes(CanonicalWriter writer, List<Attribute> attributes) {
        writer.section("attributes");
        writer.integer(attributes == null ? 0 : attributes.size());
        if (attributes != null && !attributes.isEmpty()) {
            throw new IllegalArgumentException(
                    "custom method attributes cannot be proven preserved: "
                            + attributes.stream().map(attribute -> attribute.type).toList());
        }
    }

    private static void writeAnnotationValue(CanonicalWriter writer, Object value) {
        writer.section("annotation-value");
        if (value instanceof AnnotationNode annotation) {
            writer.section("nested-annotation");
            writeAnnotation(writer, annotation);
        } else if (value instanceof List<?> list) {
            writer.section("list");
            writer.integer(list.size());
            list.forEach(element -> writeAnnotationValue(writer, element));
        } else if (value instanceof String[] enumValue) {
            writer.section("enum");
            writer.integer(enumValue.length);
            for (String element : enumValue) {
                writer.text(element);
            }
        } else {
            writeConstant(writer, value);
        }
    }

    private static void writeConstant(CanonicalWriter writer, Object value) {
        if (value == null) {
            writer.section("null");
        } else if (value instanceof Boolean booleanValue) {
            writer.section("boolean");
            writer.bool(booleanValue);
        } else if (value instanceof Byte byteValue) {
            writer.section("byte");
            writer.integer(byteValue);
        } else if (value instanceof Short shortValue) {
            writer.section("short");
            writer.integer(shortValue);
        } else if (value instanceof Character characterValue) {
            writer.section("char");
            writer.integer(characterValue);
        } else if (value instanceof Integer integerValue) {
            writer.section("int");
            writer.integer(integerValue);
        } else if (value instanceof Long longValue) {
            writer.section("long");
            writer.longInteger(longValue);
        } else if (value instanceof Float floatValue) {
            writer.section("float-bits");
            writer.integer(Float.floatToRawIntBits(floatValue));
        } else if (value instanceof Double doubleValue) {
            writer.section("double-bits");
            writer.longInteger(Double.doubleToRawLongBits(doubleValue));
        } else if (value instanceof String stringValue) {
            writer.section("string");
            writer.text(stringValue);
        } else if (value instanceof Type type) {
            writer.section("type-constant");
            writer.integer(type.getSort());
            writer.text(type.getDescriptor());
        } else if (value instanceof Handle handle) {
            writer.section("handle");
            writer.integer(handle.getTag());
            writer.text(handle.getOwner());
            writer.text(handle.getName());
            writer.text(handle.getDesc());
            writer.bool(handle.isInterface());
        } else if (value instanceof ConstantDynamic dynamic) {
            writer.section("constant-dynamic");
            writer.text(dynamic.getName());
            writer.text(dynamic.getDescriptor());
            writeConstant(writer, dynamic.getBootstrapMethod());
            writer.integer(dynamic.getBootstrapMethodArgumentCount());
            for (int index = 0; index < dynamic.getBootstrapMethodArgumentCount(); index++) {
                writeConstant(writer, dynamic.getBootstrapMethodArgument(index));
            }
        } else {
            throw new IllegalArgumentException(
                    "unsupported ASM constant in semantic fingerprint: " + value.getClass().getName());
        }
    }

    private static final class CanonicalWriter {
        private final StringBuilder value = new StringBuilder();

        void section(String section) {
            text(section);
        }

        void text(String text) {
            if (text == null) {
                value.append("-1:;");
            } else {
                value.append(text.length()).append(':').append(text).append(';');
            }
        }

        void integer(int integer) {
            value.append('i').append(integer).append(';');
        }

        void longInteger(long integer) {
            value.append('l').append(integer).append(';');
        }

        void bool(boolean booleanValue) {
            value.append(booleanValue ? "b1;" : "b0;");
        }

        @Override
        public String toString() {
            return value.toString();
        }
    }
}
