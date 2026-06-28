# basic-cli-app

Small CLI fixture for beta smoke testing.

## Source

```java
package sample.basic;

import java.util.Arrays;
import java.util.Optional;

public final class Main {
    public static void main(String[] args) {
        String name = args.length == 0 ? "world" : args[0];
        StringBuilder builder = new StringBuilder();
        builder.append("hello ").append(name);
        builder.append(" count=").append(Arrays.asList(args).size());
        builder.append(" opt=").append(Optional.of(name).orElse("missing"));
        System.out.println(builder.toString());
    }
}
```

## Config

Use the same schema shape as `docs/examples/minimal-config.json`:

```json
{
  "schemaVersion": 1,
  "jarFile": "build/samples/basic-cli-app.jar",
  "classPath": [],
  "worldModel": "PARTIAL_WORLD",
  "javaSupportTier": "TIER_5",
  "fallbackMode": "nativeEmbeddedClassBlob",
  "outputDirectory": "build/j2ll-basic",
  "whiteList": ["sample/basic/Main#main!([Ljava/lang/String;)V"],
  "blackList": [],
  "target": {"macosArm64": true},
  "signaturePolicy": "fail",
  "protection": {"enabled": true, "seed": "hash-this-seed", "intensity": "normal"}
}
```

## Command

```sh
java -jar build/dist/j2ll/j2ll.jar build config/basic-cli-app.json build/j2ll-basic-workspace
java -jar build/j2ll-basic-workspace/output/basic-cli-app.jar sample.basic.Main beta
```

Expected output:

```text
hello beta count=1 opt=beta
```

Expected report highlights:

- `reports/index.json` lists all emitted reports and SHA-256 hashes.
- `reports/summary.md` shows audit/readiness status and method counts.
- JDK collection calls may stay helper/fallback-backed; native code does not read collection internals.

Set exactly one `target` flag for the current host when running this sample.
