package xyz.melodysky.toolchain.nativetext;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Builds one independently keyed function-local record from text components. */
final class NativeTextTupleEncoder {
    static final int MAX_COMPONENTS = 8;
    static final int MAX_DECODED_BYTES = 512;

    private final NativeTextEncoder encoder = new NativeTextEncoder();

    NativeTextTupleEncoding encode(
            NativeTextBuildKey buildKey,
            NativeTextPurpose purpose,
            String stableUseIdentity,
            List<String> components) {
        Objects.requireNonNull(buildKey, "buildKey");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(stableUseIdentity, "stableUseIdentity");
        Objects.requireNonNull(components, "components");
        if (stableUseIdentity.isBlank()) {
            throw new IllegalArgumentException(
                    "native-text tuple use identity must not be blank");
        }
        if (components.isEmpty()) {
            throw new IllegalArgumentException(
                    "native-text tuple must contain at least one component");
        }
        if (components.size() > MAX_COMPONENTS) {
            throw new IllegalArgumentException(
                    "native-text tuple exceeds its component bound");
        }

        ArrayList<ComponentMaterial> materials = new ArrayList<>();
        int decodedBytes = 0;
        for (int index = 0; index < components.size(); index++) {
            String component = Objects.requireNonNull(
                    components.get(index),
                    "component");
            byte[] bytes = component.getBytes(StandardCharsets.UTF_8);
            decodedBytes = Math.addExact(
                    decodedBytes,
                    Math.addExact(bytes.length, 1));
            // Each component contributes independently domain-separated,
            // build-keyed material. The material randomizes tuple layout and
            // feeds the aggregate record identity; it is never emitted as a
            // native metadata table.
            NativeTextEncoding laneEncoding = encoder.encodeBytes(
                            buildKey,
                            purpose,
                            stableUseIdentity + ":component:" + index,
                            bytes);
            String laneToken = laneEncoding.symbol();
            NativeTextCodecPlan laneCodec = laneEncoding.codecPlan();
            materials.add(new ComponentMaterial(
                    index,
                    bytes,
                    laneToken,
                    lanePlan(laneCodec)));
        }
        if (components.size() > 1
                && decodedBytes > MAX_DECODED_BYTES) {
            throw new IllegalArgumentException(
                    "multi-component native-text tuple exceeds its decoded-byte bound");
        }
        materials.sort(Comparator
                .comparing(ComponentMaterial::laneToken)
                .thenComparingInt(ComponentMaterial::originalIndex));

        ByteArrayOutputStream recordBytes = new ByteArrayOutputStream();
        NativeTextTupleEncoding.Slice[] slices =
                new NativeTextTupleEncoding.Slice[components.size()];
        StringBuilder recordIdentity = new StringBuilder(stableUseIdentity)
                .append(":tuple");
        for (int physicalIndex = 0;
                physicalIndex < materials.size();
                physicalIndex++) {
            ComponentMaterial material = materials.get(physicalIndex);
            int offset = recordBytes.size();
            byte[] masked = material.bytes().clone();
            for (int localIndex = 0;
                    localIndex < masked.length;
                    localIndex++) {
                masked[localIndex] ^= (byte) material.lanePlan()
                        .maskByte(localIndex);
            }
            recordBytes.writeBytes(masked);
            slices[material.originalIndex()] =
                    new NativeTextTupleEncoding.Slice(
                            offset,
                            material.bytes().length,
                            material.lanePlan());
            if (physicalIndex + 1 < materials.size()) {
                recordBytes.write(0);
            }
            recordIdentity.append(':').append(material.laneToken());
        }
        NativeTextEncoding record = encoder.encodeBytes(
                buildKey,
                purpose,
                recordIdentity.toString(),
                recordBytes.toByteArray());
        return new NativeTextTupleEncoding(record, List.of(slices));
    }

    byte[] decodeBytes(NativeTextTupleEncoding tuple) {
        Objects.requireNonNull(tuple, "tuple");
        byte[] decoded = encoder.decodeBytes(tuple.record());
        for (int componentIndex = 0;
                componentIndex < tuple.componentCount();
                componentIndex++) {
            NativeTextTupleEncoding.Slice slice = tuple.slice(componentIndex);
            for (int localIndex = 0;
                    localIndex < slice.length();
                    localIndex++) {
                decoded[slice.offset() + localIndex] ^=
                        (byte) slice.lanePlan().maskByte(localIndex);
            }
        }
        return decoded;
    }

    private NativeTextTupleEncoding.LanePlan lanePlan(
            NativeTextCodecPlan codec) {
        return new NativeTextTupleEncoding.LanePlan(
                (int) codec.key0(),
                (int) codec.step(),
                (int) codec.multiplier0(),
                1 + Math.floorMod(codec.shift0(), 31),
                1 + Math.floorMod(codec.shift1(), 31),
                codec.outputShift());
    }

    private record ComponentMaterial(
            int originalIndex,
            byte[] bytes,
            String laneToken,
            NativeTextTupleEncoding.LanePlan lanePlan) {
        private ComponentMaterial {
            bytes = bytes.clone();
            Objects.requireNonNull(lanePlan, "lanePlan");
        }
    }
}
