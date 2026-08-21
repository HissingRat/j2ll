package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import xyz.melodysky.frontend.cfg.MethodCfgBuilder;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.ssa.BytecodeToSsaLowerer;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.MethodRewritePlanner;
import xyz.melodysky.packaging.NativeRegistrationPlanner;
import xyz.melodysky.testsupport.JdkGenericBridgeFixture;

class JdkGenericBridgeNativePlannerTest {
    @Test
    void plansAllExactV2GenericBridgesAsJniBackedLlvmImplementations() {
        ParsedClass parsedClass = new AsmClassParser()
                .parse(new ClassFileEntry(
                        "pkg/V2JdkBridgePlan.class",
                        JdkGenericBridgeFixture.classBytes("pkg/V2JdkBridgePlan"),
                        "fixture"))
                .artifact()
                .orElseThrow();
        List<MethodRewriteDecision> decisions = new MethodRewritePlanner().planClass(parsedClass, 0x6a326c6cL);
        Map<String, IrMethod> methods = new LinkedHashMap<>();
        for (JdkGenericBridgeFixture.CallSpec spec : JdkGenericBridgeFixture.calls()) {
            ParsedMethod parsedMethod = parsedClass.methods().stream()
                    .filter(method -> method.name().equals(spec.wrapperName()))
                    .findFirst()
                    .orElseThrow();
            IrMethod method = xyz.melodysky.testsupport.TestProtectionMaterials.ssaLowerer()
                    .lower(new MethodCfgBuilder().build(parsedMethod).artifact().orElseThrow())
                    .artifact()
                    .orElseThrow()
                    .irMethod()
                    .orElseThrow();
            methods.put(parsedMethod.methodKey(), method);
        }

        NativeImplementationPlan plan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                new NativeRegistrationPlanner().plan(decisions),
                decisions,
                methods);

        assertEquals(11, plan.implementations().size());
        for (JdkGenericBridgeFixture.CallSpec spec : JdkGenericBridgeFixture.calls()) {
            String wrapperKey = "pkg/V2JdkBridgePlan#" + spec.wrapperName() + "!" + spec.wrapperDescriptor();
            NativeMethodImplementation implementation = plan.implementationFor(wrapperKey).orElseThrow();
            assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, implementation.path(), wrapperKey);
            assertTrue(implementation.passesJniEnv(), wrapperKey);
            if (spec.invokeOpcode() == Opcodes.INVOKESTATIC) {
                assertTrue(implementation.staticCallKeys().contains(spec.targetMethodKey()), spec.targetMethodKey());
            } else {
                assertTrue(implementation.dispatchKeys().contains(spec.targetMethodKey()), spec.targetMethodKey());
            }
        }

        assertTrue(plan.implementationFor("pkg/V2JdkBridgePlan#getByte!(Ljava/nio/ByteBuffer;)B")
                .orElseThrow()
                .dispatchKeys()
                .contains("java/nio/ByteBuffer#get!()B"));
        assertTrue(plan.implementationFor("pkg/V2JdkBridgePlan#fill!([BB)V")
                .orElseThrow()
                .staticCallKeys()
                .contains("java/util/Arrays#fill!([BB)V"));
        assertTrue(plan.implementationFor(
                        "pkg/V2JdkBridgePlan#defineHiddenClass!"
                                + "(Ljava/lang/invoke/MethodHandles$Lookup;[BZ"
                                + "[Ljava/lang/invoke/MethodHandles$Lookup$ClassOption;)"
                                + "Ljava/lang/invoke/MethodHandles$Lookup;")
                .orElseThrow()
                .dispatchKeys()
                .contains(
                        "java/lang/invoke/MethodHandles$Lookup#defineHiddenClass!"
                                + "([BZ[Ljava/lang/invoke/MethodHandles$Lookup$ClassOption;)"
                                + "Ljava/lang/invoke/MethodHandles$Lookup;"));
    }
}
