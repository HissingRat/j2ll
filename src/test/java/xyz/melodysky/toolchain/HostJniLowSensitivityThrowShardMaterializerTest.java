package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;

final class HostJniLowSensitivityThrowShardMaterializerTest {
    private final HostJniLowSensitivityThrowShardMaterializer materializer =
            new HostJniLowSensitivityThrowShardMaterializer();

    @Test
    void replacesOneAnchorAndEverySiteInOneMaterializationPass() {
        Fixture fixture = fixture(33);

        String materialized = materializer.materialize(
                fixture.source(),
                fixture.plan());

        assertFalse(materialized.contains(
                HostJniLowSensitivityThrowShardDeriver
                        .placeholderPrefix()));
        assertFalse(materialized.contains(
                HostJniLowSensitivityThrowShardDeriver.anchorPrefix()));
        assertEquals(33, directCalls(fixture.plan(), materialized));
        assertEquals(
                fixture.plan().shards().size(),
                occurrences(materialized, "__attribute__((noinline, cold));"));
        for (HostJniLowSensitivityThrowShardPlan.Shard shard
                : fixture.plan().shards()) {
            assertEquals(
                    shard.sites().size(),
                    occurrences(materialized, shard.symbol() + "(env);"));
        }
    }

    @Test
    void rejectsMissingDuplicateAndUnknownSitePlaceholders() {
        Fixture fixture = fixture(2);
        String first = fixture.plan().sites().get(0).placeholder();
        String second = fixture.plan().sites().get(1).placeholder();
        String unknown = HostJniLowSensitivityThrowShardDeriver
                .placeholderPrefix() + "a".repeat(32);
        if (fixture.plan().placeholders().contains(unknown)) {
            unknown = HostJniLowSensitivityThrowShardDeriver
                    .placeholderPrefix() + "b".repeat(32);
        }
        String unknownPlaceholder = unknown;

        assertThrows(
                IllegalStateException.class,
                () -> materializer.materialize(
                        fixture.source().replace(first + "(env);", ""),
                        fixture.plan()));
        assertThrows(
                IllegalStateException.class,
                () -> materializer.materialize(
                        fixture.source() + '\n' + second + "(env);\n",
                        fixture.plan()));
        assertThrows(
                IllegalStateException.class,
                () -> materializer.materialize(
                        fixture.source()
                                + '\n'
                                + unknownPlaceholder
                                + "(env);\n",
                        fixture.plan()));
        assertThrows(
                IllegalStateException.class,
                () -> materializer.materialize(
                        fixture.source()
                                + '\n'
                                + HostJniLowSensitivityThrowShardDeriver
                                        .placeholderPrefix()
                                + "q".repeat(32)
                                + "(env);\n",
                        fixture.plan()));
    }

    @Test
    void rejectsMissingDuplicateAndUnknownDeclarationAnchors() {
        Fixture fixture = fixture(1);
        String anchor = fixture.plan().declarationAnchor();
        String unknown = HostJniLowSensitivityThrowShardDeriver
                .anchorPrefix() + "a".repeat(32);
        if (anchor.equals(unknown)) {
            unknown = HostJniLowSensitivityThrowShardDeriver
                    .anchorPrefix() + "b".repeat(32);
        }
        String unknownAnchor = unknown;

        assertThrows(
                IllegalStateException.class,
                () -> materializer.materialize(
                        fixture.source().replace(anchor, ""),
                        fixture.plan()));
        assertThrows(
                IllegalStateException.class,
                () -> materializer.materialize(
                        fixture.source() + '\n' + anchor + '\n',
                        fixture.plan()));
        assertThrows(
                IllegalStateException.class,
                () -> materializer.materialize(
                        fixture.source().replace(
                                anchor,
                                unknownAnchor),
                        fixture.plan()));
        assertThrows(
                IllegalStateException.class,
                () -> materializer.materialize(
                        fixture.source().replace(
                                anchor,
                                HostJniLowSensitivityThrowShardDeriver
                                        .anchorPrefix()
                                        + "q".repeat(32)),
                        fixture.plan()));
    }

    @Test
    void placeholderLikeTextOutsideExactCodeTokensIsNotRewritten() {
        Fixture fixture = fixture(1);
        String placeholder = fixture.plan().sites().get(0).placeholder();
        String suffixIdentifier = placeholder + "suffix";
        String decorated = fixture.source()
                + "// " + placeholder + "\n"
                + "static const char* sample = \"" + placeholder + "\";\n"
                + "static int " + suffixIdentifier + " = 1;\n";

        String materialized = materializer.materialize(
                decorated,
                fixture.plan());

        assertTrue(materialized.contains("// " + placeholder));
        assertTrue(materialized.contains("\"" + placeholder + "\""));
        assertTrue(materialized.contains(suffixIdentifier));
        assertEquals(1, directCalls(fixture.plan(), materialized));
    }

    private Fixture fixture(int useCount) {
        HostJniLowSensitivityThrowShardDeriver deriver =
                new HostJniLowSensitivityThrowShardDeriver(
                        NativeTextBuildKey.fromUtf8(
                                "materializer-build-" + useCount));
        String scope = "materializer-scope";
        String leafIdentity =
                HostJniLowSensitivityThrowShardFixture.EXCEPTION_CLASS
                        + '\0'
                        + HostJniLowSensitivityThrowShardFixture.MESSAGE;
        ArrayList<HostJniLowSensitivityThrowShardPlan.Site> sites =
                new ArrayList<>();
        StringBuilder source = new StringBuilder()
                .append(deriver.declarationAnchor())
                .append('\n');
        for (int index = 0; index < useCount; index++) {
            String identity = scope
                    + '\0'
                    + leafIdentity
                    + '\0'
                    + index;
            String placeholder = deriver.sitePlaceholder(identity);
            sites.add(new HostJniLowSensitivityThrowShardPlan.Site(
                    placeholder,
                    identity,
                    scope,
                    index,
                    leafIdentity,
                    HostJniLowSensitivityThrowShardFixture.EXCEPTION_CLASS,
                    HostJniLowSensitivityThrowShardFixture.MESSAGE));
            source.append("static void f")
                    .append(index)
                    .append("(JNIEnv* env) { ")
                    .append(placeholder)
                    .append("(env); }\n");
        }
        HostJniLowSensitivityThrowShardPlan plan =
                new HostJniLowSensitivityThrowShardPlanner(deriver)
                        .plan(deriver.declarationAnchor(), sites);
        return new Fixture(plan, source.toString());
    }

    private int directCalls(
            HostJniLowSensitivityThrowShardPlan plan,
            String source) {
        return plan.shards().stream()
                .mapToInt(shard -> occurrences(
                        source,
                        shard.symbol() + "(env);"))
                .sum();
    }

    private int occurrences(String value, String needle) {
        return value.split(
                        java.util.regex.Pattern.quote(needle),
                        -1)
                .length
                - 1;
    }

    private record Fixture(
            HostJniLowSensitivityThrowShardPlan plan,
            String source) {}
}
