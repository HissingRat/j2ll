package xyz.melodysky.toolchain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import xyz.melodysky.packaging.MethodTableHidingEntry;
import xyz.melodysky.packaging.MethodTableHidingPlan;
import xyz.melodysky.packaging.NativeRegistrationEntry;
import xyz.melodysky.packaging.NativeRegistrationPlan;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;
import xyz.melodysky.toolchain.nativetext.NativeTextEncoder;
import xyz.melodysky.toolchain.nativetext.NativeTextEncoding;
import xyz.melodysky.toolchain.nativetext.NativeTextPurpose;

/** Immutable owner-local encodings consumed by native registration emission. */
final class NativeRegistrationTextPlan {
    private NativeRegistrationTextPlan() {}

    static List<Owner> hidden(
            MethodTableHidingPlan hidingPlan,
            NativeTextBuildKey buildKey) {
        NativeTextEncoder encoder = new NativeTextEncoder();
        ArrayList<Owner> owners = new ArrayList<>();
        for (var ownerPlan : hidingPlan.owners()) {
            owners.add(owner(
                    ownerPlan.registrationOwner(),
                    ownerPlan.registrationOrder().stream()
                            .map(MethodTableHidingEntry::registration)
                            .toList(),
                    buildKey,
                    encoder,
                    false));
        }
        return List.copyOf(owners);
    }

    static List<Owner> ordinary(
            NativeRegistrationPlan registrationPlan,
            NativeTextBuildKey buildKey) {
        Map<String, List<NativeRegistrationEntry>> entriesByOwner = new TreeMap<>();
        for (NativeRegistrationEntry entry : registrationPlan.entries()) {
            entriesByOwner.computeIfAbsent(
                            entry.registrationOwner(),
                            ignored -> new ArrayList<>())
                    .add(entry);
        }
        NativeTextEncoder encoder = new NativeTextEncoder();
        return entriesByOwner.entrySet().stream()
                .map(entry -> owner(
                        entry.getKey(),
                        entry.getValue(),
                        buildKey,
                        encoder,
                        true))
                .toList();
    }

    private static Owner owner(
            String owner,
            List<NativeRegistrationEntry> registrations,
            NativeTextBuildKey buildKey,
            NativeTextEncoder encoder,
            boolean diversifyBindingOrder) {
        NativeTextEncoding ownerText = encoder.encode(
                buildKey,
                NativeTextPurpose.REGISTRATION_OWNER,
                "registration-owner:" + owner,
                owner);
        List<Binding> encodedBindings = registrations.stream()
                .map(entry -> binding(entry, buildKey, encoder))
                .toList();
        List<Binding> bindings = diversifyBindingOrder
                ? encodedBindings.stream()
                        .sorted(java.util.Comparator
                                .comparing((Binding binding) ->
                                        binding.nameText().symbol())
                                .thenComparing(binding ->
                                        binding.descriptorText().symbol())
                                .thenComparing(binding ->
                                        binding.registration().nativeSymbol()))
                        .toList()
                : encodedBindings;
        return new Owner(
                owner,
                ownerText,
                bindings,
                encoder.encode(
                        buildKey,
                        NativeTextPurpose.REGISTRATION_ERROR,
                        "registration-owner:" + owner + ":rollback-failed",
                        "native owner registration rollback failed"),
                encoder.encode(
                        buildKey,
                        NativeTextPurpose.REGISTRATION_ERROR,
                        "registration-owner:" + owner + ":exception-restore-failed",
                        "native owner registration exception restore failed"));
    }

    private static Binding binding(
            NativeRegistrationEntry entry,
            NativeTextBuildKey buildKey,
            NativeTextEncoder encoder) {
        String identity = entry.registrationOwner()
                + "#"
                + entry.methodName()
                + "!"
                + entry.descriptor();
        return new Binding(
                entry,
                encoder.encode(
                        buildKey,
                        NativeTextPurpose.REGISTRATION_METHOD_NAME,
                        identity + ":method-name",
                        entry.methodName()),
                encoder.encode(
                        buildKey,
                        NativeTextPurpose.REGISTRATION_DESCRIPTOR,
                        identity + ":descriptor",
                        entry.descriptor()));
    }

    record Owner(
            String owner,
            NativeTextEncoding ownerText,
            List<Binding> bindings,
            NativeTextEncoding rollbackFailureText,
            NativeTextEncoding exceptionRestoreFailureText) {}

    record Binding(
            NativeRegistrationEntry registration,
            NativeTextEncoding nameText,
            NativeTextEncoding descriptorText) {}
}
