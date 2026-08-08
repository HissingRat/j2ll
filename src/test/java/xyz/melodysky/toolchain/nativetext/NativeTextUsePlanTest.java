package xyz.melodysky.toolchain.nativetext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class NativeTextUsePlanTest {
    private final GeneratedCFragmentLexer lexer =
            new GeneratedCFragmentLexer();

    @Test
    void singleLiteralHasOneUnconditionalDecodePoint() {
        List<GeneratedCFragmentLexer.StringLiteral> literals = literals(
                "static int value(void) { const char* value = \"one\"; return value[0]; }");

        NativeTextUsePlan plan = NativeTextUsePlan.plan(
                List.of(0),
                literals);

        assertEquals(NativeTextUsePlan.Lifetime.SINGLE_USE, plan.lifetime());
        assertEquals(1, plan.literalUseCount());
        assertFalse(plan.requiresReadyGuard());
        assertTrue(plan.decodesAt(0));
    }

    @Test
    void directArgumentsShareOneDecodePoint() {
        List<GeneratedCFragmentLexer.StringLiteral> literals = literals("""
                static int pair(const char*, const char*);
                static int value(void) { return pair("first", "second"); }
                """);

        NativeTextUsePlan plan = NativeTextUsePlan.plan(
                List.of(0, 1),
                literals);

        assertEquals(
                NativeTextUsePlan.Lifetime.DIRECT_CALL_ARGUMENTS,
                plan.lifetime());
        assertEquals(2, plan.literalUseCount());
        assertFalse(plan.requiresReadyGuard());
        assertTrue(plan.decodesAt(0));
        assertFalse(plan.decodesAt(1));
        assertTrue(plan.argumentListStart() >= 0);
    }

    @Test
    void crossCallReuseKeepsLazyReadyGuard() {
        List<GeneratedCFragmentLexer.StringLiteral> literals = literals("""
                static int consume(const char*);
                static int value(void) {
                    return consume("same") + consume("same");
                }
                """);

        NativeTextUsePlan plan = NativeTextUsePlan.plan(
                List.of(0, 1),
                literals);

        assertEquals(NativeTextUsePlan.Lifetime.REUSED, plan.lifetime());
        assertTrue(plan.requiresReadyGuard());
        assertTrue(plan.decodesAt(0));
        assertTrue(plan.decodesAt(1));
    }

    private List<GeneratedCFragmentLexer.StringLiteral> literals(
            String source) {
        return lexer.scan(source).stringLiterals();
    }
}
