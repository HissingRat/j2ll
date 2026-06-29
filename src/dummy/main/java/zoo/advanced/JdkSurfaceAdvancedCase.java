package zoo.advanced;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.Comparator;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.stream.Collectors;
import zoo.Case;

public final class JdkSurfaceAdvancedCase implements Case {
    @Override
    public String name() {
        return "JdkSurfaceAdvancedCase";
    }

    @Override
    public String run() throws Exception {
        return nioSmoke() + ":" + resourceBundle() + ":" + localeFormat() + ":" + moduleApi();
    }

    public static String nioSmoke() throws Exception {
        Path directory = Files.createTempDirectory("j2ll-dummy");
        try {
            Path file = directory.resolve("data.txt");
            Files.writeString(file, "nio", StandardCharsets.UTF_8);
            return Files.readString(file, StandardCharsets.UTF_8) + Files.size(file);
        } finally {
            try (var stream = Files.walk(directory)) {
                for (Path path : stream.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    public static String resourceBundle() {
        ResourceBundle bundle = ResourceBundle.getBundle("zoo.i18n.DummyMessages", Locale.US);
        return bundle.getString("message");
    }

    public static String localeFormat() {
        NumberFormat format = NumberFormat.getNumberInstance(Locale.US);
        format.setGroupingUsed(false);
        format.setMaximumFractionDigits(2);
        format.setMinimumFractionDigits(2);
        return format.format(1234.5d);
    }

    public static String moduleApi() {
        Module module = JdkSurfaceAdvancedCase.class.getModule();
        String name = module.getName() == null ? "unnamed" : module.getName();
        return name + "." + module.isNamed();
    }
}
