package xyz.melodysky.app;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import xyz.melodysky.filter.ClassMethodFilter;
import xyz.melodysky.frontend.jar.JarIrBuilder;
import xyz.melodysky.ir.model.IrClass;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

public record AnalysisReport(
        String inputJar,
        String buildWorkspace,
        int totalClasses,
        int totalMethods,
        int attemptableMethods,
        int nativeLoweredMethods,
        int keptAsJavaMethods,
        int whiteListHitMethods,
        int blackListHitMethods
) {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static AnalysisReport from(Path jarPath, Path buildDirectory, ClassMethodFilter filter,
                                      JarIrBuilder.BuildResult frontendResult) throws IOException {
        MethodInventory inventory = scanJar(jarPath, filter);
        int nativeLoweredMethods = frontendResult.program().classes().stream()
                .map(IrClass::methods)
                .mapToInt(List::size)
                .sum();
        int keptAsJavaMethods = Math.max(0, inventory.attemptableMethods() - nativeLoweredMethods);
        return new AnalysisReport(
                jarPath.toAbsolutePath().normalize().toString(),
                buildDirectory.toAbsolutePath().normalize().toString(),
                inventory.totalClasses(),
                inventory.totalMethods(),
                inventory.attemptableMethods(),
                nativeLoweredMethods,
                keptAsJavaMethods,
                inventory.whiteListHitMethods(),
                inventory.blackListHitMethods()
        );
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    private static MethodInventory scanJar(Path jarPath, ClassMethodFilter filter) throws IOException {
        int totalClasses = 0;
        int totalMethods = 0;
        int attemptableMethods = 0;
        int whiteListHitMethods = 0;
        int blackListHitMethods = 0;
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            List<? extends ZipEntry> classEntries = jarFile.stream()
                    .filter(entry -> entry.getName().endsWith(".class"))
                    .toList();
            for (ZipEntry entry : classEntries) {
                try (InputStream inputStream = jarFile.getInputStream(entry)) {
                    ClassReader classReader = new ClassReader(inputStream);
                    ClassNode classNode = new ClassNode(Opcodes.ASM9);
                    classReader.accept(classNode, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    totalClasses++;
                    for (MethodNode methodNode : classNode.methods) {
                        totalMethods++;
                        if (!isAttemptable(methodNode)) {
                            continue;
                        }
                        attemptableMethods++;
                        if (filter.matchesWhiteListClass(classNode.name)
                                || filter.matchesWhiteListMethod(classNode.name, methodNode.name, methodNode.desc)) {
                            whiteListHitMethods++;
                        }
                        if (filter.matchesBlackListClass(classNode.name)
                                || filter.matchesBlackListMethod(classNode.name, methodNode.name, methodNode.desc)) {
                            blackListHitMethods++;
                        }
                    }
                }
            }
        }
        return new MethodInventory(
                totalClasses,
                totalMethods,
                attemptableMethods,
                whiteListHitMethods,
                blackListHitMethods
        );
    }

    private static boolean isAttemptable(MethodNode methodNode) {
        return (methodNode.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0;
    }

    private record MethodInventory(
            int totalClasses,
            int totalMethods,
            int attemptableMethods,
            int whiteListHitMethods,
            int blackListHitMethods
    ) {
    }
}
