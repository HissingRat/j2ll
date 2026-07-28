package xyz.melodysky.toolchain.nativetext;

import java.util.Comparator;
import java.util.List;

final class GeneratedCTextEdits {
    private GeneratedCTextEdits() {
    }

    static String apply(String source, List<Edit> edits) {
        StringBuilder result = new StringBuilder(source);
        edits.stream()
                .sorted(Comparator
                        .comparingInt(Edit::start)
                        .thenComparingInt(Edit::end)
                        .reversed())
                .forEach(edit -> result.replace(
                        edit.start(),
                        edit.end(),
                        edit.replacement()));
        return result.toString();
    }

    record Edit(int start, int end, String replacement) {
        static Edit replace(int start, int end, String replacement) {
            return new Edit(start, end, replacement);
        }

        static Edit insert(int offset, String replacement) {
            return new Edit(offset, offset, replacement);
        }
    }
}
