package xyz.melodysky.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class DiagnosticBagTest {
    @Test
    void sortsAndDeduplicatesDiagnosticsStably() {
        Diagnostic later = Diagnostic.warning(DiagnosticStage.PARSE,
                        DiagnosticCode.of("Z_PARSE_WARNING"),
                        "later")
                .at(DiagnosticLocation.methodLocation("pkg/Zed", "run", "()V"));
        Diagnostic first = Diagnostic.warning(DiagnosticStage.CFG,
                        DiagnosticCode.of("A_CFG_WARNING"),
                        "first")
                .at(DiagnosticLocation.methodLocation("pkg/Alpha", "run", "()V"));

        DiagnosticBag bag = new DiagnosticBag();
        bag.add(later);
        bag.add(first);
        bag.add(later);

        assertEquals(List.of(first, later), bag.diagnostics());
        assertEquals(2, bag.size());
    }
}
