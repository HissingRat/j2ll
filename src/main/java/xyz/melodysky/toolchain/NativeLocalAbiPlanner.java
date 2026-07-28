package xyz.melodysky.toolchain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;

/** Derives a domain-separated local ABI topology from one invocation build key. */
final class NativeLocalAbiPlanner {
    private static final byte[] DOMAIN =
            "j2ll/native-local-abi/v3".getBytes(StandardCharsets.UTF_8);

    NativeLocalAbiPlan plan(
            NativeTextBuildKey buildKey,
            String methodKey,
            int parameterCount) {
        Objects.requireNonNull(buildKey, "buildKey");
        Objects.requireNonNull(methodKey, "methodKey");
        if (methodKey.isBlank()) {
            throw new IllegalArgumentException("methodKey must not be blank");
        }
        if (parameterCount < 0) {
            throw new IllegalArgumentException(
                    "parameterCount must not be negative");
        }

        byte[] identity = derive(buildKey, methodKey, "identity");
        String token = HexFormat.of().formatHex(identity, 0, 12);
        NativeLocalAbiPlan.Shape[] shapes =
                NativeLocalAbiPlan.Shape.values();
        NativeLocalAbiPlan.Shape shape = shapes[
                Byte.toUnsignedInt(
                                derive(buildKey, methodKey, "shape")[0])
                        % shapes.length];
        ArrayList<String> bridgeSymbols =
                new ArrayList<>(shape.bridgeCount());
        ArrayList<List<Integer>> parameterOrders =
                new ArrayList<>(shape.bridgeCount());
        for (int bridge = 0;
                bridge < shape.bridgeCount();
                bridge++) {
            bridgeSymbols.add(
                    "j2ll_lab_bridge_"
                            + token
                            + "_"
                            + bridge);
            parameterOrders.add(parameterOrder(
                    buildKey,
                    methodKey,
                    parameterCount,
                    bridge));
        }
        ensureDistinctBranchedEntryOrders(shape, parameterOrders);
        int branchSalt = shape.branched()
                ? ByteBuffer.wrap(derive(
                                buildKey,
                                methodKey,
                                "branch-salt"))
                        .getInt()
                : 0;
        return new NativeLocalAbiPlan(
                shape,
                parameterCount,
                bridgeSymbols,
                parameterOrders,
                branchSalt);
    }

    private void ensureDistinctBranchedEntryOrders(
            NativeLocalAbiPlan.Shape shape,
            List<List<Integer>> parameterOrders) {
        if (!shape.branched()
                || parameterOrders.get(0).size() < 2
                || !parameterOrders.get(0).equals(
                        parameterOrders.get(1))) {
            return;
        }
        ArrayList<Integer> distinct =
                new ArrayList<>(parameterOrders.get(1));
        Integer first = distinct.get(0);
        distinct.set(0, distinct.get(1));
        distinct.set(1, first);
        parameterOrders.set(1, List.copyOf(distinct));
    }

    private List<Integer> parameterOrder(
            NativeTextBuildKey buildKey,
            String methodKey,
            int parameterCount,
            int bridge) {
        ArrayList<RankedParameter> ranked = new ArrayList<>();
        for (int index = 0; index < parameterCount; index++) {
            ranked.add(new RankedParameter(
                    index,
                    derive(
                            buildKey,
                            methodKey,
                            "bridge:"
                                    + bridge
                                    + ":parameter:"
                                    + index)));
        }
        ranked.sort((left, right) -> {
            int byRank = compareUnsigned(left.rank(), right.rank());
            return byRank != 0
                    ? byRank
                    : Integer.compare(left.index(), right.index());
        });
        return ranked.stream().map(RankedParameter::index).toList();
    }

    private byte[] derive(
            NativeTextBuildKey buildKey,
            String methodKey,
            String use) {
        MessageDigest digest = sha256();
        updateLengthPrefixed(digest, DOMAIN);
        updateLengthPrefixed(digest, buildKey.bytes());
        updateLengthPrefixed(
                digest,
                methodKey.getBytes(StandardCharsets.UTF_8));
        updateLengthPrefixed(
                digest,
                use.getBytes(StandardCharsets.UTF_8));
        return digest.digest();
    }

    private void updateLengthPrefixed(
            MessageDigest digest,
            byte[] value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(value.length)
                .array());
        digest.update(value);
    }

    private int compareUnsigned(byte[] left, byte[] right) {
        for (int index = 0;
                index < Math.min(left.length, right.length);
                index++) {
            int comparison = Integer.compare(
                    Byte.toUnsignedInt(left[index]),
                    Byte.toUnsignedInt(right[index]));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.length, right.length);
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    exception);
        }
    }

    private record RankedParameter(int index, byte[] rank) {
        RankedParameter {
            rank = rank.clone();
        }

        @Override
        public byte[] rank() {
            return rank.clone();
        }
    }
}
