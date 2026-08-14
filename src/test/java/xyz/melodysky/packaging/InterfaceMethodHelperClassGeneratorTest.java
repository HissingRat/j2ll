package xyz.melodysky.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.testsupport.AsmFixtureBuilder;

class InterfaceMethodHelperClassGeneratorTest {
    @Test
    void emitsOneJava17StaticNativeCarrierForDefaultMethod() {
        var parsed = new AsmClassParser()
                .parse(new ClassFileEntry(
                        "pkg/Api.class",
                        AsmFixtureBuilder.interfaceWithAbstractAndDefault("pkg/Api"),
                        "fixture"))
                .artifact()
                .orElseThrow();
        MethodRewriteDecision decision = new MethodRewritePlanner().planClass(parsed).stream()
                .filter(candidate -> candidate.method().name().equals("answer"))
                .findFirst()
                .orElseThrow();

        var entries = new InterfaceMethodHelperClassGenerator().generate(List.of(decision));
        byte[] bytes = entries.get(decision.registrationOwner() + ".class");
        var helper = new AsmClassParser()
                .parse(new ClassFileEntry(
                        decision.registrationOwner() + ".class",
                        bytes,
                        "generated"))
                .artifact()
                .orElseThrow();
        var method = helper.methods().get(0);

        assertEquals(61, helper.classNode().version);
        assertFalse(helper.isInterface());
        assertEquals(decision.generatedHelperName().orElseThrow(), method.name());
        assertEquals("(Lpkg/Api;)I", method.descriptor());
        assertTrue(method.accessFlags().isPublic());
        assertTrue(method.accessFlags().isStatic());
        assertTrue(method.accessFlags().isNative());
        assertFalse(method.hasCode());
    }
}
