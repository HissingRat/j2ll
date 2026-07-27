package xyz.melodysky.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.Objects;
import xyz.melodysky.pipeline.SkippedMethod;
import xyz.melodysky.pipeline.SkippedMethodApproval;
import xyz.melodysky.pipeline.SkippedMethodGateEvidence;

/** CLI rendering and input policy for the final skipped-method gate. */
final class SkippedMethodConfirmation implements SkippedMethodApproval {
    private final BufferedReader reader;
    private final PrintStream err;
    private SkippedMethodGateEvidence evidence =
            SkippedMethodGateEvidence.notAnalyzed();

    SkippedMethodConfirmation(BufferedReader reader, PrintStream err) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.err = Objects.requireNonNull(err, "err");
    }

    @Override
    public boolean approve(List<SkippedMethod> skippedMethods)
            throws IOException {
        Objects.requireNonNull(skippedMethods, "skippedMethods");
        List<SkippedMethod> sortedMethods =
                skippedMethods.stream().sorted().toList();
        if (sortedMethods.isEmpty()) {
            return true;
        }
        sortedMethods.forEach(method -> err.println(
                "skippedMethod=" + sanitize(method.methodKey())
                        + " reasonCode=" + sanitize(method.reasonCode())
                        + " reason=" + sanitize(method.reason())));
        err.println("warning=" + sortedMethods.size()
                + " selected method(s) will not be native lowered; "
                + "their original Java bytecode will remain in the output JAR.");
        while (true) {
            err.println("continue? (Y/N)");
            err.print("> ");
            err.flush();
            String answer = reader.readLine();
            if (answer == null) {
                err.println();
                err.println("cancelled=skipped methods were not approved");
                return false;
            }
            String normalized = answer.trim();
            if (normalized.equalsIgnoreCase("Y")) {
                err.println("skippedMethods=retainedJavaBytecodeUserApproved");
                return true;
            }
            if (normalized.equalsIgnoreCase("N")) {
                err.println("cancelled=skipped methods were not approved");
                return false;
            }
            err.println("Please answer Y or N.");
        }
    }

    @Override
    public void onEvaluated(SkippedMethodGateEvidence evidence) {
        this.evidence = Objects.requireNonNull(evidence, "evidence");
    }

    SkippedMethodGateEvidence evidence() {
        return evidence;
    }

    private String sanitize(String value) {
        StringBuilder sanitized = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            sanitized.append(
                    character == '\r'
                                    || character == '\n'
                                    || Character.isISOControl(character)
                            ? ' '
                            : character);
        }
        return sanitized.toString();
    }
}
