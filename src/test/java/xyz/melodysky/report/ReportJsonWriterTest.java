package xyz.melodysky.report;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticCode;
import xyz.melodysky.diagnostic.DiagnosticLocation;
import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.packaging.MethodRewriteStrategy;
import xyz.melodysky.pipeline.LoweringStatus;
import xyz.melodysky.pipeline.MethodEligibility;

class ReportJsonWriterTest {
    private final ReportJsonWriter writer = new ReportJsonWriter();

    @Test
    void writesDiagnosticsMinimumSchemaGolden() {
        Diagnostic diagnostic = Diagnostic.warning(
                        DiagnosticStage.LOWERING,
                        DiagnosticCode.of("UNSUPPORTED_EXCEPTION_STATE_MERGE"),
                        "exception-state merge cannot be native lowered")
                .at(DiagnosticLocation.methodLocation("pkg/Foo", "run", "()V").withInstructionOffset(12))
                .withDecision("skipped");

        assertEquals("""
                {
                  "schemaVersion": 1,
                  "reportVersion": 1,
                  "diagnostics": [
                    {
                      "severity": "warning",
                      "code": "UNSUPPORTED_EXCEPTION_STATE_MERGE",
                      "stage": "LOWERING",
                      "class": "pkg/Foo",
                      "method": "run",
                      "descriptor": "()V",
                      "instructionOffset": 12,
                      "artifactId": "pkg/Foo#run!()V",
                      "message": "exception-state merge cannot be native lowered",
                      "hint": "",
                      "decision": "skipped"
                    }
                  ]
                }
                """, writer.diagnosticsJson(List.of(diagnostic)));
    }

    @Test
    void writesLoweringReportMinimumSchemaGolden() {
        LoweringReportMethod lowered = new LoweringReportMethod(
                "pkg/Foo",
                "run",
                "()V",
                "run__8f3a21c0d4e5f607",
                LoweringStatus.NATIVE_LOWERED,
                MethodRewriteStrategy.NATIVE_ORIGINAL,
                List.of("public"),
                List.of("synthetic"),
                "j2ll_pkg_Foo_run_8f3a21c0d4e5f607",
                "pkg/Foo",
                "LLVM_NATIVE_PATH",
                List.of(),
                null,
                null);

        assertEquals("""
                {
                  "schemaVersion": 1,
                  "reportVersion": 1,
                  "requestedMethods": [
                    {
                      "class": "pkg/Foo",
                      "method": "run",
                      "descriptor": "()V",
                      "methodId": "run__8f3a21c0d4e5f607",
                      "status": "nativeLowered",
                      "rewriteStrategy": "nativeOriginal",
                      "retentionMode": "registeredNative",
                      "javaMethodPresent": true,
                      "registrationPresent": true,
                      "accessFlags": [
                        "public"
                      ],
                      "compilerFlags": [
                        "synthetic"
                      ],
                      "nativeSymbol": "j2ll_pkg_Foo_run_8f3a21c0d4e5f607",
                      "registrationOwner": "pkg/Foo",
                      "nativeImplementationPath": "LLVM_NATIVE_PATH",
                      "helperBackedSites": [],
                      "reasonCode": null,
                      "reason": null
                    }
                  ],
                  "ineligible": [
                    {
                      "selector": "pkg/Api#call!()V",
                      "class": "pkg/Api",
                      "method": "call",
                      "descriptor": "()V",
                      "status": "ineligible",
                      "reasonCode": "ABSTRACT_METHOD",
                      "reason": "abstract method has no lowerable body"
                    }
                  ],
                  "excluded": [
                    {
                      "selector": "pkg/Skip#run!()V",
                      "class": "pkg/Skip",
                      "method": "run",
                      "descriptor": "()V",
                      "status": "excluded",
                      "reasonCode": "BLACKLISTED",
                      "reason": "method excluded by blackList selector"
                    }
                  ]
                }
                """, writer.loweringJson(
                List.of(lowered),
                List.of(MethodEligibility.ineligible(
                        "pkg/Api",
                        "call",
                        "()V",
                        "pkg/Api#call!()V",
                        "ABSTRACT_METHOD",
                        "abstract method has no lowerable body")),
                List.of(MethodEligibility.excluded(
                        "pkg/Skip",
                        "run",
                        "()V",
                        "pkg/Skip#run!()V",
                        "BLACKLISTED",
                        "method excluded by blackList selector"))));
    }

    @Test
    void writesHelperBackedNativeLoweredSitesWithoutFallbackSurface() {
        LoweringReportMethod nativeLowered = new LoweringReportMethod(
                "pkg/Foo",
                "concat",
                "()Ljava/lang/String;",
                "concat__8f3a21c0d4e5f607",
                LoweringStatus.NATIVE_LOWERED,
                MethodRewriteStrategy.NATIVE_ORIGINAL,
                List.of("public", "static"),
                List.of(),
                "j2ll_pkg_Foo_concat_8f3a21c0d4e5f607",
                "pkg/Foo",
                "TEMPLATE_JNI_PATH",
                List.of(new HelperBackedSiteReport(
                        "j2ll_rt_string_builder_append_ref",
                        "a".repeat(64),
                        "HELPER_BACKED_LOWERING")),
                null,
                null);

        assertEquals("""
                {
                  "schemaVersion": 1,
                  "reportVersion": 1,
                  "requestedMethods": [
                    {
                      "class": "pkg/Foo",
                      "method": "concat",
                      "descriptor": "()Ljava/lang/String;",
                      "methodId": "concat__8f3a21c0d4e5f607",
                      "status": "nativeLowered",
                      "rewriteStrategy": "nativeOriginal",
                      "retentionMode": "registeredNative",
                      "javaMethodPresent": true,
                      "registrationPresent": true,
                      "accessFlags": [
                        "public",
                        "static"
                      ],
                      "compilerFlags": [],
                      "nativeSymbol": "j2ll_pkg_Foo_concat_8f3a21c0d4e5f607",
                      "registrationOwner": "pkg/Foo",
                      "nativeImplementationPath": "TEMPLATE_JNI_PATH",
                      "helperBackedSites": [
                        {
                          "helperKind": "j2ll_rt_string_builder_append_ref",
                          "helperIdentityHash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                          "reasonCode": "HELPER_BACKED_LOWERING"
                        }
                      ],
                      "reasonCode": null,
                      "reason": null
                    }
                  ],
                  "ineligible": [],
                  "excluded": []
                }
                """, writer.loweringJson(List.of(nativeLowered), List.of(), List.of()));
    }
}
