package xyz.melodysky.analysis.method;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import xyz.melodysky.analysis.callgraph.CallGraph;
import xyz.melodysky.analysis.callgraph.CallResolution;
import xyz.melodysky.analysis.callgraph.CallSite;
import xyz.melodysky.analysis.callgraph.CallTarget;
import xyz.melodysky.analysis.callgraph.InvokeKind;
import xyz.melodysky.analysis.hierarchy.AnalysisWorld;
import xyz.melodysky.analysis.hierarchy.ClassHierarchy;
import xyz.melodysky.analysis.hierarchy.ClassHierarchyBuilder;
import xyz.melodysky.analysis.reflection.ReflectionPlan;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.frontend.classfile.ParsedProgram;
import xyz.melodysky.jvm.MethodSignature;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.MethodRewriteStrategy;
import xyz.melodysky.packaging.NativeRegistrationEntry;
import xyz.melodysky.toolchain.NativeImplementationPath;
import xyz.melodysky.toolchain.NativeMethodImplementation;

final class NativeMethodInternalizationTestFixtures implements Opcodes {
    private NativeMethodInternalizationTestFixtures() {
    }

    static ParsedProgram program(ClassSpec... classes) {
        AsmClassParser parser = new AsmClassParser();
        return new ParsedProgram(java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(objectClass()),
                        java.util.stream.Stream.of(classes))
                .map(type -> parser.parse(new ClassFileEntry(
                                type.internalName() + ".class",
                                classBytes(type),
                                "native-method-internalization-test"))
                        .artifact()
                        .orElseThrow())
                .toList());
    }

    static ClassSpec type(
            String internalName,
            String superName,
            int access,
            MethodSpec... methods) {
        return typeWithInterfaces(
                internalName,
                superName,
                access,
                List.of(),
                methods);
    }

    static ClassSpec typeWithInterfaces(
            String internalName,
            String superName,
            int access,
            List<String> interfaces,
            MethodSpec... methods) {
        return new ClassSpec(
                internalName,
                superName,
                access,
                interfaces,
                List.of(methods));
    }

    static MethodSpec method(
            int access,
            String name,
            String descriptor) {
        return new MethodSpec(
                access,
                name,
                descriptor,
                method -> emitDefaultReturn(method, descriptor));
    }

    static MethodSpec method(
            int access,
            String name,
            String descriptor,
            Consumer<MethodVisitor> body) {
        return new MethodSpec(access, name, descriptor, body);
    }

    static ParsedMethod method(ParsedProgram program, String methodKey) {
        NativeMethodId id = NativeMethodId.fromMethodKey(methodKey);
        return program.findClass(id.owner())
                .orElseThrow()
                .methods()
                .stream()
                .filter(method -> method.name().equals(id.name())
                        && method.descriptor().equals(id.descriptor()))
                .findFirst()
                .orElseThrow();
    }

    static ClassHierarchy hierarchy(ParsedProgram program) {
        return new ClassHierarchyBuilder()
                .build(program, AnalysisWorld.CLOSED_WORLD)
                .artifact()
                .orElseThrow();
    }

    static CallGraph callGraph(
            String callerMethodKey,
            InvokeKind invokeKind,
            String declaredTargetKey,
            List<CallTarget> targets) {
        NativeMethodId caller = NativeMethodId.fromMethodKey(callerMethodKey);
        NativeMethodId target = NativeMethodId.fromMethodKey(declaredTargetKey);
        CallSite site = new CallSite(
                callerMethodKey + "@0",
                caller.owner(),
                new MethodSignature(
                        caller.name(),
                        caller.descriptor()),
                0,
                invokeKind,
                target.owner(),
                new MethodSignature(
                        target.name(),
                        target.descriptor()));
        return new CallGraph(List.of(new CallResolution(
                site,
                targets,
                false,
                "TEST_FINAL_CALL_FACT")));
    }

    static CallTarget known(String methodKey) {
        NativeMethodId id = NativeMethodId.fromMethodKey(methodKey);
        return CallTarget.known(
                id.owner(),
                new MethodSignature(id.name(), id.descriptor()));
    }

    static NativeMethodImplementation implementation(
            ParsedMethod method,
            NativeImplementationPath path,
            List<String> directCallTargets,
            List<String> staticCallKeys,
            List<String> dispatchKeys) {
        String symbolSuffix = Integer.toUnsignedString(
                method.methodKey().hashCode(),
                16);
        return new NativeMethodImplementation(
                new NativeRegistrationEntry(
                        method.owner(),
                        method.name(),
                        method.descriptor(),
                        "j2ll_test_" + symbolSuffix),
                new MethodRewriteDecision(
                        method,
                        MethodRewriteStrategy.NATIVE_ORIGINAL,
                        method.owner(),
                        Optional.empty(),
                        "TEST_NATIVE_LOWERED"),
                path,
                path == NativeImplementationPath.LLVM_NATIVE_PATH
                        ? Optional.of("j2ll_llvm_test_" + symbolSuffix)
                        : Optional.empty(),
                "TEST_FINAL_PATH",
                false,
                false,
                List.of(),
                directCallTargets,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                staticCallKeys,
                dispatchKeys,
                List.of(),
                Optional.empty());
    }

    static ReflectionPlan noReflection() {
        return new ReflectionPlan(
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    private static ClassSpec objectClass() {
        return new ClassSpec(
                "java/lang/Object",
                null,
                ACC_PUBLIC,
                List.of(),
                List.of());
    }

    private static byte[] classBytes(ClassSpec type) {
        ClassWriter writer =
                new ClassWriter(ClassWriter.COMPUTE_FRAMES
                        | ClassWriter.COMPUTE_MAXS);
        writer.visit(
                V17,
                type.access() | ACC_SUPER,
                type.internalName(),
                null,
                type.superName(),
                type.interfaces().toArray(String[]::new));
        for (MethodSpec definition : type.methods()) {
            MethodVisitor method = writer.visitMethod(
                    definition.access(),
                    definition.name(),
                    definition.descriptor(),
                    null,
                    null);
            method.visitCode();
            definition.body().accept(method);
            method.visitMaxs(0, 0);
            method.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void emitDefaultReturn(
            MethodVisitor method,
            String descriptor) {
        switch (Type.getReturnType(descriptor).getSort()) {
            case Type.VOID -> method.visitInsn(RETURN);
            case Type.LONG -> {
                method.visitInsn(LCONST_0);
                method.visitInsn(LRETURN);
            }
            case Type.FLOAT -> {
                method.visitInsn(FCONST_0);
                method.visitInsn(FRETURN);
            }
            case Type.DOUBLE -> {
                method.visitInsn(DCONST_0);
                method.visitInsn(DRETURN);
            }
            case Type.ARRAY, Type.OBJECT -> {
                method.visitInsn(ACONST_NULL);
                method.visitInsn(ARETURN);
            }
            default -> {
                method.visitInsn(ICONST_0);
                method.visitInsn(IRETURN);
            }
        }
    }

    record ClassSpec(
            String internalName,
            String superName,
            int access,
            List<String> interfaces,
            List<MethodSpec> methods) {
    }

    record MethodSpec(
            int access,
            String name,
            String descriptor,
            Consumer<MethodVisitor> body) {
    }
}
