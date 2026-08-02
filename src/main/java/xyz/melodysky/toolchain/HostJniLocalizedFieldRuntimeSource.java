package xyz.melodysky.toolchain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.runtime.RuntimeTokenDomain;
import xyz.melodysky.runtime.RuntimeTokenMapper;
import xyz.melodysky.runtime.jni.RuntimeLocalAbiDomain;

/**
 * Emits one field-access helper per concrete operation and field binding.
 *
 * <p>Metadata is formed inside that helper's activation. There is no global
 * token-to-owner/name/descriptor table and no matrix-wide token resolver.</p>
 */
final class HostJniLocalizedFieldRuntimeSource {
    private HostJniLocalizedFieldRuntimeSource() {}

    static void append(
            StringBuilder builder,
            List<HostJniCSourceGenerator.Binding> bindings,
            RuntimeTokenMapper runtimeTokens) {
        TreeMap<String, Access> accesses = new TreeMap<>();
        bindings.stream()
                .filter(binding ->
                        binding.path() == NativeImplementationPath.LLVM_NATIVE_PATH)
                .flatMap(binding -> binding.implementationIrMethod().stream())
                .flatMap(method -> method.blocks().stream())
                .flatMap(block -> block.instructions().stream())
                .filter(HostJniLocalizedFieldRuntimeSource::isJvmFieldAccess)
                .forEach(instruction -> {
                    Access access = Access.from(instruction);
                    accesses.putIfAbsent(access.identity(), access);
                });
        for (Access access : runtimeTokens.physicalOrder(
                RuntimeTokenDomain.FIELD_RUNTIME,
                List.copyOf(accesses.values()),
                Access::identity)) {
            appendAccess(builder, runtimeTokens, access);
        }
    }

    static String helperSymbol(
            RuntimeTokenMapper runtimeTokens,
            IrInstruction instruction) {
        Access access = Access.from(instruction);
        return runtimeTokens.helperSymbol(
                RuntimeTokenDomain.FIELD_RUNTIME,
                access.operation(),
                access.fieldKey());
    }

    private static boolean isJvmFieldAccess(IrInstruction instruction) {
        return instruction.opcode() == IrOpcode.GET_STATIC
                || instruction.opcode() == IrOpcode.PUT_STATIC
                || instruction.opcode() == IrOpcode.GET_FIELD
                || instruction.opcode() == IrOpcode.PUT_FIELD;
    }

    private static void appendAccess(
            StringBuilder builder,
            RuntimeTokenMapper runtimeTokens,
            Access access) {
        String symbol = runtimeTokens.helperSymbol(
                RuntimeTokenDomain.FIELD_RUNTIME,
                access.operation(),
                access.fieldKey());
        ValueKind kind = ValueKind.fromDescriptor(access.descriptor());
        java.util.ArrayList<HostJniLocalAbiSource.Parameter> parameters =
                new java.util.ArrayList<>();
        parameters.add(new HostJniLocalAbiSource.Parameter(
                "JNIEnv*",
                "env"));
        parameters.add(new HostJniLocalAbiSource.Parameter(
                access.staticAccess() ? "jclass" : "jobject",
                access.staticAccess() ? "ignored_owner" : "self"));
        if (access.write()) {
            parameters.add(new HostJniLocalAbiSource.Parameter(
                    kind.cType(),
                    "value"));
        }
        HostJniLocalAbiSource.Emission localAbi =
                HostJniLocalAbiSource.emit(
                        runtimeTokens,
                        RuntimeLocalAbiDomain.FIELD,
                        access.operation(),
                        access.fieldKey(),
                        parameters);
        builder.append(kind.cReturn(access.write()))
                .append(' ')
                .append(symbol)
                .append('(')
                .append(localAbi.parameterDeclarations())
                .append(") {\n");
        if (access.staticAccess()) {
            builder.append("    (void)ignored_owner;\n")
                    .append("    jclass cls = (*env)->FindClass(env, \"")
                    .append(CSourceEscaper.stringContents(access.owner()))
                    .append("\");\n");
        } else {
            builder.append("    if (self == NULL) {\n")
                    .append("        j2ll_throw_new(env, \"java/lang/NullPointerException\", \"field receiver is null\");\n")
                    .append(access.write()
                            ? "        return;\n"
                            : "        return " + kind.defaultValue() + ";\n")
                    .append("    }\n")
                    .append("    jclass cls = (*env)->FindClass(env, \"")
                    .append(CSourceEscaper.stringContents(access.owner()))
                    .append("\");\n");
        }
        builder.append("    if (cls == NULL) ")
                .append(access.write()
                        ? "return;\n"
                        : "return " + kind.defaultValue() + ";\n")
                .append("    jfieldID field = (*env)->")
                .append(access.staticAccess()
                        ? "GetStaticFieldID"
                        : "GetFieldID")
                .append("(env, cls, \"")
                .append(CSourceEscaper.stringContents(access.name()))
                .append("\", \"")
                .append(CSourceEscaper.stringContents(access.descriptor()))
                .append("\");\n")
                .append("    if (field == NULL) {\n")
                .append("        (*env)->DeleteLocalRef(env, cls);\n")
                .append(access.write()
                        ? "        return;\n"
                        : "        return " + kind.defaultValue() + ";\n")
                .append("    }\n");
        if (access.write()) {
            builder.append("    (*env)->")
                    .append(access.staticAccess() ? "SetStatic" : "Set")
                    .append(kind.jniSuffix())
                    .append("Field(env, ")
                    .append(access.staticAccess() ? "cls" : "self")
                    .append(", field, ")
                    .append(kind.writeExpression())
                    .append(");\n")
                    .append("    (*env)->DeleteLocalRef(env, cls);\n")
                    .append("}\n\n");
            return;
        }
        builder.append("    ")
                .append(kind.jniType())
                .append(" result = (*env)->")
                .append(access.staticAccess() ? "GetStatic" : "Get")
                .append(kind.jniSuffix())
                .append("Field(env, ")
                .append(access.staticAccess() ? "cls" : "self")
                .append(", field);\n")
                .append("    (*env)->DeleteLocalRef(env, cls);\n")
                .append("    return ")
                .append(kind.readExpression())
                .append(";\n")
                .append("}\n\n");
    }

    private record Access(
            IrOpcode opcode,
            String fieldKey,
            String owner,
            String name,
            String descriptor) {
        static Access from(IrInstruction instruction) {
            if (!isJvmFieldAccess(instruction)) {
                throw new IllegalArgumentException(
                        "not a JVM field access: " + instruction.opcode());
            }
            String fieldKey = instruction.symbol().orElseThrow();
            int ownerEnd = fieldKey.indexOf('#');
            int descriptorStart = fieldKey.indexOf('!');
            if (ownerEnd < 1 || descriptorStart <= ownerEnd + 1) {
                throw new IllegalArgumentException(
                        "invalid field key: " + fieldKey);
            }
            return new Access(
                    instruction.opcode(),
                    fieldKey,
                    fieldKey.substring(0, ownerEnd),
                    fieldKey.substring(ownerEnd + 1, descriptorStart),
                    fieldKey.substring(descriptorStart + 1));
        }

        boolean staticAccess() {
            return opcode == IrOpcode.GET_STATIC
                    || opcode == IrOpcode.PUT_STATIC;
        }

        boolean write() {
            return opcode == IrOpcode.PUT_STATIC
                    || opcode == IrOpcode.PUT_FIELD;
        }

        String operation() {
            return switch (opcode) {
                case GET_STATIC -> "field_get_static_" + ValueKind
                        .fromDescriptor(descriptor).suffix();
                case PUT_STATIC -> "field_put_static_" + ValueKind
                        .fromDescriptor(descriptor).suffix();
                case GET_FIELD -> "field_get_instance_" + ValueKind
                        .fromDescriptor(descriptor).suffix();
                case PUT_FIELD -> "field_put_instance_" + ValueKind
                        .fromDescriptor(descriptor).suffix();
                default -> throw new IllegalStateException(
                        "unexpected field opcode " + opcode);
            };
        }

        String identity() {
            return operation() + "\0" + fieldKey;
        }
    }

    private enum ValueKind {
        I32("i32", "int32_t", "jint", "Int", "0"),
        I64("i64", "int64_t", "jlong", "Long", "0"),
        F32("f32", "float", "jfloat", "Float", "0.0f"),
        F64("f64", "double", "jdouble", "Double", "0.0"),
        REF("ref", "jobject", "jobject", "Object", "NULL"),
        BOOLEAN("i32", "int32_t", "jboolean", "Boolean", "0"),
        BYTE("i32", "int32_t", "jbyte", "Byte", "0"),
        SHORT("i32", "int32_t", "jshort", "Short", "0"),
        CHAR("i32", "int32_t", "jchar", "Char", "0");

        private final String suffix;
        private final String cType;
        private final String jniType;
        private final String jniSuffix;
        private final String defaultValue;

        ValueKind(
                String suffix,
                String cType,
                String jniType,
                String jniSuffix,
                String defaultValue) {
            this.suffix = suffix;
            this.cType = cType;
            this.jniType = jniType;
            this.jniSuffix = jniSuffix;
            this.defaultValue = defaultValue;
        }

        static ValueKind fromDescriptor(String descriptor) {
            return switch (descriptor.charAt(0)) {
                case 'Z' -> BOOLEAN;
                case 'B' -> BYTE;
                case 'S' -> SHORT;
                case 'C' -> CHAR;
                case 'I' -> I32;
                case 'J' -> I64;
                case 'F' -> F32;
                case 'D' -> F64;
                case 'L', '[' -> REF;
                default -> throw new IllegalArgumentException(
                        "unsupported field descriptor " + descriptor);
            };
        }

        String suffix() {
            return suffix;
        }

        String cType() {
            return cType;
        }

        String jniType() {
            return jniType;
        }

        String jniSuffix() {
            return jniSuffix;
        }

        String defaultValue() {
            return defaultValue;
        }

        String cReturn(boolean write) {
            return write ? "void" : cType;
        }

        String writeExpression() {
            return switch (this) {
                case BOOLEAN ->
                    "(jboolean)((uint32_t)value & UINT32_C(1))";
                case BYTE -> "(jbyte)value";
                case SHORT -> "(jshort)value";
                case CHAR -> "(jchar)value";
                case I32 -> "(jint)value";
                case I64 -> "(jlong)value";
                case F32 -> "(jfloat)value";
                case F64 -> "(jdouble)value";
                case REF -> "value";
            };
        }

        String readExpression() {
            return switch (this) {
                case BOOLEAN -> "result == JNI_FALSE ? 0 : 1";
                case BYTE, SHORT, CHAR, I32 -> "(int32_t)result";
                case I64 -> "(int64_t)result";
                case F32 -> "(float)result";
                case F64 -> "(double)result";
                case REF -> "result";
            };
        }
    }
}
