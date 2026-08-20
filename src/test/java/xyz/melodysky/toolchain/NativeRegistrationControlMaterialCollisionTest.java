package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.packaging.NativeRegistrationEntry;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;
import xyz.melodysky.toolchain.nativetext.NativeTextEncodingTestFixture;

final class NativeRegistrationControlMaterialCollisionTest {
    @Test
    void rejectsEarlierRegistrationMaterialCollidingWithFutureControlSymbols() {
        NativeTextBuildKey key = NativeRegistrationControlTestFixture.key(
                "future-registration-material-collision");
        List<NativeRegistrationTextPlan.Owner> owners =
                NativeRegistrationControlTestFixture.physicalOwners(11, key);
        NativeRegistrationControlTopologyPlan baseline =
                new NativeRegistrationControlTopologyPlanner().plan(
                        owners,
                        key);
        List<String> futureControlSymbols = List.of(
                baseline.owners().get(1).symbol(),
                baseline.chunks().get(baseline.chunks().size() - 1).symbol(),
                baseline.routePlan().route(2).symbol());

        for (String collision : futureControlSymbols) {
            for (RegistrationMaterial material
                    : RegistrationMaterial.values()) {
                ArrayList<NativeRegistrationTextPlan.Owner> mutated =
                        new ArrayList<>(owners);
                mutated.set(
                        0,
                        collide(mutated.get(0), material, collision));
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new NativeRegistrationControlTopologyPlanner()
                                .plan(mutated, key),
                        material + " -> " + collision);
            }
        }
    }

    private NativeRegistrationTextPlan.Owner collide(
            NativeRegistrationTextPlan.Owner owner,
            RegistrationMaterial material,
            String symbol) {
        NativeRegistrationTextPlan.Binding binding = owner.bindings().get(0);
        NativeRegistrationEntry registration = binding.registration();
        NativeRegistrationTextPlan.Binding collidedBinding = switch (material) {
            case NATIVE_SYMBOL -> new NativeRegistrationTextPlan.Binding(
                    new NativeRegistrationEntry(
                            registration.registrationOwner(),
                            registration.methodName(),
                            registration.descriptor(),
                            symbol),
                    binding.nameText(),
                    binding.descriptorText());
            case METHOD_TEXT -> new NativeRegistrationTextPlan.Binding(
                    registration,
                    NativeTextEncodingTestFixture.withSymbol(
                            binding.nameText(),
                            symbol),
                    binding.descriptorText());
            default -> binding;
        };
        List<NativeRegistrationTextPlan.Binding> bindings =
                material == RegistrationMaterial.NATIVE_SYMBOL
                                || material == RegistrationMaterial.METHOD_TEXT
                        ? List.of(collidedBinding)
                        : owner.bindings();
        var ownerText = material == RegistrationMaterial.OWNER_TEXT
                ? NativeTextEncodingTestFixture.withSymbol(
                        owner.ownerText(),
                        symbol)
                : owner.ownerText();
        List<NativeRegistrationTextPlan.TextGroup> groups = owner.textGroups();
        if (material == RegistrationMaterial.GROUP_ENCODING) {
            ArrayList<NativeRegistrationTextPlan.TextGroup> collidedGroups =
                    new ArrayList<>(groups);
            NativeRegistrationTextPlan.TextGroup group =
                    collidedGroups.get(0);
            collidedGroups.set(
                    0,
                    new NativeRegistrationTextPlan.TextGroup(
                            group.purpose(),
                            NativeTextEncodingTestFixture.withSymbol(
                                    group.encoding(),
                                    symbol),
                            group.memberOffsets()));
            groups = List.copyOf(collidedGroups);
        }
        return new NativeRegistrationTextPlan.Owner(
                owner.owner(),
                ownerText,
                bindings,
                groups);
    }

    private enum RegistrationMaterial {
        NATIVE_SYMBOL,
        OWNER_TEXT,
        METHOD_TEXT,
        GROUP_ENCODING
    }
}
