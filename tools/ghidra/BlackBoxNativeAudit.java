// Black-box structural audit for a native library extracted from a published JAR.
//@category J2LL.AttackerAudit

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressIterator;
import ghidra.program.model.address.AddressSpace;
import ghidra.program.model.listing.Data;
import ghidra.program.model.listing.DataIterator;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionIterator;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.pcode.PcodeOp;
import ghidra.program.model.pcode.Varnode;
import ghidra.program.model.symbol.Namespace;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolIterator;
import ghidra.program.model.symbol.SymbolTable;
import ghidra.util.exception.CancelledException;

/**
 * Produces bounded, machine-readable evidence from the currently imported binary.
 *
 * <p>The script deliberately knows only the JVM ABI root {@code JNI_OnLoad}. It
 * does not consume j2ll source, configuration, reports, generated C/LLVM, build
 * directories, Java method names, or hard-coded addresses.</p>
 */
public class BlackBoxNativeAudit extends GhidraScript {
    private static final String SCHEMA_VERSION = "j2ll-black-box-native-audit-v1";

    @Override
    protected void run() throws Exception {
        Options options = Options.parse(getScriptArgs());

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", SCHEMA_VERSION);
        report.put("status", "PENDING");
        report.put("completed", false);
        report.put("coverageComplete", false);
        report.put("evidenceBoundary", List.of(
                "final native library only",
                "no source/config/report/intermediate input",
                "heuristics are measurements, not proof of non-recoverability"));
        report.put("input", inputFacts());
        report.put("budgets", options.asJson());

        SectionFacts sections = sectionFacts(options);
        report.put("sections", sections.rows);
        report.put("exportEvidence", exportedSymbols());
        report.put("imports", importedSymbols());

        FunctionInventory inventory = inventory(options);
        report.put("functionInventory", inventory.summary());

        RootClosure closure = rootClosure(inventory, options);
        report.put("jniOnLoadRootedClosure", closure.asJson());

        StringInventory strings = stringInventory(options);
        report.put("staticStrings", strings.asJson());

        PointerEvidence pointers = pointerEvidence(options);
        report.put("persistentCodePointers", pointers.codePointersJson());
        report.put("staticRegistrationEvidence", pointers.registrationJson());

        Map<String, Object> decoders = decoderCandidates(inventory, options);
        report.put("decoderCandidates", decoders);
        Map<String, Object> clones = cloneFamilies(inventory, options);
        report.put("normalizedPcodeCloneFamilies", clones);
        DecompilerEvidence decompiler = decompilerMetrics(closure, options);
        report.put("decompiler", decompiler.json);
        report.put("limitations", List.of(
                "indirect JNI table calls are counted but not assigned semantic JNI slot names",
                "runtime-constructed RegisterNatives arrays and runtime plaintext require dynamic observation",
                "entropy and raw-string scans are bounded and report truncation explicitly",
                "normalized p-code families ignore constants and addresses and can merge unrelated small functions"));

        boolean decoderCoverageComplete = booleanValue(decoders, "coverageComplete");
        boolean cloneCoverageComplete = booleanValue(clones, "coverageComplete");
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("sectionEntropy", coverageEntry(true, !sections.truncated, sections.truncated));
        coverage.put("exports", coverageEntry(true, true, false));
        coverage.put("imports", coverageEntry(true, true, false));
        coverage.put("functionInventory", coverageEntry(true, inventory.coverageComplete(), inventory.truncated));
        coverage.put("jniOnLoadRootedClosure", coverageEntry(true, closure.coverageComplete(), closure.truncated));
        coverage.put("staticStrings", coverageEntry(true, strings.coverageComplete(), strings.truncated));
        coverage.put("persistentCodePointers", coverageEntry(
                true, pointers.codePointerCoverageComplete(), pointers.codePointerTruncated()));
        coverage.put("staticRegistrationEvidence", coverageEntry(
                true, pointers.registrationCoverageComplete(), pointers.registrationTruncated()));
        coverage.put("decoderCandidates", coverageEntry(
                true, decoderCoverageComplete, booleanValue(decoders, "truncated")));
        coverage.put("normalizedPcodeCloneFamilies", coverageEntry(
                true, cloneCoverageComplete, booleanValue(clones, "truncated")));
        coverage.put("decompiler", coverageEntry(
                decompiler.analysisCompleted, decompiler.coverageComplete, decompiler.truncated));
        report.put("coverage", coverage);

        boolean completed = decompiler.analysisCompleted;
        boolean coverageComplete = !sections.truncated
                && inventory.coverageComplete()
                && closure.coverageComplete()
                && strings.coverageComplete()
                && pointers.codePointerCoverageComplete()
                && pointers.registrationCoverageComplete()
                && decoderCoverageComplete
                && cloneCoverageComplete
                && decompiler.coverageComplete;
        report.put("status", !completed
                ? "INCOMPLETE"
                : coverageComplete ? "COMPLETE" : "COMPLETE_WITH_PARTIAL_COVERAGE");
        report.put("completed", completed);
        report.put("coverageComplete", coverageComplete);

        Path output = Path.of(options.output).toAbsolutePath().normalize();
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(output, Json.write(report) + System.lineSeparator(), StandardCharsets.UTF_8);
        println("blackBoxNativeAudit=" + output);
    }

    private Map<String, Object> inputFacts() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("programName", currentProgram.getName());
        input.put("executablePath", currentProgram.getExecutablePath());
        input.put("format", currentProgram.getExecutableFormat());
        input.put("languageId", currentProgram.getLanguageID().toString());
        input.put("compilerSpecId", currentProgram.getCompilerSpec().getCompilerSpecID().toString());
        input.put("compiler", currentProgram.getCompiler());
        input.put("pointerSize", currentProgram.getDefaultPointerSize());
        input.put("bigEndian", currentProgram.getLanguage().isBigEndian());
        input.put("imageBase", currentProgram.getImageBase().toString());
        input.put("md5", currentProgram.getExecutableMD5());
        input.put("sha256", currentProgram.getExecutableSHA256());
        return input;
    }

    private SectionFacts sectionFacts(Options options) throws Exception {
        List<MemoryBlock> blocks = new ArrayList<>(Arrays.asList(currentProgram.getMemory().getBlocks()));
        blocks.sort(Comparator.comparing(MemoryBlock::getStart));
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean truncated = false;
        for (MemoryBlock block : blocks) {
            monitor.checkCancelled();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", block.getName());
            row.put("start", block.getStart().toString());
            row.put("end", block.getEnd().toString());
            row.put("size", block.getSize());
            row.put("read", block.isRead());
            row.put("write", block.isWrite());
            row.put("execute", block.isExecute());
            row.put("initialized", block.isInitialized());
            Entropy entropy = entropy(block, options.maxEntropyBytesPerSection);
            row.put("entropy", entropy.value);
            row.put("entropySampleBytes", entropy.sampleBytes);
            row.put("entropySampleTruncated", entropy.truncated);
            row.put("entropyCoverageComplete", !entropy.truncated);
            truncated |= entropy.truncated;
            rows.add(row);
        }
        return new SectionFacts(rows, truncated);
    }

    private Entropy entropy(MemoryBlock block, int budget) throws Exception {
        if (!block.isInitialized() || block.getSize() <= 0 || budget <= 0) {
            return new Entropy(null, 0, block.getSize() > 0);
        }
        long size = block.getSize();
        int sample = (int) Math.min(size, (long) budget);
        long[] counts = new long[256];
        Memory memory = currentProgram.getMemory();
        for (int index = 0; index < sample; index++) {
            monitor.checkCancelled();
            long offset = sample == size ? index : (index * size) / sample;
            byte value = memory.getByte(block.getStart().add(offset));
            counts[value & 0xff]++;
        }
        double entropy = 0.0;
        for (long count : counts) {
            if (count == 0) {
                continue;
            }
            double probability = (double) count / sample;
            entropy -= probability * (Math.log(probability) / Math.log(2.0));
        }
        return new Entropy(Math.round(entropy * 10000.0) / 10000.0, sample, sample < size);
    }

    private Map<String, Object> exportedSymbols() {
        SymbolTable symbols = currentProgram.getSymbolTable();
        FunctionManager functions = currentProgram.getFunctionManager();
        List<Map<String, Object>> entryPoints = new ArrayList<>();
        AddressIterator iterator = symbols.getExternalEntryPointIterator();
        while (iterator.hasNext()) {
            Address address = iterator.next();
            Symbol symbol = symbols.getPrimarySymbol(address);
            Function function = functions.getFunctionAt(address);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("address", address.toString());
            row.put("name", symbol == null ? (function == null ? null : function.getName()) : symbol.getName());
            row.put("function", function != null);
            entryPoints.add(row);
        }
        entryPoints.sort(Comparator.comparing(row -> String.valueOf(row.get("address"))));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("basis", "Ghidra loader external-entrypoint flags");
        result.put("exactDynamicExportTable", false);
        result.put("entryPointCount", entryPoints.size());
        result.put("entryPoints", entryPoints);
        return result;
    }

    private List<Map<String, Object>> importedSymbols() {
        SymbolIterator iterator = currentProgram.getSymbolTable().getExternalSymbols();
        List<Map<String, Object>> imports = new ArrayList<>();
        Set<String> seen = new TreeSet<>();
        while (iterator.hasNext()) {
            Symbol symbol = iterator.next();
            Namespace namespace = symbol.getParentNamespace();
            String library = namespace == null ? null : namespace.getName(true);
            String key = String.valueOf(library) + "\u0000" + symbol.getName() + "\u0000" + symbol.getAddress();
            if (!seen.add(key)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("library", library);
            row.put("name", symbol.getName());
            row.put("address", symbol.getAddress().toString());
            row.put("referenceCount", symbol.getReferenceCount());
            imports.add(row);
        }
        imports.sort(Comparator
                .comparing((Map<String, Object> row) -> String.valueOf(row.get("library")))
                .thenComparing(row -> String.valueOf(row.get("name")))
                .thenComparing(row -> String.valueOf(row.get("address"))));
        return imports;
    }

    private FunctionInventory inventory(Options options) throws Exception {
        FunctionManager manager = currentProgram.getFunctionManager();
        FunctionIterator iterator = manager.getFunctions(true);
        List<FunctionFacts> facts = new ArrayList<>();
        Map<Address, FunctionFacts> byEntry = new HashMap<>();
        int total = manager.getFunctionCount();
        boolean truncated = false;
        while (iterator.hasNext()) {
            monitor.checkCancelled();
            Function function = iterator.next();
            if (function.isExternal()) {
                continue;
            }
            if (facts.size() >= options.maxFunctions) {
                truncated = true;
                break;
            }
            FunctionFacts value = inspectFunction(function);
            facts.add(value);
            byEntry.put(function.getEntryPoint(), value);
        }
        facts.sort(Comparator.comparing(value -> value.function.getEntryPoint()));
        return new FunctionInventory(total, facts, byEntry, truncated);
    }

    private FunctionFacts inspectFunction(Function function) throws Exception {
        InstructionIterator iterator = currentProgram.getListing().getInstructions(function.getBody(), true);
        int instructions = 0;
        int pcodeOps = 0;
        int directCalls = 0;
        int indirectCalls = 0;
        int resolvedIndirectCalls = 0;
        int unresolvedIndirectCalls = 0;
        int conditionalBranches = 0;
        int backwardFlows = 0;
        int xorOps = 0;
        int rotateShiftOps = 0;
        int loadOps = 0;
        int storeOps = 0;
        int dataReferences = 0;
        StringBuilder normalized = new StringBuilder();
        while (iterator.hasNext()) {
            monitor.checkCancelled();
            Instruction instruction = iterator.next();
            instructions++;
            Address from = instruction.getAddress();
            for (Address flow : instruction.getFlows()) {
                if (function.getBody().contains(flow) && flow.compareTo(from) < 0) {
                    backwardFlows++;
                }
            }
            Reference[] references = currentProgram.getReferenceManager().getReferencesFrom(from);
            boolean hasResolvedCallReference = false;
            for (Reference reference : references) {
                if (reference.getReferenceType().isData()) {
                    dataReferences++;
                }
                if (reference.getReferenceType().isCall()
                        && currentProgram.getFunctionManager().getFunctionAt(reference.getToAddress()) != null) {
                    hasResolvedCallReference = true;
                }
            }
            for (PcodeOp operation : instruction.getPcode()) {
                pcodeOps++;
                int opcode = operation.getOpcode();
                if (opcode == PcodeOp.CALL) {
                    directCalls++;
                } else if (opcode == PcodeOp.CALLIND) {
                    indirectCalls++;
                    if (hasResolvedCallReference) {
                        resolvedIndirectCalls++;
                    } else {
                        unresolvedIndirectCalls++;
                    }
                } else if (opcode == PcodeOp.CBRANCH) {
                    conditionalBranches++;
                } else if (opcode == PcodeOp.INT_XOR) {
                    xorOps++;
                } else if (opcode == PcodeOp.INT_LEFT || opcode == PcodeOp.INT_RIGHT || opcode == PcodeOp.INT_SRIGHT) {
                    rotateShiftOps++;
                } else if (opcode == PcodeOp.LOAD) {
                    loadOps++;
                } else if (opcode == PcodeOp.STORE) {
                    storeOps++;
                }
                normalized.append(operation.getMnemonic());
                for (Varnode input : operation.getInputs()) {
                    normalized.append(':').append(varnodeClass(input));
                }
                normalized.append(';');
            }
        }
        Set<Function> callees = function.getCalledFunctions(monitor);
        int internalCallees = 0;
        int externalCallees = 0;
        for (Function callee : callees) {
            if (callee.isExternal()) {
                externalCallees++;
            } else {
                internalCallees++;
            }
        }
        long bodyBytes = function.getBody().getNumAddresses();
        String fingerprint = sha256(normalized.toString());
        return new FunctionFacts(function, bodyBytes, instructions, pcodeOps, directCalls, indirectCalls,
                resolvedIndirectCalls, unresolvedIndirectCalls,
                internalCallees, externalCallees, conditionalBranches, backwardFlows, xorOps,
                rotateShiftOps, loadOps, storeOps, dataReferences, fingerprint);
    }

    private String varnodeClass(Varnode value) {
        if (value.isConstant()) {
            return "C" + value.getSize();
        }
        if (value.isRegister()) {
            return "R" + value.getSize();
        }
        if (value.isUnique()) {
            return "U" + value.getSize();
        }
        if (value.isAddress()) {
            return "A" + value.getSize();
        }
        return "O" + value.getSize();
    }

    private RootClosure rootClosure(FunctionInventory inventory, Options options) throws Exception {
        List<Function> roots = findJniOnLoadRoots();
        Map<Address, Integer> depth = new LinkedHashMap<>();
        Deque<Function> queue = new ArrayDeque<>();
        for (Function root : roots) {
            if (depth.putIfAbsent(root.getEntryPoint(), 0) == null) {
                queue.add(root);
            }
        }
        int edgeCount = 0;
        int unresolvedIndirectSites = 0;
        boolean truncated = false;
        while (!queue.isEmpty()) {
            monitor.checkCancelled();
            Function function = queue.removeFirst();
            int currentDepth = depth.get(function.getEntryPoint());
            FunctionFacts known = inventory.byEntry.get(function.getEntryPoint());
            if (known != null) {
                unresolvedIndirectSites += known.unresolvedIndirectCalls;
            }
            if (currentDepth >= options.maxRootDepth) {
                if (!function.getCalledFunctions(monitor).isEmpty()) {
                    truncated = true;
                }
                continue;
            }
            List<Function> callees = new ArrayList<>(function.getCalledFunctions(monitor));
            callees.sort(Comparator.comparing(Function::getEntryPoint));
            for (Function callee : callees) {
                if (callee.isExternal()) {
                    continue;
                }
                edgeCount++;
                if (depth.containsKey(callee.getEntryPoint())) {
                    continue;
                }
                if (depth.size() >= options.maxRootFunctions) {
                    truncated = true;
                    continue;
                }
                depth.put(callee.getEntryPoint(), currentDepth + 1);
                queue.addLast(callee);
            }
        }
        List<FunctionDepth> ordered = new ArrayList<>();
        int missingFunctionFacts = 0;
        for (Map.Entry<Address, Integer> entry : depth.entrySet()) {
            Function function = currentProgram.getFunctionManager().getFunctionAt(entry.getKey());
            if (function != null) {
                FunctionFacts facts = inventory.byEntry.get(entry.getKey());
                if (facts == null) {
                    missingFunctionFacts++;
                }
                ordered.add(new FunctionDepth(function, entry.getValue(), facts));
            }
        }
        ordered.sort(Comparator.comparingInt((FunctionDepth value) -> value.depth)
                .thenComparing(value -> value.function.getEntryPoint()));
        return new RootClosure(roots, ordered, edgeCount, unresolvedIndirectSites,
                inventory.truncated, missingFunctionFacts, truncated);
    }

    private List<Function> findJniOnLoadRoots() {
        Map<Address, Function> roots = new TreeMap<>();
        SymbolIterator all = currentProgram.getSymbolTable().getAllSymbols(true);
        while (all.hasNext()) {
            Symbol symbol = all.next();
            if (!isJniOnLoadName(symbol.getName())) {
                continue;
            }
            Function function = currentProgram.getFunctionManager().getFunctionAt(symbol.getAddress());
            if (function != null && !function.isExternal()) {
                roots.put(function.getEntryPoint(), function);
            }
        }
        return new ArrayList<>(roots.values());
    }

    private boolean isJniOnLoadName(String name) {
        return "JNI_OnLoad".equals(name) || "_JNI_OnLoad".equals(name);
    }

    private StringInventory stringInventory(Options options) throws Exception {
        Map<String, StaticString> found = new LinkedHashMap<>();
        boolean[] stringLimitReached = new boolean[1];
        DataIterator data = currentProgram.getListing().getDefinedData(true);
        while (data.hasNext()) {
            monitor.checkCancelled();
            Data value = data.next();
            if (!value.hasStringValue() || value.getValue() == null) {
                continue;
            }
            addString(found, new StaticString(value.getAddress(), "GHIDRA_DEFINED", String.valueOf(value.getValue())), options, stringLimitReached);
        }

        List<MemoryBlock> blocks = new ArrayList<>(Arrays.asList(currentProgram.getMemory().getBlocks()));
        blocks.sort(Comparator.comparing(MemoryBlock::getStart));
        long scanned = 0;
        long available = 0;
        boolean truncated = false;
        for (MemoryBlock block : blocks) {
            if (!block.isInitialized()) {
                continue;
            }
            long size = block.getSize();
            available += size;
            int length = (int) Math.min(size, (long) options.maxStringScanBytesPerSection);
            if (length < size) {
                truncated = true;
            }
            if (length <= 0) {
                continue;
            }
            byte[] bytes = new byte[length];
            int read = currentProgram.getMemory().getBytes(block.getStart(), bytes);
            if (read < bytes.length) {
                truncated = true;
                bytes = Arrays.copyOf(bytes, Math.max(0, read));
            }
            scanned += bytes.length;
            scanAscii(block.getStart(), bytes, found, options, stringLimitReached);
            scanUtf16(block.getStart(), bytes, found, options, true, stringLimitReached);
            scanUtf16(block.getStart(), bytes, found, options, false, stringLimitReached);
        }
        List<StaticString> strings = new ArrayList<>(found.values());
        strings.sort(Comparator.comparing((StaticString value) -> value.address)
                .thenComparing(value -> value.encoding)
                .thenComparing(value -> value.value));
        if (strings.size() > options.maxStrings) {
            strings = new ArrayList<>(strings.subList(0, options.maxStrings));
            truncated = true;
        }
        return new StringInventory(strings, scanned, available, truncated || stringLimitReached[0]);
    }

    private void scanAscii(Address base, byte[] bytes, Map<String, StaticString> found, Options options,
            boolean[] limitReached) {
        int start = -1;
        for (int index = 0; index <= bytes.length; index++) {
            boolean printable = index < bytes.length && isPrintable(bytes[index] & 0xff);
            if (printable && start < 0) {
                start = index;
            }
            if ((!printable || index == bytes.length) && start >= 0) {
                int length = index - start;
                if (length >= options.minStringLength) {
                    String value = new String(bytes, start, length, StandardCharsets.US_ASCII);
                    addString(found, new StaticString(base.add(start), "ASCII", value), options, limitReached);
                }
                start = -1;
            }
        }
    }

    private void scanUtf16(Address base, byte[] bytes, Map<String, StaticString> found, Options options,
            boolean littleEndian, boolean[] limitReached) {
        for (int alignment = 0; alignment < 2; alignment++) {
            int start = -1;
            StringBuilder value = new StringBuilder();
            for (int index = alignment; index + 1 < bytes.length; index += 2) {
                int first = bytes[index] & 0xff;
                int second = bytes[index + 1] & 0xff;
                int character = littleEndian ? first | (second << 8) : (first << 8) | second;
                boolean printable = character >= 0x20 && character <= 0x7e;
                if (printable) {
                    if (start < 0) {
                        start = index;
                    }
                    if (value.length() < options.maxStringChars) {
                        value.append((char) character);
                    }
                } else if (start >= 0) {
                    int characters = (index - start) / 2;
                    if (characters >= options.minStringLength) {
                        addString(found, new StaticString(base.add(start), littleEndian ? "UTF16_LE" : "UTF16_BE", value.toString()), options, limitReached);
                    }
                    start = -1;
                    value.setLength(0);
                }
            }
            if (start >= 0 && value.length() >= options.minStringLength) {
                addString(found, new StaticString(base.add(start), littleEndian ? "UTF16_LE" : "UTF16_BE", value.toString()), options, limitReached);
            }
        }
    }

    private void addString(Map<String, StaticString> found, StaticString value, Options options,
            boolean[] limitReached) {
        if (found.size() >= options.maxStrings) {
            limitReached[0] = true;
            return;
        }
        if (value.value.length() > options.maxStringChars) {
            value = new StaticString(value.address, value.encoding, value.value.substring(0, options.maxStringChars));
        }
        String key = value.address + "\u0000" + value.encoding + "\u0000" + value.value;
        found.putIfAbsent(key, value);
    }

    private boolean isPrintable(int value) {
        return value >= 0x20 && value <= 0x7e;
    }

    private PointerEvidence pointerEvidence(Options options) throws Exception {
        Memory memory = currentProgram.getMemory();
        int pointerSize = currentProgram.getDefaultPointerSize();
        boolean bigEndian = currentProgram.getLanguage().isBigEndian();
        List<MemoryBlock> blocks = new ArrayList<>(Arrays.asList(memory.getBlocks()));
        blocks.sort(Comparator.comparing(MemoryBlock::getStart));
        List<CodePointer> cells = new ArrayList<>();
        List<RegistrationTriplet> triplets = new ArrayList<>();
        long scannedCells = 0;
        long detectedCodePointers = 0;
        long detectedRegistrationTriplets = 0;
        boolean scanTruncated = false;
        boolean codePointerListingTruncated = false;
        boolean registrationListingTruncated = false;
        for (MemoryBlock block : blocks) {
            if (!block.isInitialized() || block.isExecute() || block.getSize() < pointerSize) {
                continue;
            }
            long scanBytes = Math.min(block.getSize(), (long) options.maxPointerScanBytesPerSection);
            if (scanBytes < block.getSize()) {
                scanTruncated = true;
            }
            for (long offset = 0; offset + pointerSize <= scanBytes; offset += pointerSize) {
                monitor.checkCancelled();
                scannedCells++;
                Address cell = block.getStart().add(offset);
                Address target = readPointer(cell, pointerSize, bigEndian);
                MemoryBlock targetBlock = target == null ? null : memory.getBlock(target);
                if (targetBlock != null && targetBlock.isExecute()) {
                    detectedCodePointers++;
                    if (cells.size() < options.maxCodePointerCells) {
                        cells.add(new CodePointer(cell, target, block.isWrite()));
                    } else {
                        codePointerListingTruncated = true;
                    }
                }
                if (offset + (long) pointerSize * 3 > scanBytes) {
                    continue;
                }
                Address namePointer = target;
                Address descriptorPointer = readPointer(cell.add(pointerSize), pointerSize, bigEndian);
                Address codePointer = readPointer(cell.add((long) pointerSize * 2), pointerSize, bigEndian);
                if (namePointer == null || descriptorPointer == null || codePointer == null) {
                    continue;
                }
                String name = readAsciiCString(namePointer, 128);
                String descriptor = readAsciiCString(descriptorPointer, 384);
                MemoryBlock codeBlock = memory.getBlock(codePointer);
                if (name != null && descriptor != null && codeBlock != null && codeBlock.isExecute()
                        && isPlausibleJniName(name) && isPlausibleDescriptor(descriptor)) {
                    detectedRegistrationTriplets++;
                    if (triplets.size() < options.maxRegistrationCandidates) {
                        triplets.add(new RegistrationTriplet(cell, name, descriptor, codePointer, block.isWrite()));
                    } else {
                        registrationListingTruncated = true;
                    }
                }
            }
        }
        cells.sort(Comparator.comparing(value -> value.cell));
        int adjacent = 0;
        int writable = 0;
        for (int index = 0; index < cells.size(); index++) {
            if (cells.get(index).writable) {
                writable++;
            }
            if (index > 0 && cells.get(index).cell.hasSameAddressSpace(cells.get(index - 1).cell)
                    && cells.get(index).cell.subtract(cells.get(index - 1).cell) == pointerSize) {
                adjacent++;
            }
        }
        boolean namedRegisterNatives = importedSymbolExists("RegisterNatives");
        return new PointerEvidence(cells, triplets, scannedCells, detectedCodePointers,
                detectedRegistrationTriplets, adjacent, writable, namedRegisterNatives,
                scanTruncated, codePointerListingTruncated, registrationListingTruncated);
    }

    private Address readPointer(Address address, int pointerSize, boolean bigEndian) {
        try {
            long value;
            if (pointerSize == 8) {
                value = currentProgram.getMemory().getLong(address, bigEndian);
            } else if (pointerSize == 4) {
                value = currentProgram.getMemory().getInt(address, bigEndian) & 0xffffffffL;
            } else {
                return null;
            }
            AddressSpace space = currentProgram.getAddressFactory().getDefaultAddressSpace();
            return space.getAddress(value, true);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String readAsciiCString(Address address, int maxCharacters) {
        MemoryBlock block = currentProgram.getMemory().getBlock(address);
        if (block == null || !block.isInitialized()) {
            return null;
        }
        StringBuilder value = new StringBuilder();
        try {
            for (int index = 0; index <= maxCharacters; index++) {
                Address cursor = address.add(index);
                if (!block.contains(cursor)) {
                    return null;
                }
                int current = currentProgram.getMemory().getByte(cursor) & 0xff;
                if (current == 0) {
                    return value.toString();
                }
                if (!isPrintable(current)) {
                    return null;
                }
                if (index == maxCharacters) {
                    return null;
                }
                value.append((char) current);
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private boolean isPlausibleJniName(String value) {
        return value.length() <= 128 && value.matches("(?:<init>|<clinit>|[A-Za-z_$][A-Za-z0-9_$]*)");
    }

    private boolean isPlausibleDescriptor(String value) {
        return value.length() <= 384 && value.matches("\\([^)]*\\)(?:V|Z|B|C|S|I|J|F|D|L[^;]+;|\\[.+)");
    }

    private boolean importedSymbolExists(String expected) {
        SymbolIterator iterator = currentProgram.getSymbolTable().getExternalSymbols();
        while (iterator.hasNext()) {
            if (expected.equals(iterator.next().getName())) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> decoderCandidates(FunctionInventory inventory, Options options) {
        List<FunctionFacts> candidates = new ArrayList<>();
        for (FunctionFacts value : inventory.functions) {
            boolean memoryTransform = value.loadOps > 0 && value.storeOps > 0;
            boolean loopCodec = value.backwardFlows > 0 && value.xorOps + value.rotateShiftOps >= 2;
            boolean straightCodec = value.xorOps >= 4 && value.rotateShiftOps >= 2;
            if (memoryTransform && (loopCodec || straightCodec)) {
                candidates.add(value);
            }
        }
        candidates.sort(Comparator
                .comparingInt((FunctionFacts value) -> value.function.getCallingFunctions(monitor).size()).reversed()
                .thenComparing(value -> value.function.getEntryPoint()));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = 0; index < Math.min(candidates.size(), options.maxDecoderCandidates); index++) {
            FunctionFacts value = candidates.get(index);
            Map<String, Object> row = value.identityJson();
            row.put("callerFunctionCount", value.function.getCallingFunctions(monitor).size());
            row.put("backwardFlows", value.backwardFlows);
            row.put("xorOps", value.xorOps);
            row.put("rotateOrShiftOps", value.rotateShiftOps);
            row.put("loadOps", value.loadOps);
            row.put("storeOps", value.storeOps);
            rows.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("heuristic", "(load && store) && ((backward-flow && xor/shift>=2) || (xor>=4 && shift>=2))");
        result.put("candidateCount", candidates.size());
        result.put("listedCount", rows.size());
        boolean listingTruncated = rows.size() < candidates.size();
        boolean truncated = inventory.truncated || listingTruncated;
        result.put("inputInventoryTruncated", inventory.truncated);
        result.put("listingTruncated", listingTruncated);
        result.put("truncated", truncated);
        result.put("coverageComplete", !truncated);
        result.put("candidates", rows);
        return result;
    }

    private Map<String, Object> cloneFamilies(FunctionInventory inventory, Options options) {
        Map<String, List<FunctionFacts>> groups = new HashMap<>();
        for (FunctionFacts value : inventory.functions) {
            if (value.pcodeOps < options.minClonePcodeOps) {
                continue;
            }
            groups.computeIfAbsent(value.normalizedPcodeSha256, ignored -> new ArrayList<>()).add(value);
        }
        List<Map.Entry<String, List<FunctionFacts>>> families = new ArrayList<>();
        for (Map.Entry<String, List<FunctionFacts>> entry : groups.entrySet()) {
            if (entry.getValue().size() >= 2) {
                entry.getValue().sort(Comparator.comparing(value -> value.function.getEntryPoint()));
                families.add(entry);
            }
        }
        families.sort(Comparator
                .<Map.Entry<String, List<FunctionFacts>>>comparingInt(entry -> entry.getValue().size()).reversed()
                .thenComparing(Map.Entry::getKey));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = 0; index < Math.min(families.size(), options.maxCloneFamilies); index++) {
            Map.Entry<String, List<FunctionFacts>> entry = families.get(index);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("normalizedPcodeSha256", entry.getKey());
            row.put("memberCount", entry.getValue().size());
            List<Map<String, Object>> members = new ArrayList<>();
            for (int member = 0; member < Math.min(entry.getValue().size(), options.maxCloneMembersPerFamily); member++) {
                members.add(entry.getValue().get(member).identityJson());
            }
            row.put("members", members);
            row.put("membersTruncated", members.size() < entry.getValue().size());
            rows.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("normalization", "p-code opcodes plus input storage classes/sizes; constants and addresses omitted");
        result.put("minimumPcodeOps", options.minClonePcodeOps);
        result.put("familyCount", families.size());
        result.put("listedCount", rows.size());
        boolean listingTruncated = rows.size() < families.size();
        boolean truncated = inventory.truncated || listingTruncated;
        result.put("inputInventoryTruncated", inventory.truncated);
        result.put("listingTruncated", listingTruncated);
        result.put("truncated", truncated);
        result.put("coverageComplete", !truncated);
        result.put("families", rows);
        return result;
    }

    private DecompilerEvidence decompilerMetrics(RootClosure closure, Options options) throws CancelledException {
        Map<String, Object> result = new LinkedHashMap<>();
        if (closure.functions.isEmpty() || options.maxDecompileFunctions == 0) {
            boolean truncated = !closure.functions.isEmpty();
            result.put("status", truncated ? "COMPLETE_WITH_PARTIAL_COVERAGE" : "COMPLETE");
            result.put("completed", true);
            result.put("coverageComplete", !truncated);
            result.put("attempted", 0);
            result.put("succeeded", 0);
            result.put("failed", 0);
            result.put("truncated", truncated);
            result.put("functions", List.of());
            return new DecompilerEvidence(result, true, !truncated, truncated);
        }
        DecompInterface decompiler = new DecompInterface();
        List<Map<String, Object>> rows = new ArrayList<>();
        int succeeded = 0;
        boolean analysisCompleted = true;
        try {
            decompiler.setOptions(new DecompileOptions());
            decompiler.toggleCCode(true);
            decompiler.toggleSyntaxTree(true);
            decompiler.setSimplificationStyle("decompile");
            if (!decompiler.openProgram(currentProgram)) {
                result.put("status", "INCOMPLETE");
                result.put("completed", false);
                result.put("coverageComplete", false);
                result.put("openProgramError", decompiler.getLastMessage());
                result.put("attempted", 0);
                result.put("succeeded", 0);
                result.put("failed", 0);
                result.put("truncated", true);
                result.put("functions", List.of());
                return new DecompilerEvidence(result, false, false, true);
            }
            int limit = Math.min(closure.functions.size(), options.maxDecompileFunctions);
            for (int index = 0; index < limit; index++) {
                monitor.checkCancelled();
                FunctionDepth value = closure.functions.get(index);
                DecompileResults decompiled = decompiler.decompileFunction(
                        value.function, options.decompileTimeoutSeconds, monitor);
                String c = decompiled.getDecompiledFunction() == null ? null : decompiled.getDecompiledFunction().getC();
                boolean ok = decompiled.decompileCompleted() && c != null;
                if (ok) {
                    succeeded++;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("address", value.function.getEntryPoint().toString());
                row.put("name", value.function.getName());
                row.put("depth", value.depth);
                row.put("success", ok);
                row.put("cCharacters", c == null ? 0 : c.length());
                row.put("cSha256", c == null ? null : sha256(c));
                row.put("error", ok ? null : decompiled.getErrorMessage());
                rows.add(row);
            }
        } catch (CancelledException error) {
            throw error;
        } catch (Exception error) {
            analysisCompleted = false;
            result.put("scriptError", error.toString());
        } finally {
            decompiler.closeProgram();
            decompiler.dispose();
        }
        result.put("timeoutSecondsPerFunction", options.decompileTimeoutSeconds);
        result.put("attempted", rows.size());
        result.put("succeeded", succeeded);
        int failed = rows.size() - succeeded;
        result.put("failed", failed);
        boolean truncated = rows.size() < closure.functions.size();
        boolean coverageComplete = analysisCompleted && !truncated && failed == 0;
        result.put("truncated", truncated);
        result.put("status", !analysisCompleted
                ? "INCOMPLETE"
                : coverageComplete ? "COMPLETE" : "COMPLETE_WITH_PARTIAL_COVERAGE");
        result.put("completed", analysisCompleted);
        result.put("coverageComplete", coverageComplete);
        result.put("functions", rows);
        return new DecompilerEvidence(result, analysisCompleted, coverageComplete, truncated);
    }

    private static boolean booleanValue(Map<String, Object> values, String name) {
        return Boolean.TRUE.equals(values.get(name));
    }

    private static Map<String, Object> coverageEntry(
            boolean completed, boolean coverageComplete, boolean truncated) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("completed", completed);
        result.put("coverageComplete", coverageComplete);
        result.put("truncated", truncated);
        return result;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                output.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            }
            return output.toString();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private record Entropy(Double value, int sampleBytes, boolean truncated) {}

    private record SectionFacts(List<Map<String, Object>> rows, boolean truncated) {}

    private record DecompilerEvidence(Map<String, Object> json, boolean analysisCompleted,
            boolean coverageComplete, boolean truncated) {}

    private static final class FunctionFacts {
        final Function function;
        final long bodyBytes;
        final int instructions;
        final int pcodeOps;
        final int directCalls;
        final int indirectCalls;
        final int resolvedIndirectCalls;
        final int unresolvedIndirectCalls;
        final int internalCallees;
        final int externalCallees;
        final int conditionalBranches;
        final int backwardFlows;
        final int xorOps;
        final int rotateShiftOps;
        final int loadOps;
        final int storeOps;
        final int dataReferences;
        final String normalizedPcodeSha256;

        FunctionFacts(Function function, long bodyBytes, int instructions, int pcodeOps,
                int directCalls, int indirectCalls, int resolvedIndirectCalls, int unresolvedIndirectCalls,
                int internalCallees, int externalCallees,
                int conditionalBranches, int backwardFlows, int xorOps, int rotateShiftOps,
                int loadOps, int storeOps, int dataReferences, String normalizedPcodeSha256) {
            this.function = function;
            this.bodyBytes = bodyBytes;
            this.instructions = instructions;
            this.pcodeOps = pcodeOps;
            this.directCalls = directCalls;
            this.indirectCalls = indirectCalls;
            this.resolvedIndirectCalls = resolvedIndirectCalls;
            this.unresolvedIndirectCalls = unresolvedIndirectCalls;
            this.internalCallees = internalCallees;
            this.externalCallees = externalCallees;
            this.conditionalBranches = conditionalBranches;
            this.backwardFlows = backwardFlows;
            this.xorOps = xorOps;
            this.rotateShiftOps = rotateShiftOps;
            this.loadOps = loadOps;
            this.storeOps = storeOps;
            this.dataReferences = dataReferences;
            this.normalizedPcodeSha256 = normalizedPcodeSha256;
        }

        Map<String, Object> identityJson() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("address", function.getEntryPoint().toString());
            row.put("name", function.getName());
            row.put("bodyBytes", bodyBytes);
            row.put("instructionCount", instructions);
            row.put("pcodeOpCount", pcodeOps);
            return row;
        }

        Map<String, Object> fullJson() {
            Map<String, Object> row = identityJson();
            row.put("directCallOps", directCalls);
            row.put("indirectCallOps", indirectCalls);
            row.put("resolvedIndirectCallOps", resolvedIndirectCalls);
            row.put("unresolvedIndirectCallOps", unresolvedIndirectCalls);
            row.put("resolvedInternalCallees", internalCallees);
            row.put("resolvedExternalCallees", externalCallees);
            row.put("conditionalBranches", conditionalBranches);
            row.put("backwardFlows", backwardFlows);
            row.put("dataReferences", dataReferences);
            row.put("normalizedPcodeSha256", normalizedPcodeSha256);
            row.put("coarseCallShape", indirectCalls > 0 ? "HAS_INDIRECT_CALL"
                    : internalCallees == 0 ? "NO_RESOLVED_INTERNAL_CALLEE"
                    : internalCallees == 1 ? "ONE_RESOLVED_INTERNAL_CALLEE"
                    : "MULTIPLE_RESOLVED_INTERNAL_CALLEES");
            return row;
        }
    }

    private static final class FunctionInventory {
        final int ghidraFunctionCount;
        final List<FunctionFacts> functions;
        final Map<Address, FunctionFacts> byEntry;
        final boolean truncated;

        FunctionInventory(int ghidraFunctionCount, List<FunctionFacts> functions,
                Map<Address, FunctionFacts> byEntry, boolean truncated) {
            this.ghidraFunctionCount = ghidraFunctionCount;
            this.functions = functions;
            this.byEntry = byEntry;
            this.truncated = truncated;
        }

        boolean coverageComplete() {
            return !truncated;
        }

        Map<String, Object> summary() {
            int indirectFunctions = 0;
            int noInternal = 0;
            int oneInternal = 0;
            int multipleInternal = 0;
            for (FunctionFacts value : functions) {
                if (value.indirectCalls > 0) {
                    indirectFunctions++;
                }
                if (value.internalCallees == 0) {
                    noInternal++;
                } else if (value.internalCallees == 1) {
                    oneInternal++;
                } else {
                    multipleInternal++;
                }
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ghidraFunctionCount", ghidraFunctionCount);
            result.put("inspectedInternalFunctionCount", functions.size());
            result.put("truncated", truncated);
            result.put("coverageComplete", coverageComplete());
            result.put("functionsWithIndirectCalls", indirectFunctions);
            result.put("noResolvedInternalCallee", noInternal);
            result.put("oneResolvedInternalCallee", oneInternal);
            result.put("multipleResolvedInternalCallees", multipleInternal);
            return result;
        }
    }

    private record FunctionDepth(Function function, int depth, FunctionFacts facts) {}

    private static final class RootClosure {
        final List<Function> roots;
        final List<FunctionDepth> functions;
        final int resolvedInternalEdges;
        final int unresolvedIndirectCallSites;
        final boolean inputInventoryTruncated;
        final int missingFunctionFacts;
        final boolean truncated;

        RootClosure(List<Function> roots, List<FunctionDepth> functions, int resolvedInternalEdges,
                int unresolvedIndirectCallSites, boolean inputInventoryTruncated,
                int missingFunctionFacts, boolean traversalTruncated) {
            this.roots = roots;
            this.functions = functions;
            this.resolvedInternalEdges = resolvedInternalEdges;
            this.unresolvedIndirectCallSites = unresolvedIndirectCallSites;
            this.inputInventoryTruncated = inputInventoryTruncated;
            this.missingFunctionFacts = missingFunctionFacts;
            this.truncated = traversalTruncated || missingFunctionFacts > 0;
        }

        boolean coverageComplete() {
            return !truncated;
        }

        Map<String, Object> asJson() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("rootFound", !roots.isEmpty());
            result.put("rootCount", roots.size());
            result.put("functionCount", functions.size());
            result.put("resolvedInternalEdges", resolvedInternalEdges);
            result.put("unresolvedIndirectCallSites", unresolvedIndirectCallSites);
            result.put("inputInventoryTruncated", inputInventoryTruncated);
            result.put("missingFunctionFacts", missingFunctionFacts);
            result.put("unresolvedIndirectCallSiteCountComplete", missingFunctionFacts == 0);
            result.put("truncated", truncated);
            result.put("coverageComplete", coverageComplete());
            List<Map<String, Object>> rows = new ArrayList<>();
            for (FunctionDepth value : functions) {
                Map<String, Object> row = value.facts == null
                        ? new LinkedHashMap<>()
                        : value.facts.fullJson();
                row.putIfAbsent("address", value.function.getEntryPoint().toString());
                row.putIfAbsent("name", value.function.getName());
                row.put("depth", value.depth);
                rows.add(row);
            }
            result.put("functions", rows);
            return result;
        }
    }

    private record StaticString(Address address, String encoding, String value) {
        Map<String, Object> asJson() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("address", address.toString());
            row.put("encoding", encoding);
            row.put("characters", value.length());
            row.put("value", value);
            return row;
        }
    }

    private static final class StringInventory {
        final List<StaticString> strings;
        final long scannedBytes;
        final long availableBytes;
        final boolean truncated;

        StringInventory(List<StaticString> strings, long scannedBytes, long availableBytes, boolean truncated) {
            this.strings = strings;
            this.scannedBytes = scannedBytes;
            this.availableBytes = availableBytes;
            this.truncated = truncated;
        }

        boolean coverageComplete() {
            return !truncated;
        }

        Map<String, Object> asJson() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("scanMethod", "Ghidra-defined strings plus bounded raw ASCII/UTF-16 scan");
            result.put("scannedBytes", scannedBytes);
            result.put("availableInitializedBytes", availableBytes);
            result.put("count", strings.size());
            result.put("truncated", truncated);
            result.put("coverageComplete", coverageComplete());
            List<Map<String, Object>> rows = new ArrayList<>();
            for (StaticString value : strings) {
                rows.add(value.asJson());
            }
            result.put("strings", rows);
            return result;
        }
    }

    private record CodePointer(Address cell, Address target, boolean writable) {
        Map<String, Object> asJson() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("cell", cell.toString());
            row.put("target", target.toString());
            row.put("writable", writable);
            return row;
        }
    }

    private record RegistrationTriplet(Address tableAddress, String methodName, String descriptor,
            Address codePointer, boolean writable) {
        Map<String, Object> asJson() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tableAddress", tableAddress.toString());
            row.put("methodName", methodName);
            row.put("descriptor", descriptor);
            row.put("codePointer", codePointer.toString());
            row.put("writable", writable);
            return row;
        }
    }

    private static final class PointerEvidence {
        final List<CodePointer> codePointers;
        final List<RegistrationTriplet> registrationTriplets;
        final long scannedCells;
        final long detectedCodePointers;
        final long detectedRegistrationTriplets;
        final int adjacentCodePointerCells;
        final int writableCodePointerCells;
        final boolean namedRegisterNativesImport;
        final boolean scanTruncated;
        final boolean codePointerListingTruncated;
        final boolean registrationListingTruncated;

        PointerEvidence(List<CodePointer> codePointers, List<RegistrationTriplet> registrationTriplets,
                long scannedCells, long detectedCodePointers, long detectedRegistrationTriplets,
                int adjacentCodePointerCells, int writableCodePointerCells,
                boolean namedRegisterNativesImport, boolean scanTruncated,
                boolean codePointerListingTruncated, boolean registrationListingTruncated) {
            this.codePointers = codePointers;
            this.registrationTriplets = registrationTriplets;
            this.scannedCells = scannedCells;
            this.detectedCodePointers = detectedCodePointers;
            this.detectedRegistrationTriplets = detectedRegistrationTriplets;
            this.adjacentCodePointerCells = adjacentCodePointerCells;
            this.writableCodePointerCells = writableCodePointerCells;
            this.namedRegisterNativesImport = namedRegisterNativesImport;
            this.scanTruncated = scanTruncated;
            this.codePointerListingTruncated = codePointerListingTruncated;
            this.registrationListingTruncated = registrationListingTruncated;
        }

        boolean codePointerTruncated() {
            return scanTruncated || codePointerListingTruncated;
        }

        boolean codePointerCoverageComplete() {
            return !codePointerTruncated();
        }

        boolean registrationTruncated() {
            return scanTruncated || registrationListingTruncated;
        }

        boolean registrationCoverageComplete() {
            return !registrationTruncated();
        }

        Map<String, Object> codePointersJson() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("scannedAlignedPointerCells", scannedCells);
            result.put("detectedCount", detectedCodePointers);
            result.put("listedCount", codePointers.size());
            result.put("listedWritableCount", writableCodePointerCells);
            result.put("listedAdjacentCellPairCount", adjacentCodePointerCells);
            result.put("scanTruncated", scanTruncated);
            result.put("listingTruncated", codePointerListingTruncated);
            result.put("truncated", codePointerTruncated());
            result.put("coverageComplete", codePointerCoverageComplete());
            List<Map<String, Object>> rows = new ArrayList<>();
            for (CodePointer value : codePointers) {
                rows.add(value.asJson());
            }
            result.put("cells", rows);
            return result;
        }

        Map<String, Object> registrationJson() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("namedRegisterNativesImport", namedRegisterNativesImport);
            result.put("scannedAlignedPointerCells", scannedCells);
            result.put("detectedPersistentJniNativeMethodTripletCount", detectedRegistrationTriplets);
            result.put("listedPersistentJniNativeMethodTripletCount", registrationTriplets.size());
            result.put("scanTruncated", scanTruncated);
            result.put("listingTruncated", registrationListingTruncated);
            result.put("truncated", registrationTruncated());
            result.put("coverageComplete", registrationCoverageComplete());
            result.put("runtimeConstructionStillPossible", true);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (RegistrationTriplet value : registrationTriplets) {
                rows.add(value.asJson());
            }
            result.put("triplets", rows);
            result.put("interpretation", "zero triplets does not hide RegisterNatives from a runtime hook");
            return result;
        }
    }

    private static final class Options {
        String output;
        int maxFunctions = 20000;
        int maxRootFunctions = 4096;
        int maxRootDepth = 16;
        int maxEntropyBytesPerSection = 1 << 20;
        int maxStringScanBytesPerSection = 16 << 20;
        int maxStrings = 4096;
        int minStringLength = 5;
        int maxStringChars = 512;
        int maxPointerScanBytesPerSection = 16 << 20;
        int maxCodePointerCells = 4096;
        int maxRegistrationCandidates = 1024;
        int maxDecoderCandidates = 256;
        int minClonePcodeOps = 12;
        int maxCloneFamilies = 256;
        int maxCloneMembersPerFamily = 64;
        int maxDecompileFunctions = 96;
        int decompileTimeoutSeconds = 15;

        static Options parse(String[] arguments) {
            Options options = new Options();
            for (int index = 0; index < arguments.length; index++) {
                String argument = arguments[index];
                if (argument == null || !argument.startsWith("--") || argument.length() <= 2) {
                    throw new IllegalArgumentException(
                            "expected --name=value or --name value, got: " + argument);
                }
                int equals = argument.indexOf('=');
                String name;
                String value;
                if (equals >= 0) {
                    if (equals < 3) {
                        throw new IllegalArgumentException(
                                "expected --name=value or --name value, got: " + argument);
                    }
                    name = argument.substring(2, equals);
                    value = argument.substring(equals + 1);
                } else {
                    name = argument.substring(2);
                    if (index + 1 >= arguments.length
                            || arguments[index + 1] == null
                            || arguments[index + 1].startsWith("--")) {
                        throw new IllegalArgumentException("missing value for --" + name);
                    }
                    value = arguments[++index];
                }
                switch (name) {
                    case "out" -> options.output = nonBlank(name, value);
                    case "max-functions" -> options.maxFunctions = positive(name, value, true);
                    case "max-root-functions" -> options.maxRootFunctions = positive(name, value, false);
                    case "max-root-depth" -> options.maxRootDepth = positive(name, value, true);
                    case "max-entropy-bytes-per-section" -> options.maxEntropyBytesPerSection = positive(name, value, true);
                    case "max-string-scan-bytes-per-section" -> options.maxStringScanBytesPerSection = positive(name, value, true);
                    case "max-strings" -> options.maxStrings = positive(name, value, true);
                    case "min-string-length" -> options.minStringLength = positive(name, value, false);
                    case "max-string-chars" -> options.maxStringChars = positive(name, value, false);
                    case "max-pointer-scan-bytes-per-section" -> options.maxPointerScanBytesPerSection = positive(name, value, true);
                    case "max-code-pointer-cells" -> options.maxCodePointerCells = positive(name, value, true);
                    case "max-registration-candidates" -> options.maxRegistrationCandidates = positive(name, value, true);
                    case "max-decoder-candidates" -> options.maxDecoderCandidates = positive(name, value, true);
                    case "min-clone-pcode-ops" -> options.minClonePcodeOps = positive(name, value, false);
                    case "max-clone-families" -> options.maxCloneFamilies = positive(name, value, true);
                    case "max-clone-members-per-family" -> options.maxCloneMembersPerFamily = positive(name, value, false);
                    case "max-decompile-functions" -> options.maxDecompileFunctions = positive(name, value, true);
                    case "decompile-timeout-seconds" -> options.decompileTimeoutSeconds = positive(name, value, false);
                    default -> throw new IllegalArgumentException("unknown option --" + name);
                }
            }
            if (options.output == null) {
                throw new IllegalArgumentException("missing required --out=<absolute-or-relative-path>");
            }
            return options;
        }

        private static int positive(String name, String value, boolean zeroAllowed) {
            int parsed = Integer.parseInt(value);
            if (parsed < (zeroAllowed ? 0 : 1)) {
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

        Map<String, Object> asJson() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("maxFunctions", maxFunctions);
            values.put("maxRootFunctions", maxRootFunctions);
            values.put("maxRootDepth", maxRootDepth);
            values.put("maxEntropyBytesPerSection", maxEntropyBytesPerSection);
            values.put("maxStringScanBytesPerSection", maxStringScanBytesPerSection);
            values.put("maxStrings", maxStrings);
            values.put("minStringLength", minStringLength);
            values.put("maxStringChars", maxStringChars);
            values.put("maxPointerScanBytesPerSection", maxPointerScanBytesPerSection);
            values.put("maxCodePointerCells", maxCodePointerCells);
            values.put("maxRegistrationCandidates", maxRegistrationCandidates);
            values.put("maxDecoderCandidates", maxDecoderCandidates);
            values.put("minClonePcodeOps", minClonePcodeOps);
            values.put("maxCloneFamilies", maxCloneFamilies);
            values.put("maxCloneMembersPerFamily", maxCloneMembersPerFamily);
            values.put("maxDecompileFunctions", maxDecompileFunctions);
            values.put("decompileTimeoutSeconds", decompileTimeoutSeconds);
            return values;
        }
    }

    private static final class Json {
        static String write(Object value) {
            StringBuilder output = new StringBuilder();
            append(output, value, 0);
            return output.toString();
        }

        private static void append(StringBuilder output, Object value, int indent) {
            if (value == null) {
                output.append("null");
            } else if (value instanceof String text) {
                string(output, text);
            } else if (value instanceof Number || value instanceof Boolean) {
                output.append(value);
            } else if (value instanceof Map<?, ?> map) {
                output.append('{');
                if (!map.isEmpty()) {
                    output.append('\n');
                }
                int index = 0;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (index++ > 0) {
                        output.append(",\n");
                    }
                    spaces(output, indent + 2);
                    string(output, String.valueOf(entry.getKey()));
                    output.append(": ");
                    append(output, entry.getValue(), indent + 2);
                }
                if (!map.isEmpty()) {
                    output.append('\n');
                    spaces(output, indent);
                }
                output.append('}');
            } else if (value instanceof Iterable<?> iterable) {
                output.append('[');
                int index = 0;
                for (Object item : iterable) {
                    if (index++ == 0) {
                        output.append('\n');
                    } else {
                        output.append(",\n");
                    }
                    spaces(output, indent + 2);
                    append(output, item, indent + 2);
                }
                if (index > 0) {
                    output.append('\n');
                    spaces(output, indent);
                }
                output.append(']');
            } else {
                string(output, String.valueOf(value));
            }
        }

        private static void string(StringBuilder output, String value) {
            output.append('"');
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                switch (character) {
                    case '"' -> output.append("\\\"");
                    case '\\' -> output.append("\\\\");
                    case '\b' -> output.append("\\b");
                    case '\f' -> output.append("\\f");
                    case '\n' -> output.append("\\n");
                    case '\r' -> output.append("\\r");
                    case '\t' -> output.append("\\t");
                    default -> {
                        if (character < 0x20) {
                            output.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                        } else {
                            output.append(character);
                        }
                    }
                }
            }
            output.append('"');
        }

        private static void spaces(StringBuilder output, int count) {
            output.append(" ".repeat(count));
        }
    }
}
