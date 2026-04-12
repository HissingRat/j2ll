package xyz.melodysky.packaging;

import xyz.melodysky.backend.llvm.JniMangler;
import xyz.melodysky.ir.model.IrClass;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrProgram;
import xyz.melodysky.ir.util.IrDescriptors;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarFile;

public class NativeRegistrationPlanner {

    private final NativeMethodClassRewriter nativeMethodClassRewriter;

    public NativeRegistrationPlanner() {
        this(new NativeMethodClassRewriter());
    }

    public NativeRegistrationPlanner(NativeMethodClassRewriter nativeMethodClassRewriter) {
        this.nativeMethodClassRewriter = nativeMethodClassRewriter;
    }

    public NativeRegistrationPlan plan(Path inputJar, List<RequestedClass> requestedClasses) throws IOException {
        LinkedHashMap<String, Set<NativeMethodClassRewriter.MethodKey>> requestedMethods = requestedMethods(requestedClasses);
        ArrayList<NativeRegistrationPlan.ClassRegistration> classes = new ArrayList<>();

        try (JarFile jarFile = new JarFile(inputJar.toFile())) {
            int classIndex = 0;
            for (RequestedClass requestedClass : requestedClasses) {
                Set<NativeMethodClassRewriter.MethodKey> requested = requestedMethods.get(requestedClass.internalName());
                if (requested == null || requested.isEmpty()) {
                    continue;
                }

                String entryName = requestedClass.internalName() + ".class";
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
                for (RequestedMethod method : requestedClass.methods()) {
                    NativeMethodClassRewriter.MethodKey methodKey =
                            new NativeMethodClassRewriter.MethodKey(method.name(), method.descriptor());
                    if (!rewritable.contains(methodKey)) {
                        continue;
                    }
                    methods.add(new NativeRegistrationPlan.MethodRegistration(
                            method.name(),
                            methodKey.descriptor(),
                            method.bridgeSymbol()
                    ));
                }
                if (!methods.isEmpty()) {
                    classes.add(new NativeRegistrationPlan.ClassRegistration(
                            classIndex++,
                            requestedClass.internalName(),
                            List.copyOf(methods)
                    ));
                }
            }
        }

        return classes.isEmpty() ? NativeRegistrationPlan.empty() : new NativeRegistrationPlan(List.copyOf(classes));
    }

    private LinkedHashMap<String, Set<NativeMethodClassRewriter.MethodKey>> requestedMethods(List<RequestedClass> requestedClasses) {
        LinkedHashMap<String, Set<NativeMethodClassRewriter.MethodKey>> methodsByClass = new LinkedHashMap<>();
        for (RequestedClass requestedClass : requestedClasses) {
            LinkedHashSet<NativeMethodClassRewriter.MethodKey> methods = new LinkedHashSet<>();
            for (RequestedMethod method : requestedClass.methods()) {
                methods.add(new NativeMethodClassRewriter.MethodKey(method.name(), method.descriptor()));
            }
            if (!methods.isEmpty()) {
                methodsByClass.put(requestedClass.internalName(), Set.copyOf(methods));
            }
        }
        return methodsByClass;
    }

    public record RequestedClass(String internalName, List<RequestedMethod> methods) {
        public RequestedClass {
            methods = List.copyOf(methods);
        }

        public static List<RequestedClass> fromProgram(IrProgram program) {
            ArrayList<RequestedClass> classes = new ArrayList<>();
            for (IrClass irClass : program.classes()) {
                ArrayList<RequestedMethod> methods = new ArrayList<>();
                for (IrMethod method : irClass.methods()) {
                    if (method.name().startsWith("<")) {
                        continue;
                    }
                    methods.add(new RequestedMethod(
                            method.name(),
                            IrDescriptors.methodDescriptor(method),
                            JniMangler.nativeBridgeName(irClass, method)
                    ));
                }
                if (!methods.isEmpty()) {
                    classes.add(new RequestedClass(irClass.reference().internalName(), methods));
                }
            }
            return List.copyOf(classes);
        }
    }

    public record RequestedMethod(String name, String descriptor, String bridgeSymbol) {
    }
}
