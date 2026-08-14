package xyz.melodysky.toolchain;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;

/**
 * Collects explicitly allowlisted, metadata-free exception construction sites
 * before freezing one immutable physical-shard plan.
 *
 * <p>The collector never accepts an owner, member name, descriptor,
 * reflection target or business string. Calls outside the closed allowlist
 * remain in their original function-local native-text lifetime.</p>
 */
final class HostJniLowSensitivityThrowLeafPool {
    private static final Pattern THROW_NEW = Pattern.compile(
            "j2ll_throw_new\\s*\\(\\s*env\\s*,\\s*\"([^\"\\\\]*)\"\\s*,\\s*\"([^\"\\\\]*)\"\\s*\\)\\s*;");

    private final HostJniLowSensitivityThrowShardDeriver deriver;
    private final HostJniLowSensitivityThrowShardPlanner planner;
    private final ArrayList<HostJniLowSensitivityThrowShardPlan.Site> sites =
            new ArrayList<>();
    private final Set<String> scopes = new HashSet<>();
    private final Set<String> placeholders = new HashSet<>();
    private State state = State.COLLECTING;
    private boolean declarationAnchorEmitted;
    private HostJniLowSensitivityThrowShardPlan frozenPlan;

    HostJniLowSensitivityThrowLeafPool(
            NativeTextBuildKey buildKey) {
        deriver = new HostJniLowSensitivityThrowShardDeriver(
                Objects.requireNonNull(buildKey, "buildKey"));
        planner = new HostJniLowSensitivityThrowShardPlanner(deriver);
    }

    String rewrite(
            String scope,
            String fragment) {
        requireCollecting();
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(fragment, "fragment");
        if (scope.isBlank()) {
            throw new IllegalArgumentException(
                    "low-sensitivity fragment scope must not be blank");
        }
        if (!scopes.add(scope)) {
            throw new IllegalArgumentException(
                    "duplicate low-sensitivity fragment scope");
        }

        Matcher matcher = THROW_NEW.matcher(fragment);
        StringBuffer rewritten = new StringBuffer(fragment.length());
        EnumMap<HostJniLowSensitivityThrowLeaf, Integer> ordinals =
                new EnumMap<>(HostJniLowSensitivityThrowLeaf.class);
        boolean matched = false;
        while (matcher.find()) {
            HostJniLowSensitivityThrowLeaf leaf =
                    HostJniLowSensitivityThrowLeaf.find(
                            matcher.group(1),
                            matcher.group(2));
            if (leaf == null) {
                matcher.appendReplacement(
                        rewritten,
                        Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            int leafLocalOrdinal = ordinals.getOrDefault(leaf, 0);
            ordinals.put(leaf, leafLocalOrdinal + 1);
            String siteIdentity = scope
                    + '\0'
                    + leaf.identity()
                    + '\0'
                    + leafLocalOrdinal;
            String placeholder = deriver.sitePlaceholder(siteIdentity);
            if (!placeholders.add(placeholder)) {
                throw new IllegalStateException(
                        "low-sensitivity throw-site placeholder collision");
            }
            sites.add(new HostJniLowSensitivityThrowShardPlan.Site(
                    placeholder,
                    siteIdentity,
                    scope,
                    leafLocalOrdinal,
                    leaf.identity(),
                    leaf.exceptionClass(),
                    leaf.message()));
            matcher.appendReplacement(
                    rewritten,
                    Matcher.quoteReplacement(placeholder + "(env);"));
            matched = true;
        }
        matcher.appendTail(rewritten);
        if (!matched) {
            return fragment;
        }
        if (!declarationAnchorEmitted) {
            declarationAnchorEmitted = true;
            return deriver.declarationAnchor()
                    + '\n'
                    + rewritten;
        }
        return rewritten.toString();
    }

    boolean isEmpty() {
        return sites.isEmpty();
    }

    HostJniLowSensitivityThrowShardPlan freeze() {
        requireCollecting();
        state = State.FROZEN;
        frozenPlan = planner.plan(
                deriver.declarationAnchor(),
                sites);
        if (frozenPlan.isEmpty() == declarationAnchorEmitted) {
            throw new IllegalStateException(
                    "low-sensitivity declaration-anchor collection mismatch");
        }
        return frozenPlan;
    }

    HostJniLowSensitivityThrowShardPlan frozenPlan() {
        if (state != State.FROZEN || frozenPlan == null) {
            throw new IllegalStateException(
                    "low-sensitivity shard plan is not frozen");
        }
        return frozenPlan;
    }

    private void requireCollecting() {
        if (state != State.COLLECTING) {
            throw new IllegalStateException(
                    "low-sensitivity shard collection is already frozen");
        }
    }

    private enum State {
        COLLECTING,
        FROZEN
    }
}
