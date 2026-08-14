package xyz.melodysky.packaging;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticLocation;
import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.frontend.classfile.ParsedClass;

/** Rejects source or generated methods that collide with an initializer carrier. */
public final class InitializerCarrierCollisionValidator {
    public List<Diagnostic> validate(
            List<ParsedClass> classes,
            List<MethodRewriteDecision> decisions) {
        Map<String, ParsedClass> classesByOwner = new HashMap<>();
        classes.forEach(parsedClass -> classesByOwner.put(
                parsedClass.internalName(), parsedClass));
        Set<String> plannedCarriers = new HashSet<>();
        ArrayList<Diagnostic> diagnostics = new ArrayList<>();
        decisions.stream()
                .filter(this::isInitializer)
                .sorted(java.util.Comparator.comparing(
                        decision -> decision.method().methodKey()))
                .forEach(decision -> {
                    String name = decision.generatedHelperName().orElseThrow();
                    String descriptor = NativeHelperDescriptor.forDecision(decision);
                    String carrierKey = decision.registrationOwner()
                            + "#" + name + "!" + descriptor;
                    ParsedClass owner = classesByOwner.get(
                            decision.registrationOwner());
                    boolean sourceCollision = owner != null
                            && owner.methods().stream().anyMatch(method ->
                                    method.name().equals(name)
                                            && method.descriptor()
                                                    .equals(descriptor));
                    boolean generatedCollision = !plannedCarriers.add(carrierKey);
                    if (sourceCollision || generatedCollision) {
                        diagnostics.add(Diagnostic.error(
                                        DiagnosticStage.PACKAGING,
                                        PackagingDiagnostics
                                                .GENERATED_INITIALIZER_HELPER_COLLISION,
                                        "initializer native carrier collides with another method")
                                .at(DiagnosticLocation.methodLocation(
                                        decision.method().owner(),
                                        decision.method().name(),
                                        decision.method().descriptor()))
                                .withDecision("failed"));
                    }
                });
        return List.copyOf(diagnostics);
    }

    private boolean isInitializer(MethodRewriteDecision decision) {
        return decision.strategy() == MethodRewriteStrategy.CONSTRUCTOR_STUB
                || decision.strategy()
                        == MethodRewriteStrategy.CLASS_INITIALIZER_STUB;
    }
}
