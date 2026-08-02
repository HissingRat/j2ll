package zoo.basic;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Objects;
import java.util.Optional;
import zoo.Case;

public final class StringJdkBasicCase implements Case {
    @Override
    public String name() {
        return "StringJdkBasicCase";
    }

    @Override
    public String run() {
        return stableStringOps()
                + ":"
                + collectionOps()
                + ":"
                + Arrays.toString(bigEndianIntFrame(0x01020304))
                + ":"
                + Arrays.toString(bigEndianIntFrame(Integer.MIN_VALUE))
                + ":"
                + Arrays.toString(bigEndianIntFrame(Integer.MAX_VALUE));
    }

    public static byte[] bigEndianIntFrame(int value) {
        return ByteBuffer.allocate(4).putInt(value).array();
    }

    public static String stableStringOps() {
        String text = "feature-zoo";
        StringBuilder builder = new StringBuilder();
        builder.append(text.substring(0, 7)).append(':').append(text.charAt(8));
        return text.length() + ":" + text.equals("feature-zoo") + ":" + text.startsWith("feat")
                + ":" + text.endsWith("zoo") + ":" + text.isEmpty() + ":" + builder
                + ":" + Math.max(Math.abs(-7), Math.min(9, 11)) + ":" + Objects.equals("x", "x")
                + ":" + Integer.valueOf(7).intValue() + ":" + Long.valueOf(8L).longValue()
                + ":" + Boolean.TRUE.booleanValue() + ":" + Double.valueOf(1.5d).doubleValue();
    }

    private static String collectionOps() {
        ArrayList<String> list = new ArrayList<>();
        list.add("alpha");
        list.add("beta");
        HashMap<String, Integer> map = new HashMap<>();
        map.put("alpha", 1);
        map.put("alpha", 2);
        int[] numbers = {1, 2, 3};
        int[] copy = Arrays.copyOf(numbers, 4);
        Arrays.fill(copy, 3, 4, 9);
        Optional<String> optional = Optional.ofNullable(list.contains("beta") ? "present" : null);
        String formatted = String.format("fmt-%s-%d", optional.orElse("missing"), map.get("alpha"));
        return Arrays.equals(copy, new int[] {1, 2, 3, 9})
                + ":" + Arrays.asList("a", "b").size() + ":" + formatted;
    }
}
