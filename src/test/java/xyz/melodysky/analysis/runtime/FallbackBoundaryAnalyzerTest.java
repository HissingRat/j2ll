package xyz.melodysky.analysis.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.ssa.SsaMethodResult;
import xyz.melodysky.testsupport.AsmFixtureBuilder;

class FallbackBoundaryAnalyzerTest implements Opcodes {
    private final FallbackBoundaryAnalyzer analyzer = new FallbackBoundaryAnalyzer();

    @Test
    void reflectionScanHasPriorityOverDynamicReflectionAndJdkFallback() {
        SsaMethodResult result = halfLowered(
                IrOpcode.CALL_VIRTUAL,
                "java/util/ArrayList#add!(Ljava/lang/Object;)Z",
                "java/lang/Class#forName!(Ljava/lang/String;)Ljava/lang/Class;",
                "java/lang/Class#getDeclaredMethods!()[Ljava/lang/reflect/Method;");

        FallbackBoundarySite site = analyzer.analyze(result).orElseThrow();

        assertEquals("REFLECTION_UNSUPPORTED_SCAN", site.reasonCode());
        assertEquals("nativeEmbeddedClassBlob", site.fallbackMode());
    }

    @Test
    void altMetafactoryHasPriorityOverGenericMethodHandleFallback() {
        SsaMethodResult result = halfLowered(
                IrOpcode.CALL_DYNAMIC,
                "java/lang/invoke/MethodHandles#permuteArguments!",
                "java/lang/invoke/LambdaMetafactory#altMetafactory!");

        assertEquals("ALT_METAFACTORY_FALLBACK", analyzer.reasonCode(result));
    }

    @Test
    void methodHandleAdapterPriorityKeepsTheMostSpecificReason() {
        SsaMethodResult result = halfLowered(
                IrOpcode.CALL_STATIC,
                "java/lang/invoke/MethodHandles#foldArguments!",
                "java/lang/invoke/MethodHandles#filterArguments!",
                "java/lang/invoke/MethodHandles#permuteArguments!");

        assertEquals("METHOD_HANDLE_PERMUTE_FALLBACK", analyzer.reasonCode(result));
    }

    @Test
    void fullyLoweredMethodHasNoFallbackBoundary() {
        ParsedMethod source = sourceMethod();
        SsaMethodResult result = SsaMethodResult.lowered(source, methodWith(source));

        assertTrue(analyzer.analyze(result).isEmpty());
    }

    private SsaMethodResult halfLowered(IrOpcode opcode, String... symbols) {
        ParsedMethod source = sourceMethod();
        IrInstruction[] instructions = Arrays.stream(symbols)
                .map(symbol -> IrInstruction.call(Optional.empty(), opcode, List.of(), symbol))
                .toArray(IrInstruction[]::new);
        return SsaMethodResult.halfLowered(
                source,
                methodWith(source, instructions),
                "JVM_HELPER_FALLBACK",
                "fixture fallback");
    }

    private ParsedMethod sourceMethod() {
        var parsedClass = new AsmClassParser()
                .parse(new ClassFileEntry(
                        "pkg/Fallback.class",
                        AsmFixtureBuilder.classWithVoidMethod(
                                "pkg/Fallback",
                                "java/lang/Object",
                                null,
                                ACC_PUBLIC | ACC_SUPER,
                                "run",
                                ACC_PUBLIC | ACC_STATIC),
                        "fixture"))
                .artifact()
                .orElseThrow();
        return parsedClass.methods().stream()
                .filter(method -> method.name().equals("run"))
                .findFirst()
                .orElseThrow();
    }

    private IrMethod methodWith(ParsedMethod source, IrInstruction... instructions) {
        return new IrMethod(
                source.owner(),
                source.name(),
                source.descriptor(),
                IrType.VOID,
                List.of(),
                List.of(new IrBlock("entry", List.of(instructions), IrTerminator.returnVoid())));
    }
}
