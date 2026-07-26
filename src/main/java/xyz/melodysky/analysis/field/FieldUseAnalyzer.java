package xyz.melodysky.analysis.field;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.frontend.classfile.ParsedProgram;

public final class FieldUseAnalyzer {
    private final FieldDynamicBoundaryDetector boundaryDetector;

    public FieldUseAnalyzer() {
        this(new FieldDynamicBoundaryDetector());
    }

    FieldUseAnalyzer(FieldDynamicBoundaryDetector boundaryDetector) {
        this.boundaryDetector = Objects.requireNonNull(boundaryDetector, "boundaryDetector");
    }

    public FieldUseIndex analyze(ParsedProgram inputProgram) {
        return analyze(inputProgram, List.of());
    }

    public FieldUseIndex analyze(ParsedProgram inputProgram, List<ParsedProgram> classpathPrograms) {
        Objects.requireNonNull(inputProgram, "inputProgram");
        classpathPrograms = List.copyOf(Objects.requireNonNull(classpathPrograms, "classpathPrograms"));
        FieldDeclarationIndex declarations = FieldDeclarationIndex.create(inputProgram, classpathPrograms);
        HashMap<FieldId, List<FieldAccessSite>> accesses = new HashMap<>();
        ArrayList<UnresolvedFieldReference> unresolved = new ArrayList<>();
        HashSet<FieldDynamicBoundary> boundaries = new HashSet<>();

        for (FieldDeclarationIndex.ClassFact classFact : declarations.allClasses()) {
            scanClass(classFact, declarations, accesses, unresolved, boundaries);
        }

        return new FieldUseIndex(
                declarations.inputBaseFields(),
                accesses,
                declarations.ambiguousInputBaseFields(),
                declarations.inputMultiReleaseOwners(),
                declarations.ownersWithClassInitializer(),
                declarations.serializableOwners(),
                List.copyOf(boundaries),
                unresolved);
    }

    private void scanClass(
            FieldDeclarationIndex.ClassFact classFact,
            FieldDeclarationIndex declarations,
            Map<FieldId, List<FieldAccessSite>> accesses,
            List<UnresolvedFieldReference> unresolved,
            Set<FieldDynamicBoundary> boundaries) {
        ParsedClass parsedClass = classFact.parsedClass();
        for (ParsedMethod method : parsedClass.methods()) {
            if (method.accessFlags().isNative()) {
                boundaries.add(new FieldDynamicBoundary(
                        FieldDynamicBoundaryKind.NATIVE_JNI,
                        method.owner(),
                        method.methodKey(),
                        "native method declaration"));
            }
            int instructionIndex = 0;
            for (AbstractInsnNode instruction : method.methodNode().instructions) {
                if (instruction instanceof FieldInsnNode fieldInstruction) {
                    detectBoundaries(
                            method,
                            fieldInstruction.owner,
                            fieldInstruction.name,
                            boundaries);
                    recordReference(
                            declarations,
                            accesses,
                            unresolved,
                            method,
                            classFact.origin(),
                            fieldInstruction.owner,
                            fieldInstruction.name,
                            fieldInstruction.desc,
                            directKind(fieldInstruction.getOpcode()),
                            instructionIndex,
                            false);
                } else if (instruction instanceof MethodInsnNode invocation) {
                    detectBoundaries(method, invocation.owner, invocation.name, boundaries);
                } else if (instruction instanceof LdcInsnNode ldc) {
                    scanDynamicValue(
                            ldc.cst,
                            declarations,
                            accesses,
                            unresolved,
                            method,
                            classFact.origin(),
                            instructionIndex,
                            false,
                            boundaries);
                } else if (instruction instanceof InvokeDynamicInsnNode invokedynamic) {
                    for (Object bootstrapArgument : invokedynamic.bsmArgs) {
                        scanDynamicValue(
                                bootstrapArgument,
                                declarations,
                                accesses,
                                unresolved,
                                method,
                                classFact.origin(),
                                instructionIndex,
                                true,
                                boundaries);
                    }
                }
                instructionIndex++;
            }
        }
    }

    private void scanDynamicValue(
            Object value,
            FieldDeclarationIndex declarations,
            Map<FieldId, List<FieldAccessSite>> accesses,
            List<UnresolvedFieldReference> unresolved,
            ParsedMethod method,
            FieldCodeOrigin origin,
            int instructionIndex,
            boolean bootstrapArgument,
            Set<FieldDynamicBoundary> boundaries) {
        if (value instanceof Handle handle) {
            if (!isFieldHandle(handle)) {
                return;
            }
            boundaries.add(new FieldDynamicBoundary(
                    FieldDynamicBoundaryKind.METHOD_HANDLE,
                    method.owner(),
                    method.methodKey(),
                    "field MethodHandle reference"));
            recordReference(
                    declarations,
                    accesses,
                    unresolved,
                    method,
                    origin,
                    handle.getOwner(),
                    handle.getName(),
                    handle.getDesc(),
                    handleKind(handle.getTag()),
                    instructionIndex,
                    bootstrapArgument);
        } else if (value instanceof ConstantDynamic constantDynamic) {
            for (int index = 0; index < constantDynamic.getBootstrapMethodArgumentCount(); index++) {
                scanDynamicValue(
                        constantDynamic.getBootstrapMethodArgument(index),
                        declarations,
                        accesses,
                        unresolved,
                        method,
                        origin,
                        instructionIndex,
                        true,
                        boundaries);
            }
        }
    }

    private void detectBoundaries(
            ParsedMethod method,
            String owner,
            String name,
            Set<FieldDynamicBoundary> boundaries) {
        for (FieldDynamicBoundaryKind kind : boundaryDetector.detectMemberReference(owner, name)) {
            boundaries.add(new FieldDynamicBoundary(
                    kind,
                    method.owner(),
                    method.methodKey(),
                    owner + "#" + name));
        }
    }

    private void recordReference(
            FieldDeclarationIndex declarations,
            Map<FieldId, List<FieldAccessSite>> accesses,
            List<UnresolvedFieldReference> unresolved,
            ParsedMethod method,
            FieldCodeOrigin origin,
            String symbolicOwner,
            String name,
            String descriptor,
            FieldReferenceKind referenceKind,
            int instructionIndex,
            boolean bootstrapArgument) {
        declarations.resolve(symbolicOwner, name, descriptor).ifPresentOrElse(field -> {
            FieldId fieldId = new FieldId(field.owner(), field.name(), field.descriptor());
            FieldAccessSite site = new FieldAccessSite(
                    fieldId,
                    method.methodKey(),
                    method.owner(),
                    method.name(),
                    method.accessFlags().isStatic(),
                    origin,
                    referenceKind,
                    symbolicOwner,
                    instructionIndex,
                    bootstrapArgument);
            accesses.computeIfAbsent(fieldId, ignored -> new ArrayList<>()).add(site);
        }, () -> unresolved.add(new UnresolvedFieldReference(
                symbolicOwner,
                name,
                descriptor,
                method.methodKey(),
                referenceKind,
                instructionIndex)));
    }

    private FieldReferenceKind directKind(int opcode) {
        return switch (opcode) {
            case Opcodes.GETFIELD -> FieldReferenceKind.BYTECODE_INSTANCE_READ;
            case Opcodes.PUTFIELD -> FieldReferenceKind.BYTECODE_INSTANCE_WRITE;
            case Opcodes.GETSTATIC -> FieldReferenceKind.BYTECODE_STATIC_READ;
            case Opcodes.PUTSTATIC -> FieldReferenceKind.BYTECODE_STATIC_WRITE;
            default -> throw new IllegalArgumentException("not a field opcode: " + opcode);
        };
    }

    private FieldReferenceKind handleKind(int tag) {
        return switch (tag) {
            case Opcodes.H_GETFIELD -> FieldReferenceKind.METHOD_HANDLE_INSTANCE_READ;
            case Opcodes.H_PUTFIELD -> FieldReferenceKind.METHOD_HANDLE_INSTANCE_WRITE;
            case Opcodes.H_GETSTATIC -> FieldReferenceKind.METHOD_HANDLE_STATIC_READ;
            case Opcodes.H_PUTSTATIC -> FieldReferenceKind.METHOD_HANDLE_STATIC_WRITE;
            default -> throw new IllegalArgumentException("not a field handle tag: " + tag);
        };
    }

    private boolean isFieldHandle(Handle handle) {
        return EnumSet.of(
                        HandleTag.GET_FIELD,
                        HandleTag.GET_STATIC,
                        HandleTag.PUT_FIELD,
                        HandleTag.PUT_STATIC)
                .contains(HandleTag.from(handle.getTag()));
    }

    private enum HandleTag {
        GET_FIELD(Opcodes.H_GETFIELD),
        GET_STATIC(Opcodes.H_GETSTATIC),
        PUT_FIELD(Opcodes.H_PUTFIELD),
        PUT_STATIC(Opcodes.H_PUTSTATIC),
        OTHER(-1);

        private final int tag;

        HandleTag(int tag) {
            this.tag = tag;
        }

        static HandleTag from(int tag) {
            for (HandleTag value : values()) {
                if (value.tag == tag) {
                    return value;
                }
            }
            return OTHER;
        }
    }
}
