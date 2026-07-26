package xyz.melodysky.ir.pass.protection;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrClass;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrProgram;

/**
 * Applies an already-validated call-indirection plan without changing the
 * semantic opcode, symbol, operands, result, or exception metadata.
 */
public final class IrCallIndirectionRewriter {
    public IrProgram rewrite(IrProgram program, IrCallIndirectionPlan plan) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(plan, "plan");
        ArrayList<IrClass> classes = new ArrayList<>(program.classes().size());
        for (IrClass irClass : program.classes()) {
            ArrayList<IrMethod> methods = new ArrayList<>(irClass.methods().size());
            for (IrMethod method : irClass.methods()) {
                methods.add(rewriteMethod(method, plan));
            }
            classes.add(new IrClass(irClass.internalName(), methods));
        }
        return new IrProgram(classes);
    }

    private IrMethod rewriteMethod(IrMethod method, IrCallIndirectionPlan plan) {
        ArrayList<IrBlock> blocks = new ArrayList<>(method.blocks().size());
        boolean changed = false;
        for (IrBlock block : method.blocks()) {
            ArrayList<IrInstruction> instructions = new ArrayList<>(block.instructions().size());
            for (int index = 0; index < block.instructions().size(); index++) {
                IrInstruction instruction = block.instructions().get(index);
                IrCallSiteId siteId = new IrCallSiteId(method.methodKey(), block.name(), index);
                var site = plan.site(siteId);
                if (site.isPresent()) {
                    instruction = instruction.withCallIndirection(site.orElseThrow().reference());
                    changed = true;
                }
                instructions.add(instruction);
            }
            blocks.add(new IrBlock(
                    block.name(),
                    block.parameters(),
                    block.exceptionCatchTypes(),
                    block.exceptionEdges(),
                    instructions,
                    block.terminator()));
        }
        if (!changed) {
            return method;
        }
        return new IrMethod(
                method.owner(),
                method.name(),
                method.descriptor(),
                method.returnType(),
                method.parameters(),
                blocks);
    }
}
