package com.example.geotime.web;

import com.example.geotime.model.LocationMatch;
import com.example.geotime.model.LocationTimeReport;
import com.example.geotime.model.ResolvedLocationTime;
import com.example.geotime.service.CityAliasRagService;
import com.example.geotime.tools.GeoTimeTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Hybrid Neuro-Symbolic Agentic Orchestrator driving the Adaptive UI.
 *
 * <p>Follows the Composed Method pattern so top-level endpoints read like a
 * four-stage agentic pipeline while delegating execution to small, single-responsibility
 * atomic methods:</p>
 * <ol>
 *   <li><b>Hybrid Semantic Router / Intent Extraction</b> ({@link #parseIntent})</li>
 *   <li><b>Deterministic Tool Execution (MCP)</b> ({@link #findMatchingLocations})</li>
 *   <li><b>Agentic RAG-as-a-Tool (Autonomous Fallback)</b> ({@link #resolveViaRagFallback})</li>
 *   <li><b>Dynamic Control Flow: Pipeline / Parallel Fan-Out / HITL Guardrail + Evaluator</b> ({@link #routeByAmbiguity})</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/geo-time")
public class GeoTimeAgentController {

    private static final Logger log = LoggerFactory.getLogger(GeoTimeAgentController.class);
    private static final int MAX_FAN_OUT_MATCHES = 5;

    private final ChatClient chatClient;
    private final GeoTimeTools geoTimeTools;
    private final CityAliasRagService cityAliasRagService;

    public GeoTimeAgentController(
        ChatClient.Builder builder,
        GeoTimeTools geoTimeTools,
        CityAliasRagService cityAliasRagService
    ) {
        this.geoTimeTools = geoTimeTools;
        this.cityAliasRagService = cityAliasRagService;
        this.chatClient = builder.build();
    }

    public record QueryRequest(String query) {}
    public record ResumeRequest(String checkpointId, String chosenState, String message) {}
    private record ParsedIntent(String city, String state) {}

    // =========================================================================
    // TOP-LEVEL AGENTIC ORCHESTRATION
    // =========================================================================

    /**
     * Orchestrates end-to-end Geo-Time resolution across four canonical Agentic AI patterns:
     *
     * <ol>
     *   <li><b>Hybrid Semantic Router / Intent Extraction</b> ({@link #parseIntent}):
     *       Uses a fast deterministic string split for structured inputs ("Gary, IN"), and
     *       invokes Qwen 2.5 7B via Bifrost <i>only</i> when unstructured natural language arrives.</li>
     *   <li><b>Deterministic Tool Execution (MCP)</b> ({@link #findMatchingLocations}):
     *       Calls structured external APIs (Open-Meteo) with strong Java record contracts rather
     *       than letting an LLM hallucinate coordinates or geography.</li>
     *   <li><b>Agentic RAG-as-a-Tool (Autonomous Fallback)</b> ({@link #resolveViaRagFallback}):
     *       Instead of running vector search blindly on every request, invokes Milvus +
     *       embeddinggemma-2:270m <i>only</i> when exact structured lookup returns empty
     *       ("The Big Easy" -> "New Orleans, Louisiana").</li>
     *   <li><b>Dynamic Control Flow: Pipeline / Parallel Fan-Out / HITL Guardrail + Evaluator</b>
     *       ({@link #routeByAmbiguity}): Branches across three execution topologies—a 1-match
     *       Prompt Chain + Evaluator (validateTimeReport), a 2–5 match Java 21 Virtual-Thread
     *       Fan-Out, or a &gt;5 match PostgreSQL HITL Checkpoint—and drives the Adaptive UI accordingly.</li>
     * </ol>
     */
    @PostMapping("/query")
    public LocationTimeReport queryCity(@RequestBody QueryRequest request) {
        String rawQuery = request.query().trim();

        // 1. Hybrid Semantic Router / Intent Extraction:
        //    Fast deterministic string split for structured inputs ("Gary, IN"); invokes Qwen 2.5 7B
        //    via Bifrost ONLY when unstructured natural language arrives.
        ParsedIntent intent = parseIntent(rawQuery);

        // 2. Deterministic Tool Execution (MCP):
        //    Calls structured external APIs (Open-Meteo) with strong Java record contracts rather
        //    than letting an LLM hallucinate coordinates or geography.
        List<LocationMatch> matches = findMatchingLocations(intent);

        // 3. Agentic RAG-as-a-Tool (Autonomous Fallback):
        //    Invokes Milvus + embeddinggemma-2:270m ONLY when exact structured lookup returns empty
        //    ("The Big Easy" -> "New Orleans, Louisiana") instead of running vector search blindly.
        if (matches.isEmpty()) {
            matches = resolveViaRagFallback(rawQuery);
        }

        // 4. Dynamic Control Flow: Pipeline / Parallel Fan-Out / HITL Guardrail + Evaluator:
        //    Branches across 1-match Prompt Chain + Evaluator (validateTimeReport), 2-5 match
        //    Java 21 Virtual-Thread Fan-Out, or >5 match PostgreSQL HITL Checkpoint to drive the Adaptive UI.
        String resolvedCity = matches.isEmpty() ? intent.city() : matches.get(0).city();
        return routeByAmbiguity(resolvedCity, matches);
    }

    /**
     * Resumes a paused PostgreSQL Human-in-the-Loop (HITL) checkpoint after user clarification.
     */
    @PostMapping("/resume")
    public LocationTimeReport resumeCheckpoint(@RequestBody ResumeRequest request) {
        String userReply = extractUserReply(request);
        String canonicalState = normalizeStateWithLlm(userReply);

        try {
            LocationMatch match = geoTimeTools.resumeHitlCheckpoint(request.checkpointId(), canonicalState);
            return buildSingleResultReport(
                match.city(),
                match,
                "Resumed Postgres checkpoint " + request.checkpointId() + " for " + match.city() + ", " + match.state() + "."
            );
        } catch (RuntimeException ex) {
            log.warn("Invalid state clarification '{}' for checkpoint {}: {}", canonicalState, request.checkpointId(), ex.getMessage());
            return buildInvalidClarificationReport(request.checkpointId(), canonicalState);
        }
    }

    // =========================================================================
    // STAGE 1: HYBRID SEMANTIC ROUTER / INTENT EXTRACTION
    // =========================================================================

    private ParsedIntent parseIntent(String rawQuery) {
        if (rawQuery.contains(",")) {
            List<String> parts = List.of(rawQuery.split(",", 2));
            return new ParsedIntent(parts.get(0).trim(), parts.get(1).trim());
        }
        if (rawQuery.split("\\s+").length > 3) {
            return extractIntentWithLlm(rawQuery);
        }
        return new ParsedIntent(rawQuery, null);
    }

    private ParsedIntent extractIntentWithLlm(String rawQuery) {
        ParsedIntent extracted = chatClient.prompt()
            .system("Extract the U.S. city and optional U.S. state from the user query. Return JSON: {\"city\": \"...\", \"state\": null or \"...\"}")
            .user(rawQuery)
            .call()
            .entity(ParsedIntent.class);

        return (extracted != null && extracted.city() != null)
            ? extracted
            : new ParsedIntent(rawQuery, null);
    }

    // =========================================================================
    // STAGE 2 & 3: DETERMINISTIC TOOL LOOKUP + MILVUS AGENTIC RAG FALLBACK
    // =========================================================================

    private List<LocationMatch> findMatchingLocations(ParsedIntent intent) {
        List<LocationMatch> matches = geoTimeTools.lookupLocation(intent.city());
        if (intent.state() == null || intent.state().isBlank()) {
            return matches;
        }
        String filter = intent.state().toLowerCase();
        return matches.stream()
            .filter(m -> m.state().toLowerCase().contains(filter))
            .toList();
    }

    private List<LocationMatch> resolveViaRagFallback(String rawQuery) {
        String canonical = cityAliasRagService.resolveCityAlias(rawQuery);
        if (canonical == null || !canonical.contains(",")) {
            return List.of();
        }
        ParsedIntent aliasIntent = parseIntent(canonical);
        return findMatchingLocations(aliasIntent);
    }

    // =========================================================================
    // STAGE 4: DYNAMIC CONTROL-FLOW ROUTING & ADAPTIVE UI CONTRACT BUILDERS
    // =========================================================================

    private LocationTimeReport routeByAmbiguity(String city, List<LocationMatch> matches) {
        if (matches.isEmpty()) {
            return buildNotFoundReport(city);
        }
        if (matches.size() == 1) {
            return buildSingleResultReport(city, matches.get(0), "Resolved a single deterministic match.");
        }
        if (matches.size() <= MAX_FAN_OUT_MATCHES) {
            return buildParallelFanOutReport(city, matches);
        }
        return buildHitlGuardrailReport(city, matches);
    }

    private LocationTimeReport buildSingleResultReport(String queryCity, LocationMatch match, String statusMessage) {
        ResolvedLocationTime item = resolveLocationTime(match);
        LocationTimeReport report = new LocationTimeReport(queryCity, false, null, statusMessage, List.of(item));
        validateReport(report);
        return report;
    }

    private LocationTimeReport buildParallelFanOutReport(String city, List<LocationMatch> matches) {
        List<ResolvedLocationTime> resolvedList = matches.parallelStream()
            .map(this::resolveLocationTime)
            .toList();

        LocationTimeReport report = new LocationTimeReport(
            city,
            true,
            null,
            "Found " + resolvedList.size() + " matches for " + city + ". Please select the matching state.",
            resolvedList
        );
        validateReport(report);
        return report;
    }

    private LocationTimeReport buildHitlGuardrailReport(String city, List<LocationMatch> matches) {
        String checkpointId = geoTimeTools.saveHitlCheckpoint(city, matches);
        List<String> topStates = matches.stream().map(LocationMatch::state).limit(6).toList();

        return new LocationTimeReport(
            city,
            true,
            checkpointId,
            "Found " + matches.size() + " matches for " + city + " (including " + String.join(", ", topStates) + "). Please specify the U.S. state to resume.",
            List.of()
        );
    }

    private LocationTimeReport buildNotFoundReport(String city) {
        return new LocationTimeReport(
            city,
            true,
            "manual-clarification",
            "No exact matches found for '" + city + "'. Please clarify.",
            List.of()
        );
    }

    private LocationTimeReport buildInvalidClarificationReport(String checkpointId, String invalidInput) {
        return new LocationTimeReport(
            "Clarification Needed",
            true,
            checkpointId,
            "'" + invalidInput + "' is not a valid U.S. state for this checkpoint. Please enter a state name (for example: Georgia, Texas, or Illinois).",
            List.of()
        );
    }

    // =========================================================================
    // ATOMIC HELPERS & EVALUATOR VALIDATION
    // =========================================================================

    private ResolvedLocationTime resolveLocationTime(LocationMatch match) {
        var tz = geoTimeTools.getTimeZone(match.latitude(), match.longitude(), match.state(), match.county());
        String localTime = geoTimeTools.getCurrentTime(tz.timeZone());
        return new ResolvedLocationTime(match.city(), match.county(), match.state(), tz.timeZone(), localTime);
    }

    private void validateReport(LocationTimeReport report) {
        var validation = geoTimeTools.validateTimeReport(report);
        if (!validation.valid()) {
            log.warn("Evaluator flagged issues: {}", validation.errors());
        }
    }

    private String extractUserReply(ResumeRequest request) {
        return (request.chosenState() != null && !request.chosenState().isBlank())
            ? request.chosenState().trim()
            : request.message().trim();
    }

    private String normalizeStateWithLlm(String userReply) {
        if (userReply.split("\\s+").length <= 2) {
            return userReply;
        }
        String normalized = chatClient.prompt()
            .system("Extract ONLY the canonical U.S. state name from the user's reply (e.g., Georgia). Output nothing else.")
            .user(userReply)
            .call()
            .content()
            .trim();
        log.info("LLM normalized HITL user reply '{}' -> '{}'", userReply, normalized);
        return normalized;
    }
}
