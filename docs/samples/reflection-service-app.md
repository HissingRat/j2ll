# reflection-service-app

Small service-style fixture for beta smoke testing reflection and packaging metadata.

## Source

```java
package sample.reflect;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ServiceLoader;

public final class Main {
    public static void main(String[] args) throws Exception {
        Class<?> type = Class.forName("sample.reflect.PluginImpl");
        Constructor<?> constructor = type.getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        Object plugin = constructor.newInstance("beta");
        Method method = type.getDeclaredMethod("message", int.class);
        method.setAccessible(true);
        System.out.println(method.invoke(plugin, 7));
        System.out.println(ServiceLoader.load(Plugin.class).findFirst().isPresent());
    }
}
```

```java
package sample.reflect;

public interface Plugin {
    String message(int value);
}
```

```java
package sample.reflect;

final class PluginImpl implements Plugin {
    private final String prefix;

    private PluginImpl(String prefix) {
        this.prefix = prefix;
    }

    public String message(int value) {
        return prefix + ":" + value;
    }
}
```

`META-INF/services/sample.reflect.Plugin`:

```text
sample.reflect.PluginImpl
```

## Config

Use the same fields as `docs/examples/protection-all-on-config.json`; keep sensitive seeds out of shared logs.

```json
{
  "schemaVersion": 1,
  "jarFile": "build/samples/reflection-service-app.jar",
  "classPath": [],
  "worldModel": "PARTIAL_WORLD",
  "outputDirectory": "build/j2ll-reflection",
  "whiteList": ["sample/reflect/Main#main!([Ljava/lang/String;)V"],
  "blackList": [],
  "target": {"macosArm64": true},
  "signaturePolicy": "fail",
  "protection": {"enabled": true, "seed": "hash-this-seed"}
}
```

## Command

```sh
java -jar build/dist/j2ll/j2ll.jar --config config/reflection-service-app.json
java -jar <outputJar-path-printed-by-j2ll> sample.reflect.Main
```

j2ll creates `<resolved-outputDirectory>/build_yyyy-MM-dd_HH-mm-ss[-n]/` automatically and writes `reflection-service-app.jar` directly in that workspace.

Expected output:

```text
beta:7
true
```

Expected report highlights:

- `reports/index.json` lists all emitted reports and SHA-256 hashes.
- `packaging-report.json` keeps `META-INF/services/*` and module/multi-release metadata when present.
- Constant reflection can use helper-backed lowering; dynamic names stay fallback-backed with explicit reason codes.
- `protection-report.json` and `artifact-audit.json` remain hash-only for sensitive plaintext.

Enable the current host target when you want to run this sample locally. You may also enable any of the other five fixed targets in the same build; managed Zig produces the selected DLL/SO/dylib matrix in one invocation, while only the host artifact is exercised by the local child JVM.
