package xyz.melodysky.testsupport;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import xyz.melodysky.toolchain.HostPlatform;
import xyz.melodysky.toolchain.J2llHomeResolver;

public final class FakeManagedZig {
    private FakeManagedZig() {}

    public static AutoCloseable installAndUse(Path j2llHome) throws IOException {
        return installAndUse(j2llHome, false);
    }

    public static AutoCloseable installAndUseTamperingEvidence(Path j2llHome)
            throws IOException {
        return installAndUse(j2llHome, true);
    }

    private static AutoCloseable installAndUse(
            Path j2llHome,
            boolean tamperEvidence) throws IOException {
        HostPlatform host = HostPlatform.detect().orElse(null);
        assumeTrue(supportsCurrentHostFixture(host), "fake managed Zig can only produce macOS/Linux host fixtures");
        assumeTrue(Files.exists(Path.of(System.getProperty("java.home")).resolve("include/jni.h")),
                "JDK headers are required for fake managed Zig JNI build");
        Path executable = j2llHome.resolve("zig").resolve(isWindows() ? "zig.exe" : "zig");
        assumeTrue(Files.notExists(executable), "fake managed Zig requires a clean test distribution");
        Files.createDirectories(executable.getParent());
        Files.writeString(executable, script(tamperEvidence), StandardCharsets.UTF_8);
        executable.toFile().setExecutable(true);
        AutoCloseable homeOverride = useHome(j2llHome);
        return () -> {
            try {
                homeOverride.close();
            } finally {
                Files.deleteIfExists(executable);
            }
        };
    }

    public static boolean supportsCurrentHostFixture(HostPlatform host) {
        return host != null && !host.target().isWindows();
    }

    private static AutoCloseable useHome(Path j2llHome) {
        String previous = System.getProperty(J2llHomeResolver.OVERRIDE_PROPERTY);
        System.setProperty(J2llHomeResolver.OVERRIDE_PROPERTY, j2llHome.toString());
        return () -> {
            if (previous == null) {
                System.clearProperty(J2llHomeResolver.OVERRIDE_PROPERTY);
            } else {
                System.setProperty(J2llHomeResolver.OVERRIDE_PROPERTY, previous);
            }
        };
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private static String script(boolean tamperEvidence) {
        return ("""
                #!/usr/bin/env python3
                import json
                import os
                import pathlib
                import subprocess
                import sys
                import tempfile

                VERSION = "0.15.2"

                def fail(message):
                    print(message, file=sys.stderr)
                    return 1

                def path_from_workspace(workspace, raw):
                    path = pathlib.Path(raw)
                    if path.is_absolute():
                        return path
                    return (workspace / path).resolve()

                def output_from_prefix(workspace, raw):
                    path = pathlib.Path(raw)
                    if path.is_absolute():
                        return path
                    return (workspace.parent.parent / path).resolve()

                def shared_flag(target):
                    if target.startswith("macos-"):
                        return ["-dynamiclib"]
                    if target.startswith("linux-"):
                        return ["-shared"]
                    return None

                def build():
                    workspace = pathlib.Path.cwd().resolve()
                    manifest_path = workspace / "j2ll-build-manifest.json"
                    manifest = json.loads(manifest_path.read_text())
                    includes = []
                    for include in manifest.get("includeDirectories", []):
                        includes.extend(["-I", include])
                    c_sources = [path_from_workspace(workspace, item) for item in manifest.get("cSources", [])]
                    llvm_sources = [path_from_workspace(workspace, item) for item in manifest.get("llvmSources", [])]
                    for target in manifest.get("targets", []):
                        if not target.get("buildable", True):
                            continue
                        target_name = target["target"]
                        flags = shared_flag(target_name)
                        if flags is None:
                            return fail("test-only managed Zig does not implement target output " + target_name)
                        output = output_from_prefix(workspace, target["output"])
                        output.parent.mkdir(parents=True, exist_ok=True)
                        temp_root = workspace / "logs"
                        temp_root.mkdir(parents=True, exist_ok=True)
                        with tempfile.TemporaryDirectory(prefix="fake-zig-", dir=str(temp_root.resolve())) as tempdir:
                            objects = []
                            evidence_root = workspace / "evidence" / "optimized-assembly" / target_name
                            evidence_root.mkdir(parents=True, exist_ok=True)
                            assembly_sources = []
                            for index, source in enumerate(c_sources):
                                assembly = evidence_root / ("c-%d.s" % index)
                                command = ["cc", "-std=gnu11", "-Os", "-S", "-g0", "-fPIC",
                                           "-DNDEBUG", "-fvisibility=hidden", "-ffunction-sections",
                                           "-fdata-sections"]
                                command.extend(includes)
                                command.extend([str(source), "-o", str(assembly)])
                                subprocess.check_call(command)
                                assembly_sources.append(str(assembly))
                            for index, llvm in enumerate(llvm_sources):
                                obj = pathlib.Path(tempdir) / ("llvm_%d.o" % index)
                                subprocess.check_call(["cc", "-c", "-fPIC", str(llvm), "-o", str(obj)])
                                objects.append(str(obj))
                            command = ["cc", "-fPIC", "-fvisibility=hidden"]
                            command.extend(includes)
                            command.extend(flags)
                            command.extend(assembly_sources)
                            command.extend(objects)
                            command.extend(["-o", str(output)])
                            subprocess.check_call(command)
                            if TAMPER_EVIDENCE and assembly_sources:
                                pathlib.Path(assembly_sources[0]).write_text("")
                    return 0

                def main():
                    if len(sys.argv) >= 2 and sys.argv[1] == "version":
                        print(VERSION)
                        return 0
                    if len(sys.argv) >= 2 and sys.argv[1] == "build":
                        return build()
                    return fail("unsupported fake managed Zig invocation: " + " ".join(sys.argv[1:]))

                if __name__ == "__main__":
                    sys.exit(main())
                """).replace("TAMPER_EVIDENCE", tamperEvidence ? "True" : "False");
    }
}
