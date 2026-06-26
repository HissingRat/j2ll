package xyz.melodysky.report;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProtectionReportWriterTest {
    @Test
    void writesStableProtectionPassReportGolden() {
        assertEquals("""
                {
                  "schemaVersion": 1,
                  "seed": "seed-1",
                  "passes": [
                    {
                      "passName": "BASIC_BLOCK_SPLITTING",
                      "layer": "IR",
                      "status": "RAN",
                      "reasonCode": "OK",
                      "affectedMethods": [
                        "pkg/Foo#run!()I"
                      ],
                      "affectedSymbols": [],
                      "seed": "7"
                    },
                    {
                      "passName": "LLVM_NAME_OBFUSCATION",
                      "layer": "LLVM",
                      "status": "SKIPPED",
                      "reasonCode": "PROTECTION_PASS_DISABLED",
                      "affectedMethods": [
                        "pkg/Foo#run!()I"
                      ],
                      "affectedSymbols": [],
                      "seed": "7"
                    }
                  ]
                }
                """, new ProtectionReportWriter().json("seed-1", List.of(
                new ProtectionPassReport(
                        "LLVM_NAME_OBFUSCATION",
                        "LLVM",
                        "SKIPPED",
                        "PROTECTION_PASS_DISABLED",
                        List.of("pkg/Foo#run!()I"),
                        List.of(),
                        "7"),
                new ProtectionPassReport(
                        "BASIC_BLOCK_SPLITTING",
                        "IR",
                        "RAN",
                        "OK",
                        List.of("pkg/Foo#run!()I"),
                        List.of(),
                        "7"))));
    }
}
