package xyz.melodysky.ir.pass.protection;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import xyz.melodysky.ir.model.BusinessStringConstantRef;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.report.SensitivePlaintextFact;

public final class StringEncryptionPass implements ProtectionPass {
    private static final String CARRIER_PREFIX = "j2ll_rt_string_constant|string:";
    private static final String CONST_STRING_PREFIX = "string:";
    private static final String ENCRYPTED_PREFIX =
            "j2ll_rt_string_constant|enc:v2:";

    @Override
    public String name() {
        return "STRING_ENCRYPTION";
    }

    @Override
    public boolean enabled(ProtectionConfig config) {
        return config.enabled() && config.stringEncryption();
    }

    @Override
    public boolean applicable(IrMethod method) {
        return hasStringCarrier(method) || (!isReflectionSensitive(method) && hasOrdinaryConstString(method));
    }

    @Override
    public String skipReasonCode(IrMethod method) {
        if (isReflectionSensitive(method) && hasOrdinaryConstString(method) && !hasStringCarrier(method)) {
            return "STRING_ENCRYPTION_REFLECTION_SENSITIVE";
        }
        return "NO_STRING_CONSTANT_CARRIER";
    }

    @Override
    public IrMethod run(IrMethod method, ProtectionConfig config) {
        if (!enabled(config)) {
            return method;
        }
        ProtectionRandom random = new ProtectionRandom(config.seed());
        ArrayList<IrBlock> blocks = new ArrayList<>();
        boolean rewriteConstStrings = !isReflectionSensitive(method);
        int[] constStringIndex = {0};
        for (IrBlock block : method.blocks()) {
            ArrayList<IrInstruction> instructions = new ArrayList<>();
            for (IrInstruction instruction : block.instructions()) {
                appendEncryptedInstruction(method, random, rewriteConstStrings, constStringIndex, instructions, instruction);
            }
            blocks.add(new IrBlock(
                    block.name(),
                    block.parameters(),
                    block.exceptionCatchTypes(),
                    block.exceptionEdges(),
                    instructions,
                    block.terminator()));
        }
        return new IrMethod(method.owner(), method.name(), method.descriptor(), method.returnType(), method.parameters(), blocks);
    }

    public List<SensitivePlaintextFact> sensitivePlaintextFacts(IrMethod method) {
        if (!applicable(method)) {
            return List.of();
        }
        ArrayList<SensitivePlaintextFact> facts = new ArrayList<>();
        boolean rewriteConstStrings = !isReflectionSensitive(method);
        method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .forEach(instruction -> {
                    if (isStringCarrier(instruction)) {
                        facts.add(SensitivePlaintextFact.of(
                                instruction.symbol().orElseThrow().substring(CARRIER_PREFIX.length()),
                                method.methodKey(),
                                name(),
                                List.of("generated-c", "llvm-ir", "native-library")));
                    } else if (rewriteConstStrings && isOrdinaryConstString(instruction)) {
                        facts.add(SensitivePlaintextFact.of(
                                constStringValue(instruction),
                                method.methodKey(),
                                name(),
                                List.of("generated-c", "llvm-ir", "native-library")));
                    }
                });
        return facts.stream()
                .distinct()
                .sorted(java.util.Comparator
                        .comparing(SensitivePlaintextFact::literalHash)
                        .thenComparing(SensitivePlaintextFact::sourceMethod))
                .toList();
    }

    private void appendEncryptedInstruction(
            IrMethod method,
            ProtectionRandom random,
            boolean rewriteConstStrings,
            int[] constStringIndex,
            List<IrInstruction> instructions,
            IrInstruction instruction) {
        if (isStringCarrier(instruction)) {
            String value = instruction.symbol().orElseThrow().substring(CARRIER_PREFIX.length());
            instructions.add(instructionWithEncryptedSymbol(
                    instruction,
                    encryptedCarrier(method, random, value).symbol()));
            return;
        }
        if (rewriteConstStrings && isOrdinaryConstString(instruction)) {
            String value = constStringValue(instruction);
            EncryptedCarrierPlan carrier =
                    encryptedCarrier(method, random, value);
            long token = carrier.token();
            int siteIndex = constStringIndex[0]++;
            xyz.melodysky.ir.model.IrValue tokenValue = new xyz.melodysky.ir.model.IrValue(
                    "%j2ll_v_" + random.token(
                            name() + "_VALUE",
                            method.methodKey()
                                    + ":"
                                    + siteIndex
                                    + ":"
                                    + Long.toUnsignedString(token),
                            24),
                    xyz.melodysky.ir.model.IrType.I64);
            instructions.add(IrInstruction.constLong(tokenValue, token));
            instructions.add(new IrInstruction(
                    instruction.result(),
                    IrOpcode.CALL_RUNTIME_HELPER,
                    List.of(tokenValue),
                    instruction.intLiteral(),
                    instruction.longLiteral(),
                    instruction.floatLiteral(),
                    instruction.doubleLiteral(),
                    java.util.Optional.of(carrier.symbol()),
                    instruction.exceptionSites()));
            return;
        }
        instructions.add(instruction);
    }

    private IrInstruction instructionWithEncryptedSymbol(
            IrInstruction instruction,
            String encryptedSymbol) {
        return new IrInstruction(
                instruction.result(),
                instruction.opcode(),
                instruction.operands(),
                instruction.intLiteral(),
                instruction.longLiteral(),
                instruction.floatLiteral(),
                instruction.doubleLiteral(),
                java.util.Optional.of(encryptedSymbol),
                instruction.exceptionSites());
    }

    private EncryptedCarrierPlan encryptedCarrier(
            IrMethod method,
            ProtectionRandom random,
            String value) {
        long stableIntegrityToken =
                BusinessStringConstantRef.integrityToken(value);
        byte[] plain = value.getBytes(StandardCharsets.UTF_8);
        byte[] key = HexFormat.of().parseHex(random.token(
                name(),
                method.methodKey() + ":" + stableIntegrityToken,
                32));
        long token = BusinessStringConstantRef.encryptedCarrierToken(
                value,
                key);
        byte[] cipher = new byte[plain.length];
        for (int index = 0; index < plain.length; index++) {
            cipher[index] = (byte) (plain[index] ^ key[index % key.length]);
        }
        return new EncryptedCarrierPlan(
                token,
                ENCRYPTED_PREFIX
                        + token
                        + ":"
                        + HexFormat.of().formatHex(key)
                        + ":"
                        + HexFormat.of().formatHex(cipher));
    }

    private boolean hasStringCarrier(IrMethod method) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(this::isStringCarrier);
    }

    private boolean hasOrdinaryConstString(IrMethod method) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(this::isOrdinaryConstString);
    }

    private boolean isStringCarrier(IrInstruction instruction) {
        return instruction.opcode() == IrOpcode.CALL_RUNTIME_HELPER
                && instruction.symbol().map(symbol -> symbol.startsWith(CARRIER_PREFIX)).orElse(false);
    }

    private boolean isOrdinaryConstString(IrInstruction instruction) {
        return instruction.opcode() == IrOpcode.CONST_STRING && instruction.symbol().isPresent();
    }

    private String constStringValue(IrInstruction instruction) {
        String symbol = instruction.symbol().orElseThrow();
        return symbol.startsWith(CONST_STRING_PREFIX) ? symbol.substring(CONST_STRING_PREFIX.length()) : symbol;
    }

    private boolean isReflectionSensitive(IrMethod method) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .flatMap(instruction -> instruction.symbol().stream())
                .map(this::baseSymbol)
                .anyMatch(symbol -> symbol.equals("j2ll_rt_class_for_name_static")
                        || symbol.startsWith("j2ll_rt_get_declared_")
                        || symbol.startsWith("j2ll_rt_reflect_")
                        || symbol.startsWith("j2ll_rt_method_handle")
                        || symbol.startsWith("j2ll_rt_lambda")
                        || symbol.startsWith("j2ll_rt_constant_dynamic"));
    }

    private String baseSymbol(String symbol) {
        int separator = symbol.indexOf('|');
        return separator < 0 ? symbol : symbol.substring(0, separator);
    }

    private record EncryptedCarrierPlan(
            long token,
            String symbol) {}
}
