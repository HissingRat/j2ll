package xyz.melodysky.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.pipeline.SkippedMethod;
import xyz.melodysky.pipeline.SkippedMethodGate;
import xyz.melodysky.pipeline.SkippedMethodGateDecision;

class SkippedMethodConfirmationTest {
    @Test
    void listsSkippedMethodsInStableOrderAndAcceptsExplicitY() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SkippedMethodConfirmation confirmation = confirmation(
                "maybe\n  y  \n",
                output);

        SkippedMethodGate.Result result = new SkippedMethodGate().evaluate(
                List.of(
                skipped("z/Owner", "later", "()V", "Z_REASON", "later reason"),
                skipped("a/Owner", "first", "(I)I", "A_REASON", "first\nreason")),
                false,
                confirmation);

        assertTrue(result.decision() == SkippedMethodGateDecision.APPROVED);
        String rendered = output.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.indexOf("a/Owner#first!(I)I")
                < rendered.indexOf("z/Owner#later!()V"), rendered);
        assertTrue(rendered.contains("reason=first reason"), rendered);
        assertTrue(rendered.contains("Please answer Y or N."), rendered);
        assertTrue(rendered.contains("skippedMethods=retainedJavaBytecodeUserApproved"), rendered);
        assertTrue(confirmation.evidence().methods().get(0).owner().equals("a/Owner"));
        assertTrue(confirmation.evidence().decision()
                == SkippedMethodGateDecision.APPROVED);
    }

    @Test
    void rejectsExplicitNAndEndOfInput() throws Exception {
        ByteArrayOutputStream explicitOutput = new ByteArrayOutputStream();
        SkippedMethodConfirmation explicit =
                confirmation("  n \n", explicitOutput);
        assertFalse(new SkippedMethodGate()
                .evaluate(
                        List.of(skipped(
                                "pkg/C",
                                "m",
                                "()V",
                                "UNSUPPORTED",
                                "unsupported")),
                        false,
                        explicit)
                .diagnostics()
                .isEmpty());
        assertTrue(explicit.evidence().decision()
                == SkippedMethodGateDecision.REJECTED);
        assertTrue(explicitOutput.toString(StandardCharsets.UTF_8)
                .contains("cancelled=skipped methods were not approved"));

        ByteArrayOutputStream eofOutput = new ByteArrayOutputStream();
        assertFalse(confirmation("", eofOutput).approve(List.of(
                skipped("pkg/C", "m", "()V", "UNSUPPORTED", "unsupported"))));
        assertTrue(eofOutput.toString(StandardCharsets.UTF_8)
                .contains("cancelled=skipped methods were not approved"));
    }

    @Test
    void emptySkippedSetDoesNotPromptOrConsumeInput() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SkippedMethodConfirmation confirmation = confirmation("", output);
        assertTrue(new SkippedMethodGate()
                .evaluate(List.of(), false, confirmation)
                .diagnostics()
                .isEmpty());
        assertTrue(confirmation.evidence().decision()
                == SkippedMethodGateDecision.NOT_REQUIRED);
        assertTrue(output.toString(StandardCharsets.UTF_8).isEmpty());
    }

    @Test
    void inputFailureRetainsTheSkippedSetAndInputErrorDecision() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BufferedReader brokenReader =
                new BufferedReader(new StringReader("")) {
                    @Override
                    public String readLine() throws IOException {
                        throw new IOException("broken input");
                    }
                };
        SkippedMethodConfirmation confirmation =
                new SkippedMethodConfirmation(
                        brokenReader,
                        new PrintStream(
                                output,
                                true,
                                StandardCharsets.UTF_8));

        SkippedMethodGate.Result result = new SkippedMethodGate().evaluate(
                List.of(skipped(
                        "pkg/C",
                        "m",
                        "()V",
                        "UNSUPPORTED",
                        "unsupported")),
                false,
                confirmation);

        assertTrue(result.decision() == SkippedMethodGateDecision.INPUT_ERROR);
        assertTrue(confirmation.evidence().decision()
                == SkippedMethodGateDecision.INPUT_ERROR);
        assertTrue(confirmation.evidence().methods().get(0).methodKey()
                .equals("pkg/C#m!()V"));
    }

    private SkippedMethodConfirmation confirmation(
            String input,
            ByteArrayOutputStream output) {
        return new SkippedMethodConfirmation(
                new BufferedReader(new StringReader(input)),
                new PrintStream(output, true, StandardCharsets.UTF_8));
    }

    private SkippedMethod skipped(
            String owner,
            String name,
            String descriptor,
            String reasonCode,
            String reason) {
        return new SkippedMethod(
                owner,
                name,
                descriptor,
                DiagnosticStage.LOWERING,
                reasonCode,
                reason);
    }
}
