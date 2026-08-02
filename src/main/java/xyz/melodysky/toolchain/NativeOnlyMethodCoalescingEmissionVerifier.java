package xyz.melodysky.toolchain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import xyz.melodysky.analysis.method.NativeOnlyMethodCoalescingDecision;
import xyz.melodysky.analysis.method.NativeOnlyMethodCoalescingPlan;

/** Verifies that a logical coalesced method has no physical native body. */
public final class NativeOnlyMethodCoalescingEmissionVerifier {
    public List<String> residuals(
            NativeOnlyMethodCoalescingPlan plan,
            NativeImplementationPlan implementationPlan,
            NativeLlvmCompilation compilation) {
        ArrayList<String> residuals = new ArrayList<>();
        Map<String, NativeMethodImplementation> implementations =
                implementationPlan.implementations().stream()
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                NativeMethodImplementation::methodKey,
                                implementation -> implementation));
        for (NativeOnlyMethodCoalescingDecision decision : plan.decisions()) {
            if (!decision.coalesced()) {
                continue;
            }
            String calleeKey = decision.calleeMethodKey();
            String callerKey = decision.callerMethodKey().orElseThrow();
            NativeMethodImplementation callee = implementations.get(calleeKey);
            NativeMethodImplementation caller = implementations.get(callerKey);
            if (callee == null
                    || callee.coalescedIntoMethodKey()
                            .filter(callerKey::equals)
                            .isEmpty()) {
                residuals.add(calleeKey + ":logicalPlanMismatch");
                continue;
            }
            if (caller == null || !caller.emitsStandaloneLlvmBody()) {
                residuals.add(calleeKey + ":callerNotEmitted");
            }
            boolean callerCompiled = compilation.modules().stream()
                    .flatMap(module -> module.compiledMethods().stream())
                    .anyMatch(method -> method.methodKey().equals(callerKey));
            if (!callerCompiled) {
                residuals.add(calleeKey + ":callerMissingFromCompilation");
            }
            boolean calleeCompiled = compilation.modules().stream()
                    .flatMap(module -> module.compiledMethods().stream())
                    .anyMatch(method -> method.methodKey().equals(calleeKey));
            if (calleeCompiled) {
                residuals.add(calleeKey + ":standaloneCompiledMethod");
            }
            String symbol = callee.llvmFunctionSymbol().orElse("");
            if (symbol.isBlank()) {
                residuals.add(calleeKey + ":missingLogicalLlvmSymbol");
                continue;
            }
            boolean functionPresent = compilation.modules().stream()
                    .flatMap(module -> module.module().functions().stream())
                    .anyMatch(function -> function.name().equals(symbol));
            boolean declarationPresent = compilation.modules().stream()
                    .flatMap(module -> module.module().declarations().stream())
                    .anyMatch(declaration -> declaration.name().equals(symbol));
            boolean textReferencePresent = compilation.modules().stream()
                    .map(NativeLlvmModuleCompilation::llvmText)
                    .anyMatch(text -> containsLlvmSymbol(text, symbol));
            if (functionPresent || declarationPresent || textReferencePresent) {
                residuals.add(calleeKey + ":llvmSymbolResidual");
            }
        }
        return residuals.stream().distinct().sorted().toList();
    }

    public List<String> workspaceResiduals(
            Path workspaceRoot,
            NativeOnlyMethodCoalescingPlan plan,
            NativeImplementationPlan implementationPlan,
            NativeLlvmCompilation compilation) throws IOException {
        ArrayList<String> residuals = new ArrayList<>(
                residuals(plan, implementationPlan, compilation));
        List<String> forbiddenSymbols = plan.decisions().stream()
                .filter(NativeOnlyMethodCoalescingDecision::coalesced)
                .map(NativeOnlyMethodCoalescingDecision::calleeMethodKey)
                .map(implementationPlan::implementationFor)
                .flatMap(java.util.Optional::stream)
                .flatMap(implementation -> Stream.of(
                        implementation.llvmFunctionSymbol().orElse(""),
                        implementation.entry().nativeSymbol()))
                .filter(symbol -> !symbol.isBlank())
                .distinct()
                .sorted()
                .toList();
        Path nativeRoot = workspaceRoot.resolve("native");
        if (!forbiddenSymbols.isEmpty() && Files.isDirectory(nativeRoot)) {
            try (Stream<Path> paths = Files.walk(nativeRoot)) {
                for (Path path : paths.filter(Files::isRegularFile)
                        .filter(this::isGeneratedSource)
                        .sorted()
                        .toList()) {
                    String text = Files.readString(path, StandardCharsets.UTF_8);
                    for (String symbol : forbiddenSymbols) {
                        if (containsIdentifier(text, symbol)) {
                            residuals.add(workspaceRoot.relativize(path)
                                            .toString()
                                            .replace('\\', '/')
                                    + ":"
                                    + symbol);
                        }
                    }
                }
            }
        }
        return residuals.stream().distinct().sorted().toList();
    }

    private boolean containsLlvmSymbol(String text, String symbol) {
        return text.matches("(?s).*@" + java.util.regex.Pattern.quote(symbol)
                + "(?=[^A-Za-z0-9_.$]|$).*");
    }

    private boolean containsIdentifier(String text, String symbol) {
        return text.matches("(?s).*(?<![A-Za-z0-9_])"
                + java.util.regex.Pattern.quote(symbol)
                + "(?![A-Za-z0-9_]).*");
    }

    private boolean isGeneratedSource(Path path) {
        String lower = path.getFileName().toString()
                .toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".c") || lower.endsWith(".ll");
    }
}
