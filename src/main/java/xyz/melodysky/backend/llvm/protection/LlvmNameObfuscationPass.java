package xyz.melodysky.backend.llvm.protection;

import java.util.ArrayList;
import xyz.melodysky.backend.llvm.model.LlvmFunction;
import xyz.melodysky.backend.llvm.model.LlvmModule;
import xyz.melodysky.ir.pass.protection.ProtectionRandom;

public final class LlvmNameObfuscationPass implements LlvmModulePass {
    @Override
    public String name() {
        return "llvmNameObfuscation";
    }

    @Override
    public LlvmModule run(LlvmModule module, LlvmProtectionConfig config) {
        if (!config.enabled() || !config.nameObfuscation()) {
            return module;
        }
        ProtectionRandom random = new ProtectionRandom(config.seed());
        ArrayList<LlvmFunction> functions = new ArrayList<>();
        for (LlvmFunction function : module.functions()) {
            String token = random.token(name(), module.identifier() + ":" + function.name(), 16);
            functions.add(new LlvmFunction(
                    "j2ll_f_" + token,
                    function.linkage(),
                    function.visibility(),
                    function.returnType(),
                    function.parameters(),
                    function.blocks()));
        }
        return new LlvmModule(module.identifier(), module.declarations(), module.globals(), functions);
    }
}
