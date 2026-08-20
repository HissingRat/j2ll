package xyz.melodysky.toolchain;

import java.util.List;
import java.util.Objects;
import xyz.melodysky.packaging.MethodTableHidingEntry;
import xyz.melodysky.packaging.MethodTableHidingPlan;
import xyz.melodysky.packaging.NativeRegistrationEntry;
import xyz.melodysky.packaging.NativeRegistrationPlan;
import xyz.melodysky.packaging.RuntimeLoaderPlan;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;
import xyz.melodysky.toolchain.nativetext.NativeTextCEmitter;

/**
 * Orchestrates owner-local, transient JNI registration functions.
 *
 * <p>Text planning and per-owner C emission remain separate so this class only
 * owns whole-plan validation and root registration ordering.</p>
 */
public final class HostNativeRegistrationSource {
    private static final NativeTextBuildKey COMPATIBILITY_BUILD_KEY =
            NativeTextBuildKey.fromUtf8("j2ll-registration-text-compatibility-v1");

    public String emit(
            NativeRegistrationPlan registrationPlan,
            MethodTableHidingPlan hidingPlan) {
        return emit(
                registrationPlan,
                hidingPlan,
                RuntimeLoaderPlan.create("native0"),
                COMPATIBILITY_BUILD_KEY);
    }

    public String emit(
            NativeRegistrationPlan registrationPlan,
            MethodTableHidingPlan hidingPlan,
            NativeTextBuildKey buildKey) {
        return emit(
                registrationPlan,
                hidingPlan,
                RuntimeLoaderPlan.create("native0"),
                buildKey);
    }

    public String emit(
            NativeRegistrationPlan registrationPlan,
            MethodTableHidingPlan hidingPlan,
            RuntimeLoaderPlan runtimeLoaderPlan) {
        return emit(
                registrationPlan,
                hidingPlan,
                runtimeLoaderPlan,
                COMPATIBILITY_BUILD_KEY);
    }

    public String emit(
            NativeRegistrationPlan registrationPlan,
            MethodTableHidingPlan hidingPlan,
            RuntimeLoaderPlan runtimeLoaderPlan,
            NativeTextBuildKey buildKey) {
        return emitWithPlan(
                registrationPlan,
                hidingPlan,
                runtimeLoaderPlan,
                buildKey).source();
    }

    Emission emitWithPlan(
            NativeRegistrationPlan registrationPlan,
            MethodTableHidingPlan hidingPlan,
            RuntimeLoaderPlan runtimeLoaderPlan,
            NativeTextBuildKey buildKey) {
        validatePlan(registrationPlan, hidingPlan);
        Objects.requireNonNull(runtimeLoaderPlan, "runtimeLoaderPlan");
        Objects.requireNonNull(buildKey, "buildKey");
        NativeTextCEmitter textEmitter = new NativeTextCEmitter();
        StringBuilder source = new StringBuilder(textEmitter.runtimeSource());
        HostNativeOwnerRegistrationSource ownerEmitter =
                new HostNativeOwnerRegistrationSource();
        HostNativeRegistrationResolverSource resolverEmitter =
                new HostNativeRegistrationResolverSource();
        List<NativeRegistrationTextPlan.Owner> owners;
        if (hidingPlan.changed()) {
            owners = physicalOwnerOrder(
                    NativeRegistrationTextPlan.hidden(hidingPlan, buildKey));
        } else {
            owners = physicalOwnerOrder(
                    NativeRegistrationTextPlan.ordinary(registrationPlan, buildKey));
        }
        NativeRegistrationControlTopologyPlan topologyPlan =
                new NativeRegistrationControlTopologyPlanner().plan(
                        owners,
                        buildKey);
        HostNativeRegistrationFailureLeafSource failureLeafSource =
                new HostNativeRegistrationFailureLeafSource();
        HostNativeRegistrationFailureLeafSource.Plan failureLeaves =
                failureLeafSource.plan(
                        buildKey,
                        topologyPlan.failureSymbols());
        source.append(failureLeafSource.emit(failureLeaves));
        for (NativeRegistrationControlTopologyPlan.Owner owner
                : topologyPlan.owners()) {
            source.append(ownerEmitter.emit(owner, failureLeaves));
        }
        NativeRegistrationResolverPlan resolverPlan = owners.isEmpty()
                ? null
                : NativeRegistrationResolverPlan.create(
                        runtimeLoaderPlan,
                        buildKey,
                        owners.size());
        if (resolverPlan != null) {
            source.append(resolverEmitter.ciphertextDeclaration(resolverPlan));
        }
        source.append(new HostNativeRegistrationChunkSource().emit(
                topologyPlan));
        source.append(new HostNativeRegistrationRootSource().emit(
                topologyPlan,
                failureLeaves,
                resolverPlan,
                resolverEmitter));
        Emission emission = new Emission(
                source.toString(),
                topologyPlan);
        new NativeRegistrationControlSourceVerifier().verify(
                emission.source(),
                topologyPlan);
        return emission;
    }

    private List<NativeRegistrationTextPlan.Owner> physicalOwnerOrder(
            List<NativeRegistrationTextPlan.Owner> owners) {
        return owners.stream()
                .sorted(java.util.Comparator.comparing(
                        owner -> owner.ownerText().symbol()))
                .toList();
    }

    private void validatePlan(
            NativeRegistrationPlan registrationPlan,
            MethodTableHidingPlan hidingPlan) {
        Objects.requireNonNull(registrationPlan, "registrationPlan");
        Objects.requireNonNull(hidingPlan, "hidingPlan");
        if (!hidingPlan.enabled()) {
            return;
        }
        if (!hidingPlan.changed()) {
            if (!registrationPlan.entries().isEmpty()) {
                throw new IllegalArgumentException(
                        "enabled method-table hiding plan does not cover the native registration plan");
            }
            return;
        }
        List<NativeRegistrationEntry> planned = hidingPlan.owners().stream()
                .flatMap(owner -> owner.registrationOrder().stream())
                .map(MethodTableHidingEntry::registration)
                .sorted()
                .toList();
        List<NativeRegistrationEntry> requested = registrationPlan.entries().stream()
                .sorted()
                .toList();
        if (!planned.equals(requested)) {
            throw new IllegalArgumentException(
                    "method-table hiding plan does not match the native registration plan");
        }
    }

    record Emission(
            String source,
            NativeRegistrationControlTopologyPlan topologyPlan) {
        Emission {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(topologyPlan, "topologyPlan");
        }
    }
}
