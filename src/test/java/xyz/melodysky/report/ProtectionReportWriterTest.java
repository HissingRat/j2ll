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
                  "reportVersion": 1,
                  "seedHash": "0eb026731d9ea3f870511f8c18daeb814eaa2c9e276082b204f2a962212fb5bd",
                  "sensitivePlaintextFacts": [],
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
                      "sensitivePlaintextFacts": [],
                      "seedHash": "7902699be42c8a8e46fbbb4501726517e86b22c56a189f7625a6da49081b2451"
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
                      "sensitivePlaintextFacts": [],
                      "seedHash": "7902699be42c8a8e46fbbb4501726517e86b22c56a189f7625a6da49081b2451"
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

    @Test
    void writesSensitivePlaintextFactsByHashOnly() {
        String json = new ProtectionReportWriter().json("seed-1", List.of(new ProtectionPassReport(
                "STRING_ENCRYPTION",
                "IR",
                "RAN",
                "OK",
                List.of("pkg/Foo#secret!()Ljava/lang/String;"),
                List.of(),
                "7",
                List.of(SensitivePlaintextFact.of(
                        "plain-secret",
                        "pkg/Foo#secret!()Ljava/lang/String;",
                        "STRING_ENCRYPTION",
                        List.of("generated-c", "llvm-ir"))))));

        org.junit.jupiter.api.Assertions.assertFalse(json.contains("plain-secret"));
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("seed-1"));
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"literalHash\""));
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"sourceMethod\": \"pkg/Foo#secret!()Ljava/lang/String;\""));
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"passName\": \"STRING_ENCRYPTION\""));
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"pathKind\": \"LLVM_NATIVE_PATH\""));
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"gateMode\": \"blocking\""));
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"promotionReason\": \"llvmNativeSurface\""));
    }
}
