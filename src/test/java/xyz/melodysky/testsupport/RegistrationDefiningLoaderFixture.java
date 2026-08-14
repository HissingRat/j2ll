package xyz.melodysky.testsupport;

import static org.objectweb.asm.Opcodes.ACC_NATIVE;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.ACC_SYNTHETIC;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.GETFIELD;
import static org.objectweb.asm.Opcodes.GETSTATIC;
import static org.objectweb.asm.Opcodes.IADD;
import static org.objectweb.asm.Opcodes.ICONST_1;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.IRETURN;
import static org.objectweb.asm.Opcodes.PUTSTATIC;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V17;

import java.util.List;
import java.util.Map;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import xyz.melodysky.packaging.NativeRegistrationEntry;
import xyz.melodysky.packaging.NativeRegistrationPlan;

/** Bytecode fixture for defining-loader registration without owner initialization. */
public final class RegistrationDefiningLoaderFixture {
    public static final String PACKAGE_INTERNAL_NAME = "registration/fixture";
    public static final String LOADER_INTERNAL_NAME =
            PACKAGE_INTERNAL_NAME + "/Loader";
    public static final String TRACKER_INTERNAL_NAME =
            PACKAGE_INTERNAL_NAME + "/InitializationTracker";
    public static final String CLINIT_OWNER_INTERNAL_NAME =
            PACKAGE_INTERNAL_NAME + "/NativeClinitOwner";
    public static final String CONSTRUCTOR_OWNER_INTERNAL_NAME =
            PACKAGE_INTERNAL_NAME + "/ConstructorOwner";

    public static final String LOADER_BINARY_NAME = binaryName(LOADER_INTERNAL_NAME);
    public static final String TRACKER_BINARY_NAME = binaryName(TRACKER_INTERNAL_NAME);
    public static final String CLINIT_OWNER_BINARY_NAME = binaryName(CLINIT_OWNER_INTERNAL_NAME);
    public static final String CONSTRUCTOR_OWNER_BINARY_NAME =
            binaryName(CONSTRUCTOR_OWNER_INTERNAL_NAME);

    public static final String CLINIT_HELPER_NAME =
            "abcdefghijklmnopabcdefghijklmnop";
    public static final String CONSTRUCTOR_HELPER_NAME =
            "ponmlkjihgfedcbaponmlkjihgfedcba";
    public static final String CLINIT_HELPER_SYMBOL =
            "j2ll_registration_fixture_clinit_body";
    public static final String CLINIT_CALLS_SYMBOL =
            "j2ll_registration_fixture_clinit_calls";
    public static final String CONSTRUCTOR_HELPER_SYMBOL =
            "j2ll_registration_fixture_constructor_body";
    public static final String CONSTRUCTOR_CALLS_SYMBOL =
            "j2ll_registration_fixture_constructor_calls";

    private RegistrationDefiningLoaderFixture() {}

    public static Map<String, byte[]> classEntries(byte[] loaderClass) {
        return Map.of(
                LOADER_INTERNAL_NAME + ".class", loaderClass,
                TRACKER_INTERNAL_NAME + ".class", trackerClass(),
                CLINIT_OWNER_INTERNAL_NAME + ".class", nativeClinitOwnerClass(),
                CONSTRUCTOR_OWNER_INTERNAL_NAME + ".class", constructorOwnerClass());
    }

    public static NativeRegistrationPlan registrationPlan() {
        return new NativeRegistrationPlan(List.of(
                new NativeRegistrationEntry(
                        CLINIT_OWNER_INTERNAL_NAME,
                        CLINIT_HELPER_NAME,
                        "()V",
                        CLINIT_HELPER_SYMBOL),
                new NativeRegistrationEntry(
                        CLINIT_OWNER_INTERNAL_NAME,
                        "nativeCalls",
                        "()I",
                        CLINIT_CALLS_SYMBOL),
                new NativeRegistrationEntry(
                        CONSTRUCTOR_OWNER_INTERNAL_NAME,
                        CONSTRUCTOR_HELPER_NAME,
                        constructorHelperDescriptor(),
                        CONSTRUCTOR_HELPER_SYMBOL),
                new NativeRegistrationEntry(
                        CONSTRUCTOR_OWNER_INTERNAL_NAME,
                        "nativeCalls",
                        "()I",
                        CONSTRUCTOR_CALLS_SYMBOL)));
    }

    private static byte[] trackerClass() {
        ClassWriter writer = newClass(TRACKER_INTERNAL_NAME);
        writer.visitField(
                        ACC_PUBLIC | ACC_STATIC,
                        "nativeClinitOwnerCount",
                        "I",
                        null,
                        null)
                .visitEnd();
        writer.visitField(
                        ACC_PUBLIC | ACC_STATIC,
                        "constructorOwnerClinitCount",
                        "I",
                        null,
                        null)
                .visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] nativeClinitOwnerClass() {
        ClassWriter writer = newClass(CLINIT_OWNER_INTERNAL_NAME);
        nativeMethod(
                writer,
                ACC_PRIVATE | ACC_STATIC | ACC_NATIVE | ACC_SYNTHETIC,
                CLINIT_HELPER_NAME,
                "()V");
        nativeMethod(
                writer,
                ACC_PUBLIC | ACC_STATIC | ACC_NATIVE,
                "nativeCalls",
                "()I");

        MethodVisitor initializer = writer.visitMethod(
                ACC_STATIC,
                "<clinit>",
                "()V",
                null,
                null);
        initializer.visitCode();
        initializer.visitMethodInsn(
                INVOKESTATIC,
                LOADER_INTERNAL_NAME,
                "ensureLoaded",
                "()V",
                false);
        incrementTracker(initializer, "nativeClinitOwnerCount");
        initializer.visitMethodInsn(
                INVOKESTATIC,
                CLINIT_OWNER_INTERNAL_NAME,
                CLINIT_HELPER_NAME,
                "()V",
                false);
        initializer.visitInsn(RETURN);
        initializer.visitMaxs(0, 0);
        initializer.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] constructorOwnerClass() {
        ClassWriter writer = newClass(CONSTRUCTOR_OWNER_INTERNAL_NAME);
        writer.visitField(ACC_PRIVATE, "value", "I", null, null).visitEnd();
        nativeMethod(
                writer,
                ACC_PRIVATE | ACC_STATIC | ACC_NATIVE | ACC_SYNTHETIC,
                CONSTRUCTOR_HELPER_NAME,
                constructorHelperDescriptor());
        nativeMethod(
                writer,
                ACC_PUBLIC | ACC_STATIC | ACC_NATIVE,
                "nativeCalls",
                "()I");

        MethodVisitor initializer = writer.visitMethod(
                ACC_STATIC,
                "<clinit>",
                "()V",
                null,
                null);
        initializer.visitCode();
        incrementTracker(initializer, "constructorOwnerClinitCount");
        initializer.visitInsn(RETURN);
        initializer.visitMaxs(0, 0);
        initializer.visitEnd();

        MethodVisitor constructor = writer.visitMethod(
                ACC_PUBLIC,
                "<init>",
                "(I)V",
                null,
                null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(
                INVOKESPECIAL,
                "java/lang/Object",
                "<init>",
                "()V",
                false);
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitVarInsn(ILOAD, 1);
        constructor.visitMethodInsn(
                INVOKESTATIC,
                CONSTRUCTOR_OWNER_INTERNAL_NAME,
                CONSTRUCTOR_HELPER_NAME,
                constructorHelperDescriptor(),
                false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        MethodVisitor value = writer.visitMethod(
                ACC_PUBLIC,
                "value",
                "()I",
                null,
                null);
        value.visitCode();
        value.visitVarInsn(ALOAD, 0);
        value.visitFieldInsn(GETFIELD, CONSTRUCTOR_OWNER_INTERNAL_NAME, "value", "I");
        value.visitInsn(IRETURN);
        value.visitMaxs(0, 0);
        value.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassWriter newClass(String internalName) {
        ClassWriter writer = new ClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(
                V17,
                ACC_PUBLIC | ACC_SUPER,
                internalName,
                null,
                "java/lang/Object",
                null);
        return writer;
    }

    private static void nativeMethod(
            ClassWriter writer,
            int access,
            String name,
            String descriptor) {
        writer.visitMethod(access, name, descriptor, null, null).visitEnd();
    }

    private static void incrementTracker(
            MethodVisitor method,
            String fieldName) {
        method.visitFieldInsn(GETSTATIC, TRACKER_INTERNAL_NAME, fieldName, "I");
        method.visitInsn(ICONST_1);
        method.visitInsn(IADD);
        method.visitFieldInsn(PUTSTATIC, TRACKER_INTERNAL_NAME, fieldName, "I");
    }

    private static String constructorHelperDescriptor() {
        return "(L" + CONSTRUCTOR_OWNER_INTERNAL_NAME + ";I)V";
    }

    private static String binaryName(String internalName) {
        return internalName.replace('/', '.');
    }
}
