package xyz.melodysky.protection.audit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.util.Objects;

/** Stable aggregate-only JSON for cross-build carrier reuse. */
public final class BusinessStringCarrierReuseReportWriter {
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    public String json(BusinessStringCarrierReuseMetric metric) {
        Objects.requireNonNull(metric, "metric");
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("reportVersion", 1);
        root.addProperty("seedMode", metric.seedMode().wireName());
        root.addProperty("firstCarrierCount", metric.firstCarrierCount());
        root.addProperty("secondCarrierCount", metric.secondCarrierCount());
        root.addProperty("commonNameCount", metric.commonNameCount());
        root.addProperty(
                "commonNumericTokenCount",
                metric.commonNumericTokenCount());
        root.addProperty(
                "reuseRateBasisPoints",
                metric.reuseRateBasisPoints());
        root.addProperty("passed", metric.passed());
        root.addProperty("reasonCode", metric.reasonCode());
        return GSON.toJson(root) + "\n";
    }
}
