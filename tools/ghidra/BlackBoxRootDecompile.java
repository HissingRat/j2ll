// Bounded decompilation of the call closure rooted at a public native ABI entry.
//@category J2LL.AttackerAudit

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.pcode.PcodeOp;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolIterator;

/**
 * Writes a bounded text corpus for manual attacker review.
 *
 * <p>The default root is the public JVM ABI symbol {@code JNI_OnLoad}; callers
 * may select another public symbol with {@code --root-name}. No application or
 * compiler-internal method name, address, source, config, report, or intermediate
 * is embedded in this script.</p>
 */
public class BlackBoxRootDecompile extends GhidraScript {
    @Override
    protected void run() throws Exception {
        Options options = Options.parse(getScriptArgs());

        List<Function> roots = findRoots(options.rootName);
        Closure closure = collectClosure(roots, options);
        String text = decompile(closure, options);

        Path output = Path.of(options.output).toAbsolutePath().normalize();
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(output, text, StandardCharsets.UTF_8);
        println("blackBoxRootDecompile=" + output);
    }

    private List<Function> findRoots(String rootName) {
        Map<Address, Function> roots = new TreeMap<>();
        SymbolIterator iterator = currentProgram.getSymbolTable().getAllSymbols(true);
        while (iterator.hasNext()) {
            Symbol symbol = iterator.next();
            if (!matchesRoot(rootName, symbol.getName())) {
                continue;
            }
            Function function = currentProgram.getFunctionManager().getFunctionAt(symbol.getAddress());
            if (function != null && !function.isExternal()) {
                roots.put(function.getEntryPoint(), function);
            }
        }
        return new ArrayList<>(roots.values());
    }

    private boolean matchesRoot(String requested, String actual) {
        return requested.equals(actual) || ("_" + requested).equals(actual);
    }

    private Closure collectClosure(List<Function> roots, Options options) throws Exception {
        Map<Address, Node> visited = new LinkedHashMap<>();
        Deque<Node> queue = new ArrayDeque<>();
        for (Function root : roots) {
            Node node = new Node(root, 0);
            if (visited.putIfAbsent(root.getEntryPoint(), node) == null) {
                queue.add(node);
            }
        }
        List<Edge> edges = new ArrayList<>();
        boolean truncated = false;
        while (!queue.isEmpty()) {
            monitor.checkCancelled();
            Node node = queue.removeFirst();
            if (node.depth >= options.maxDepth) {
                if (!node.function.getCalledFunctions(monitor).isEmpty()) {
                    truncated = true;
                }
                continue;
            }
            List<Function> callees = new ArrayList<>(node.function.getCalledFunctions(monitor));
            callees.sort(Comparator.comparing(Function::getEntryPoint));
            for (Function callee : callees) {
                edges.add(new Edge(node.function, callee));
                if (callee.isExternal() || visited.containsKey(callee.getEntryPoint())) {
                    continue;
                }
                if (visited.size() >= options.maxFunctions) {
                    truncated = true;
                    continue;
                }
                Node child = new Node(callee, node.depth + 1);
                visited.put(callee.getEntryPoint(), child);
                queue.addLast(child);
            }
        }
        List<Node> nodes = new ArrayList<>(visited.values());
        nodes.sort(Comparator.comparingInt((Node value) -> value.depth)
                .thenComparing(value -> value.function.getEntryPoint()));
        edges.sort(Comparator.comparing((Edge value) -> value.from.getEntryPoint())
                .thenComparing(value -> value.to.getEntryPoint()));
        return new Closure(roots, nodes, edges, truncated);
    }

    private String decompile(Closure closure, Options options) throws Exception {
        StringBuilder output = new StringBuilder();
        output.append("J2LL BLACK-BOX ROOT DECOMPILE v1\n");
        output.append("program=").append(currentProgram.getName()).append('\n');
        output.append("format=").append(currentProgram.getExecutableFormat()).append('\n');
        output.append("language=").append(currentProgram.getLanguageID()).append('\n');
        output.append("rootName=").append(options.rootName).append('\n');
        output.append("rootCount=").append(closure.roots.size()).append('\n');
        output.append("closureFunctionCount=").append(closure.nodes.size()).append('\n');
        output.append("resolvedEdgeCount=").append(closure.edges.size()).append('\n');
        output.append("closureTruncated=").append(closure.truncated).append('\n');
        output.append("maxFunctions=").append(options.maxFunctions).append('\n');
        output.append("maxDepth=").append(options.maxDepth).append('\n');
        output.append("timeoutSecondsPerFunction=").append(options.timeoutSeconds).append('\n');
        output.append("maxCCharactersPerFunction=").append(options.maxCCharactersPerFunction).append('\n');
        output.append("evidenceBoundary=final native library only; no source/config/report/intermediate input\n");
        output.append("interpretation=decompiler output is heuristic and is not proof of recovered Java semantics\n");

        output.append("\n[RESOLVED CALL EDGES]\n");
        for (Edge edge : closure.edges) {
            output.append(edge.from.getEntryPoint()).append(' ')
                    .append(edge.from.getName()).append(" -> ")
                    .append(edge.to.getEntryPoint()).append(' ')
                    .append(edge.to.getName());
            if (edge.to.isExternal()) {
                output.append(" [external]");
            }
            output.append('\n');
        }

        if (closure.nodes.isEmpty()) {
            output.append("\n[NO ROOT FOUND]\n");
            return finish(output, true, !closure.truncated);
        }

        DecompInterface decompiler = new DecompInterface();
        int decompileFailures = 0;
        boolean cOutputTruncated = false;
        try {
            decompiler.setOptions(new DecompileOptions());
            decompiler.toggleCCode(true);
            decompiler.toggleSyntaxTree(true);
            decompiler.setSimplificationStyle("decompile");
            if (!decompiler.openProgram(currentProgram)) {
                output.append("\n[DECOMPILER OPEN ERROR]\n")
                        .append(decompiler.getLastMessage()).append('\n');
                return finish(output, false, false);
            }
            for (Node node : closure.nodes) {
                monitor.checkCancelled();
                output.append("\n============================================================\n");
                output.append("FUNCTION address=").append(node.function.getEntryPoint())
                        .append(" name=").append(node.function.getName())
                        .append(" depth=").append(node.depth)
                        .append(" bodyBytes=").append(node.function.getBody().getNumAddresses())
                        .append('\n');
                CallCounts calls = callCounts(node.function);
                output.append("CALL_EVIDENCE directPcodeOps=").append(calls.direct)
                        .append(" indirectPcodeOps=").append(calls.indirect)
                        .append(" resolvedCallees=").append(node.function.getCalledFunctions(monitor).size())
                        .append('\n');

                DecompileResults result = decompiler.decompileFunction(
                        node.function, options.timeoutSeconds, monitor);
                String c = result.getDecompiledFunction() == null
                        ? null
                        : result.getDecompiledFunction().getC();
                if (!result.decompileCompleted() || c == null) {
                    decompileFailures++;
                    output.append("DECOMPILE_FAILED error=")
                            .append(oneLine(result.getErrorMessage())).append('\n');
                    continue;
                }
                boolean truncated = c.length() > options.maxCCharactersPerFunction;
                cOutputTruncated |= truncated;
                output.append("DECOMPILE_SUCCEEDED cCharacters=").append(c.length())
                        .append(" truncated=").append(truncated).append('\n');
                if (truncated) {
                    output.append(c, 0, options.maxCCharactersPerFunction);
                    output.append("\n/* per-function output budget reached */\n");
                } else {
                    output.append(c);
                    if (!c.endsWith("\n")) {
                        output.append('\n');
                    }
                }
            }
        } finally {
            decompiler.closeProgram();
            decompiler.dispose();
        }
        return finish(output, true,
                !closure.truncated && decompileFailures == 0 && !cOutputTruncated);
    }

    private String finish(StringBuilder output, boolean completed, boolean coverageComplete) {
        String status = !completed
                ? "INCOMPLETE"
                : coverageComplete ? "COMPLETE" : "COMPLETE_WITH_PARTIAL_COVERAGE";
        int headerEnd = output.indexOf("\n") + 1;
        output.insert(headerEnd, "status=" + status + "\n"
                + "completed=" + completed + "\n"
                + "coverageComplete=" + coverageComplete + "\n");
        return output.toString();
    }

    private CallCounts callCounts(Function function) {
        int direct = 0;
        int indirect = 0;
        InstructionIterator instructions = currentProgram.getListing().getInstructions(function.getBody(), true);
        while (instructions.hasNext()) {
            Instruction instruction = instructions.next();
            for (PcodeOp operation : instruction.getPcode()) {
                if (operation.getOpcode() == PcodeOp.CALL) {
                    direct++;
                } else if (operation.getOpcode() == PcodeOp.CALLIND) {
                    indirect++;
                }
            }
        }
        return new CallCounts(direct, indirect);
    }

    private String oneLine(String value) {
        if (value == null) {
            return "unknown";
        }
        return value.replace('\r', ' ').replace('\n', ' ');
    }

    private record Node(Function function, int depth) {}

    private record Edge(Function from, Function to) {}

    private record Closure(List<Function> roots, List<Node> nodes, List<Edge> edges, boolean truncated) {}

    private record CallCounts(int direct, int indirect) {}

    private static final class Options {
        String output;
        String rootName = "JNI_OnLoad";
        int maxFunctions = 256;
        int maxDepth = 12;
        int timeoutSeconds = 30;
        int maxCCharactersPerFunction = 200000;

        static Options parse(String[] arguments) {
            Options options = new Options();
            for (String argument : arguments) {
                int equals = argument.indexOf('=');
                if (!argument.startsWith("--") || equals < 3) {
                    throw new IllegalArgumentException("expected --name=value, got: " + argument);
                }
                String name = argument.substring(2, equals);
                String value = argument.substring(equals + 1);
                switch (name) {
                    case "out" -> options.output = nonBlank(name, value);
                    case "root-name" -> options.rootName = nonBlank(name, value);
                    case "max-functions" -> options.maxFunctions = positive(name, value);
                    case "max-depth" -> options.maxDepth = nonNegative(name, value);
                    case "timeout-seconds" -> options.timeoutSeconds = positive(name, value);
                    case "max-c-characters-per-function" -> options.maxCCharactersPerFunction = positive(name, value);
                    default -> throw new IllegalArgumentException("unknown option --" + name);
                }
            }
            if (options.output == null) {
                throw new IllegalArgumentException("missing required --out=<absolute-or-relative-path>");
            }
            return options;
        }

        private static int positive(String name, String value) {
            int parsed = Integer.parseInt(value);
            if (parsed < 1) {
                throw new IllegalArgumentException("--" + name + " out of range: " + value);
            }
            return parsed;
        }

        private static int nonNegative(String name, String value) {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) {
                throw new IllegalArgumentException("--" + name + " out of range: " + value);
            }
            return parsed;
        }

        private static String nonBlank(String name, String value) {
            if (value.isBlank()) {
                throw new IllegalArgumentException("--" + name + " must not be blank");
            }
            return value;
        }
    }
}
