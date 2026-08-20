package xyz.melodysky.toolchain;

import java.util.ArrayList;
import java.util.List;

/** Rejects preprocessor ambiguity around the final registration topology. */
final class NativeRegistrationControlPreprocessorVerifier {
    void verify(
            NativeRegistrationControlSourceIndex index,
            NativeRegistrationControlTopologyPlan plan) {
        List<String> symbols = controlSymbols(plan);
        int controlStart = symbols.stream()
                .mapToInt(index::firstIdentifierOffset)
                .min()
                .orElse(-1);
        int controlEnd = index.functionEndOffset(
                NativeRegistrationControlCFunctionPolicy.definitionHeader(
                        "JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved)"));
        if (controlStart < 0 || controlEnd <= controlStart) {
            fail("PREPROCESSOR_CONTROL_SPAN_MISSING");
        }

        int conditionalDepth = 0;
        int lineStart = 0;
        String code = index.codeView();
        ArrayList<Span> directiveLines = new ArrayList<>();
        ArrayList<LineEvidence> lines = new ArrayList<>();
        while (lineStart < code.length()) {
            int lineEnd = code.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = code.length();
            }
            String line = code.substring(lineStart, lineEnd);
            String stripped = line.stripLeading();
            int directivePrefix = directivePrefixLength(stripped);
            boolean directiveStart = directivePrefix != 0;
            lines.add(new LineEvidence(
                    lineStart,
                    lineEnd,
                    conditionalDepth));
            if (directiveStart) {
                directiveLines.add(new Span(lineStart, lineEnd));
                String trailing = line.stripTrailing();
                if (trailing.endsWith("\\")) {
                    fail("PREPROCESSOR_CONTINUATION_UNSUPPORTED");
                }
            }
            if (lineStart < controlEnd
                    && lineEnd >= controlStart
                    && directiveStart) {
                fail("PREPROCESSOR_IN_CONTROL_SPAN");
            }
            if (directiveStart) {
                conditionalDepth = updateDepth(
                        conditionalDepth,
                        stripped.substring(directivePrefix)
                                .stripLeading());
            }
            if (lineStart <= controlStart && controlStart <= lineEnd
                    && conditionalDepth != 0) {
                fail("CONTROL_SPAN_IS_CONDITIONAL");
            }
            lineStart = lineEnd == code.length()
                    ? code.length()
                    : lineEnd + 1;
        }
        if (conditionalDepth != 0) {
            fail("PREPROCESSOR_CONDITIONAL_UNBALANCED");
        }
        for (String symbol : symbols) {
            for (int offset : index.identifierOffsets(symbol)) {
                if (offset < controlStart || offset >= controlEnd) {
                    fail("CONTROL_SYMBOL_OUTSIDE_CONTROL_SPAN");
                }
                if (insideAny(offset, directiveLines)) {
                    fail("CONTROL_SYMBOL_IN_PREPROCESSOR_DIRECTIVE");
                }
                if (conditionalDepthAt(offset, lines) != 0) {
                    fail("CONTROL_SYMBOL_IS_CONDITIONAL");
                }
            }
        }
    }

    private int directivePrefixLength(String line) {
        if (line.startsWith("#")) {
            return 1;
        }
        if (line.startsWith("%:")) {
            return 2;
        }
        if (line.startsWith("??=")) {
            return 3;
        }
        return 0;
    }

    private int updateDepth(int depth, String keyword) {
        int end = 0;
        while (end < keyword.length()
                && Character.isLetter(keyword.charAt(end))) {
            end++;
        }
        String name = keyword.substring(0, end);
        if (name.equals("if")
                || name.equals("ifdef")
                || name.equals("ifndef")) {
            return depth + 1;
        }
        if (name.equals("endif")) {
            if (depth == 0) {
                fail("PREPROCESSOR_CONDITIONAL_UNBALANCED");
            }
            return depth - 1;
        }
        if ((name.equals("else") || name.equals("elif"))
                && depth == 0) {
            fail("PREPROCESSOR_CONDITIONAL_UNBALANCED");
        }
        return depth;
    }

    private boolean insideAny(int offset, List<Span> spans) {
        int low = 0;
        int high = spans.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            Span span = spans.get(middle);
            if (offset < span.start()) {
                high = middle - 1;
            } else if (offset > span.end()) {
                low = middle + 1;
            } else {
                return true;
            }
        }
        return false;
    }

    private int conditionalDepthAt(
            int offset,
            List<LineEvidence> lines) {
        int low = 0;
        int high = lines.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            LineEvidence line = lines.get(middle);
            if (offset < line.start()) {
                high = middle - 1;
            } else if (offset > line.end()) {
                low = middle + 1;
            } else {
                return line.conditionalDepth();
            }
        }
        fail("CONTROL_SYMBOL_LINE_MISSING");
        return -1;
    }

    private List<String> controlSymbols(
            NativeRegistrationControlTopologyPlan plan) {
        ArrayList<String> symbols = new ArrayList<>();
        symbols.add("JNI_OnLoad");
        symbols.add(plan.aggregateSymbol());
        symbols.addAll(plan.failureSymbols().symbols());
        for (NativeRegistrationControlTopologyPlan.Owner owner
                : plan.owners()) {
            symbols.add(owner.symbol());
        }
        for (NativeRegistrationControlTopologyPlan.Chunk chunk
                : plan.chunks()) {
            symbols.add(chunk.symbol());
        }
        for (NativeRegistrationControlRoutePlan.Route route
                : plan.routePlan().routes()) {
            symbols.add(route.symbol());
        }
        return List.copyOf(symbols);
    }

    private void fail(String code) {
        throw new IllegalStateException(
                "native registration control topology audit failed: "
                        + code);
    }

    private record Span(int start, int end) {}

    private record LineEvidence(
            int start,
            int end,
            int conditionalDepth) {}
}
