package xyz.melodysky.toolchain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import xyz.melodysky.toolchain.nativetext.NativeTextEncoding;

/** Owner-local decoded registration text layout with no cross-owner sharing. */
record NativeRegistrationTextStorageLayout(
        List<Text> texts,
        List<Binding> bindings,
        int textBytes) {
    NativeRegistrationTextStorageLayout {
        texts = List.copyOf(texts);
        bindings = List.copyOf(bindings);
        if (textBytes <= 0) {
            throw new IllegalArgumentException(
                    "registration text layout must not be empty");
        }
    }

    static NativeRegistrationTextStorageLayout plan(
            NativeRegistrationTextPlan.Owner owner) {
        ArrayList<Text> texts = new ArrayList<>();
        Map<String, Integer> absoluteMemberOffsets = new HashMap<>();
        ArrayList<Binding> offsets = new ArrayList<>();
        int nextOffset = 0;
        for (NativeRegistrationTextPlan.TextGroup group
                : owner.textGroups()) {
            texts.add(new Text(group.encoding(), nextOffset));
            for (Map.Entry<String, Integer> member
                    : group.memberOffsets().entrySet()) {
                Integer previous = absoluteMemberOffsets.put(
                        member.getKey(),
                        Math.addExact(nextOffset, member.getValue()));
                if (previous != null) {
                    throw new IllegalArgumentException(
                            "registration text member appears in more than one owner-local group");
                }
            }
            nextOffset = Math.addExact(
                    nextOffset,
                    group.encoding().decodedBufferLength());
        }
        for (NativeRegistrationTextPlan.Binding binding : owner.bindings()) {
            offsets.add(new Binding(
                    requiredOffset(
                            absoluteMemberOffsets,
                            binding.nameText().symbol()),
                    requiredOffset(
                            absoluteMemberOffsets,
                            binding.descriptorText().symbol())));
        }
        return new NativeRegistrationTextStorageLayout(
                texts,
                offsets,
                nextOffset);
    }

    private static int requiredOffset(
            Map<String, Integer> offsets,
            String identitySymbol) {
        Integer offset = offsets.get(identitySymbol);
        if (offset == null) {
            throw new IllegalArgumentException(
                    "registration text member is absent from its owner-local group");
        }
        return offset;
    }

    record Text(
            NativeTextEncoding encoding,
            int offset) {}

    record Binding(
            int nameOffset,
            int descriptorOffset) {}
}
