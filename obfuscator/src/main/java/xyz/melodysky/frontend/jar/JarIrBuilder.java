package xyz.melodysky.frontend.jar;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import xyz.melodysky.filter.ClassMethodFilter;
import xyz.melodysky.frontend.bytecode.ClassIrBuilder;
import xyz.melodysky.ir.model.IrClass;
import xyz.melodysky.ir.model.IrProgram;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

public class JarIrBuilder {

    private final ClassIrBuilder classIrBuilder = new ClassIrBuilder();
    private static final ProgressListener NO_PROGRESS = new ProgressListener() {};

    public interface ProgressListener {
        default void onReadStart(int totalClasses) {}
        default void onClassRead(int current, int totalClasses, String className) {}
        default void onLowerStart(int totalClasses) {}
        default void onClassLowered(int current, int totalClasses, String className) {}
    }

    public BuildResult build(Path jarPath) throws IOException {
        return build(jarPath, ClassMethodFilter.allowAll(), NO_PROGRESS);
    }

    public BuildResult build(Path jarPath, ClassMethodFilter classMethodFilter) throws IOException {
        return build(jarPath, classMethodFilter, NO_PROGRESS);
    }

    public BuildResult build(Path jarPath, ClassMethodFilter classMethodFilter, ProgressListener progressListener) throws IOException {
        ArrayList<ClassNode> classNodes = new ArrayList<>();
        ArrayList<ClassBuildResult> initialClassResults = new ArrayList<>();

        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            List<? extends ZipEntry> classEntries = jarFile.stream()
                    .filter(entry -> entry.getName().endsWith(".class"))
                    .toList();

            progressListener.onReadStart(classEntries.size());
            int readIndex = 0;
            for (ZipEntry entry : classEntries) {
                readIndex++;
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }

                try (InputStream inputStream = jarFile.getInputStream(entry)) {
                    ClassReader classReader = new ClassReader(inputStream);
                    ClassNode classNode = new ClassNode(Opcodes.ASM9);
                    classReader.accept(classNode, 0);
                    if (!classMethodFilter.shouldProcess(classNode)) {
                        progressListener.onClassRead(readIndex, classEntries.size(), classNode.name);
                        continue;
                    }
                    classNodes.add(classNode);
                    progressListener.onClassRead(readIndex, classEntries.size(), classNode.name);
                } catch (RuntimeException exception) {
                    String className = entry.getName().replace(".class", "");
                    progressListener.onClassRead(readIndex, classEntries.size(), className);
                    initialClassResults.add(new ClassBuildResult(className, null,
                            List.of(new ClassIrBuilder.SkippedMethod("<class>", "<class>", exception.getMessage()))));
                }
            }
        }

        progressListener.onLowerStart(classNodes.size());
        int loweredCount = 0;
        for (ClassNode classNode : classNodes) {
            loweredCount++;
            try {
                ClassIrBuilder.BuildResult classBuildResult = classIrBuilder.build(classNode, classMethodFilter);
                initialClassResults.add(new ClassBuildResult(
                        classNode.name,
                        classBuildResult.irClass(),
                        classBuildResult.skippedMethods()
                ));
            } catch (RuntimeException exception) {
                initialClassResults.add(new ClassBuildResult(
                        classNode.name,
                        null,
                        List.of(new ClassIrBuilder.SkippedMethod("<class>", "<class>", exception.getMessage()))
                ));
            }
            progressListener.onClassLowered(loweredCount, classNodes.size(), classNode.name);
        }

        return finalizeBuild(classNodes, initialClassResults, classMethodFilter);
    }

    private BuildResult finalizeBuild(List<ClassNode> classNodes, List<ClassBuildResult> initialClassResults,
                                      ClassMethodFilter classMethodFilter) {
        LinkedHashMap<String, LinkedHashMap<String, ClassIrBuilder.SkippedMethod>> skippedByClass = new LinkedHashMap<>();
        for (ClassBuildResult classBuildResult : initialClassResults) {
            LinkedHashMap<String, ClassIrBuilder.SkippedMethod> perClass = new LinkedHashMap<>();
            for (ClassIrBuilder.SkippedMethod skippedMethod : classBuildResult.skippedMethods()) {
                perClass.put(methodKey(skippedMethod.name(), skippedMethod.descriptor()), skippedMethod);
            }
            skippedByClass.put(classBuildResult.className(), perClass);
        }

        ArrayList<IrClass> classes = new ArrayList<>();
        ArrayList<ClassBuildResult> finalResults = new ArrayList<>();
        for (ClassBuildResult initialResult : initialClassResults) {
            LinkedHashMap<String, ClassIrBuilder.SkippedMethod> skippedMethods =
                    skippedByClass.getOrDefault(initialResult.className(), new LinkedHashMap<>());
            IrClass filteredClass = filterIrClass(initialResult.irClass(), skippedMethods.keySet());
            if (filteredClass != null && !filteredClass.methods().isEmpty()) {
                classes.add(filteredClass);
            }
            finalResults.add(new ClassBuildResult(
                    initialResult.className(),
                    filteredClass,
                    new ArrayList<>(skippedMethods.values())
            ));
        }

        return new BuildResult(new IrProgram(classes), finalResults);
    }

    private IrClass filteredIrClass(IrClass irClass, Set<String> skippedMethodKeys) {
        if (irClass == null) {
            return null;
        }
        ArrayList<xyz.melodysky.ir.model.IrMethod> methods = new ArrayList<>();
        for (var method : irClass.methods()) {
            if (skippedMethodKeys.contains(methodKey(method.name(), xyz.melodysky.ir.util.IrDescriptors.methodDescriptor(method)))) {
                continue;
            }
            methods.add(method);
        }
        return new IrClass(irClass.reference(), methods);
    }

    private IrClass filterIrClass(IrClass irClass, Set<String> skippedMethodKeys) {
        return filteredIrClass(irClass, skippedMethodKeys);
    }

    private String methodKey(String name, String descriptor) {
        return name + descriptor;
    }

    public record BuildResult(IrProgram program, List<ClassBuildResult> classResults) {
        public BuildResult {
            classResults = List.copyOf(classResults);
        }
    }

    public record ClassBuildResult(String className, IrClass irClass, List<ClassIrBuilder.SkippedMethod> skippedMethods) {
        public ClassBuildResult {
            skippedMethods = List.copyOf(skippedMethods);
        }
    }
}
