package com.skyscan.agri;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * OPTIONAL off-flight report narration. After the classifier's structured
 * findings exist, an LLM can turn them into a readable agronomic summary
 * (prioritized, spatially aware) that complements the structured cures/checks.
 *
 * This runs on the backend AFTER landing — never on the drone, never in the
 * flight loop — where latency and model size don't matter and a human reviews
 * the output. It is DISABLED by default: with no endpoint configured,
 * narrate() returns null and the report shows only the structured catalog
 * content. Set skyscan.agri.narrator.* (any OpenAI-compatible chat endpoint,
 * incl. a local server) to enable.
 *
 * The LLM only writes prose from facts we already computed — it does NOT make
 * the disease calls (that's the purpose-built classifier's job). This keeps
 * the fluent-but-unreliable failure mode out of the diagnosis.
 */
@Component
public class ReportNarrator {

    private static final Logger log = LoggerFactory.getLogger(ReportNarrator.class);
    private static final ObjectMapper M = new ObjectMapper();

    private final boolean enabled;
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    public ReportNarrator(
            @Value("${skyscan.agri.narrator.enabled:false}") boolean enabled,
            @Value("${skyscan.agri.narrator.endpoint:}") String endpoint,
            @Value("${skyscan.agri.narrator.api-key:}") String apiKey,
            @Value("${skyscan.agri.narrator.model:gpt-4o-mini}") String model) {
        this.enabled = enabled;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.model = model;
        if (enabled) log.info("Report narrator enabled (model {}, endpoint {})", model, endpoint);
    }

    public boolean isEnabled() { return enabled && !endpoint.isBlank(); }

    /**
     * Return a short HTML narrative (already escaped/safe fragment), or null if
     * disabled or on any failure — callers must treat null as "no narrative"
     * and render the structured report regardless.
     */
    public String narrate(String missionName, int plantCount, int affected,
                          Map<String, Integer> diseaseTally,
                          List<String> perPlantLines) {
        if (!isEnabled()) return null;
        try {
            String facts = buildFacts(missionName, plantCount, affected, diseaseTally, perPlantLines);
            String prompt = "You are an agronomy assistant. Using ONLY the facts below, write a concise "
                    + "HTML summary (2-4 short paragraphs, no <html>/<head>, plain <p> tags) for a grower: "
                    + "overall health, notable disease clusters or patterns, and prioritized next actions. "
                    + "Do not invent diseases or numbers not present. End by reminding the reader to confirm "
                    + "findings on the ground.\n\nFACTS:\n" + facts;

            ObjectNode req = M.createObjectNode();
            req.put("model", model);
            req.put("temperature", 0.3);
            ArrayNode msgs = req.putArray("messages");
            ObjectNode u = msgs.addObject();
            u.put("role", "user");
            u.put("content", prompt);

            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(40))
                    .header("Content-Type", "application/json");
            if (!apiKey.isBlank()) b.header("Authorization", "Bearer " + apiKey);
            HttpResponse<String> res = http.send(
                    b.POST(HttpRequest.BodyPublishers.ofString(req.toString())).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                log.warn("narrator HTTP {}: {}", res.statusCode(), res.body());
                return null;
            }
            String content = M.readTree(res.body())
                    .path("choices").path(0).path("message").path("content").asText(null);
            return (content == null || content.isBlank()) ? null : content;
        } catch (Exception e) {
            log.warn("narrator failed, using structured report only: {}", e.getMessage());
            return null;
        }
    }

    private String buildFacts(String missionName, int plantCount, int affected,
                              Map<String, Integer> diseaseTally, List<String> perPlantLines) {
        StringBuilder f = new StringBuilder();
        f.append("Mission: ").append(missionName).append('\n');
        f.append("Plants inspected: ").append(plantCount).append('\n');
        f.append("Plants with findings: ").append(affected).append('\n');
        f.append("Disease tally: ");
        diseaseTally.forEach((d, c) -> f.append(d).append('=').append(c).append("; "));
        f.append('\n');
        f.append("Per-plant (label, kind, location, disease, severity):\n");
        for (String line : perPlantLines) f.append("  ").append(line).append('\n');
        return f.toString();
    }
}
