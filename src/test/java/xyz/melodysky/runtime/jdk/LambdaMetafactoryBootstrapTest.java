package xyz.melodysky.runtime.jdk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import xyz.melodysky.diagnostic.DiagnosticCode;

class LambdaMetafactoryBootstrapTest {
    @Test
    void parsesMetafactoryImplementationHandle() {
        Handle implementation = new Handle(
                Opcodes.H_INVOKESTATIC,
                "pkg/Lambda",
                "target",
                "()V",
                false);

        var plan = new LambdaMetafactoryBootstrap().parse(
                bootstrap("metafactory"),
                new Object[] {"sam", implementation, "instantiated"});

        assertTrue(plan.lambdaMetafactory());
        assertTrue(plan.supported());
        assertTrue(plan.implementationHandle().isPresent());
    }

    @Test
    void parsesAltMetafactoryCommonFlags() {
        Handle implementation = new Handle(
                Opcodes.H_INVOKESTATIC,
                "pkg/Lambda",
                "target",
                "()V",
                false);

        var plan = new LambdaMetafactoryBootstrap().parse(
                bootstrap("altMetafactory"),
                new Object[] {
                        Type.getMethodType("()V"),
                        implementation,
                        Type.getMethodType("()V"),
                        LambdaMetafactoryBootstrap.FLAG_SERIALIZABLE
                                | LambdaMetafactoryBootstrap.FLAG_MARKERS
                                | LambdaMetafactoryBootstrap.FLAG_BRIDGES,
                        1,
                        Type.getObjectType("java/io/Serializable"),
                        1,
                        Type.getMethodType("()V")
                });

        assertTrue(plan.lambdaMetafactory());
        assertTrue(plan.supported());
        assertTrue(plan.serializable());
        assertEquals(DiagnosticCode.ALT_METAFACTORY_UNSUPPORTED, plan.unsupportedDiagnosticCode());
        assertEquals(java.util.List.of("java/io/Serializable"), plan.markerInterfaces());
        assertEquals(java.util.List.of("()V"), plan.bridgeMethodDescriptors());
    }

    @Test
    void rejectsUnsupportedAltMetafactoryFlagsForFallback() {
        var plan = new LambdaMetafactoryBootstrap().parse(
                bootstrap("altMetafactory"),
                new Object[] {"sam", new Handle(Opcodes.H_INVOKESTATIC, "pkg/Lambda", "target", "()V", false), "instantiated", 8});

        assertTrue(plan.lambdaMetafactory());
        assertFalse(plan.supported());
        assertEquals(DiagnosticCode.ALT_METAFACTORY_UNSUPPORTED, plan.unsupportedDiagnosticCode());
    }

    private Handle bootstrap(String name) {
        return new Handle(
                Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory",
                name,
                "()Ljava/lang/invoke/CallSite;",
                false);
    }
}
