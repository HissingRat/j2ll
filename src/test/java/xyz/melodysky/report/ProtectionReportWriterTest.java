package xyz.melodysky.report;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.config.ProtectionSeedMode;

class ProtectionReportWriterTest {
    @Test
    void writesStableProtectionPassReportGolden() {
        assertEquals("""
                {
                  "schemaVersion": 1,
                  "reportVersion": 1,
                  "seedHash": "0eb026731d9ea3f870511f8c18daeb814eaa2c9e276082b204f2a962212fb5bd",
                  "sensitivePlaintextFacts": [],
                  "coverage": {
                    "evaluatedFacts": 2,
                    "requestedFacts": 1,
                    "applicableFacts": 0,
                    "notApplicableFacts": 0,
                    "unknownApplicabilityFacts": 2,
                    "affectedFacts": 0,
                    "affectedRateBasisPoints": 0,
                    "passes": [
                      {
                        "layer": "IR",
                        "passName": "BASIC_BLOCK_SPLITTING",
                        "evaluatedSubjects": 1,
                        "requestedSubjects": 1,
                        "applicableSubjects": 0,
                        "notApplicableSubjects": 0,
                        "unknownApplicabilitySubjects": 1,
                        "affectedSubjects": 0,
                        "affectedRateBasisPoints": 0,
                        "statusCounts": {
                          "RAN": 1
                        },
                        "reasonCounts": {
                          "OK": 1
                        }
                      },
                      {
                        "layer": "LLVM",
                        "passName": "LLVM_NAME_OBFUSCATION",
                        "evaluatedSubjects": 1,
                        "requestedSubjects": 0,
                        "applicableSubjects": 0,
                        "notApplicableSubjects": 0,
                        "unknownApplicabilitySubjects": 1,
                        "affectedSubjects": 0,
                        "affectedRateBasisPoints": 0,
                        "statusCounts": {
                          "SKIPPED": 1
                        },
                        "reasonCounts": {
                          "PROTECTION_PASS_DISABLED": 1
                        }
                      }
                    ],
                    "facts": [
                      {
                        "layer": "IR",
                        "passName": "BASIC_BLOCK_SPLITTING",
                        "subjectIdentityHash": "4699f487cd5b59a201c8a7d2b7e94a0789bda358ece377d20d6193a2642b4811",
                        "requested": true,
                        "applicability": "unknown",
                        "affected": false,
                        "status": "RAN",
                        "reasonCode": "OK"
                      },
                      {
                        "layer": "LLVM",
                        "passName": "LLVM_NAME_OBFUSCATION",
                        "subjectIdentityHash": "4699f487cd5b59a201c8a7d2b7e94a0789bda358ece377d20d6193a2642b4811",
                        "requested": false,
                        "applicability": "unknown",
                        "affected": false,
                        "status": "SKIPPED",
                        "reasonCode": "PROTECTION_PASS_DISABLED"
                      }
                    ]
                  },
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

    @Test
    void recordsRandomizedModeAndContextBoundIdentity() {
        String identityHash = "ab".repeat(32);

        String json = new ProtectionReportWriter().json(
                ProtectionSeedMode.RANDOMIZED,
                identityHash,
                List.of());

        org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"seedMode\": \"randomized\""));
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"seedHash\": \"" + identityHash + "\""));
    }
}
