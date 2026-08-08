package xyz.melodysky.ir.pass;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrExceptionEdge;
import xyz.melodysky.ir.model.IrExceptionSite;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrValue;

/** Plans only exact carrier chains whose terminal operation resolves the same JVM class itself. */
public final class ActiveUseCarrierFusionPlanner {
    public ActiveUseCarrierFusionPlan plan(
            IrMethod method,
            Set<String> possibleDirectNativeCalls) {
        Objects.requireNonNull(method, "method");
        possibleDirectNativeCalls = Set.copyOf(Objects.requireNonNull(
                possibleDirectNativeCalls,
                "possibleDirectNativeCalls"));
        Map<IrValue, Integer> useCounts = useCounts(method);
        ArrayList<ActiveUseCarrierFusionPlan.Site> sites = new ArrayList<>();
        for (IrBlock block : method.blocks()) {
            List<IrInstruction> instructions = block.instructions();
            for (int start = 0; start + 4 < instructions.size(); start++) {
                Optional<ActiveUseCarrierFusionPlan.Site> site = site(
                        block,
                        start,
                        useCounts,
                        possibleDirectNativeCalls);
                if (site.isPresent()) {
                    sites.add(site.orElseThrow());
                    start += 4;
                }
            }
        }
        return new ActiveUseCarrierFusionPlan(sites);
    }

    private Optional<ActiveUseCarrierFusionPlan.Site> site(
            IrBlock block,
            int start,
            Map<IrValue, Integer> useCounts,
            Set<String> possibleDirectNativeCalls) {
        IrInstruction token = block.instructions().get(start);
        IrInstruction classObject = block.instructions().get(start + 1);
        IrInstruction guard = block.instructions().get(start + 2);
        IrInstruction happensBefore = block.instructions().get(start + 3);
        IrInstruction activeUse = block.instructions().get(start + 4);
        if (token.opcode() != IrOpcode.CONST_LONG
                || token.result().isEmpty()
                || token.result().orElseThrow().type()
                        != xyz.melodysky.ir.model.IrType.I64
                || !token.operands().isEmpty()
                || token.longLiteral().isEmpty()
                || token.exceptionSites().size() != 0
                || classObject.opcode() != IrOpcode.CLASS_OBJECT
                || classObject.result().isEmpty()
                || classObject.result().orElseThrow().type()
                        != xyz.melodysky.ir.model.IrType.REFERENCE
                || classObject.operands().size() != 1
                || !classObject.operands().get(0).equals(token.result().orElseThrow())
                || guard.opcode() != IrOpcode.CLASS_INIT_GUARD
                || guard.result().isPresent()
                || guard.operands().size() != 1
                || happensBefore.opcode() != IrOpcode.CLASS_INIT_HAPPENS_BEFORE
                || happensBefore.result().isPresent()
                || happensBefore.operands().size() != 1
                || !guard.operands().get(0).equals(classObject.result().orElseThrow())
                || !happensBefore.operands().get(0).equals(classObject.result().orElseThrow())
                || token.callIndirection().isPresent()
                || classObject.callIndirection().isPresent()
                || guard.callIndirection().isPresent()
                || happensBefore.callIndirection().isPresent()
                || activeUse.callIndirection().isPresent()) {
            return Optional.empty();
        }
        String classSymbol = classObject.symbol().orElse("");
        if (!validClassSymbol(classSymbol)
                || guard.symbol()
                        .filter((classSymbol + ":superBeforeSubclass")::equals)
                        .isEmpty()
                || happensBefore.symbol().filter("classInitGuard"::equals).isEmpty()
                || useCounts.getOrDefault(token.result().orElseThrow(), 0) != 1
                || useCounts.getOrDefault(classObject.result().orElseThrow(), 0) != 2) {
            return Optional.empty();
        }
        String activeOwner = activeUseOwner(activeUse, possibleDirectNativeCalls)
                .orElse(null);
        if (activeOwner == null
                || !classSymbol.equals("class:L" + activeOwner + ";")
                || !sameExceptionBoundary(classObject, guard)
                || !sameExceptionBoundary(guard, activeUse)) {
            return Optional.empty();
        }
        return Optional.of(new ActiveUseCarrierFusionPlan.Site(
                block.name(),
                start,
                start + 4,
                classSymbol,
                activeUse.opcode()));
    }

    private Optional<String> activeUseOwner(
            IrInstruction instruction,
            Set<String> possibleDirectNativeCalls) {
        if (instruction.opcode() == IrOpcode.CALL_STATIC) {
            String methodKey = instruction.symbol().orElse("");
            if (possibleDirectNativeCalls.contains(methodKey)) {
                return Optional.empty();
            }
            return memberOwner(methodKey);
        }
        if (instruction.opcode() == IrOpcode.GET_STATIC
                || instruction.opcode() == IrOpcode.PUT_STATIC) {
            return instruction.symbol().flatMap(this::memberOwner);
        }
        return Optional.empty();
    }

    private Optional<String> memberOwner(String key) {
        int member = key.indexOf('#');
        int descriptor = key.indexOf('!');
        if (member <= 0 || descriptor <= member + 1 || descriptor == key.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(key.substring(0, member));
    }

    private boolean validClassSymbol(String symbol) {
        return symbol.startsWith("class:L")
                && symbol.endsWith(";")
                && symbol.length() > "class:L;".length()
                && symbol.substring("class:L".length(), symbol.length() - 1)
                        .indexOf(';') < 0;
    }

    private boolean sameExceptionBoundary(
            IrInstruction left,
            IrInstruction right) {
        if (left.exceptionSites().size() != right.exceptionSites().size()) {
            return false;
        }
        for (int index = 0; index < left.exceptionSites().size(); index++) {
            IrExceptionSite leftSite = left.exceptionSites().get(index);
            IrExceptionSite rightSite = right.exceptionSites().get(index);
            if (leftSite.kind() != rightSite.kind()
                    || leftSite.exceptionValue().isPresent()
                            != rightSite.exceptionValue().isPresent()
                    || leftSite.handlers().size() != rightSite.handlers().size()) {
                return false;
            }
            for (int edgeIndex = 0;
                    edgeIndex < leftSite.handlers().size();
                    edgeIndex++) {
                if (!sameExceptionEdge(
                        leftSite,
                        leftSite.handlers().get(edgeIndex),
                        rightSite,
                        rightSite.handlers().get(edgeIndex))) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean sameExceptionEdge(
            IrExceptionSite leftSite,
            IrExceptionEdge left,
            IrExceptionSite rightSite,
            IrExceptionEdge right) {
        if (!left.target().equals(right.target())
                || !left.catchType().equals(right.catchType())
                || left.arguments().size() != right.arguments().size()) {
            return false;
        }
        for (int index = 0; index < left.arguments().size(); index++) {
            IrValue leftArgument = left.arguments().get(index);
            IrValue rightArgument = right.arguments().get(index);
            if (leftSite.exceptionValue().filter(leftArgument::equals).isPresent()) {
                if (rightSite.exceptionValue().filter(rightArgument::equals).isEmpty()) {
                    return false;
                }
            } else if (!leftArgument.equals(rightArgument)) {
                return false;
            }
        }
        return true;
    }

    private Map<IrValue, Integer> useCounts(IrMethod method) {
        HashMap<IrValue, Integer> counts = new HashMap<>();
        for (IrBlock block : method.blocks()) {
            for (IrInstruction instruction : block.instructions()) {
                instruction.operands().forEach(value -> increment(counts, value));
                instruction.exceptionSites().stream()
                        .flatMap(site -> site.handlers().stream())
                        .flatMap(edge -> edge.arguments().stream())
                        .forEach(value -> increment(counts, value));
            }
            block.exceptionEdges().stream()
                    .flatMap(edge -> edge.arguments().stream())
                    .forEach(value -> increment(counts, value));
            block.terminator().value().ifPresent(value -> increment(counts, value));
            block.terminator().condition().ifPresent(value -> increment(counts, value));
            block.terminator().switchValue().ifPresent(value -> increment(counts, value));
            block.terminator().targetArguments().forEach(value -> increment(counts, value));
            block.terminator().trueTargetArguments().forEach(value -> increment(counts, value));
            block.terminator().falseTargetArguments().forEach(value -> increment(counts, value));
            block.terminator().defaultTargetArguments().forEach(value -> increment(counts, value));
            block.terminator().switchCases().stream()
                    .flatMap(item -> item.arguments().stream())
                    .forEach(value -> increment(counts, value));
        }
        return Map.copyOf(counts);
    }

    private void increment(Map<IrValue, Integer> counts, IrValue value) {
        counts.merge(value, 1, Integer::sum);
    }
}
