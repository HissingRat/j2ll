package xyz.melodysky.toolchain;

import java.util.ArrayList;
import java.util.List;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;

/** Test-only input fixture for bounded low-sensitivity throw-leaf sharding. */
final class HostJniLowSensitivityThrowShardFixture {
    static final int MAX_USES_PER_SHARD = 32;
    static final String EXCEPTION_CLASS =
            "java/lang/NullPointerException";
    static final String MESSAGE = "array is null";

    private HostJniLowSensitivityThrowShardFixture() {}

    static Scenario singleFragment(int useCount) {
        return fragments(useCount);
    }

    static Scenario fragments(int... fragmentUseCounts) {
        if (fragmentUseCounts == null) {
            throw new NullPointerException("fragmentUseCounts");
        }
        ArrayList<Fragment> fragments = new ArrayList<>();
        ArrayList<Site> sites = new ArrayList<>();
        for (int fragmentIndex = 0;
                fragmentIndex < fragmentUseCounts.length;
                fragmentIndex++) {
            int useCount = fragmentUseCounts[fragmentIndex];
            if (useCount < 0) {
                throw new IllegalArgumentException(
                        "fragment use count must not be negative");
            }
            String scope = "fixture-fragment-" + fragmentIndex;
            StringBuilder source = new StringBuilder();
            ArrayList<Site> fragmentSites = new ArrayList<>();
            for (int localIndex = 0;
                    localIndex < useCount;
                    localIndex++) {
                String function = "fixture_throw_"
                        + decimalToken(fragmentIndex)
                        + '_'
                        + decimalToken(localIndex);
                String siteId = scope + ':' + localIndex;
                Site site = new Site(
                        siteId,
                        scope,
                        function,
                        fragmentIndex,
                        localIndex);
                sites.add(site);
                fragmentSites.add(site);
                source.append("static void ")
                        .append(function)
                        .append("(JNIEnv* env) {\n")
                        .append("    j2ll_throw_new(env, \"")
                        .append(EXCEPTION_CLASS)
                        .append("\", \"")
                        .append(MESSAGE)
                        .append("\");\n")
                        .append("}\n");
            }
            fragments.add(new Fragment(
                    scope,
                    source.toString(),
                    fragmentSites));
        }
        return new Scenario(fragments, sites);
    }

    static int expectedShardCount(int useCount) {
        if (useCount < 0) {
            throw new IllegalArgumentException(
                    "use count must not be negative");
        }
        return (useCount + MAX_USES_PER_SHARD - 1)
                / MAX_USES_PER_SHARD;
    }

    static Collected collect(
            String buildKey,
            Scenario scenario) {
        HostJniLowSensitivityThrowLeafPool pool =
                new HostJniLowSensitivityThrowLeafPool(
                        NativeTextBuildKey.fromUtf8(buildKey));
        StringBuilder stagedSource = new StringBuilder();
        for (Fragment fragment : scenario.fragments()) {
            stagedSource.append(pool.rewrite(
                    fragment.scope(),
                    fragment.source()));
        }
        return new Collected(
                pool,
                pool.freeze(),
                stagedSource.toString());
    }

    private static String decimalToken(int value) {
        return String.format(java.util.Locale.ROOT, "%03d", value);
    }

    record Scenario(
            List<Fragment> fragments,
            List<Site> sites) {
        Scenario {
            fragments = List.copyOf(fragments);
            sites = List.copyOf(sites);
        }

        int useCount() {
            return sites.size();
        }

        int expectedShardCount() {
            return HostJniLowSensitivityThrowShardFixture
                    .expectedShardCount(useCount());
        }
    }

    record Fragment(
            String scope,
            String source,
            List<Site> sites) {
        Fragment {
            sites = List.copyOf(sites);
        }
    }

    record Site(
            String id,
            String fragmentScope,
            String function,
            int fragmentIndex,
            int localIndex) {}

    record Collected(
            HostJniLowSensitivityThrowLeafPool pool,
            HostJniLowSensitivityThrowShardPlan plan,
            String stagedSource) {}
}
