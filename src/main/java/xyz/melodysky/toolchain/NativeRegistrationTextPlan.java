package xyz.melodysky.toolchain;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
    static final int MAX_GROUP_VALUES = 8;
    static final int MAX_GROUP_DECODED_BYTES = 512;

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
        Map<TextKey, NativeTextEncoding> sharedOwnerText =
                new LinkedHashMap<>();
        List<Binding> encodedBindings = registrations.stream()
                .map(entry -> binding(
                        entry,
                        buildKey,
                        encoder,
                        sharedOwnerText))
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
                textGroups(owner, bindings, buildKey, encoder));
    }

    private static Binding binding(
            NativeRegistrationEntry entry,
            NativeTextBuildKey buildKey,
            NativeTextEncoder encoder,
            Map<TextKey, NativeTextEncoding> sharedOwnerText) {
        return new Binding(
                entry,
                sharedOwnerText.computeIfAbsent(
                        new TextKey(
                                NativeTextPurpose.REGISTRATION_METHOD_NAME,
                                entry.methodName()),
                        ignored -> encoder.encode(
                                buildKey,
                                NativeTextPurpose.REGISTRATION_METHOD_NAME,
                                "registration-owner:"
                                        + entry.registrationOwner()
                                        + ":method-name:"
                                        + entry.methodName(),
                                entry.methodName())),
                sharedOwnerText.computeIfAbsent(
                        new TextKey(
                                NativeTextPurpose.REGISTRATION_DESCRIPTOR,
                                entry.descriptor()),
                        ignored -> encoder.encode(
                                buildKey,
                                NativeTextPurpose.REGISTRATION_DESCRIPTOR,
                                "registration-owner:"
                                        + entry.registrationOwner()
                                        + ":descriptor:"
                                        + entry.descriptor(),
                                entry.descriptor())));
    }

    /**
     * Groups only text that is already exposed together by one owner-local
     * RegisterNatives activation. Names and descriptors stay in distinct
     * purpose domains, groups are bounded, and no group crosses an owner.
     */
    private static List<TextGroup> textGroups(
            String owner,
            List<Binding> bindings,
            NativeTextBuildKey buildKey,
            NativeTextEncoder encoder) {
        ArrayList<TextGroup> groups = new ArrayList<>();
        groups.addAll(groupsForPurpose(
                owner,
                bindings,
                NativeTextPurpose.REGISTRATION_METHOD_NAME,
                buildKey,
                encoder));
        groups.addAll(groupsForPurpose(
                owner,
                bindings,
                NativeTextPurpose.REGISTRATION_DESCRIPTOR,
                buildKey,
                encoder));
        return List.copyOf(groups);
    }

    private static List<TextGroup> groupsForPurpose(
            String owner,
            List<Binding> bindings,
            NativeTextPurpose purpose,
            NativeTextBuildKey buildKey,
            NativeTextEncoder encoder) {
        LinkedHashMap<String, Component> unique = new LinkedHashMap<>();
        for (Binding binding : bindings) {
            NativeTextEncoding encoding = purpose
                            == NativeTextPurpose.REGISTRATION_METHOD_NAME
                    ? binding.nameText()
                    : binding.descriptorText();
            String plaintext = purpose
                            == NativeTextPurpose.REGISTRATION_METHOD_NAME
                    ? binding.registration().methodName()
                    : binding.registration().descriptor();
            unique.putIfAbsent(
                    encoding.symbol(),
                    new Component(encoding.symbol(), plaintext));
        }

        // The physical order is build-derived rather than Java declaration
        // order. This preserves cross-build diversity without a runtime table.
        List<Component> components = unique.values().stream()
                .sorted(Comparator.comparing(Component::identitySymbol))
                .toList();
        ArrayList<TextGroup> groups = new ArrayList<>();
        ArrayList<Component> pending = new ArrayList<>();
        int pendingBytes = 0;
        for (Component component : components) {
            int componentBytes = Math.addExact(
                    component.plaintext().getBytes(StandardCharsets.UTF_8).length,
                    1);
            if (!pending.isEmpty()
                    && (pending.size() == MAX_GROUP_VALUES
                            || pendingBytes + componentBytes
                                    > MAX_GROUP_DECODED_BYTES)) {
                groups.add(group(
                        owner,
                        purpose,
                        groups.size(),
                        pending,
                        buildKey,
                        encoder));
                pending.clear();
                pendingBytes = 0;
            }
            pending.add(component);
            pendingBytes = Math.addExact(pendingBytes, componentBytes);
        }
        if (!pending.isEmpty()) {
            groups.add(group(
                    owner,
                    purpose,
                    groups.size(),
                    pending,
                    buildKey,
                    encoder));
        }
        return List.copyOf(groups);
    }

    private static TextGroup group(
            String owner,
            NativeTextPurpose purpose,
            int index,
            List<Component> components,
            NativeTextBuildKey buildKey,
            NativeTextEncoder encoder) {
        ByteArrayOutputStream plaintext = new ByteArrayOutputStream();
        LinkedHashMap<String, Integer> memberOffsets = new LinkedHashMap<>();
        StringBuilder identity = new StringBuilder()
                .append("registration-owner-group:")
                .append(owner)
                .append(':')
                .append(purpose.domain())
                .append(':')
                .append(index);
        for (int componentIndex = 0;
                componentIndex < components.size();
                componentIndex++) {
            Component component = components.get(componentIndex);
            if (componentIndex != 0) {
                plaintext.write(0);
            }
            memberOffsets.put(
                    component.identitySymbol(),
                    plaintext.size());
            plaintext.writeBytes(component.plaintext()
                    .getBytes(StandardCharsets.UTF_8));
            identity.append(':').append(component.identitySymbol());
        }
        NativeTextEncoding encoding = encoder.encodeBytes(
                buildKey,
                purpose,
                identity.toString(),
                plaintext.toByteArray());
        return new TextGroup(
                purpose,
                encoding,
                Map.copyOf(memberOffsets));
    }

    record Owner(
            String owner,
            NativeTextEncoding ownerText,
            List<Binding> bindings,
            List<TextGroup> textGroups) {
        Owner {
            bindings = List.copyOf(bindings);
            textGroups = List.copyOf(textGroups);
        }
    }

    record Binding(
            NativeRegistrationEntry registration,
            NativeTextEncoding nameText,
            NativeTextEncoding descriptorText) {}

    record TextGroup(
            NativeTextPurpose purpose,
            NativeTextEncoding encoding,
            Map<String, Integer> memberOffsets) {
        TextGroup {
            memberOffsets = Map.copyOf(memberOffsets);
            if (memberOffsets.isEmpty()
                    || memberOffsets.size() > MAX_GROUP_VALUES) {
                throw new IllegalArgumentException(
                        "registration text group member count is outside its bound");
            }
            if (memberOffsets.size() > 1
                    && encoding.decodedBufferLength()
                            > MAX_GROUP_DECODED_BYTES) {
                throw new IllegalArgumentException(
                        "multi-value registration text group exceeds its decoded byte bound");
            }
            for (int offset : memberOffsets.values()) {
                if (offset < 0
                        || offset >= encoding.decodedBufferLength()) {
                    throw new IllegalArgumentException(
                            "registration text group member offset is outside its decoded payload");
                }
            }
        }

        int memberOffset(String identitySymbol) {
            Integer offset = memberOffsets.get(identitySymbol);
            if (offset == null) {
                throw new IllegalArgumentException(
                        "registration text group has no member " + identitySymbol);
            }
            return offset;
        }
    }

    private record Component(
            String identitySymbol,
            String plaintext) {}

    private record TextKey(
            NativeTextPurpose purpose,
            String value) {}
}
