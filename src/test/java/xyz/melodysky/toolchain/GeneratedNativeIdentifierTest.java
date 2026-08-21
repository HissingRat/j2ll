package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import xyz.melodysky.frontend.cfg.MethodCfgBuilder;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.ssa.BytecodeToSsaLowerer;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.MethodRewritePlanner;
import xyz.melodysky.packaging.NativeRegistrationEntry;
import xyz.melodysky.packaging.NativeRegistrationPlan;
import xyz.melodysky.packaging.NativeRegistrationPlanner;
import xyz.melodysky.testsupport.AsmFixtureBuilder;

class GeneratedNativeIdentifierTest {
    @Test
    void everyInitializerCarrierUsesOnlyPrefixFreeJavaIdentifierHashNames() {
        String constructorOwner = "sensitive/acme/Initializer";
        ParsedClass constructorClass = new AsmClassParser()
                .parse(new ClassFileEntry(
                        constructorOwner + ".class",
                        AsmFixtureBuilder.minimalClass(constructorOwner),
                        "fixture"))
                .artifact()
                .orElseThrow();
        String classInitializerOwner = "sensitive/acme/StaticInitializer";
        ParsedClass classInitializerClass = new AsmClassParser()
                .parse(new ClassFileEntry(
                        classInitializerOwner + ".class",
                        AsmFixtureBuilder.classWithClassInitializer(
                                classInitializerOwner),
                        "fixture"))
                .artifact()
                .orElseThrow();
        MethodRewritePlanner planner = new MethodRewritePlanner();
        List<MethodRewriteDecision> decisions =
                java.util.stream.Stream.concat(
                                planner.planClass(
                                                constructorClass,
                                                0x1020304050607080L)
                                        .stream(),
                                planner.planClass(
                                                classInitializerClass,
                                                0x1020304050607080L)
                                        .stream())
                        .filter(decision -> decision.generatedHelperName()
                                .isPresent())
                        .toList();
        NativeRegistrationPlan registration =
                new NativeRegistrationPlanner().plan(decisions);

        assertEquals(2, decisions.size());
        Set<String> names = decisions.stream()
                .map(decision -> decision.generatedHelperName()
                        .orElseThrow())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        assertEquals(2, names.size());
        assertEquals(
                names,
                registration.entries().stream()
                        .map(NativeRegistrationEntry::methodName)
                        .collect(java.util.stream.Collectors
                                .toUnmodifiableSet()));
        assertTrue(names.stream().allMatch(name -> name.matches("[a-p]{32}")));
        assertTrue(names.stream().allMatch(this::isJavaIdentifier));
        assertTrue(names.stream().noneMatch(name -> name.contains("j2ll")
                || name.contains("init")
                || name.contains("clinit")
                || name.contains("body")
                || name.contains("_")
                || name.contains("$")));
    }

    @Test
    void interfaceCarrierIdentifiersAreBuildScopedHashOnlyTokens() {
        String owner = "sensitive/acme/SecretApi";
        ParsedClass parsedClass = new AsmClassParser()
                .parse(new ClassFileEntry(
                        owner + ".class",
                        AsmFixtureBuilder.interfaceWithAbstractAndDefault(owner),
                        "fixture"))
                .artifact()
                .orElseThrow();
        MethodRewritePlanner planner = new MethodRewritePlanner();

        MethodRewriteDecision first = interfaceDecision(
                planner.planClass(parsedClass, 0x10203040L));
        MethodRewriteDecision repeated = interfaceDecision(
                planner.planClass(parsedClass, 0x10203040L));
        MethodRewriteDecision nextBuild = interfaceDecision(
                planner.planClass(parsedClass, 0x50607080L));

        assertTrue(first.registrationOwner().matches(
                "j2ll/generated/i_[0-9a-f]{32}"));
        assertTrue(first.generatedHelperName().orElseThrow().matches(
                "j2ll_m_[0-9a-f]{32}"));
        assertFalse(first.registrationOwner().contains("SecretApi"));
        assertFalse(first.generatedHelperName().orElseThrow().contains("answer"));
        assertEquals(first.registrationOwner(), repeated.registrationOwner());
        assertEquals(first.generatedHelperName(), repeated.generatedHelperName());
        assertNotEquals(first.registrationOwner(), nextBuild.registrationOwner());
        assertNotEquals(first.generatedHelperName(), nextBuild.generatedHelperName());
    }

    @Test
    void generatedCUsesHashOnlyApplicationIdentifiersAndKeepsExportedAbi() {
        String owner = "sensitive/acme/PlainOwner";
        String methodName = "secretMethod";
        ParsedClass parsedClass = new AsmClassParser()
                .parse(new ClassFileEntry(
                        owner + ".class",
                        AsmFixtureBuilder.classWithIntMethod(owner, methodName, 7),
                        "fixture"))
                .artifact()
                .orElseThrow();
        ParsedMethod parsedMethod = parsedClass.methods().stream()
                .filter(candidate -> candidate.name().equals(methodName))
                .findFirst()
                .orElseThrow();
        MethodRewriteDecision decision = new MethodRewritePlanner().planMethod(parsedClass, parsedMethod, 0x6a326c6cL);
        NativeRegistrationPlan registrationPlan = new NativeRegistrationPlanner().plan(List.of(decision));
        IrMethod irMethod = xyz.melodysky.testsupport.TestProtectionMaterials.ssaLowerer()
                .lower(new MethodCfgBuilder().build(parsedMethod).artifact().orElseThrow())
                .artifact()
                .orElseThrow()
                .irMethod()
                .orElseThrow();
        NativeImplementationPlan implementationPlan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                registrationPlan,
                List.of(decision),
                Map.of(parsedMethod.methodKey(), irMethod));

        NativeRegistrationEntry entry = registrationPlan.entries().get(0);
        String llvmSymbol = implementationPlan.implementations().get(0).llvmFunctionSymbol().orElseThrow();
        String source = xyz.melodysky.testsupport.TestProtectionMaterials
                .hostJniSource(implementationPlan);

        assertTrue(entry.nativeSymbol().matches("j2ll_n_[0-9a-f]{32}"));
        assertTrue(llvmSymbol.matches("j2ll_f_[0-9a-f]{32}"));
        assertTrue(source.contains(entry.nativeSymbol()));
        assertTrue(source.contains(llvmSymbol));
        assertFalse(source.contains("PlainOwner"));
        assertFalse(source.contains("secretMethod"));
        assertTrue(source.matches(
                "(?s).*static jint [a-p]{32}\\(JavaVM\\* vm\\) "
                        + Pattern.quote(
                                NativeRegistrationControlCFunctionPolicy
                                        .ATTRIBUTES)
                        + ";.*"));
        assertFalse(source.contains("j2ll_register_"));
        assertFalse(source.contains("JNIEXPORT jint JNICALL j2ll_register(JavaVM* vm)"));
        assertTrue(source.contains("JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved)"));
    }

    private MethodRewriteDecision interfaceDecision(
            List<MethodRewriteDecision> decisions) {
        return decisions.stream()
                .filter(decision -> decision.method().name().equals("answer"))
                .findFirst()
                .orElseThrow();
    }

    private boolean isJavaIdentifier(String value) {
        return !value.isEmpty()
                && Character.isJavaIdentifierStart(value.codePointAt(0))
                && value.codePoints()
                        .skip(1)
                        .allMatch(Character::isJavaIdentifierPart);
    }
}
