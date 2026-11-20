package com.example.geotime.web;

import com.example.geotime.model.LocationMatch;
import com.example.geotime.model.LocationTimeReport;
import com.example.geotime.model.ResolvedLocationTime;
import com.example.geotime.model.TimeZoneInfo;
import com.example.geotime.model.ValidationResult;
import com.example.geotime.tools.GeoTimeTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/geo-time")
public class GeoTimeAgentController {

    private static final Logger log = LoggerFactory.getLogger(GeoTimeAgentController.class);

    private final ChatClient chatClient;
    private final GeoTimeTools geoTimeTools;

    public GeoTimeAgentController(ChatClient.Builder builder, GeoTimeTools geoTimeTools) {
        this.geoTimeTools = geoTimeTools;
        this.chatClient = builder.build();
    }

    public record QueryRequest(String query) {}
    public record ResumeRequest(String checkpointId, String chosenState, String message) {}
    private record ParsedIntent(String city, String state) {}

    @PostMapping("/query")
    public LocationTimeReport queryCity(@RequestBody QueryRequest request) {
        String rawQuery = request.query().trim();
        String city = rawQuery;
        String stateFilter = null;

        if (rawQuery.contains(",")) {
            List<String> parts = List.of(rawQuery.split(",", 2));
            city = parts.get(0).trim();
            stateFilter = parts.get(1).trim();
        } else if (rawQuery.split("\\s+").length > 3) {
            ParsedIntent intent = chatClient.prompt()
                .system("Extract the U.S. city and optional U.S. state from the user query. Return JSON: {\"city\": \"...\", \"state\": null or \"...\"}")
                .user(rawQuery)
                .call()
                .entity(ParsedIntent.class);
            if (intent != null && intent.city() != null) {
                city = intent.city();
                stateFilter = intent.state();
            }
        }

        List<LocationMatch> matches = geoTimeTools.lookupLocation(city);
        if (stateFilter != null && !stateFilter.isBlank()) {
            String filter = stateFilter.toLowerCase();
            matches = matches.stream()
                .filter(m -> m.state().toLowerCase().contains(filter))
                .toList();
        }

        if (matches.size() == 1) {
            return resolveAndValidateSingle(city, matches.get(0), "Resolved a single deterministic match.");
        }

        if (matches.size() >= 2 && matches.size() <= 5) {
            List<ResolvedLocationTime> resolvedList = matches.parallelStream()
                .map(m -> {
                    TimeZoneInfo tz = geoTimeTools.getTimeZone(m.latitude(), m.longitude(), m.state(), m.county());
                    String time = geoTimeTools.getCurrentTime(tz.zoneId());
                    return new ResolvedLocationTime(m.city(), m.county(), m.state(), tz.zoneId(), time);
                })
                .toList();

            LocationTimeReport report = new LocationTimeReport(
                city, true, null,
                "Found " + resolvedList.size() + " matches for " + city + ". Please select the matching state.",
                resolvedList
            );
            geoTimeTools.validateTimeReport(report);
            return report;
        }

        if (matches.size() > 5) {
            String checkpointId = geoTimeTools.saveHitlCheckpoint(city, matches);
            List<String> topStates = matches.stream().map(LocationMatch::state).limit(6).toList();
            return new LocationTimeReport(
                city, true, checkpointId,
                "Found " + matches.size() + " matches for " + city + " (including " + String.join(", ", topStates) + "). Please specify the U.S. state to resume.",
                List.of()
            );
        }

        return new LocationTimeReport(city, true, "manual-clarification", "No exact matches found for '" + city + "'. Please clarify.", List.of());
    }

    @PostMapping("/resume")
    public LocationTimeReport resumeCheckpoint(@RequestBody ResumeRequest request) {
        String userReply = (request.chosenState() != null && !request.chosenState().isBlank())
            ? request.chosenState().trim()
            : request.message().trim();

        String canonicalState = userReply;
        if (userReply.split("\\s+").length > 2) {
            canonicalState = chatClient.prompt()
                .system("Extract ONLY the canonical U.S. state name from the user's reply (e.g., Tennessee). Output nothing else.")
                .user(userReply)
                .call()
                .content()
                .trim();
            log.info("LLM normalized HITL user reply '{}' -> '{}'", userReply, canonicalState);
        }

        LocationMatch match = geoTimeTools.resumeHitlCheckpoint(request.checkpointId(), canonicalState);
        return resolveAndValidateSingle(
            match.city(),
            match,
            "Resumed Postgres checkpoint " + request.checkpointId() + " for " + match.city() + ", " + match.state() + "."
        );
    }

    private LocationTimeReport resolveAndValidateSingle(String queryCity, LocationMatch m, String message) {
        TimeZoneInfo tz = geoTimeTools.getTimeZone(m.latitude(), m.longitude(), m.state(), m.county());
        String localTime = geoTimeTools.getCurrentTime(tz.zoneId());
        ResolvedLocationTime item = new ResolvedLocationTime(m.city(), m.county(), m.state(), tz.zoneId(), localTime);
        LocationTimeReport report = new LocationTimeReport(queryCity, false, null, message, List.of(item));

        var validation = geoTimeTools.validateTimeReport(report);
        if (!validation.valid()) {
            log.warn("Evaluator flagged issues: {}", validation.errors());
        }

        return report;
    }
}
