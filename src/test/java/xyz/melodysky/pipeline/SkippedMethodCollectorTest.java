package xyz.melodysky.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodNode;
import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.ssa.SsaMethodResult;
import xyz.melodysky.jvm.AccessFlags;

class SkippedMethodCollectorTest implements Opcodes {
    @Test
    void collectsOnlySkippedMethodsAndSortsByIdentity() {
        ParsedMethod nativeMethod = method("pkg/C", "nativeMethod", "()V");
        ParsedMethod later = method("z/C", "later", "()V");
        ParsedMethod first = method("a/C", "first", "(I)V");

        List<SkippedMethod> skipped = new SkippedMethodCollector().collect(List.of(
                SsaMethodResult.skipped(
                        later,
                        DiagnosticStage.LLVM_MODEL,
                        "BACKEND_GAP",
                        "backend gap"),
                SsaMethodResult.nativeLowered(nativeMethod, voidIr(nativeMethod)),
                SsaMethodResult.skipped(
                        first,
                        DiagnosticStage.LOWERING,
                        "FRONTEND_GAP",
                        "frontend gap")));

        assertEquals(
                List.of("a/C#first!(I)V", "z/C#later!()V"),
                skipped.stream().map(SkippedMethod::methodKey).toList());
        assertEquals(DiagnosticStage.LOWERING, skipped.get(0).stage());
        assertEquals("BACKEND_GAP", skipped.get(1).reasonCode());
    }

    private ParsedMethod method(String owner, String name, String descriptor) {
        int access = ACC_PUBLIC | ACC_STATIC;
        return new ParsedMethod(
                owner,
                name,
                descriptor,
                new AccessFlags(access),
                List.of(),
                List.of(),
                List.of(),
                true,
                0,
                0,
                new MethodNode(ASM9, access, name, descriptor, null, null));
    }

    private IrMethod voidIr(ParsedMethod method) {
        return new IrMethod(
                method.owner(),
                method.name(),
                method.descriptor(),
                IrType.VOID,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(),
                        IrTerminator.returnVoid())));
    }
}
