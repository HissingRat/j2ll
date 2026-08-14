package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.backend.llvm.model.LlvmType;

/** Focused 6B eligibility, ABI projection and topology contracts. */
class SemanticJniProxyPlanningTest {
    private static final List<String> SEMANTIC_METHODS = List.of(
            "staticIdentity",
            "instanceIdentity",
            "intArrayIdentity",
            "objectArrayIdentity",
            "allocateObject",
            "allocateBytes",
            "readStaticField",
            "readInstanceField",
            "divide",
            "remainder",
            "callStringValueOf",
            "alwaysThrow");

    @Test
    void referenceArrayJniAndExceptionSurfacesUseBranchedLlvmProxies()
            throws Exception {
        var fixture = fixture(SEMANTIC_METHODS);

        for (String methodName : SEMANTIC_METHODS) {
            NativeMethodImplementation implementation = implementation(
                    fixture,
                    methodName);
            NativeJniEntryPlan entry = entry(fixture, implementation);
            assertTrue(entry.llvmJniProxy(), methodName);
            assertEquals(
                    "LLVM_JNI_PROXY_SEMANTIC_SURFACE",
                    entry.reasonCode(),
                    methodName);
            assertEquals(
                    NativeJniEntryTopology.Shape.BRANCHED_PERMUTING_BRIDGE,
                    entry.topology().orElseThrow().shape(),
                    methodName);
            NativeJniProxyAbiProjection projection =
                    NativeJniProxyAbiProjection.derive(implementation)
                            .orElseThrow();
            assertEquals(
                    projection.semanticParameterCount(),
                    entry.topology().orElseThrow().parameterCount(),
                    methodName);
        }
    }

    @Test
    void staticAndInstancePhysicalParametersProjectExactlyOntoSemanticAbi()
            throws Exception {
        var fixture = fixture(List.of(
                "staticIdentity",
                "instanceIdentity",
                "readStaticField",
                "readInstanceField",
                "divide"));

        assertProjection(
                fixture,
                "staticIdentity",
                List.of(LlvmType.PTR, LlvmType.PTR, LlvmType.PTR),
                List.of(LlvmType.PTR),
                List.of(2));
        assertProjection(
                fixture,
                "instanceIdentity",
                List.of(LlvmType.PTR, LlvmType.PTR, LlvmType.PTR),
                List.of(LlvmType.PTR, LlvmType.PTR),
                List.of(1, 2));
        assertProjection(
                fixture,
                "readStaticField",
                List.of(LlvmType.PTR, LlvmType.PTR),
                List.of(LlvmType.PTR, LlvmType.PTR),
                List.of(0, 1));
        assertProjection(
                fixture,
                "readInstanceField",
                List.of(LlvmType.PTR, LlvmType.PTR),
                List.of(LlvmType.PTR, LlvmType.PTR),
                List.of(0, 1));
        assertProjection(
                fixture,
                "divide",
                List.of(
                        LlvmType.PTR,
                        LlvmType.PTR,
                        LlvmType.I32,
                        LlvmType.I32),
                List.of(LlvmType.PTR, LlvmType.I32, LlvmType.I32),
                List.of(0, 2, 3));
    }

    @Test
    void ownedLocalReferenceEvidenceStillPermitsOnlyBranchedProxies()
            throws Exception {
        var fixture = fixture(List.of("allocateObject", "allocateBytes"));
        for (String methodName : List.of("allocateObject", "allocateBytes")) {
            NativeMethodImplementation implementation = implementation(
                    fixture,
                    methodName);
            assertTrue(
                    new NativeLocalReferenceSafety()
                            .createsOwnedLocalReference(implementation
                                    .implementationIrMethod()
                                    .orElseThrow()),
                    methodName);
            assertEquals(
                    NativeJniEntryTopology.Shape.BRANCHED_PERMUTING_BRIDGE,
                    entry(fixture, implementation)
                            .topology()
                            .orElseThrow()
                            .shape(),
                    methodName);
        }
    }

    @Test
    void unsafePhysicalShapesRetainGeneratedCWrappers() throws Exception {
        List<String> methods = List.of(
                "<clinit>",
                "<init>",
                "readStaticFromInstance",
                "synchronizedIdentity",
                "narrowBoolean",
                "narrowByte",
                "narrowChar",
                "narrowShort");
        var fixture = fixture(methods);

        for (String methodName : methods) {
            NativeMethodImplementation implementation = implementation(
                    fixture,
                    methodName);
            NativeJniEntryPlan entry = entry(fixture, implementation);
            assertFalse(entry.llvmJniProxy(), methodName);
            assertEquals(
                    NativeJniEntryPlan.Kind.GENERATED_C_WRAPPER,
                    entry.kind(),
                    methodName);
        }
        assertEquals(
                "LLVM_JNI_PROXY_INSTANCE_OWNER_CLASS",
                entry(fixture, implementation(fixture, "readStaticFromInstance"))
                        .reasonCode());
        assertEquals(
                "LLVM_JNI_PROXY_SYNCHRONIZED",
                entry(fixture, implementation(fixture, "synchronizedIdentity"))
                        .reasonCode());
        for (String methodName : List.of(
                "narrowBoolean", "narrowByte", "narrowChar", "narrowShort")) {
            assertEquals(
                    "LLVM_JNI_PROXY_UNSAFE_DESCRIPTOR",
                    entry(fixture, implementation(fixture, methodName))
                            .reasonCode(),
                    methodName);
        }
    }

    @Test
    void nativeCallerAloneDoesNotForceSemanticTopology() throws Exception {
        var fixture = DirectJniEntryTestFixture.fixture(
                DirectJniEntryTestFixture.ineligibleClass(),
                List.of("callee", "caller"));
        NativeJniEntryPlan callee = entry(
                fixture,
                implementation(fixture, "callee"));
        NativeJniEntryPlan caller = entry(
                fixture,
                implementation(fixture, "caller"));

        assertTrue(callee.llvmJniProxy());
        assertEquals("LLVM_JNI_PROXY_PURE_SCALAR", callee.reasonCode());
        assertTrue(caller.llvmJniProxy());
        assertEquals("LLVM_JNI_PROXY_SEMANTIC_SURFACE", caller.reasonCode());
        assertEquals(
                NativeJniEntryTopology.Shape.BRANCHED_PERMUTING_BRIDGE,
                caller.topology().orElseThrow().shape());
    }

    private DirectJniEntryTestFixture.Fixture fixture(List<String> methods) {
        return DirectJniEntryTestFixture.fixture(
                DirectJniEntryTestFixture.semanticClass(),
                methods);
    }

    private NativeMethodImplementation implementation(
            DirectJniEntryTestFixture.Fixture fixture,
            String methodName) {
        return DirectJniEntryTestFixture.implementation(fixture, methodName);
    }

    private NativeJniEntryPlan entry(
            DirectJniEntryTestFixture.Fixture fixture,
            NativeMethodImplementation implementation) {
        return fixture.implementationPlan()
                .jniEntryPlanFor(implementation.methodKey());
    }

    private void assertProjection(
            DirectJniEntryTestFixture.Fixture fixture,
            String methodName,
            List<LlvmType> physical,
            List<LlvmType> semantic,
            List<Integer> indices) {
        NativeJniProxyAbiProjection projection =
                NativeJniProxyAbiProjection.derive(
                                implementation(fixture, methodName))
                        .orElseThrow();
        assertEquals(physical, projection.physicalParameterTypes(), methodName);
        assertEquals(semantic, projection.semanticParameterTypes(), methodName);
        assertEquals(indices, projection.semanticFromPhysicalIndices(), methodName);
    }
}
