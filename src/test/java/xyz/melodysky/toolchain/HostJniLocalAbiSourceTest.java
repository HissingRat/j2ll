package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.runtime.RuntimeTokenMapper;
import xyz.melodysky.runtime.jni.RuntimeLocalAbiDomain;

final class HostJniLocalAbiSourceTest {
    @Test
    void emitsOnlyRealParametersInBuildScopedPhysicalOrder() {
        RuntimeTokenMapper tokens = mapper("build-one");
        HostJniLocalAbiSource.Emission emission =
                HostJniLocalAbiSource.emit(
                        tokens,
                        RuntimeLocalAbiDomain.DISPATCH,
                        "virtual_dispatch_i32",
                        "pkg/Owner#run!(I)I",
                        List.of(
                                new HostJniLocalAbiSource.Parameter(
                                        "JNIEnv*",
                                        "env"),
                                new HostJniLocalAbiSource.Parameter(
                                        "jobject",
                                        "receiver"),
                                new HostJniLocalAbiSource.Parameter(
                                        "jvalue*",
                                        "args")));

        assertTrue(emission.parameterDeclarations()
                .contains("JNIEnv* env"));
        assertTrue(emission.parameterDeclarations()
                .contains("jobject receiver"));
        assertTrue(emission.parameterDeclarations()
                .contains("jvalue* args"));
        assertFalse(emission.parameterDeclarations()
                .contains("uint64_t"));
        assertFalse(emission.parameterDeclarations()
                .contains("j2ll_k"));
        assertTrue(emission.plan().physicalSlots().stream()
                .allMatch(slot -> slot >= 0 && slot < 3));
    }

    @Test
    void anotherBuildCanChangePhysicalShapeWithoutAddingAnArgument() {
        HostJniLocalAbiSource.Emission first = emission("build-one");
        HostJniLocalAbiSource.Emission second = emission("build-two");

        assertNotEquals(
                first.plan().physicalSlots(),
                second.plan().physicalSlots());
        assertFalse(first.parameterDeclarations().contains("LinkageError"));
        assertFalse(second.parameterDeclarations().contains("LinkageError"));
    }

    private HostJniLocalAbiSource.Emission emission(String build) {
        return HostJniLocalAbiSource.emit(
                mapper(build),
                RuntimeLocalAbiDomain.REFLECTION,
                "reflection_lookup_method",
                "method:pkg/Owner#run!()V",
                List.of(
                        new HostJniLocalAbiSource.Parameter(
                                "JNIEnv*",
                                "env"),
                        new HostJniLocalAbiSource.Parameter(
                                "jobject",
                                "receiver"),
                        new HostJniLocalAbiSource.Parameter(
                                "jvalue*",
                                "args"),
                        new HostJniLocalAbiSource.Parameter(
                                "jint",
                                "mode"),
                        new HostJniLocalAbiSource.Parameter(
                                "jlong",
                                "stamp"),
                        new HostJniLocalAbiSource.Parameter(
                                "jobject",
                                "owner")));
    }

    private RuntimeTokenMapper mapper(String build) {
        return RuntimeTokenMapper.fromBytes(
                build.getBytes(StandardCharsets.UTF_8));
    }
}
