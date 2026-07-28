package xyz.melodysky.protection.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import xyz.melodysky.config.ProtectionSeedMode;

class BusinessStringCarrierReuseAuditTest {
    private static final String NAME_A = "0123456789abcdef01234567";
    private static final String NAME_B = "89abcdef0123456789abcdef";
    private static final String NAME_C = "fedcba9876543210fedcba98";

    private final BusinessStringCarrierLlvmScanner scanner =
            new BusinessStringCarrierLlvmScanner();
    private final BusinessStringCarrierReuseAudit audit =
            new BusinessStringCarrierReuseAudit();

    @Test
    void parsesOnlyExactCarrierDeclarationsAndRetainsHashOnlyEvidence() {
        BusinessStringCarrierSnapshot snapshot = scanner.scan("""
                  %%j2ll_v_%s = add i64 0, -9223372036854775808
                %%not_a_carrier = add i64 0, 7
                  %%j2ll_v_user_value = add i64 0, 8
                  call void @sink(i64 %%j2ll_v_%s)
                  %%j2ll_v_%s = add i64 0, 9223372036854775807
                """.formatted(NAME_A, NAME_A, NAME_B));

        assertEquals(2, snapshot.carrierCount());
        assertEquals(2, snapshot.carrierNameIdentityHashes().size());
        assertEquals(2, snapshot.numericTokenIdentityHashes().size());
        assertFalse(snapshot.toString().contains(NAME_A));
        assertFalse(snapshot.toString().contains("-9223372036854775808"));
    }

    @Test
    void randomizedBuildRequiresZeroOverlapAcrossBothAnchorKinds() {
        BusinessStringCarrierSnapshot first = scan(
                declaration(NAME_A, 11),
                declaration(NAME_B, 22));
        BusinessStringCarrierSnapshot diversified = scan(
                declaration(NAME_C, 33));

        BusinessStringCarrierReuseMetric passed = audit.compare(
                ProtectionSeedMode.RANDOMIZED,
                first,
                diversified);
        BusinessStringCarrierReuseMetric reusedName = audit.compare(
                ProtectionSeedMode.RANDOMIZED,
                first,
                scan(declaration(NAME_A, 44)));
        BusinessStringCarrierReuseMetric reusedToken = audit.compare(
                ProtectionSeedMode.RANDOMIZED,
                first,
                scan(declaration(NAME_C, 22)));
        String json =
                new BusinessStringCarrierReuseReportWriter().json(passed);

        assertTrue(passed.passed());
        assertEquals(0, passed.commonNameCount());
        assertEquals(0, passed.commonNumericTokenCount());
        assertEquals(0, passed.reuseRateBasisPoints());
        assertEquals(
                BusinessStringCarrierReuseAudit.RANDOMIZED_DIVERSIFIED,
                passed.reasonCode());
        assertFalse(reusedName.passed());
        assertEquals(1, reusedName.commonNameCount());
        assertEquals(
                BusinessStringCarrierReuseAudit.RANDOMIZED_REUSE_DETECTED,
                reusedName.reasonCode());
        assertFalse(reusedToken.passed());
        assertEquals(1, reusedToken.commonNumericTokenCount());
        assertTrue(json.contains("\"firstCarrierCount\": 2"));
        assertTrue(json.contains("\"reuseRateBasisPoints\": 0"));
        assertTrue(json.contains("\"passed\": true"));
        assertFalse(json.contains(NAME_A));
        assertFalse(json.contains("\"11\""));
    }

    @Test
    void reproducibleBuildRequiresExactNameAndNumericTokenSets() {
        BusinessStringCarrierSnapshot first = scan(
                declaration(NAME_A, 11),
                declaration(NAME_B, 22));
        BusinessStringCarrierSnapshot reordered = scan(
                declaration(NAME_B, 22),
                declaration(NAME_A, 11));
        BusinessStringCarrierSnapshot tokenChanged = scan(
                declaration(NAME_A, 11),
                declaration(NAME_B, 23));

        BusinessStringCarrierReuseMetric matched = audit.compare(
                ProtectionSeedMode.REPRODUCIBLE,
                first,
                reordered);
        BusinessStringCarrierReuseMetric changed = audit.compare(
                ProtectionSeedMode.REPRODUCIBLE,
                first,
                tokenChanged);

        assertTrue(matched.passed());
        assertEquals(2, matched.commonNameCount());
        assertEquals(2, matched.commonNumericTokenCount());
        assertEquals(10_000, matched.reuseRateBasisPoints());
        assertEquals(
                BusinessStringCarrierReuseAudit.REPRODUCIBLE_MATCHED,
                matched.reasonCode());
        assertFalse(changed.passed());
        assertEquals(
                BusinessStringCarrierReuseAudit.REPRODUCIBLE_CHANGED,
                changed.reasonCode());
    }

    @Test
    void rejectsMalformedOverflowAndDuplicateNameWithoutLeakingTheLine() {
        BusinessStringCarrierScanException malformed = assertThrows(
                BusinessStringCarrierScanException.class,
                () -> scanner.scan(
                        "  %j2ll_v_" + NAME_A + " = sub i64 0, 1\n"));
        BusinessStringCarrierScanException overflow = assertThrows(
                BusinessStringCarrierScanException.class,
                () -> scanner.scan(
                        declarationText(NAME_A, "9223372036854775808")));
        BusinessStringCarrierScanException duplicate = assertThrows(
                BusinessStringCarrierScanException.class,
                () -> scan(
                        declaration(NAME_A, 11),
                        declaration(NAME_A, 12)));

        assertEquals(
                BusinessStringCarrierLlvmScanner.MALFORMED_DECLARATION,
                malformed.reasonCode());
        assertEquals(
                BusinessStringCarrierLlvmScanner.MALFORMED_DECLARATION,
                overflow.reasonCode());
        assertEquals(
                BusinessStringCarrierLlvmScanner.DUPLICATE_NAME,
                duplicate.reasonCode());
        assertFalse(malformed.getMessage().contains(NAME_A));
        assertFalse(overflow.getMessage().contains("9223372036854775808"));
    }

    @Test
    void duplicateNumericTokensAreCountedAsOneReusableAnchor() {
        BusinessStringCarrierSnapshot first = scan(
                declaration(NAME_A, 11),
                declaration(NAME_B, 11));
        BusinessStringCarrierSnapshot second = scan(
                declaration(NAME_A, 11),
                declaration(NAME_B, 11));

        BusinessStringCarrierReuseMetric metric = audit.compare(
                ProtectionSeedMode.REPRODUCIBLE,
                first,
                second);

        assertEquals(2, first.carrierCount());
        assertEquals(1, first.numericTokenIdentityHashes().size());
        assertTrue(metric.passed());
        assertEquals(1, metric.commonNumericTokenCount());
        assertEquals(10_000, metric.reuseRateBasisPoints());
    }

    @Test
    void emptyRandomizedComparisonIsExplicitlyNotApplicable() {
        BusinessStringCarrierSnapshot empty = scanner.scan("");
        BusinessStringCarrierSnapshot nonEmpty =
                scan(declaration(NAME_A, 11));

        BusinessStringCarrierReuseMetric randomized = audit.compare(
                ProtectionSeedMode.RANDOMIZED,
                empty,
                nonEmpty);
        BusinessStringCarrierReuseMetric reproducibleEmpty = audit.compare(
                ProtectionSeedMode.REPRODUCIBLE,
                empty,
                empty);
        BusinessStringCarrierReuseMetric reproducibleMismatch = audit.compare(
                ProtectionSeedMode.REPRODUCIBLE,
                empty,
                nonEmpty);

        assertTrue(randomized.passed());
        assertEquals(
                BusinessStringCarrierReuseAudit.EMPTY_COMPARISON,
                randomized.reasonCode());
        assertEquals(0, randomized.reuseRateBasisPoints());
        assertTrue(reproducibleEmpty.passed());
        assertFalse(reproducibleMismatch.passed());
    }

    private BusinessStringCarrierSnapshot scan(String... lines) {
        return scanner.scan(String.join("\n", lines));
    }

    private String declaration(String name, long token) {
        return declarationText(name, Long.toString(token));
    }

    private String declarationText(String name, String token) {
        return "%j2ll_v_" + name + " = add i64 0, " + token;
    }
}
