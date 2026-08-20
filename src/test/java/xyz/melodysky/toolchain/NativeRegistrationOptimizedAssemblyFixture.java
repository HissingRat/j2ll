package xyz.melodysky.toolchain;

import java.util.ArrayList;
import java.util.List;

/** Synthetic six-dialect assembly fixture for focused registration gate mutations. */
final class NativeRegistrationOptimizedAssemblyFixture {
    private final NativeRegistrationOptimizedContinuationFixture continuations =
            new NativeRegistrationOptimizedContinuationFixture();

    String assembly(
            TargetTriple target,
            NativeRegistrationControlTopologyPlan plan) {
        StringBuilder assembly = new StringBuilder();
        if (!plan.routePlan().enabled()) {
            appendZeroOwnerRoot(assembly, target, plan.aggregateSymbol());
            appendLeaf(assembly, target, plan.aggregateSymbol());
            return assembly.toString();
        }
        appendRoot(assembly, target, plan);
        appendFunction(
                assembly,
                target,
                plan.aggregateSymbol(),
                List.of(plan.chunks().get(0).symbol()),
                0,
                false,
                null);
        for (NativeRegistrationControlRoutePlan.Route route
                : plan.routePlan().routes()) {
            String callee = route.targetKind()
                            == NativeRegistrationControlRoutePlan.TargetKind.AGGREGATE
                    ? plan.aggregateSymbol()
                    : plan.routePlan().route(route.targetRouteOrdinal()).symbol();
            appendRoute(assembly, target, route, callee);
        }
        for (int ordinal = 0; ordinal < plan.chunks().size(); ordinal++) {
            NativeRegistrationControlTopologyPlan.Chunk chunk = plan.chunks().get(ordinal);
            ArrayList<String> calls = new ArrayList<>(chunk.owners().stream()
                    .map(NativeRegistrationControlTopologyPlan.Owner::symbol)
                    .toList());
            boolean forwards = ordinal + 1 < plan.chunks().size();
            if (forwards) {
                calls.add(plan.chunks().get(ordinal + 1).symbol());
            }
            appendFunction(
                    assembly,
                    target,
                    chunk.symbol(),
                    calls,
                    ordinal + 5,
                    true,
                    forwards ? chunk.postCallVariant() : null);
        }
        for (NativeRegistrationControlTopologyPlan.Owner owner : plan.owners()) {
            appendLeaf(assembly, target, owner.symbol());
        }
        for (String failure : plan.failureSymbols().symbols()) {
            appendLeaf(assembly, target, failure);
        }
        return assembly.toString();
    }

    private void appendZeroOwnerRoot(
            StringBuilder assembly,
            TargetTriple target,
            String aggregate) {
        start(assembly, target, "JNI_OnLoad");
        call(assembly, target, aggregate);
        assembly.append(target.archClassifier().equals("x64")
                ? "\tmovl\t%eax, -4(%rsp)\n\tmovl\t-4(%rsp), %eax\n\tretq\n"
                : "\tstr\tw0, [sp, #4]\n\tldr\tw0, [sp, #4]\n\tret\n");
        end(assembly, target, "JNI_OnLoad");
    }

    private void appendRoot(
            StringBuilder assembly,
            TargetTriple target,
            NativeRegistrationControlTopologyPlan plan) {
        start(assembly, target, "JNI_OnLoad");
        if (target.archClassifier().equals("x64")) {
            assembly.append("\ttestl\t%eax, %eax\n")
                    .append("\tjne\t.Lroot_else\n")
                    .append("\tcallq\t").append(symbol(target, plan.routePlan().route(0).symbol()))
                    .append("\n\tjmp\t.Lroot_done\n")
                    .append(".Lroot_else:\n")
                    .append("\tcallq\t").append(symbol(target, plan.routePlan().route(1).symbol()))
                    .append("\n.Lroot_done:\n")
                    .append("\tmovl\t%eax, -4(%rsp)\n")
                    .append("\tmovl\t-4(%rsp), %eax\n")
                    .append("\tretq\n");
        } else {
            assembly.append("\tcmp\tw8, #0\n")
                    .append("\tb.ne\t.Lroot_else\n")
                    .append("\tbl\t").append(symbol(target, plan.routePlan().route(0).symbol()))
                    .append("\n\tb\t.Lroot_done\n")
                    .append(".Lroot_else:\n")
                    .append("\tbl\t").append(symbol(target, plan.routePlan().route(1).symbol()))
                    .append("\n.Lroot_done:\n")
                    .append("\tstr\tw0, [sp, #4]\n")
                    .append("\tldr\tw0, [sp, #4]\n")
                    .append("\tret\n");
        }
        end(assembly, target, "JNI_OnLoad");
    }

    private void appendRoute(
            StringBuilder assembly,
            TargetTriple target,
            NativeRegistrationControlRoutePlan.Route route,
            String callee) {
        start(assembly, target, route.symbol());
        call(assembly, target, callee);
        continuations.appendRoute(assembly, target, route.postCallRecipe());
        assembly.append(target.archClassifier().equals("x64") ? "\tretq\n" : "\tret\n");
        end(assembly, target, route.symbol());
    }

    private void appendFunction(
            StringBuilder assembly,
            TargetTriple target,
            String function,
            List<String> calls,
            int shape,
            boolean guarded,
            NativeRegistrationChunkPostCallVariant variant) {
        start(assembly, target, function);
        int guardedCalls = guarded ? calls.size() - (variant == null ? 0 : 1) : 0;
        for (int index = 0; index < calls.size(); index++) {
            call(assembly, target, calls.get(index));
            if (index < guardedCalls) {
                assembly.append(target.archClassifier().equals("x64")
                        ? "\ttestl\t%eax, %eax\n\tjne\t.Lfail_"
                        : "\tcbnz\tw0, .Lfail_")
                        .append(function).append('_').append(index).append('\n');
            }
        }
        if (variant != null) {
            continuations.appendChunk(assembly, target, variant);
        }
        appendNormalReturn(assembly, target, shape);
        for (int index = 0; index < guardedCalls; index++) {
            assembly.append(".Lfail_").append(function).append('_').append(index).append(":\n")
                    .append(target.archClassifier().equals("x64")
                            ? "\tmovl\t$-1, %eax\n\tretq\n"
                            : "\tmov\tw0, #-1\n\tret\n");
        }
        end(assembly, target, function);
    }

    private void appendNormalReturn(
            StringBuilder assembly,
            TargetTriple target,
            int shape) {
        if (target.archClassifier().equals("x64")) {
            assembly.append("\tmovl\t%eax, %r10d\n");
            for (int index = 0; index < shape; index++) {
                assembly.append(index % 2 == 0
                        ? "\txorl\t%r11d, %r10d\n"
                        : "\taddl\t%r11d, %r10d\n");
            }
            assembly.append("\tmovl\t%r10d, %eax\n\tretq\n");
            return;
        }
        assembly.append("\tmov\tw10, w0\n");
        for (int index = 0; index < shape; index++) {
            assembly.append(index % 2 == 0
                    ? "\teor\tw10, w10, w11\n"
                    : "\tadd\tw10, w10, w11\n");
        }
        assembly.append("\tmov\tw0, w10\n\tret\n");
    }

    private void appendLeaf(
            StringBuilder assembly,
            TargetTriple target,
            String function) {
        start(assembly, target, function);
        assembly.append(target.archClassifier().equals("x64")
                ? "\tmovl\t$0, %eax\n\tretq\n"
                : "\tmov\tw0, #0\n\tret\n");
        end(assembly, target, function);
    }

    private void call(StringBuilder assembly, TargetTriple target, String callee) {
        assembly.append(target.archClassifier().equals("x64") ? "\tcallq\t" : "\tbl\t")
                .append(symbol(target, callee)).append('\n');
    }

    private void start(StringBuilder assembly, TargetTriple target, String function) {
        assembly.append(symbol(target, function)).append(":\n");
    }

    private void end(StringBuilder assembly, TargetTriple target, String function) {
        if (target.osClassifier().equals("windows")) {
            assembly.append("\t.seh_endproc\n");
        } else if (target.osClassifier().equals("macos")) {
            assembly.append("\t.cfi_endproc\n");
        } else {
            assembly.append("\t.size\t").append(function).append(", .-orphan\n");
        }
    }

    private String symbol(TargetTriple target, String value) {
        return target.osClassifier().equals("macos") ? "_" + value : value;
    }
}
