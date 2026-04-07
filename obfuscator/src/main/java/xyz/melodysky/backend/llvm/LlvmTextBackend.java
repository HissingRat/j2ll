package xyz.melodysky.backend.llvm;

import xyz.melodysky.backend.IrProgramBackend;
import xyz.melodysky.ir.model.IrBinaryOpcode;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrClass;
import xyz.melodysky.ir.model.IrClassRef;
import xyz.melodysky.ir.model.IrCompareOpcode;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrMethodRef;
import xyz.melodysky.ir.model.IrProgram;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;
import xyz.melodysky.ir.util.IrDescriptors;
import xyz.melodysky.ir.validate.IrProgramValidator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class LlvmTextBackend implements IrProgramBackend {

    private static final String MODULE_TRIPLE = "unknown-unknown-unknown";
    private static final int DEFAULT_MIN_SHARD_BYTES = 4 * 1024 * 1024;

    private final int minShardBytes;
    private static final EmissionProgressListener NO_PROGRESS = new EmissionProgressListener() {};

    public interface EmissionProgressListener {
        default void onEmissionStart(int totalClasses) {}
        default void onClassEmitted(int current, int totalClasses, String className) {}
    }

    public LlvmTextBackend() {
        this(DEFAULT_MIN_SHARD_BYTES);
    }

    LlvmTextBackend(int minShardBytes) {
        this.minShardBytes = Math.max(1, minShardBytes);
    }

    @Override
    public String name() {
        return "llvm-text";
    }

    @Override
    public String emit(IrProgram program) {
        return emitModuleSet(program, 1).monolithicText();
    }

    public ModuleSet emitModuleSet(IrProgram program, int requestedShardCount) {
        return emitModuleSet(program, requestedShardCount, NO_PROGRESS);
    }

    public ModuleSet emitModuleSet(IrProgram program, int requestedShardCount, EmissionProgressListener progressListener) {
        new IrProgramValidator().validate(program);

        Map<MethodKey, DirectCallTarget> directCallTargets = collectDirectCallTargets(program);
        String monolithicText = emitMonolithicModule(program, directCallTargets, progressListener);
        List<ModuleFragment> shardModules = emitShardModules(program, directCallTargets, requestedShardCount);
        return new ModuleSet(monolithicText, shardModules);
    }

    private String emitMonolithicModule(IrProgram program, Map<MethodKey, DirectCallTarget> directCallTargets,
                                        EmissionProgressListener progressListener) {
        StringBuilder builder = new StringBuilder();
        appendModulePreamble(builder, "j2ll", true);

        LinkedHashMap<String, String> helperDeclarations = new LinkedHashMap<>();
        LinkedHashSet<DirectCallTarget> emittedThunks = new LinkedHashSet<>();
        progressListener.onEmissionStart(program.classes().size());
        int emittedClasses = 0;
        for (IrClass irClass : program.classes()) {
            emitClass(builder, helperDeclarations, directCallTargets, emittedThunks, irClass);
            emittedClasses++;
            progressListener.onClassEmitted(emittedClasses, program.classes().size(), irClass.reference().internalName());
        }

        appendDirectCallDefinitions(builder, emittedThunks, true);

        if (!helperDeclarations.isEmpty()) {
            builder.append("; Runtime helper declarations\n");
            for (String declaration : helperDeclarations.values()) {
                builder.append(declaration).append('\n');
            }
        }

        return builder.toString();
    }

    private List<ModuleFragment> emitShardModules(IrProgram program,
                                                  Map<MethodKey, DirectCallTarget> directCallTargets,
                                                  int requestedShardCount) {
        List<List<ClassShardUnit>> buckets = shardBuckets(program, directCallTargets, requestedShardCount);
        ArrayList<ModuleFragment> fragments = new ArrayList<>();
        LinkedHashSet<DirectCallTarget> allEmittedThunks = new LinkedHashSet<>();

        for (int bucketIndex = 0; bucketIndex < buckets.size(); bucketIndex++) {
            List<ClassShardUnit> bucketUnits = buckets.get(bucketIndex);
            if (bucketUnits.isEmpty()) {
                continue;
            }

            StringBuilder builder = new StringBuilder();
            appendModulePreamble(builder, "j2ll.shard." + bucketIndex, false);

            LinkedHashMap<String, String> helperDeclarations = new LinkedHashMap<>();
            LinkedHashSet<DirectCallTarget> emittedThunks = new LinkedHashSet<>();
            for (ClassShardUnit unit : bucketUnits) {
                emitClassUnit(builder, helperDeclarations, directCallTargets, emittedThunks, unit);
            }

            if (!emittedThunks.isEmpty()) {
                builder.append("; Direct call thunk declarations\n");
                for (DirectCallTarget thunkTarget : emittedThunks) {
                    builder.append(thunkDeclaration(thunkTarget)).append('\n');
                }
            }

            if (!helperDeclarations.isEmpty()) {
                builder.append("; Runtime helper declarations\n");
                for (String declaration : helperDeclarations.values()) {
                    builder.append(declaration).append('\n');
                }
            }

            allEmittedThunks.addAll(emittedThunks);
            fragments.add(new ModuleFragment("shard-" + String.format(Locale.ROOT, "%02d", bucketIndex) + ".ll", builder.toString()));
        }

        StringBuilder commonBuilder = new StringBuilder();
        appendModulePreamble(commonBuilder, "j2ll.common", true);
        if (!allEmittedThunks.isEmpty()) {
            commonBuilder.append("; Program function declarations\n");
            for (DirectCallTarget target : allEmittedThunks) {
                commonBuilder.append(programFunctionDeclaration(target)).append('\n');
            }
            appendDirectCallDefinitions(commonBuilder, allEmittedThunks, false);
        }
        fragments.add(0, new ModuleFragment("common.ll", commonBuilder.toString()));
        return List.copyOf(fragments);
    }

    private void appendModulePreamble(StringBuilder builder, String moduleId, boolean defineRuntimeCurrentEnv) {
        builder.append("; ModuleID = '").append(moduleId).append("'\n");
        builder.append("target triple = \"").append(MODULE_TRIPLE).append("\"\n\n");
        if (defineRuntimeCurrentEnv) {
            builder.append('@').append(runtimeCurrentEnvGlobal()).append(" = thread_local global ptr null\n\n");
        } else {
            builder.append('@').append(runtimeCurrentEnvGlobal()).append(" = external thread_local global ptr\n\n");
        }
    }

    private List<List<ClassShardUnit>> shardBuckets(IrProgram program,
                                                    Map<MethodKey, DirectCallTarget> directCallTargets,
                                                    int requestedShardCount) {
        if (program.classes().isEmpty()) {
            return List.of(List.of());
        }

        ArrayList<ClassShardUnit> shardUnits = new ArrayList<>(program.classes().size());
        long totalBytes = 0L;
        for (IrClass irClass : program.classes()) {
            for (ClassShardUnit unit : splitClassIntoUnits(irClass, directCallTargets)) {
                shardUnits.add(unit);
                totalBytes += unit.estimatedBytes();
            }
        }

        int maxShardCount = Math.max(1, Math.min(shardUnits.size(), Math.max(1, requestedShardCount)));
        int targetShardCount = (int) Math.max(1L, Math.min(maxShardCount, totalBytes / minShardBytes));
        targetShardCount = Math.max(1, Math.min(maxShardCount, targetShardCount));

        ArrayList<ShardBucket> buckets = new ArrayList<>(targetShardCount);
        for (int index = 0; index < targetShardCount; index++) {
            buckets.add(new ShardBucket(new ArrayList<>(), 0));
        }

        shardUnits.sort((left, right) -> Integer.compare(right.estimatedBytes(), left.estimatedBytes()));
        for (ClassShardUnit unit : shardUnits) {
            ShardBucket bucket = smallestBucket(buckets);
            bucket.units().add(unit);
            bucket.estimatedBytes += unit.estimatedBytes();
        }

        ArrayList<List<ClassShardUnit>> result = new ArrayList<>(buckets.size());
        for (ShardBucket bucket : buckets) {
            result.add(List.copyOf(bucket.units()));
        }
        return List.copyOf(result);
    }

    private List<ClassShardUnit> splitClassIntoUnits(IrClass irClass, Map<MethodKey, DirectCallTarget> directCallTargets) {
        int classBytes = estimateClassShardBytes(irClass, directCallTargets);
        if (classBytes <= minShardBytes || irClass.methods().size() <= 1) {
            return List.of(new ClassShardUnit(irClass.reference(), List.copyOf(irClass.methods()), classBytes));
        }

        ArrayList<MethodWeight> methodWeights = new ArrayList<>(irClass.methods().size());
        long totalMethodBytes = 0L;
        for (IrMethod method : irClass.methods()) {
            int estimatedBytes = estimateMethodShardBytes(irClass.reference(), method, directCallTargets);
            methodWeights.add(new MethodWeight(method, estimatedBytes));
            totalMethodBytes += estimatedBytes;
        }

        int sliceCount = (int) Math.max(1L, Math.min(irClass.methods().size(), totalMethodBytes / minShardBytes));
        sliceCount = Math.max(2, sliceCount);
        ArrayList<ShardMethodBucket> buckets = new ArrayList<>(sliceCount);
        for (int index = 0; index < sliceCount; index++) {
            buckets.add(new ShardMethodBucket(new ArrayList<>(), 0));
        }

        methodWeights.sort((left, right) -> Integer.compare(right.estimatedBytes(), left.estimatedBytes()));
        for (MethodWeight methodWeight : methodWeights) {
            ShardMethodBucket bucket = smallestMethodBucket(buckets);
            bucket.methods().add(methodWeight.method());
            bucket.estimatedBytes += methodWeight.estimatedBytes();
        }

        ArrayList<ClassShardUnit> result = new ArrayList<>(buckets.size());
        for (ShardMethodBucket bucket : buckets) {
            if (bucket.methods().isEmpty()) {
                continue;
            }
            result.add(new ClassShardUnit(irClass.reference(), List.copyOf(bucket.methods()), bucket.estimatedBytes()));
        }
        return List.copyOf(result);
    }

    private int estimateClassShardBytes(IrClass irClass, Map<MethodKey, DirectCallTarget> directCallTargets) {
        StringBuilder builder = new StringBuilder();
        LinkedHashMap<String, String> helperDeclarations = new LinkedHashMap<>();
        LinkedHashSet<DirectCallTarget> emittedThunks = new LinkedHashSet<>();
        emitClass(builder, helperDeclarations, directCallTargets, emittedThunks, irClass);
        if (!emittedThunks.isEmpty()) {
            for (DirectCallTarget thunkTarget : emittedThunks) {
                builder.append(thunkDeclaration(thunkTarget)).append('\n');
            }
        }
        for (String declaration : helperDeclarations.values()) {
            builder.append(declaration).append('\n');
        }
        return Math.max(1, builder.length());
    }

    private int estimateMethodShardBytes(IrClassRef classRef, IrMethod method,
                                         Map<MethodKey, DirectCallTarget> directCallTargets) {
        StringBuilder builder = new StringBuilder();
        IrClass singleMethodClass = new IrClass(classRef, List.of(method));
        LinkedHashMap<String, String> helperDeclarations = new LinkedHashMap<>();
        LinkedHashSet<DirectCallTarget> emittedThunks = new LinkedHashSet<>();
        emitMethod(builder, helperDeclarations, directCallTargets, emittedThunks, singleMethodClass, method);
        emitNativeBridge(builder, singleMethodClass, method);
        builder.append('\n');
        if (!emittedThunks.isEmpty()) {
            for (DirectCallTarget thunkTarget : emittedThunks) {
                builder.append(thunkDeclaration(thunkTarget)).append('\n');
            }
        }
        for (String declaration : helperDeclarations.values()) {
            builder.append(declaration).append('\n');
        }
        return Math.max(1, builder.length());
    }

    private ShardBucket smallestBucket(List<ShardBucket> buckets) {
        ShardBucket smallest = buckets.getFirst();
        for (int index = 1; index < buckets.size(); index++) {
            ShardBucket current = buckets.get(index);
            if (current.estimatedBytes() < smallest.estimatedBytes()) {
                smallest = current;
            }
        }
        return smallest;
    }

    private ShardMethodBucket smallestMethodBucket(List<ShardMethodBucket> buckets) {
        ShardMethodBucket smallest = buckets.getFirst();
        for (int index = 1; index < buckets.size(); index++) {
            ShardMethodBucket current = buckets.get(index);
            if (current.estimatedBytes() < smallest.estimatedBytes()) {
                smallest = current;
            }
        }
        return smallest;
    }

    private void emitClassUnit(StringBuilder builder, Map<String, String> helperDeclarations,
                               Map<MethodKey, DirectCallTarget> directCallTargets,
                               Set<DirectCallTarget> emittedThunks,
                               ClassShardUnit unit) {
        IrClass unitClass = new IrClass(unit.classRef(), unit.methods());
        emitClass(builder, helperDeclarations, directCallTargets, emittedThunks, unitClass);
    }

    private void appendDirectCallDefinitions(StringBuilder builder, Set<DirectCallTarget> emittedThunks,
                                             boolean internalLinkage) {
        if (emittedThunks.isEmpty()) {
            return;
        }
        builder.append("; Direct call signature dispatchers\n");
        for (DispatchGroup dispatchGroup : collectDispatchGroups(emittedThunks)) {
            emitDirectCallDispatcher(builder, dispatchGroup, internalLinkage);
            builder.append('\n');
        }
        builder.append("; Direct call indirection thunks\n");
        for (DirectCallTarget thunkTarget : emittedThunks) {
            emitDirectCallThunk(builder, thunkTarget, internalLinkage);
            builder.append('\n');
        }
    }

    private void emitClass(StringBuilder builder, Map<String, String> helperDeclarations,
                           Map<MethodKey, DirectCallTarget> directCallTargets,
                           Set<DirectCallTarget> emittedThunks,
                           IrClass irClass) {
        builder.append("; class ").append(irClass.reference().internalName()).append('\n');
        for (IrMethod method : irClass.methods()) {
            emitMethod(builder, helperDeclarations, directCallTargets, emittedThunks, irClass, method);
            emitNativeBridge(builder, irClass, method);
            builder.append('\n');
        }
    }

    private void emitMethod(StringBuilder builder, Map<String, String> helperDeclarations,
                            Map<MethodKey, DirectCallTarget> directCallTargets,
                            Set<DirectCallTarget> emittedThunks,
                            IrClass irClass,
                            IrMethod method) {
        String functionName = functionName(irClass, method);
        List<ParameterBinding> parameters = parameterBindings(irClass, method);
        Map<Integer, IrType> localSlotTypes = inferLocalSlotTypes(irClass, method, parameters);
        int[] tempNameCounter = new int[]{0};

        builder.append("define ")
                .append(llvmType(method.returnType()))
                .append(" @\"")
                .append(functionName)
                .append("\"(")
                .append(renderParameterList(parameters))
                .append(") {\n");

        builder.append("prologue:\n");
        emitEntrySetup(builder, localSlotTypes, parameters, tempNameCounter);
        builder.append("  br label %").append(method.entryBlock()).append('\n');

        for (IrBlock block : method.blocks()) {
            builder.append(block.label()).append(":\n");
            Map<Integer, ConstructorPair> constructorPairs = collectConstructorPairs(block.instructions());
            Set<IrValue> absorbedNewObjects = absorbedNewObjects(constructorPairs);
            for (int instructionIndex = 0; instructionIndex < block.instructions().size(); instructionIndex++) {
                IrInstruction instruction = block.instructions().get(instructionIndex);
                if (instruction instanceof IrInstruction.NewObject newObject
                        && absorbedNewObjects.contains(newObject.result())) {
                    continue;
                }
                ConstructorPair constructorPair = constructorPairs.get(instructionIndex);
                if (instruction instanceof IrInstruction.Invoke invoke && constructorPair != null) {
                    emitHelperCall(
                            builder,
                            helperDeclarations,
                            newInitHelperName(invoke.method()),
                            constructorPair.newObject().result().type(),
                            invoke.arguments().subList(1, invoke.arguments().size()),
                            constructorPair.newObject().result()
                    );
                    continue;
                }
                emitInstruction(builder, helperDeclarations, localSlotTypes, directCallTargets, emittedThunks, instruction, tempNameCounter);
            }
            emitTerminator(builder, helperDeclarations, method.returnType(), block.terminator());
        }

        builder.append("}\n");
    }

    private void emitNativeBridge(StringBuilder builder, IrClass irClass, IrMethod method) {
        if (method.name().startsWith("<")) {
            return;
        }

        String exportName = JniMangler.nativeBridgeName(irClass, method);
        String targetFunction = functionName(irClass, method);
        List<ParameterBinding> parameters = parameterBindings(irClass, method);

        builder.append("define ")
                .append(llvmType(method.returnType()))
                .append(" @\"")
                .append(exportName)
                .append("\"(")
                .append(renderJniWrapperParameterList(irClass, method))
                .append(") {\n");

        builder.append("entry:\n");
        builder.append("  store ptr %jni.env, ptr @").append(runtimeCurrentEnvGlobal()).append('\n');
        builder.append("  ");
        if (method.returnType() != IrType.VOID) {
            builder.append("%jni.result = ");
        }
        builder.append("call ")
                .append(llvmType(method.returnType()))
                .append(" @\"")
                .append(targetFunction)
                .append("\"(")
                .append(renderJniCallArguments(irClass, method, parameters))
                .append(")\n");
        if (method.returnType() == IrType.VOID) {
            builder.append("  ret void\n");
        } else {
            builder.append("  ret ")
                    .append(llvmType(method.returnType()))
                    .append(" %jni.result\n");
        }
        builder.append("}\n");
    }

    private void emitEntrySetup(StringBuilder builder, Map<Integer, IrType> localSlotTypes, List<ParameterBinding> parameters,
                                int[] tempNameCounter) {
        for (Map.Entry<Integer, IrType> entry : localSlotTypes.entrySet()) {
            builder.append("  ")
                    .append(localSlotPointer(entry.getKey()))
                    .append(" = alloca ")
                    .append(llvmType(entry.getValue()))
                    .append('\n');
        }
        for (ParameterBinding parameter : parameters) {
            emitLocalStore(builder, parameter.localSlot(), parameter.type(), parameter.llvmName(), localSlotTypes, tempNameCounter);
        }
    }

    private void emitInstruction(StringBuilder builder, Map<String, String> helperDeclarations,
                                 Map<Integer, IrType> localSlotTypes,
                                 Map<MethodKey, DirectCallTarget> directCallTargets,
                                 Set<DirectCallTarget> emittedThunks,
                                 IrInstruction instruction,
                                 int[] tempNameCounter) {
        switch (instruction) {
            case IrInstruction.Const constant -> emitConst(builder, constant);
            case IrInstruction.LoadLocal loadLocal -> emitLocalLoad(builder, loadLocal, localSlotTypes);
            case IrInstruction.StoreLocal storeLocal -> emitLocalStore(
                    builder,
                    storeLocal.slot(),
                    storeLocal.value().type(),
                    llvmOperand(storeLocal.value()),
                    localSlotTypes,
                    tempNameCounter
            );
            case IrInstruction.Binary binary -> builder.append("  ")
                    .append(llvmValue(binary.result()))
                    .append(" = ")
                    .append(llvmBinaryOpcode(binary.opcode(), binary.result().type()))
                    .append(' ')
                    .append(llvmType(binary.result().type()))
                    .append(' ')
                    .append(llvmOperand(binary.left()))
                    .append(", ")
                    .append(llvmOperand(binary.right()))
                    .append('\n');
            case IrInstruction.Compare compare -> {
                if (usesReferenceEqualityHelper(compare)) {
                    emitHelperCall(
                            builder,
                            helperDeclarations,
                            compare.opcode() == IrCompareOpcode.EQ ? "ir_rt_ref_eq" : "ir_rt_ref_ne",
                            IrType.BOOLEAN,
                            List.of(compare.left(), compare.right()),
                            compare.result()
                    );
                    break;
                }
                builder.append("  ")
                        .append(llvmValue(compare.result()))
                        .append(" = ")
                        .append(llvmComparePrefix(compare.left().type()))
                        .append(' ')
                        .append(llvmCompareOpcode(compare.opcode(), compare.left().type()))
                        .append(' ')
                        .append(llvmType(compare.left().type()))
                        .append(' ')
                        .append(llvmOperand(compare.left()))
                        .append(", ")
                        .append(llvmOperand(compare.right()))
                        .append('\n');
            }
            case IrInstruction.Convert convert -> builder.append("  ")
                    .append(renderConvert(convert))
                    .append('\n');
            case IrInstruction.LoadField loadField -> emitHelperCall(
                    builder,
                    helperDeclarations,
                    fieldHelperName("get_field", loadField.field()),
                    loadField.result().type(),
                    List.of(loadField.owner())
            , loadField.result());
            case IrInstruction.LoadStaticField loadStaticField -> emitHelperCall(
                    builder,
                    helperDeclarations,
                    fieldHelperName("get_static", loadStaticField.field()),
                    loadStaticField.result().type(),
                    List.of(),
                    loadStaticField.result());
            case IrInstruction.NewObject newObject -> emitHelperCall(
                    builder,
                    helperDeclarations,
                    allocateHelperName(newObject.classRef()),
                    newObject.result().type(),
                    List.of(),
                    newObject.result());
            case IrInstruction.StoreField storeField -> emitHelperCall(
                    builder,
                    helperDeclarations,
                    fieldHelperName("put_field", storeField.field()),
                    IrType.VOID,
                    List.of(storeField.owner(), storeField.value()),
                    null);
            case IrInstruction.StoreStaticField storeStaticField -> emitHelperCall(
                    builder,
                    helperDeclarations,
                    fieldHelperName("put_static", storeStaticField.field()),
                    IrType.VOID,
                    List.of(storeStaticField.value()),
                    null);
            case IrInstruction.Invoke invoke -> {
                DirectCallTarget directTarget = directCallTargets.get(methodKey(invoke.method()));
                if (directTarget != null && canDirectLower(invoke.method().callKind(), directTarget)) {
                    emittedThunks.add(directTarget);
                    emitDirectCall(
                            builder,
                            directTarget.thunkSymbolName(),
                            invoke.method().returnType(),
                            invoke.arguments(),
                            invoke.method().returnType() == IrType.VOID ? null : invoke.result()
                    );
                } else {
                    emitHelperCall(
                            builder,
                            helperDeclarations,
                            invokeHelperName(invoke.method()),
                            invoke.method().returnType(),
                            invoke.arguments(),
                            invoke.method().returnType() == IrType.VOID ? null : invoke.result());
                }
            }
            case IrInstruction.CallHelper helper -> emitHelperCall(
                    builder,
                    helperDeclarations,
                    sanitizeSymbol(helper.helperName()),
                    helper.result().type(),
                    helper.arguments(),
                    helper.result().type() == IrType.VOID ? null : helper.result());
            case IrInstruction.CallHelperVoid helper -> emitHelperCall(
                    builder,
                    helperDeclarations,
                    sanitizeSymbol(helper.helperName()),
                    IrType.VOID,
                    helper.arguments(),
                    null);
        }
    }

    private void emitTerminator(StringBuilder builder, Map<String, String> helperDeclarations,
                                IrType methodReturnType, IrTerminator terminator) {
        switch (terminator) {
            case IrTerminator.Goto goTo -> builder.append("  br label %").append(goTo.targetBlock()).append('\n');
            case IrTerminator.Branch branch -> builder.append("  br i1 ")
                    .append(llvmOperand(branch.condition()))
                    .append(", label %")
                    .append(branch.trueTarget())
                    .append(", label %")
                    .append(branch.falseTarget())
                    .append('\n');
            case IrTerminator.Switch switchTerminator -> {
                builder.append("  switch i32 ")
                        .append(llvmOperand(switchTerminator.selector()))
                        .append(", label %")
                        .append(switchTerminator.defaultTarget())
                        .append(" [\n");
                for (Map.Entry<Integer, String> entry : switchTerminator.targetByKey().entrySet()) {
                    builder.append("    i32 ")
                            .append(entry.getKey())
                            .append(", label %")
                            .append(entry.getValue())
                            .append('\n');
                }
                builder.append("  ]\n");
            }
            case IrTerminator.Return returnTerminator -> builder.append("  ret ")
                    .append(llvmType(returnTerminator.value().type()))
                    .append(' ')
                    .append(llvmOperand(returnTerminator.value()))
                    .append('\n');
            case IrTerminator.ReturnVoid ignored -> builder.append("  ret void\n");
            case IrTerminator.Throw throwTerminator -> {
                emitHelperCall(
                        builder,
                        helperDeclarations,
                        "ir_rt_throw",
                        IrType.VOID,
                        List.of(throwTerminator.exceptionValue()),
                        null
                );
                emitThrowReturn(builder, methodReturnType);
            }
            case IrTerminator.Unreachable ignored -> builder.append("  unreachable\n");
        }
    }

    private void emitThrowReturn(StringBuilder builder, IrType methodReturnType) {
        switch (methodReturnType.kind()) {
            case VOID -> builder.append("  ret void\n");
            case BOOLEAN -> builder.append("  ret i1 false\n");
            case BYTE -> builder.append("  ret i8 0\n");
            case SHORT, CHAR -> builder.append("  ret i16 0\n");
            case INT -> builder.append("  ret i32 0\n");
            case LONG -> builder.append("  ret i64 0\n");
            case FLOAT -> builder.append("  ret float 0.0\n");
            case DOUBLE -> builder.append("  ret double 0.0\n");
            case REFERENCE, ARRAY -> builder.append("  ret ptr null\n");
        }
    }

    private void emitConst(StringBuilder builder, IrInstruction.Const constant) {
        if (constant.result().type() == IrType.INT) {
            builder.append("  ")
                    .append(llvmValue(constant.result()))
                    .append(" = add i32 0, ")
                    .append(constant.value())
                    .append('\n');
            return;
        }
        if (constant.result().type() == IrType.BOOLEAN) {
            boolean value = Boolean.TRUE.equals(constant.value());
            builder.append("  ")
                    .append(llvmValue(constant.result()))
                    .append(" = or i1 false, ")
                    .append(value ? "true" : "false")
                    .append('\n');
            return;
        }
        if (constant.result().type() == IrType.LONG) {
            builder.append("  ")
                    .append(llvmValue(constant.result()))
                    .append(" = add i64 0, ")
                    .append(constant.value())
                    .append('\n');
            return;
        }
        if (constant.result().type() == IrType.FLOAT) {
            builder.append("  ")
                    .append(llvmValue(constant.result()))
                    .append(" = fadd float 0.0, ")
                    .append(renderFloatingConstant((Number) constant.value(), constant.result().type()))
                    .append('\n');
            return;
        }
        if (constant.result().type() == IrType.DOUBLE) {
            builder.append("  ")
                    .append(llvmValue(constant.result()))
                    .append(" = fadd double 0.0, ")
                    .append(renderFloatingConstant((Number) constant.value(), constant.result().type()))
                    .append('\n');
            return;
        }
        if (!constant.result().type().isPrimitive() && constant.value() == null) {
            builder.append("  ")
                    .append(llvmValue(constant.result()))
                    .append(" = select i1 true, ptr null, ptr null\n");
            return;
        }
        throw new IllegalStateException("Unsupported constant for LLVM emission: " + constant.result().type().displayName());
    }

    private void emitHelperCall(StringBuilder builder, Map<String, String> helperDeclarations, String symbolName,
                                IrType returnType, List<IrValue> arguments, IrValue result) {
        String emittedSymbolName = helperSymbolName(symbolName);
        helperDeclarations.put(emittedSymbolName, helperDeclaration(emittedSymbolName, symbolName, returnType, arguments));

        builder.append("  ");
        if (result != null) {
            builder.append(llvmValue(result)).append(" = ");
        }
        builder.append("call ")
                .append(llvmType(returnType))
                .append(" @\"")
                .append(emittedSymbolName)
                .append("\"(");

        for (int index = 0; index < arguments.size(); index++) {
            IrValue argument = arguments.get(index);
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(llvmType(argument.type()))
                    .append(' ')
                    .append(llvmOperand(argument));
        }
        builder.append(")\n");
    }

    private void emitDirectCall(StringBuilder builder, String symbolName, IrType returnType, List<IrValue> arguments, IrValue result) {
        builder.append("  ");
        if (result != null) {
            builder.append(llvmValue(result)).append(" = ");
        }
        builder.append("call ")
                .append(llvmType(returnType))
                .append(" @\"")
                .append(symbolName)
                .append("\"(");

        for (int index = 0; index < arguments.size(); index++) {
            IrValue argument = arguments.get(index);
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(llvmType(argument.type()))
                    .append(' ')
                    .append(llvmOperand(argument));
        }
        builder.append(")\n");
    }

    private void emitDirectCallThunk(StringBuilder builder, DirectCallTarget target, boolean internalLinkage) {
        builder.append("define ");
        if (internalLinkage) {
            builder.append("internal ");
        }
        builder
                .append(llvmType(target.returnType()))
                .append(" @\"")
                .append(target.thunkSymbolName())
                .append("\"(")
                .append(renderThunkParameters(target))
                .append(") {\n");
        builder.append("entry:\n");
        builder.append("  %dispatch.id = xor i64 ")
                .append(renderI64Literal(target.encodedDispatchId()))
                .append(", ")
                .append(renderI64Literal(target.dispatchMask()))
                .append('\n');
        if (target.returnType() == IrType.VOID) {
            builder.append("  call void @\"")
                    .append(target.dispatcherSymbolName())
                    .append("\"(i64 %dispatch.id");
            if (!target.parameterTypes().isEmpty()) {
                builder.append(", ");
            }
            builder.append(renderThunkArguments(target))
                    .append(")\n");
            builder.append("  ret void\n");
        } else {
            builder.append("  %ret = call ")
                    .append(llvmType(target.returnType()))
                    .append(" @\"")
                    .append(target.dispatcherSymbolName())
                    .append("\"(i64 %dispatch.id");
            if (!target.parameterTypes().isEmpty()) {
                builder.append(", ");
            }
            builder.append(renderThunkArguments(target))
                    .append(")\n");
            builder.append("  ret ")
                    .append(llvmType(target.returnType()))
                    .append(" %ret\n");
        }
        builder.append("}\n");
    }

    private void emitDirectCallDispatcher(StringBuilder builder, DispatchGroup dispatchGroup, boolean internalLinkage) {
        builder.append("define ");
        if (internalLinkage) {
            builder.append("internal ");
        }
        builder
                .append(llvmType(dispatchGroup.signature().returnType()))
                .append(" @\"")
                .append(dispatchGroup.dispatcherSymbolName())
                .append("\"(i64 %dispatch.id");
        if (!dispatchGroup.signature().parameterTypes().isEmpty()) {
            builder.append(", ");
        }
        builder.append(renderDispatcherParameters(dispatchGroup.signature().parameterTypes()))
                .append(") {\n");
        builder.append("entry:\n");
        builder.append("  switch i64 %dispatch.id, label %dispatch.unreachable [\n");
        for (int index = 0; index < dispatchGroup.targets().size(); index++) {
            DirectCallTarget target = dispatchGroup.targets().get(index);
            builder.append("    i64 ")
                    .append(renderI64Literal(target.dispatchId()))
                    .append(", label %dispatch.case.")
                    .append(index)
                    .append('\n');
        }
        builder.append("  ]\n");
        for (int index = 0; index < dispatchGroup.targets().size(); index++) {
            DirectCallTarget target = dispatchGroup.targets().get(index);
            builder.append("dispatch.case.")
                    .append(index)
                    .append(":\n");
            if (dispatchGroup.signature().returnType() == IrType.VOID) {
                builder.append("  call void @\"")
                        .append(target.symbolName())
                        .append("\"(")
                        .append(renderDispatcherArguments(dispatchGroup.signature().parameterTypes()))
                        .append(")\n");
                builder.append("  ret void\n");
            } else {
                builder.append("  %dispatch.ret.")
                        .append(index)
                        .append(" = call ")
                        .append(llvmType(dispatchGroup.signature().returnType()))
                        .append(" @\"")
                        .append(target.symbolName())
                        .append("\"(")
                        .append(renderDispatcherArguments(dispatchGroup.signature().parameterTypes()))
                        .append(")\n");
                builder.append("  ret ")
                        .append(llvmType(dispatchGroup.signature().returnType()))
                        .append(" %dispatch.ret.")
                        .append(index)
                        .append('\n');
            }
        }
        builder.append("dispatch.unreachable:\n");
        builder.append("  unreachable\n");
        builder.append("}\n");
    }

    private List<DispatchGroup> collectDispatchGroups(Set<DirectCallTarget> emittedThunks) {
        LinkedHashMap<DispatchSignature, ArrayList<DirectCallTarget>> groupedTargets = new LinkedHashMap<>();
        for (DirectCallTarget target : emittedThunks) {
            groupedTargets.computeIfAbsent(
                    new DispatchSignature(target.returnType(), target.parameterTypes()),
                    ignored -> new ArrayList<>()
            ).add(target);
        }
        ArrayList<DispatchGroup> dispatchGroups = new ArrayList<>(groupedTargets.size());
        for (Map.Entry<DispatchSignature, ArrayList<DirectCallTarget>> entry : groupedTargets.entrySet()) {
            dispatchGroups.add(new DispatchGroup(
                    entry.getKey(),
                    dispatcherName(entry.getKey()),
                    List.copyOf(entry.getValue())
            ));
        }
        return List.copyOf(dispatchGroups);
    }

    private String renderDispatcherParameters(List<IrType> parameterTypes) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < parameterTypes.size(); index++) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(llvmType(parameterTypes.get(index)))
                    .append(" %dispatch.arg")
                    .append(index);
        }
        return builder.toString();
    }

    private String renderDispatcherArguments(List<IrType> parameterTypes) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < parameterTypes.size(); index++) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(llvmType(parameterTypes.get(index)))
                    .append(" %dispatch.arg")
                    .append(index);
        }
        return builder.toString();
    }

    private String dispatcherName(DispatchSignature signature) {
        StringBuilder identity = new StringBuilder("dispatcher|")
                .append(signature.returnType().displayName());
        for (IrType parameterType : signature.parameterTypes()) {
            identity.append('|').append(parameterType.displayName());
        }
        return JniMangler.opaqueSymbol(identity.toString(), 24);
    }

    private String renderI64Literal(long value) {
        return Long.toString(value);
    }

    private String helperDeclaration(String emittedSymbolName, String semanticSymbolName, IrType returnType, List<IrValue> arguments) {
        StringBuilder builder = new StringBuilder();
        if (!emittedSymbolName.equals(semanticSymbolName)) {
            builder.append("; helper-meta ")
                    .append(emittedSymbolName)
                    .append(" = ")
                    .append(semanticSymbolName)
                    .append('\n');
        }
        builder.append("declare ")
                .append(llvmType(returnType))
                .append(" @\"")
                .append(emittedSymbolName)
                .append("\"(");
        for (int index = 0; index < arguments.size(); index++) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(llvmType(arguments.get(index).type()));
        }
        builder.append(')');
        return builder.toString();
    }

    private String thunkDeclaration(DirectCallTarget thunkTarget) {
        return "declare " + llvmType(thunkTarget.returnType())
                + " @\"" + thunkTarget.thunkSymbolName() + "\"("
                + renderThunkDeclarationParameterTypes(thunkTarget.parameterTypes()) + ")";
    }

    private String programFunctionDeclaration(DirectCallTarget target) {
        return "declare " + llvmType(target.returnType())
                + " @\"" + target.symbolName() + "\"("
                + renderThunkDeclarationParameterTypes(target.parameterTypes()) + ")";
    }

    private String helperSymbolName(String semanticSymbolName) {
        if (!shouldOpaqueHelperSymbol(semanticSymbolName)) {
            return semanticSymbolName;
        }
        return JniMangler.opaqueSymbol("helper|" + semanticSymbolName, 24);
    }

    private boolean shouldOpaqueHelperSymbol(String semanticSymbolName) {
        return semanticSymbolName.startsWith("ir_rt_");
    }

    private String runtimeCurrentEnvGlobal() {
        return JniMangler.opaqueSymbol("runtime|current-env-global", 24);
    }

    private boolean usesReferenceEqualityHelper(IrInstruction.Compare compare) {
        IrType type = compare.left().type();
        if (type.kind() != IrType.Kind.REFERENCE && type.kind() != IrType.Kind.ARRAY) {
            return false;
        }
        return compare.opcode() == IrCompareOpcode.EQ || compare.opcode() == IrCompareOpcode.NE;
    }

    private boolean isCombinedConstructorPair(IrInstruction.NewObject newObject, IrInstruction.Invoke invoke) {
        return "<init>".equals(invoke.method().name())
                && invoke.method().callKind() == IrMethodRef.CallKind.SPECIAL
                && !invoke.arguments().isEmpty()
                && invoke.arguments().get(0).equals(newObject.result())
                && invoke.method().owner().internalName().equals(newObject.classRef().internalName());
    }

    private Map<Integer, ConstructorPair> collectConstructorPairs(List<IrInstruction> instructions) {
        LinkedHashMap<Integer, ConstructorPair> pairs = new LinkedHashMap<>();
        Set<IrValue> absorbedValues = new HashSet<>();
        for (int instructionIndex = 0; instructionIndex < instructions.size(); instructionIndex++) {
            IrInstruction instruction = instructions.get(instructionIndex);
            if (!(instruction instanceof IrInstruction.Invoke invoke) || invoke.arguments().isEmpty()) {
                continue;
            }
            IrInstruction.NewObject newObject = resolveConstructorNewObject(instructions, instructionIndex, invoke);
            if (newObject == null || absorbedValues.contains(newObject.result())) {
                continue;
            }
            pairs.put(instructionIndex, new ConstructorPair(newObject));
            absorbedValues.add(newObject.result());
        }
        return pairs;
    }

    private Set<IrValue> absorbedNewObjects(Map<Integer, ConstructorPair> constructorPairs) {
        LinkedHashSet<IrValue> values = new LinkedHashSet<>();
        for (ConstructorPair constructorPair : constructorPairs.values()) {
            values.add(constructorPair.newObject().result());
        }
        return Set.copyOf(values);
    }

    private IrInstruction.NewObject resolveConstructorNewObject(List<IrInstruction> instructions, int invokeIndex, IrInstruction.Invoke invoke) {
        if (!"<init>".equals(invoke.method().name()) || invoke.arguments().isEmpty()) {
            return null;
        }
        return resolveNewObjectOrigin(
                instructions,
                invokeIndex - 1,
                invoke.arguments().get(0),
                invoke.method().owner().internalName(),
                new HashSet<>()
        );
    }

    private IrInstruction.NewObject resolveNewObjectOrigin(List<IrInstruction> instructions,
                                                           int searchStart,
                                                           IrValue value,
                                                           String expectedOwner,
                                                           Set<ValueSearchKey> seen) {
        if (searchStart < 0) {
            return null;
        }
        ValueSearchKey searchKey = new ValueSearchKey(searchStart, value);
        if (!seen.add(searchKey)) {
            return null;
        }
        for (int index = searchStart; index >= 0; index--) {
            IrInstruction instruction = instructions.get(index);
            if (!instruction.producedValue().filter(value::equals).isPresent()) {
                continue;
            }
            return switch (instruction) {
                case IrInstruction.NewObject newObject ->
                        newObject.classRef().internalName().equals(expectedOwner) ? newObject : null;
                case IrInstruction.Convert convert ->
                        isReferenceLike(convert.result().type()) && isReferenceLike(convert.value().type())
                                ? resolveNewObjectOrigin(instructions, index - 1, convert.value(), expectedOwner, seen)
                                : null;
                case IrInstruction.LoadLocal loadLocal -> {
                    if (!isReferenceLike(loadLocal.result().type())) {
                        yield null;
                    }
                    LocalStore store = resolveLocalStore(instructions, index - 1, loadLocal.slot());
                    if (store == null) {
                        yield null;
                    }
                    yield resolveNewObjectOrigin(instructions, store.instructionIndex() - 1, store.value(), expectedOwner, seen);
                }
                default -> null;
            };
        }
        return null;
    }

    private LocalStore resolveLocalStore(List<IrInstruction> instructions, int searchStart, int slot) {
        for (int index = searchStart; index >= 0; index--) {
            IrInstruction instruction = instructions.get(index);
            if (instruction instanceof IrInstruction.StoreLocal storeLocal && storeLocal.slot() == slot) {
                return new LocalStore(index, storeLocal.value());
            }
        }
        return null;
    }

    private boolean isReferenceLike(IrType type) {
        return type.kind() == IrType.Kind.REFERENCE || type.kind() == IrType.Kind.ARRAY;
    }

    private Map<MethodKey, DirectCallTarget> collectDirectCallTargets(IrProgram program) {
        LinkedHashMap<MethodKey, DirectCallTarget> targets = new LinkedHashMap<>();
        for (IrClass irClass : program.classes()) {
            for (IrMethod method : irClass.methods()) {
                MethodKey key = new MethodKey(
                        irClass.reference().internalName(),
                        method.name(),
                        IrDescriptors.methodDescriptor(method)
                );
                List<IrType> parameterTypes = directThunkParameterTypes(irClass, method);
                DispatchSignature signature = new DispatchSignature(method.returnType(), parameterTypes);
                long dispatchId = dispatchId(key);
                long dispatchMask = dispatchMask(key);
                targets.put(key,
                        new DirectCallTarget(
                                key,
                                functionName(irClass, method),
                                directThunkName(key),
                                dispatcherName(signature),
                                method.returnType(),
                                parameterTypes,
                                dispatchId,
                                dispatchMask,
                                dispatchId ^ dispatchMask,
                                method.isStatic(),
                                method.isPrivate(),
                                method.isFinal()
                        ));
            }
        }
        return targets;
    }

    private String functionName(IrClass irClass, IrMethod method) {
        String identity = irClass.reference().internalName()
                + "|"
                + method.name()
                + "|"
                + IrDescriptors.methodDescriptor(method)
                + "|"
                + (method.isStatic() ? "static" : "instance");
        return JniMangler.opaqueSymbol("fn|" + identity, 24);
    }

    private List<ParameterBinding> parameterBindings(IrClass irClass, IrMethod method) {
        ArrayList<ParameterBinding> bindings = new ArrayList<>();
        int slot = 0;
        int argIndex = 0;
        if (!method.isStatic()) {
            bindings.add(new ParameterBinding(slot, IrType.reference(irClass.reference().internalName()), "%arg" + argIndex++));
            slot += slotWidth(IrType.reference(irClass.reference().internalName()));
        }
        for (IrType parameterType : method.parameterTypes()) {
            bindings.add(new ParameterBinding(slot, parameterType, "%arg" + argIndex++));
            slot += slotWidth(parameterType);
        }
        return bindings;
    }

    private Map<Integer, IrType> inferLocalSlotTypes(IrClass irClass, IrMethod method, List<ParameterBinding> parameters) {
        LinkedHashMap<Integer, IrType> localSlotTypes = new LinkedHashMap<>();
        for (ParameterBinding parameter : parameters) {
            localSlotTypes.put(parameter.localSlot(), localStorageType(parameter.type()));
        }

        for (IrBlock block : method.blocks()) {
            for (IrInstruction instruction : block.instructions()) {
                switch (instruction) {
                    case IrInstruction.LoadLocal loadLocal -> recordLocalSlotType(localSlotTypes, loadLocal.slot(), loadLocal.result().type());
                    case IrInstruction.StoreLocal storeLocal -> recordLocalSlotType(localSlotTypes, storeLocal.slot(), storeLocal.value().type());
                    default -> {
                    }
                }
            }
        }

        return localSlotTypes;
    }

    private void recordLocalSlotType(Map<Integer, IrType> localSlotTypes, int slot, IrType type) {
        IrType storageType = localStorageType(type);
        IrType previous = localSlotTypes.putIfAbsent(slot, storageType);
        if (previous != null && !previous.equals(storageType)) {
            if (previous.equals(storageType) || llvmType(previous).equals(llvmType(storageType))) {
                return;
            }
            throw new IllegalStateException("Local slot " + slot + " uses inconsistent LLVM types: "
                    + previous.displayName() + " vs " + type.displayName());
        }
    }

    private void emitLocalLoad(StringBuilder builder, IrInstruction.LoadLocal loadLocal, Map<Integer, IrType> localSlotTypes) {
        IrType storageType = localSlotTypes.getOrDefault(loadLocal.slot(), localStorageType(loadLocal.result().type()));
        String storageLlvmType = llvmType(storageType);
        String targetLlvmType = llvmType(loadLocal.result().type());
        if (storageType.equals(loadLocal.result().type()) || storageLlvmType.equals(targetLlvmType)) {
            builder.append("  ")
                    .append(llvmValue(loadLocal.result()))
                    .append(" = load ")
                    .append(targetLlvmType)
                    .append(", ptr ")
                    .append(localSlotPointer(loadLocal.slot()))
                    .append('\n');
            return;
        }

        String rawName = llvmValue(loadLocal.result()) + ".raw";
        builder.append("  ")
                .append(rawName)
                .append(" = load ")
                .append(storageLlvmType)
                .append(", ptr ")
                .append(localSlotPointer(loadLocal.slot()))
                .append('\n');
        builder.append("  ")
                .append(llvmValue(loadLocal.result()))
                .append(" = ")
                .append(localStorageConvertOpcode(storageType, loadLocal.result().type()))
                .append(' ')
                .append(storageLlvmType)
                .append(' ')
                .append(rawName)
                .append(" to ")
                .append(targetLlvmType)
                .append('\n');
    }

    private void emitLocalStore(StringBuilder builder, int slot, IrType valueType, String valueOperand,
                                Map<Integer, IrType> localSlotTypes, int[] tempNameCounter) {
        IrType storageType = localSlotTypes.getOrDefault(slot, localStorageType(valueType));
        String storageLlvmType = llvmType(storageType);
        String valueLlvmType = llvmType(valueType);
        if (storageType.equals(valueType) || storageLlvmType.equals(valueLlvmType)) {
            builder.append("  store ")
                    .append(valueLlvmType)
                    .append(' ')
                    .append(valueOperand)
                    .append(", ptr ")
                    .append(localSlotPointer(slot))
                    .append('\n');
            return;
        }

        String castName = nextTempName("local_store_" + slot, tempNameCounter);
        builder.append("  ")
                .append(castName)
                .append(" = ")
                .append(localStorageConvertOpcode(valueType, storageType))
                .append(' ')
                .append(valueLlvmType)
                .append(' ')
                .append(valueOperand)
                .append(" to ")
                .append(storageLlvmType)
                .append('\n');
        builder.append("  store ")
                .append(storageLlvmType)
                .append(' ')
                .append(castName)
                .append(", ptr ")
                .append(localSlotPointer(slot))
                .append('\n');
    }

    private String nextTempName(String base, int[] tempNameCounter) {
        return "%" + sanitizeSymbol(base + "_" + tempNameCounter[0]++);
    }

    private IrType localStorageType(IrType type) {
        return switch (type.kind()) {
            case BOOLEAN, BYTE, SHORT, CHAR, INT -> IrType.INT;
            default -> type;
        };
    }

    private String localStorageConvertOpcode(IrType sourceType, IrType targetType) {
        if (sourceType.equals(targetType)) {
            throw new IllegalArgumentException("No local storage conversion required for identical types");
        }
        if (targetType == IrType.INT && isJvmIntLike(sourceType)) {
            if (sourceType == IrType.BOOLEAN || sourceType == IrType.CHAR) {
                return "zext";
            }
            if (sourceType == IrType.BYTE || sourceType == IrType.SHORT) {
                return "sext";
            }
        }
        if (sourceType == IrType.INT && isJvmIntLike(targetType)) {
            return "trunc";
        }
        throw new IllegalArgumentException("Unsupported local storage conversion: "
                + sourceType.displayName() + " -> " + targetType.displayName());
    }

    private boolean isJvmIntLike(IrType type) {
        return type == IrType.BOOLEAN
                || type == IrType.BYTE
                || type == IrType.SHORT
                || type == IrType.CHAR
                || type == IrType.INT;
    }

    private String renderParameterList(List<ParameterBinding> parameters) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < parameters.size(); index++) {
            if (index > 0) {
                builder.append(", ");
            }
            ParameterBinding parameter = parameters.get(index);
            builder.append(llvmType(parameter.type()))
                    .append(' ')
                    .append(parameter.llvmName());
        }
        return builder.toString();
    }

    private String renderJniWrapperParameterList(IrClass irClass, IrMethod method) {
        StringBuilder builder = new StringBuilder();
        builder.append("ptr %jni.env, ptr %jni.classOrThis");
        int argumentIndex = 0;
        for (IrType parameterType : method.parameterTypes()) {
            builder.append(", ")
                    .append(llvmType(parameterType))
                    .append(" %jni.arg")
                    .append(argumentIndex++);
        }
        return builder.toString();
    }

    private String renderJniCallArguments(IrClass irClass, IrMethod method, List<ParameterBinding> parameters) {
        ArrayList<String> arguments = new ArrayList<>();
        if (!method.isStatic()) {
            arguments.add("ptr %jni.classOrThis");
        }
        int argumentIndex = 0;
        for (IrType parameterType : method.parameterTypes()) {
            arguments.add(llvmType(parameterType) + " %jni.arg" + argumentIndex++);
        }
        return String.join(", ", arguments);
    }

    private String llvmType(IrType type) {
        return switch (type.kind()) {
            case VOID -> "void";
            case BOOLEAN -> "i1";
            case BYTE -> "i8";
            case SHORT, CHAR -> "i16";
            case INT -> "i32";
            case LONG -> "i64";
            case FLOAT -> "float";
            case DOUBLE -> "double";
            case REFERENCE, ARRAY -> "ptr";
        };
    }

    private String llvmBinaryOpcode(IrBinaryOpcode opcode, IrType type) {
        if (type == IrType.FLOAT || type == IrType.DOUBLE) {
            return switch (opcode) {
                case ADD -> "fadd";
                case SUB -> "fsub";
                case MUL -> "fmul";
                case DIV -> "fdiv";
                case REM -> "frem";
                default -> throw new IllegalArgumentException("Unsupported floating binary opcode: " + opcode);
            };
        }
        return switch (opcode) {
            case ADD -> "add";
            case SUB -> "sub";
            case MUL -> "mul";
            case DIV -> "sdiv";
            case REM -> "srem";
            case AND -> "and";
            case OR -> "or";
            case XOR -> "xor";
            case SHL -> "shl";
            case SHR -> "ashr";
            case USHR -> "lshr";
        };
    }

    private String llvmComparePrefix(IrType type) {
        return type == IrType.FLOAT || type == IrType.DOUBLE ? "fcmp" : "icmp";
    }

    private String llvmCompareOpcode(IrCompareOpcode opcode, IrType type) {
        if (type == IrType.FLOAT || type == IrType.DOUBLE) {
            return switch (opcode) {
                case EQ -> "oeq";
                case NE -> "one";
                case LT -> "olt";
                case LE -> "ole";
                case GT -> "ogt";
                case GE -> "oge";
            };
        }
        return switch (opcode) {
            case EQ -> "eq";
            case NE -> "ne";
            case LT -> "slt";
            case LE -> "sle";
            case GT -> "sgt";
            case GE -> "sge";
        };
    }

    private String llvmConvertOpcode(IrType sourceType, IrType targetType) {
        if (isIntegerLike(sourceType) && isIntegerLike(targetType)) {
            int sourceBits = integerBitWidth(sourceType);
            int targetBits = integerBitWidth(targetType);
            if (sourceBits > targetBits) {
                return "trunc";
            }
            if (sourceType == IrType.CHAR || sourceType == IrType.BOOLEAN) {
                return "zext";
            }
            return "sext";
        }
        if (isIntegerLike(sourceType) && isFloatingType(targetType)) {
            return sourceType == IrType.CHAR || sourceType == IrType.BOOLEAN ? "uitofp" : "sitofp";
        }
        if (isFloatingType(sourceType) && isIntegerLike(targetType)) {
            return targetType == IrType.CHAR || targetType == IrType.BOOLEAN ? "fptoui" : "fptosi";
        }
        if (isFloatingType(sourceType) && isFloatingType(targetType)) {
            return sourceType == IrType.FLOAT && targetType == IrType.DOUBLE ? "fpext" : "fptrunc";
        }
        int sourceBits = integerBitWidth(sourceType);
        int targetBits = integerBitWidth(targetType);
        if (sourceBits > targetBits) {
            return "trunc";
        }
        if (sourceType == IrType.CHAR || sourceType == IrType.BOOLEAN) {
            return "zext";
        }
        return "sext";
    }

    private String renderConvert(IrInstruction.Convert convert) {
        String sourceType = llvmType(convert.value().type());
        String targetType = llvmType(convert.result().type());
        if (sourceType.equals("ptr") && targetType.equals("ptr")) {
            return llvmValue(convert.result()) + " = select i1 true, ptr " + llvmOperand(convert.value()) + ", ptr " + llvmOperand(convert.value());
        }
        if (sourceType.equals(targetType)) {
            if (sourceType.equals("float")) {
                return llvmValue(convert.result()) + " = fadd float " + llvmOperand(convert.value()) + ", 0.0";
            }
            if (sourceType.equals("double")) {
                return llvmValue(convert.result()) + " = fadd double " + llvmOperand(convert.value()) + ", 0.0";
            }
            return llvmValue(convert.result()) + " = or " + sourceType + " " + llvmOperand(convert.value()) + ", 0";
        }
        return llvmValue(convert.result()) + " = " + llvmConvertOpcode(convert.value().type(), convert.result().type())
                + " " + sourceType + " " + llvmOperand(convert.value()) + " to " + targetType;
    }

    private boolean isIntegerLike(IrType type) {
        return switch (type.kind()) {
            case BOOLEAN, BYTE, SHORT, CHAR, INT, LONG -> true;
            default -> false;
        };
    }

    private boolean isFloatingType(IrType type) {
        return type == IrType.FLOAT || type == IrType.DOUBLE;
    }

    private String renderFloatingConstant(Number value, IrType type) {
        if (type == IrType.FLOAT) {
            long bits = Double.doubleToRawLongBits(value.floatValue());
            return String.format(Locale.ROOT, "0x%016X", bits);
        }
        if (type == IrType.DOUBLE) {
            long bits = Double.doubleToRawLongBits(value.doubleValue());
            return String.format(Locale.ROOT, "0x%016X", bits);
        }
        throw new IllegalArgumentException("Not a floating-point type: " + type.displayName());
    }

    private int slotWidth(IrType type) {
        return type.isWide() ? 2 : 1;
    }

    private int integerBitWidth(IrType type) {
        return switch (type.kind()) {
            case BOOLEAN -> 1;
            case BYTE -> 8;
            case SHORT, CHAR -> 16;
            case INT -> 32;
            case LONG -> 64;
            default -> throw new IllegalArgumentException("Not an integer-like type: " + type.displayName());
        };
    }

    private String llvmOperand(IrValue value) {
        return llvmValue(value);
    }

    private String llvmValue(IrValue value) {
        return "%" + sanitizeSymbol(value.symbol().substring(1));
    }

    private String localSlotPointer(int slot) {
        return "%local." + slot;
    }

    private String fieldHelperName(String prefix, xyz.melodysky.ir.model.IrFieldRef field) {
        return sanitizeSymbol("ir_rt_" + prefix + "__"
                + encodeHelperToken(field.owner().internalName()) + "__"
                + encodeHelperToken(field.name()) + "__"
                + encodeHelperToken(field.type().displayName()));
    }

    private String allocateHelperName(xyz.melodysky.ir.model.IrClassRef classRef) {
        return sanitizeSymbol("ir_rt_new__" + encodeHelperToken(classRef.internalName()));
    }

    private String newInitHelperName(IrMethodRef methodRef) {
        ArrayList<String> pieces = new ArrayList<>();
        pieces.add("ir_rt_new_init");
        pieces.add(encodeHelperToken(methodRef.owner().internalName()));
        for (IrType parameterType : methodRef.parameterTypes()) {
            pieces.add(encodeHelperToken(parameterType.displayName()));
        }
        return sanitizeSymbol(String.join("__", pieces));
    }

    private String invokeHelperName(IrMethodRef methodRef) {
        ArrayList<String> pieces = new ArrayList<>();
        pieces.add("ir_rt_call");
        pieces.add(methodRef.callKind().name().toLowerCase(Locale.ROOT));
        pieces.add(encodeHelperToken(methodRef.owner().internalName()));
        pieces.add(encodeHelperToken(methodRef.name()));
        for (IrType parameterType : methodRef.parameterTypes()) {
            pieces.add(encodeHelperToken(parameterType.displayName()));
        }
        pieces.add(encodeHelperToken(methodRef.returnType().displayName()));
        return sanitizeSymbol(String.join("__", pieces));
    }

    private MethodKey methodKey(IrMethodRef methodRef) {
        return new MethodKey(
                methodRef.owner().internalName(),
                methodRef.name(),
                descriptorFromMethodRef(methodRef)
        );
    }

    private boolean canDirectLower(IrMethodRef.CallKind callKind, DirectCallTarget target) {
        if (callKind == IrMethodRef.CallKind.SPECIAL) {
            return true;
        }
        if (callKind == IrMethodRef.CallKind.STATIC) {
            return target.isStatic();
        }
        if (callKind == IrMethodRef.CallKind.VIRTUAL) {
            return target.isPrivate() || target.isFinal();
        }
        return false;
    }

    private String renderThunkParameters(DirectCallTarget target) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < target.parameterTypes().size(); index++) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(llvmType(target.parameterTypes().get(index)))
                    .append(" %arg")
                    .append(index);
        }
        return builder.toString();
    }

    private String renderThunkDeclarationParameterTypes(List<IrType> parameterTypes) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < parameterTypes.size(); index++) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(llvmType(parameterTypes.get(index)));
        }
        return builder.toString();
    }

    private String renderThunkArguments(DirectCallTarget target) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < target.parameterTypes().size(); index++) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(llvmType(target.parameterTypes().get(index)))
                    .append(" %arg")
                    .append(index);
        }
        return builder.toString();
    }

    private List<IrType> directThunkParameterTypes(IrClass irClass, IrMethod method) {
        ArrayList<IrType> parameterTypes = new ArrayList<>();
        if (!method.isStatic()) {
            parameterTypes.add(IrType.reference(irClass.reference().internalName()));
        }
        parameterTypes.addAll(method.parameterTypes());
        return List.copyOf(parameterTypes);
    }

    private String directThunkName(MethodKey key) {
        return JniMangler.opaqueSymbol("di|" + key.owner() + "|" + key.name() + "|" + key.descriptor(), 24);
    }

    private long dispatchId(MethodKey key) {
        String digest = JniMangler.opaqueDigest("dispatch-id|" + key.owner() + "|" + key.name() + "|" + key.descriptor());
        return Long.parseUnsignedLong(digest.substring(0, 16), 16);
    }

    private long dispatchMask(MethodKey key) {
        String digest = JniMangler.opaqueDigest("dispatch-mask|" + key.owner() + "|" + key.name() + "|" + key.descriptor());
        long mask = Long.parseUnsignedLong(digest.substring(0, 16), 16);
        return mask == 0L ? 0x6a09e667f3bcc909L : mask;
    }

    private String descriptorFromMethodRef(IrMethodRef methodRef) {
        StringBuilder builder = new StringBuilder();
        builder.append('(');
        for (IrType parameterType : methodRef.parameterTypes()) {
            builder.append(IrDescriptors.typeDescriptor(parameterType));
        }
        builder.append(')').append(IrDescriptors.typeDescriptor(methodRef.returnType()));
        return builder.toString();
    }

    private String sanitizeSymbol(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (char current : value.toCharArray()) {
            if ((current >= 'a' && current <= 'z')
                    || (current >= 'A' && current <= 'Z')
                    || (current >= '0' && current <= '9')
                    || current == '_') {
                builder.append(current);
            } else {
                builder.append('_');
            }
        }
        return builder.toString();
    }

    private String encodeHelperToken(String value) {
        return encodeHexUtf8(value);
    }

    private String encodeHexUtf8(String value) {
        StringBuilder builder = new StringBuilder(value.length() * 2);
        for (byte current : value.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            builder.append(String.format(java.util.Locale.ROOT, "%02x", current & 0xff));
        }
        return builder.toString();
    }

    private record ParameterBinding(int localSlot, IrType type, String llvmName) {
    }

    private record ConstructorPair(IrInstruction.NewObject newObject) {
    }

    private record LocalStore(int instructionIndex, IrValue value) {
    }

    private record MethodKey(String owner, String name, String descriptor) {
    }

    private record DirectCallTarget(
            MethodKey key,
            String symbolName,
            String thunkSymbolName,
            String dispatcherSymbolName,
            IrType returnType,
            List<IrType> parameterTypes,
            long dispatchId,
            long dispatchMask,
            long encodedDispatchId,
            boolean isStatic,
            boolean isPrivate,
            boolean isFinal
    ) {
    }

    private record DispatchSignature(IrType returnType, List<IrType> parameterTypes) {
    }

    private record DispatchGroup(
            DispatchSignature signature,
            String dispatcherSymbolName,
            List<DirectCallTarget> targets
    ) {
    }

    private record ValueSearchKey(int instructionIndex, IrValue value) {
    }

    public record ModuleFragment(String fileName, String llvmText) {
    }

    public record ModuleSet(String monolithicText, List<ModuleFragment> shardModules) {
    }

    private record ClassShardUnit(IrClassRef classRef, List<IrMethod> methods, int estimatedBytes) {
    }

    private static final class ShardBucket {
        private final ArrayList<ClassShardUnit> units;
        private int estimatedBytes;

        private ShardBucket(ArrayList<ClassShardUnit> units, int estimatedBytes) {
            this.units = units;
            this.estimatedBytes = estimatedBytes;
        }

        private ArrayList<ClassShardUnit> units() {
            return units;
        }

        private int estimatedBytes() {
            return estimatedBytes;
        }
    }

    private record MethodWeight(IrMethod method, int estimatedBytes) {
    }

    private static final class ShardMethodBucket {
        private final ArrayList<IrMethod> methods;
        private int estimatedBytes;

        private ShardMethodBucket(ArrayList<IrMethod> methods, int estimatedBytes) {
            this.methods = methods;
            this.estimatedBytes = estimatedBytes;
        }

        private ArrayList<IrMethod> methods() {
            return methods;
        }

        private int estimatedBytes() {
            return estimatedBytes;
        }
    }
}
