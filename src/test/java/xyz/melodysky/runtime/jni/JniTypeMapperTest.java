package xyz.melodysky.runtime.jni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class JniTypeMapperTest {
    @Test
    void mapsPrimitiveObjectArrayStringAndClassDescriptors() {
        JniTypeMapper mapper = new JniTypeMapper();

        assertEquals("jint", mapper.jniType("I"));
        assertEquals("jlong", mapper.jniType("J"));
        assertEquals("jdouble", mapper.jniType("D"));
        assertEquals("void", mapper.jniType("V"));
        assertEquals("jobject", mapper.jniType("Ljava/lang/Object;"));
        assertEquals("jstring", mapper.jniType("Ljava/lang/String;"));
        assertEquals("jclass", mapper.jniType("Ljava/lang/Class;"));
        assertEquals("jintArray", mapper.jniType("[I"));
        assertEquals("jobjectArray", mapper.jniType("[Ljava/lang/String;"));
        assertEquals("jobjectArray", mapper.jniType("[[I"));
    }

    @Test
    void buildsStaticAndInstanceMethodAbi() {
        JniTypeMapper mapper = new JniTypeMapper();

        JniMethodDescriptor staticMethod = mapper.methodDescriptor(
                "pkg/Foo",
                "call",
                "(ILjava/lang/String;[ILjava/lang/Class;)Ljava/lang/String;",
                true);
        assertEquals(List.of("JNIEnv*", "jclass"), staticMethod.implicitParameterTypes());
        assertEquals(List.of("jint", "jstring", "jintArray", "jclass"), staticMethod.jniParameterTypes());
        assertEquals("jstring", staticMethod.jniReturnType());
        assertEquals(
                "jstring j2ll_pkg_Foo_call(JNIEnv*, jclass, jint, jstring, jintArray, jclass)",
                staticMethod.cPrototype("j2ll_pkg_Foo_call"));

        JniMethodDescriptor instanceMethod = mapper.methodDescriptor("pkg/Foo", "run", "()V", false);
        assertEquals(List.of("JNIEnv*", "jobject"), instanceMethod.implicitParameterTypes());
        assertEquals("void j2ll_pkg_Foo_run(JNIEnv*, jobject)", instanceMethod.cPrototype("j2ll_pkg_Foo_run"));
    }

    @Test
    void modelsReferenceLifetimeAndLocalFrames() {
        JniReferencePolicy local = JniReferencePolicy.localFrame();
        JniReferencePolicy global = JniReferencePolicy.global();
        JniReferencePolicy weak = JniReferencePolicy.weakGlobal();

        assertEquals(JniReferenceKind.LOCAL, local.kind());
        assertEquals("DeleteLocalRef", local.releaseFunction());
        assertEquals("DeleteGlobalRef", global.releaseFunction());
        assertEquals("DeleteWeakGlobalRef", weak.releaseFunction());

        JniLocalFramePlan frame = JniLocalFramePlan.forNativeCall(8, List.of(weak, local, global));
        assertTrue(frame.enterFrame());
        assertTrue(frame.exitFrame());
        assertEquals(JniPendingExceptionPolicy.PROPAGATE_TO_JVM, frame.pendingExceptionPolicy());
        assertEquals(List.of(local, global, weak), frame.references());
    }
}
