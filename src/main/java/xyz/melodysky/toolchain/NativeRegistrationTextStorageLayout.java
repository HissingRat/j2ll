package xyz.melodysky.toolchain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
            List<NativeRegistrationTextPlan.Binding> bindings) {
        LinkedHashMap<String, Text> texts = new LinkedHashMap<>();
        ArrayList<Binding> offsets = new ArrayList<>();
        int nextOffset = 0;
        for (NativeRegistrationTextPlan.Binding binding : bindings) {
            Text name = texts.get(binding.nameText().symbol());
            if (name == null) {
                name = new Text(binding.nameText(), nextOffset);
                texts.put(binding.nameText().symbol(), name);
                nextOffset = Math.addExact(
                        nextOffset,
                        binding.nameText().decodedBufferLength());
            }
            Text descriptor = texts.get(
                    binding.descriptorText().symbol());
            if (descriptor == null) {
                descriptor = new Text(
                        binding.descriptorText(),
                        nextOffset);
                texts.put(
                        binding.descriptorText().symbol(),
                        descriptor);
                nextOffset = Math.addExact(
                        nextOffset,
                        binding.descriptorText()
                                .decodedBufferLength());
            }
            offsets.add(new Binding(
                    name.offset(),
                    descriptor.offset()));
        }
        return new NativeRegistrationTextStorageLayout(
                List.copyOf(texts.values()),
                offsets,
                nextOffset);
    }

    record Text(
            NativeTextEncoding encoding,
            int offset) {}

    record Binding(
            int nameOffset,
            int descriptorOffset) {}
}
