package xyz.melodysky.runtime.metadata;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.RecordComponentNode;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.frontend.classfile.ParsedProgram;
import xyz.melodysky.jvm.AccessFlags;
import xyz.melodysky.pipeline.StageResult;

public final class RuntimeMetadataIndexBuilder {
    public StageResult<RuntimeMetadataIndex> build(ParsedProgram program) {
        ArrayList<ClassMetadata> classes = new ArrayList<>();
        for (ParsedClass parsedClass : program.classes()) {
            classes.add(toClassMetadata(parsedClass));
        }
        RuntimeMetadataIndex index = new RuntimeMetadataIndex(classes);
        List<Diagnostic> diagnostics = new RuntimeMetadataValidator().validate(index);
        if (diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity().wireName().equals("error"))) {
            return StageResult.failed(DiagnosticStage.RUNTIME_ANALYSIS, diagnostics);
        }
        return StageResult.complete(DiagnosticStage.RUNTIME_ANALYSIS, index, diagnostics);
    }

    private ClassMetadata toClassMetadata(ParsedClass parsedClass) {
        ClassNode classNode = parsedClass.classNode();
        Map<String, ParsedMethod> parsedMethods = new LinkedHashMap<>();
        for (ParsedMethod method : parsedClass.methods()) {
            parsedMethods.put(method.name() + "!" + method.descriptor(), method);
        }

        ArrayList<FieldMetadata> fields = new ArrayList<>();
        for (FieldNode field : classNode.fields) {
            fields.add(toFieldMetadata(parsedClass.internalName(), field));
        }

        boolean recordClass = isRecordClass(classNode);
        ArrayList<MethodMetadata> methods = new ArrayList<>();
        for (MethodNode method : classNode.methods) {
            ParsedMethod parsedMethod = parsedMethods.get(method.name + "!" + method.desc);
            methods.add(toMethodMetadata(parsedClass.internalName(), method, parsedMethod, recordClass, classNode));
        }

        return new ClassMetadata(
                parsedClass.internalName(),
                binaryName(parsedClass.internalName()),
                accessFlagNames(FlagTarget.CLASS, classNode.access),
                compilerFlags(FlagTarget.CLASS, classNode.access, recordClass, false, false),
                parsedClass.majorVersion(),
                parsedClass.minorVersion(),
                parsedClass.superName(),
                parsedClass.interfaces(),
                SignatureMetadata.of(classNode.signature),
                annotations(classNode.visibleAnnotations, classNode.invisibleAnnotations),
                fields,
                methods,
                recordMetadata(classNode),
                nestMetadata(classNode),
                innerClasses(classNode),
                classInitMetadata(parsedClass));
    }

    private FieldMetadata toFieldMetadata(String owner, FieldNode field) {
        return new FieldMetadata(
                owner,
                field.name,
                field.desc,
                accessFlagNames(FlagTarget.FIELD, field.access),
                compilerFlags(FlagTarget.FIELD, field.access, false, false, false),
                SignatureMetadata.of(field.signature),
                annotations(field.visibleAnnotations, field.invisibleAnnotations));
    }

    private MethodMetadata toMethodMetadata(
            String owner,
            MethodNode method,
            ParsedMethod parsedMethod,
            boolean recordClass,
            ClassNode classNode) {
        boolean recordGenerated = recordClass && isRecordGeneratedMethod(method, classNode);
        boolean hasCode = parsedMethod != null
                ? parsedMethod.hasCode()
                : !new AccessFlags(method.access).isAbstract()
                        && !new AccessFlags(method.access).isNative()
                        && method.instructions != null
                        && method.instructions.size() > 0;
        return new MethodMetadata(
                owner,
                method.name,
                method.desc,
                accessFlagNames(FlagTarget.METHOD, method.access),
                compilerFlags(FlagTarget.METHOD, method.access, false, recordGenerated, false),
                SignatureMetadata.of(method.signature),
                annotations(method.visibleAnnotations, method.invisibleAnnotations),
                method.exceptions == null ? List.of() : method.exceptions,
                hasCode);
    }

    private RecordMetadata recordMetadata(ClassNode classNode) {
        if (!isRecordClass(classNode)) {
            return RecordMetadata.nonRecord();
        }
        ArrayList<RecordComponentMetadata> components = new ArrayList<>();
        if (classNode.recordComponents != null) {
            for (RecordComponentNode component : classNode.recordComponents) {
                components.add(new RecordComponentMetadata(
                        component.name,
                        component.descriptor,
                        SignatureMetadata.of(component.signature),
                        annotations(component.visibleAnnotations, component.invisibleAnnotations)));
            }
        }
        return new RecordMetadata(true, components);
    }

    private NestMetadata nestMetadata(ClassNode classNode) {
        List<String> nestMembers = classNode.nestMembers == null ? List.of() : classNode.nestMembers;
        return new NestMetadata(
                classNode.nestHostClass,
                nestMembers,
                classNode.outerClass,
                classNode.outerMethod,
                classNode.outerMethodDesc);
    }

    private List<InnerClassMetadata> innerClasses(ClassNode classNode) {
        if (classNode.innerClasses == null) {
            return List.of();
        }
        ArrayList<InnerClassMetadata> innerClasses = new ArrayList<>();
        for (InnerClassNode innerClass : classNode.innerClasses) {
            innerClasses.add(new InnerClassMetadata(
                    innerClass.name,
                    innerClass.outerName,
                    innerClass.innerName,
                    accessFlagNames(FlagTarget.CLASS, innerClass.access),
                    compilerFlags(FlagTarget.CLASS, innerClass.access, (innerClass.access & Opcodes.ACC_RECORD) != 0, false, false)));
        }
        return innerClasses;
    }

    private ClassInitMetadata classInitMetadata(ParsedClass parsedClass) {
        boolean hasClassInitializer = parsedClass.methods().stream()
                .anyMatch(method -> method.name().equals("<clinit>") && method.descriptor().equals("()V") && method.hasCode());
        String token = symbolToken(parsedClass.internalName());
        return new ClassInitMetadata(
                hasClassInitializer,
                "j2ll_meta_class_object_" + token,
                "j2ll_meta_class_init_state_" + token);
    }

    private List<AnnotationMetadata> annotations(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        ArrayList<AnnotationMetadata> annotations = new ArrayList<>();
        if (visible != null) {
            for (AnnotationNode annotation : visible) {
                annotations.add(annotation(annotation, true));
            }
        }
        if (invisible != null) {
            for (AnnotationNode annotation : invisible) {
                annotations.add(annotation(annotation, false));
            }
        }
        Collections.sort(annotations);
        return annotations;
    }

    private AnnotationMetadata annotation(AnnotationNode annotation, boolean visible) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        if (annotation.values != null) {
            for (int index = 0; index < annotation.values.size(); index += 2) {
                String name = Objects.toString(annotation.values.get(index));
                Object value = index + 1 < annotation.values.size() ? annotation.values.get(index + 1) : null;
                values.put(name, annotationValue(value));
            }
        }
        return new AnnotationMetadata(annotation.desc, visible, values);
    }

    @SuppressWarnings("unchecked")
    private String annotationValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Type type) {
            return type.getDescriptor();
        }
        if (value instanceof String[] enumValue && enumValue.length == 2) {
            return enumValue[0] + "." + enumValue[1];
        }
        if (value instanceof AnnotationNode annotation) {
            return "@" + annotation.desc;
        }
        if (value instanceof List<?> values) {
            return values.stream().map(this::annotationValue).toList().toString();
        }
        if (value.getClass().isArray()) {
            if (value instanceof Object[] values) {
                return Arrays.stream(values).map(this::annotationValue).toList().toString();
            }
        }
        return String.valueOf(value);
    }

    private boolean isRecordClass(ClassNode classNode) {
        return (classNode.access & Opcodes.ACC_RECORD) != 0
                || (classNode.recordComponents != null && !classNode.recordComponents.isEmpty());
    }

    private boolean isRecordGeneratedMethod(MethodNode method, ClassNode classNode) {
        if (method.name.equals("toString") && method.desc.equals("()Ljava/lang/String;")) {
            return true;
        }
        if (method.name.equals("hashCode") && method.desc.equals("()I")) {
            return true;
        }
        if (method.name.equals("equals") && method.desc.equals("(Ljava/lang/Object;)Z")) {
            return true;
        }
        if (classNode.recordComponents == null) {
            return false;
        }
        return classNode.recordComponents.stream()
                .anyMatch(component -> method.name.equals(component.name) && method.desc.equals("()" + component.descriptor));
    }

    private String binaryName(String internalName) {
        return internalName.replace('/', '.');
    }

    private List<String> accessFlagNames(FlagTarget target, int access) {
        ArrayList<String> names = new ArrayList<>();
        addIf(names, access, Opcodes.ACC_PUBLIC, "public");
        addIf(names, access, Opcodes.ACC_PRIVATE, "private");
        addIf(names, access, Opcodes.ACC_PROTECTED, "protected");
        addIf(names, access, Opcodes.ACC_STATIC, "static");
        addIf(names, access, Opcodes.ACC_FINAL, "final");
        if (target == FlagTarget.METHOD) {
            addIf(names, access, Opcodes.ACC_SYNCHRONIZED, "synchronized");
            addIf(names, access, Opcodes.ACC_BRIDGE, "bridge");
            addIf(names, access, Opcodes.ACC_VARARGS, "varargs");
            addIf(names, access, Opcodes.ACC_NATIVE, "native");
            addIf(names, access, Opcodes.ACC_ABSTRACT, "abstract");
            addIf(names, access, Opcodes.ACC_STRICT, "strict");
        } else if (target == FlagTarget.FIELD) {
            addIf(names, access, Opcodes.ACC_VOLATILE, "volatile");
            addIf(names, access, Opcodes.ACC_TRANSIENT, "transient");
        } else {
            addIf(names, access, Opcodes.ACC_INTERFACE, "interface");
            addIf(names, access, Opcodes.ACC_ABSTRACT, "abstract");
            addIf(names, access, Opcodes.ACC_ANNOTATION, "annotation");
            addIf(names, access, Opcodes.ACC_ENUM, "enum");
            addIf(names, access, Opcodes.ACC_MODULE, "module");
            addIf(names, access, Opcodes.ACC_RECORD, "record");
        }
        addIf(names, access, Opcodes.ACC_SYNTHETIC, "synthetic");
        return names.stream().sorted().distinct().toList();
    }

    private List<String> compilerFlags(
            FlagTarget target,
            int access,
            boolean recordClass,
            boolean recordGenerated,
            boolean unused) {
        ArrayList<String> flags = new ArrayList<>();
        if ((access & Opcodes.ACC_SYNTHETIC) != 0) {
            flags.add("synthetic");
        }
        if (target == FlagTarget.METHOD && (access & Opcodes.ACC_BRIDGE) != 0) {
            flags.add("bridge");
        }
        if ((access & Opcodes.ACC_ENUM) != 0) {
            flags.add("enum");
        }
        if (recordClass) {
            flags.add("record");
        }
        if (recordGenerated) {
            flags.add("recordGenerated");
        }
        return flags.stream().sorted().distinct().toList();
    }

    private void addIf(List<String> names, int access, int mask, String name) {
        if ((access & mask) != 0) {
            names.add(name);
        }
    }

    private String symbolToken(String internalName) {
        String sanitized = internalName.replaceAll("[^A-Za-z0-9_]", "_");
        if (sanitized.length() > 48) {
            sanitized = sanitized.substring(0, 48);
        }
        return sanitized + "_" + shortHash(internalName);
    }

    private String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int index = 0; index < 8; index++) {
                builder.append(String.format("%02x", hash[index]));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private enum FlagTarget {
        CLASS,
        FIELD,
        METHOD
    }
}
