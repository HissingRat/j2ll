package zoo.basic;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ServiceLoader;
import zoo.Case;
import zoo.services.ZooService;
import zoo.versioned.VersionedFeature;

public final class PackagingBasicCase implements Case {
    @Override
    public String name() {
        return "PackagingBasicCase";
    }

    @Override
    public String run() throws Exception {
        String message;
        try (InputStream stream = PackagingBasicCase.class.getClassLoader()
                .getResourceAsStream("resources/zoo-message.txt")) {
            if (stream == null) {
                throw new IllegalStateException("missing resource");
            }
            message = new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        String service = ServiceLoader.load(ZooService.class)
                .findFirst()
                .map(ZooService::message)
                .orElse("missing-service");
        return message + ":" + service + ":" + VersionedFeature.value();
    }
}
