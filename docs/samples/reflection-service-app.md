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
  "javaSupportTier": "TIER_5",
  "fallbackMode": "nativeEmbeddedClassBlob",
  "outputDirectory": "build/j2ll-reflection",
  "whiteList": ["sample/reflect/Main#main!([Ljava/lang/String;)V"],
  "blackList": [],
  "target": {"macosArm64": true},
  "signaturePolicy": "fail",
  "protection": {"enabled": true, "seed": "hash-this-seed", "intensity": "normal"}
}
```

## Command

```sh
java -jar build/dist/j2ll/j2ll.jar build config/reflection-service-app.json build/j2ll-reflection-workspace
java -jar build/j2ll-reflection-workspace/output/reflection-service-app.jar sample.reflect.Main
```

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

Set exactly one `target` flag for the current host when running this sample.
