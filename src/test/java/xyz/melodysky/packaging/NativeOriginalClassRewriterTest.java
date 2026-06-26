package xyz.melodysky.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.testsupport.AsmFixtureBuilder;

class NativeOriginalClassRewriterTest implements Opcodes {
    private final MethodRewritePlanner planner = new MethodRewritePlanner();

    @Test
    void nativeOriginalRemovesCodeAndSetsNativeFlag() {
        ParsedClass parsedClass = parsed("pkg/Mathy", AsmFixtureBuilder.classWithAddMethod("pkg/Mathy"));
        MethodRewriteDecision decision = planner.planClass(parsedClass).stream()
                .filter(item -> item.method().name().equals("add"))
                .findFirst()
                .orElseThrow();

        ClassRewriteResult result = new NativeOriginalClassRewriter().rewrite(parsedClass, List.of(decision));
        ParsedMethod rewritten = parsed("pkg/Mathy", result.classBytes()).methods().stream()
                .filter(method -> method.name().equals("add"))
                .findFirst()
                .orElseThrow();

        assertTrue(result.diagnostics().isEmpty());
        assertEquals(1, result.applied().size());
        assertTrue(rewritten.accessFlags().isNative());
        assertFalse(rewritten.hasCode());
        assertEquals("(II)I", rewritten.descriptor());
    }

    @Test
    void nativeOriginalCanInjectLoaderTriggerIntoGeneratedClassInitializer() {
        ParsedClass parsedClass = parsed("pkg/Mathy", AsmFixtureBuilder.classWithAddMethod("pkg/Mathy"));
        MethodRewriteDecision decision = planner.planClass(parsedClass).stream()
                .filter(item -> item.method().name().equals("add"))
                .findFirst()
                .orElseThrow();

        ClassRewriteResult result = new NativeOriginalClassRewriter().rewrite(
                parsedClass,
                List.of(decision),
                "j2ll/generated/seed/NativeLoader");
        ParsedClass rewritten = parsed("pkg/Mathy", result.classBytes());
        MethodNode clinit = rewritten.classNode().methods.stream()
                .filter(method -> method.name.equals("<clinit>"))
                .findFirst()
                .orElseThrow();
        MethodInsnNode trigger = (MethodInsnNode) clinit.instructions.getFirst();

        assertEquals(INVOKESTATIC, trigger.getOpcode());
        assertEquals("j2ll/generated/seed/NativeLoader", trigger.owner);
        assertEquals("ensureLoaded", trigger.name);
        assertEquals("()V", trigger.desc);
    }

    @Test
    void constructorStubInjectsPrivateStaticNativeBodyHelper() {
        ParsedClass parsedClass = parsed("pkg/Foo", AsmFixtureBuilder.minimalClass("pkg/Foo"));
        MethodRewriteDecision constructor = planner.planClass(parsedClass).stream()
                .filter(item -> item.method().name().equals("<init>"))
                .findFirst()
                .orElseThrow();

        ClassRewriteResult result = new NativeOriginalClassRewriter().rewrite(parsedClass, List.of(constructor));
        ParsedClass rewrittenClass = parsed("pkg/Foo", result.classBytes());
        ParsedMethod rewritten = rewrittenClass.methods().stream()
                .filter(method -> method.name().equals("<init>"))
                .findFirst()
                .orElseThrow();
        ParsedMethod helper = rewrittenClass.methods().stream()
                .filter(method -> method.name().startsWith("__j2ll_init_body$"))
                .findFirst()
                .orElseThrow();

        assertTrue(result.diagnostics().isEmpty());
        assertEquals(1, result.applied().size());
        assertFalse(rewritten.accessFlags().isNative());
        assertTrue(rewritten.hasCode());
        assertTrue(helper.accessFlags().isNative());
        assertFalse(helper.hasCode());
        assertEquals("(Lpkg/Foo;)V", helper.descriptor());
    }

    private ParsedClass parsed(String internalName, byte[] bytes) {
        return new AsmClassParser()
                .parse(new ClassFileEntry(internalName + ".class", bytes, "fixture"))
                .artifact()
                .orElseThrow();
    }
}
