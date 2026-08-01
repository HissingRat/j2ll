package xyz.melodysky.analysis.method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static xyz.melodysky.analysis.method.NativeMethodInternalizationTestFixtures.method;
import static xyz.melodysky.analysis.method.NativeMethodInternalizationTestFixtures.program;
import static xyz.melodysky.analysis.method.NativeMethodInternalizationTestFixtures.type;

import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.config.SelectorParser;

class PublicMethodInternalizationAllowListResolverTest {
    private final SelectorParser selectors = new SelectorParser();
    private final PublicMethodInternalizationAllowListResolver resolver =
            new PublicMethodInternalizationAllowListResolver();

    @Test
    void resolvesExactPublicCodeMethod() {
        var result = resolver.resolve(
                program(type(
                        "fixture/Owner",
                        "java/lang/Object",
                        ACC_PUBLIC,
                        method(ACC_PUBLIC | ACC_STATIC, "target", "()I"))),
                List.of(selectors.parse("fixture/Owner#target!()I")));

        assertTrue(result.successful(), result.diagnostics().toString());
        assertEquals(
                List.of("fixture/Owner#target!()I"),
                result.methods().stream()
                        .map(NativeMethodId::methodKey)
                        .sorted()
                        .toList());
    }

    @Test
    void failsClosedForMissingTarget() {
        var result = resolver.resolve(
                program(type(
                        "fixture/Owner",
                        "java/lang/Object",
                        ACC_PUBLIC)),
                List.of(selectors.parse("fixture/Owner#missing!()I")));

        assertEquals(
                List.of(PublicMethodInternalizationAllowListDiagnostics
                        .TARGET_NOT_FOUND),
                result.diagnostics().stream()
                        .map(diagnostic -> diagnostic.code())
                        .toList());
    }

    @Test
    void failsClosedForNonPublicTarget() {
        var result = resolver.resolve(
                program(type(
                        "fixture/Owner",
                        "java/lang/Object",
                        ACC_PUBLIC,
                        method(ACC_PRIVATE | ACC_STATIC, "target", "()I"))),
                List.of(selectors.parse("fixture/Owner#target!()I")));

        assertEquals(
                List.of(PublicMethodInternalizationAllowListDiagnostics
                        .TARGET_NOT_PUBLIC_CODE),
                result.diagnostics().stream()
                        .map(diagnostic -> diagnostic.code())
                        .toList());
    }
}
