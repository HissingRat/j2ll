package xyz.melodysky.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class KnownBlockersWriterTest {
    @Test
    void writesDeterministicKnownBlockersGolden() {
        String json = new KnownBlockersWriter().json(List.of(
                new KnownBlockerEntry("zeta", "Z_REASON", "rc-blocker", "rc", "z behavior", "reports/z.json", "fix z"),
                new KnownBlockerEntry("alpha", "A_REASON", "future-blocker", "post-rc", "a behavior", "reports/a.json", "fix a")));

        assertEquals("""
                {
                  "schemaVersion": 1,
                  "reportVersion": 1,
                  "blockers": [
                    {
                      "id": "alpha",
                      "reasonCode": "A_REASON",
                      "severity": "future-blocker",
                      "targetMilestone": "post-rc",
                      "currentBehavior": "a behavior",
                      "reportLocation": "reports/a.json",
                      "suggestedFuturePath": "fix a"
                    },
                    {
                      "id": "zeta",
                      "reasonCode": "Z_REASON",
                      "severity": "rc-blocker",
                      "targetMilestone": "rc",
                      "currentBehavior": "z behavior",
                      "reportLocation": "reports/z.json",
                      "suggestedFuturePath": "fix z"
                    }
                  ]
                }
                """, json);
    }

    @Test
    void defaultBlockersTrackReleaseReadinessBoundaries() {
        String json = new KnownBlockersWriter().json();

        for (String reasonCode : List.of(
                "UNSUPPORTED_EXCEPTION_STATE_MERGE",
                "UNSUPPORTED_FINALLY_SUBROUTINE",
                "UNSUPPORTED_MONITOR_FINALLY_INTERACTION",
                "UNSUPPORTED_MULTI_EXIT_FINALLY",
                "UNSUPPORTED_NESTED_FINALLY",
                "ALT_METAFACTORY_UNSUPPORTED",
                "METHOD_HANDLE_CHAIN_UNSUPPORTED",
                "METHOD_HANDLE_PERMUTE_UNSUPPORTED",
                "METHOD_HANDLE_FILTER_UNSUPPORTED",
                "METHOD_HANDLE_FOLD_UNSUPPORTED",
                "METHOD_HANDLE_COLLECTOR_UNSUPPORTED",
                "CROSS_TARGET_RUNTIME_E2E_PENDING",
                "UNSAFE_RAW_MEMORY_UNSUPPORTED",
                "VAR_HANDLE_DYNAMIC_UNSUPPORTED",
                "WAIT_NOTIFY_UNSUPPORTED")) {
            assertTrue(json.contains("\"reasonCode\": \"" + reasonCode + "\""), reasonCode);
        }
        assertTrue(json.contains("\"reportLocation\": \"reports/skipped-method-report.json\""));
        assertTrue(json.contains("\"reportLocation\": \"reports/packaging-report.json\""));
        assertTrue(json.contains("\"severity\": \"future-blocker\""));
        assertTrue(json.contains("\"targetMilestone\": \"explicit-nongoal\""));
        assertTrue(json.contains("EXPLICIT_NONGOAL_STANDALONE_NATIVE_IMAGE"));
    }
}
