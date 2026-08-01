package xyz.melodysky.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import xyz.melodysky.config.ConfigLoader;

class ResolvedConfigReportWriterTest {
    @Test
    void recordsExactPublicMethodInternalizationAuthorization() throws Exception {
        Path example = Path.of("docs/examples/minimal-config.json");
        JsonObject json = JsonParser.parseString(Files.readString(example))
                .getAsJsonObject();
        JsonArray allowList = new JsonArray();
        allowList.add("fixture/PublicApi#zeta!()V");
        allowList.add("fixture/PublicApi#alpha!(I)I");
        json.getAsJsonObject("protection")
                .getAsJsonObject("ir")
                .add("publicMethodInternalizationAllowList", allowList);
        var loaded = new ConfigLoader().load(json, example.getParent());
        assertFalse(loaded.hasErrors(), loaded.diagnostics().toString());

        JsonArray reported = JsonParser.parseString(new ResolvedConfigReportWriter()
                        .json(loaded.config().orElseThrow()))
                .getAsJsonObject()
                .getAsJsonArray("publicMethodInternalizationAllowList");

        assertEquals(2, reported.size());
        assertEquals("fixture/PublicApi#zeta!()V", reported.get(0).getAsString());
        assertEquals("fixture/PublicApi#alpha!(I)I", reported.get(1).getAsString());
    }
}
