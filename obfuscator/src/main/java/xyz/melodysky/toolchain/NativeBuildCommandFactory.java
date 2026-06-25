package xyz.melodysky.toolchain;

import xyz.melodysky.config.BuildTarget;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class NativeBuildCommandFactory {
    private final NativeBuildWorkspacePaths paths;

    NativeBuildCommandFactory(NativeBuildWorkspacePaths paths) {
        this.paths = paths;
    }

    List<String> createCompileCommand(String zigCommand, Path llvmFile, Path runtimeStubFile,
                                      Path outputFile, BuildTarget target) {
        return createLinkCommand(
                zigCommand,
                List.of(llvmFile.toAbsolutePath(), runtimeStubFile.toAbsolutePath()),
                outputFile,
                target
        );
    }

    List<String> createLlvmObjectCompileCommand(String zigCommand, Path llvmModuleFile, Path outputFile, BuildTarget target) {
        ArrayList<String> command = new ArrayList<>();
        command.add(zigCommand);
        command.add("cc");
        command.add("-target");
        command.add(target.getZigTarget());
        command.add("-g0");
        command.addAll(paths.createPathSanitizingFlags());
        if (requiresPic(target)) {
            command.add("-fPIC");
        }
        command.add("-c");
        command.add(paths.commandPath(llvmModuleFile));
        command.add("-o");
        command.add(paths.commandPath(outputFile));
        return List.copyOf(command);
    }

    List<String> createRuntimeObjectCompileCommand(String zigCommand, Path runtimeStubFile, Path outputFile, BuildTarget target) {
        ArrayList<String> command = new ArrayList<>();
        Path jniHeadersDirectory = paths.ensureBundledJniHeaders(target);
        command.add(zigCommand);
        command.add("cc");
        command.add("-target");
        command.add(target.getZigTarget());
        command.add("-g0");
        command.addAll(paths.createPathSanitizingFlags());
        if (requiresPic(target)) {
            command.add("-fPIC");
        }
        command.add("-x");
        command.add("c");
        command.add("-c");
        command.add("-I");
        command.add(paths.commandPath(jniHeadersDirectory));
        command.add("-I");
        command.add(paths.commandPath(jniHeadersDirectory.resolve(target.getJniHeaderSubdir())));
        command.add(paths.commandPath(runtimeStubFile));
        command.add("-o");
        command.add(paths.commandPath(outputFile));
        return List.copyOf(command);
    }

    List<String> createLinkCommand(String zigCommand, List<Path> objectFiles, Path outputFile, BuildTarget target) {
        ArrayList<String> command = new ArrayList<>();
        command.add(zigCommand);
        command.add("cc");
        command.add("-target");
        command.add(target.getZigTarget());
        command.add("-g0");
        command.addAll(paths.createPathSanitizingFlags());
        command.add("-shared");
        command.add("-s");
        for (Path objectFile : objectFiles) {
            command.add(paths.commandPath(objectFile));
        }
        command.add("-o");
        command.add(paths.commandPath(outputFile));
        return List.copyOf(command);
    }

    List<String> createZigBuildCommand(String zigCommand, Path outputDirectory, Path buildProjectDirectory,
                                       BuildTarget target) {
        ArrayList<String> command = new ArrayList<>();
        command.add(zigCommand);
        command.add("build");
        command.add(target.getConfigKey());
        command.add("--prefix");
        command.add(outputDirectory.toAbsolutePath().normalize().toString());
        command.add("--cache-dir");
        command.add(paths.zigBuildCacheDirectory(buildProjectDirectory).toString());
        command.add("--global-cache-dir");
        command.add(paths.zigGlobalCacheDirectory().toString());
        return List.copyOf(command);
    }

    private boolean requiresPic(BuildTarget target) {
        return switch (target) {
            case LINUX_X64, LINUX_ARM64, MACOS_X64, MACOS_ARM64 -> true;
            case WINDOWS_X64, WINDOWS_ARM64 -> false;
        };
    }
}
