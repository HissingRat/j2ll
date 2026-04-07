package xyz.melodysky.packaging;

import xyz.melodysky.backend.llvm.JniMangler;
import xyz.melodysky.ir.model.IrClass;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrProgram;
import xyz.melodysky.ir.util.IrDescriptors;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;

public class NativeRegistrationPlanner {

    private final NativeMethodClassRewriter nativeMethodClassRewriter;

    public NativeRegistrationPlanner() {
        this(new NativeMethodClassRewriter());
    }

    public NativeRegistrationPlanner(NativeMethodClassRewriter nativeMethodClassRewriter) {
        this.nativeMethodClassRewriter = nativeMethodClassRewriter;
    }

    public NativeRegistrationPlan plan(Path inputJar, IrProgram program) throws IOException {
        LinkedHashMap<String, Set<NativeMethodClassRewriter.MethodKey>> requestedMethods = requestedMethods(program);
        ArrayList<NativeRegistrationPlan.ClassRegistration> classes = new ArrayList<>();

        try (JarFile jarFile = new JarFile(inputJar.toFile())) {
            int classIndex = 0;
            for (IrClass irClass : program.classes()) {
                Set<NativeMethodClassRewriter.MethodKey> requested = requestedMethods.get(irClass.reference().internalName());
                if (requested == null || requested.isEmpty()) {
                    continue;
                }

                String entryName = irClass.reference().internalName() + ".class";
                if (jarFile.getEntry(entryName) == null) {
                    continue;
                }

                byte[] classBytes;
                try (InputStream input = jarFile.getInputStream(jarFile.getEntry(entryName))) {
                    classBytes = input.readAllBytes();
                }
                Set<NativeMethodClassRewriter.MethodKey> rewritable = nativeMethodClassRewriter.filterRewritableMethods(classBytes, requested);
                if (rewritable.isEmpty()) {
                    continue;
                }

                ArrayList<NativeRegistrationPlan.MethodRegistration> methods = new ArrayList<>();
                for (IrMethod method : irClass.methods()) {
                    NativeMethodClassRewriter.MethodKey methodKey =
                            new NativeMethodClassRewriter.MethodKey(method.name(), IrDescriptors.methodDescriptor(method));
                    if (!rewritable.contains(methodKey)) {
                        continue;
                    }
                    methods.add(new NativeRegistrationPlan.MethodRegistration(
                            method.name(),
                            methodKey.descriptor(),
                            JniMangler.nativeBridgeName(irClass, method)
                    ));
                }
                if (!methods.isEmpty()) {
                    classes.add(new NativeRegistrationPlan.ClassRegistration(
                            classIndex++,
                            irClass.reference().internalName(),
                            List.copyOf(methods)
                    ));
                }
            }
        }

        return classes.isEmpty() ? NativeRegistrationPlan.empty() : new NativeRegistrationPlan(List.copyOf(classes));
    }

    private LinkedHashMap<String, Set<NativeMethodClassRewriter.MethodKey>> requestedMethods(IrProgram program) {
        LinkedHashMap<String, Set<NativeMethodClassRewriter.MethodKey>> methodsByClass = new LinkedHashMap<>();
        for (IrClass irClass : program.classes()) {
            LinkedHashSet<NativeMethodClassRewriter.MethodKey> methods = new LinkedHashSet<>();
            for (IrMethod method : irClass.methods()) {
                if (method.name().startsWith("<")) {
                    continue;
                }
                methods.add(new NativeMethodClassRewriter.MethodKey(method.name(), IrDescriptors.methodDescriptor(method)));
            }
            if (!methods.isEmpty()) {
                methodsByClass.put(irClass.reference().internalName(), Set.copyOf(methods));
            }
        }
        return methodsByClass;
    }
}
