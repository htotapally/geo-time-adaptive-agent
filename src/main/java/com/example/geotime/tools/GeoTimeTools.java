package com.example.geotime.tools;

import com.example.geotime.model.LocationMatch;
import com.example.geotime.model.LocationTimeReport;
import com.example.geotime.model.ResolvedLocationTime;
import com.example.geotime.model.TimeZoneInfo;
import com.example.geotime.persistence.AgentCheckpoint;
import com.example.geotime.persistence.AgentCheckpointRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class GeoTimeTools {

	private final AgentCheckpointRepository checkpointRepo;
	private final ObjectMapper objectMapper;

	private static final Logger log = LoggerFactory.getLogger(GeoTimeTools.class);

	private static final Set<String> SPLIT_TIMEZONE_STATES = Set.of("Florida", "Idaho", "Indiana", "Kansas", "Kentucky",
			"Michigan", "Nebraska", "North Dakota", "Oregon", "South Dakota", "Tennessee", "Texas");

	private final RestClient restClient;

	public GeoTimeTools(RestClient.Builder builder, AgentCheckpointRepository checkpointRepo,
			ObjectMapper objectMapper) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(3));
		factory.setReadTimeout(Duration.ofSeconds(3));

		this.restClient = builder.requestFactory(factory).defaultHeader("User-Agent", "SpringBootAgenticDemo/1.0")
				.build();
		this.checkpointRepo = checkpointRepo;
		this.objectMapper = objectMapper;
	}
	
	@Tool(description = "HITL PAUSE: Save candidate locations to Postgres when >5 matches are found. Returns a checkpointId to give the user so they can resume later.")
	public String saveHitlCheckpoint(
	    @ToolParam(description = "The searched city name") String queryCity,
	    @ToolParam(description = "The list of candidate LocationMatch objects returned by lookupLocation") List<LocationMatch> candidates
	) {
	    try {
	        String id = "chk-" + queryCity.toLowerCase().replaceAll("[^a-z0-9]", "") + "-" + (System.currentTimeMillis() % 10000);
	        String json = objectMapper.writeValueAsString(candidates);
	        checkpointRepo.save(new AgentCheckpoint(id, queryCity, json));
	        log.info("HITL CHECKPOINT SAVED -> id={}, city={}, candidates={}", id, queryCity, candidates.size());
	        return id;
	    } catch (Exception e) {
	        log.error("Failed to save HITL checkpoint", e);
	        return "ERROR_SAVING_CHECKPOINT";
	    }
	}

	@Tool(description = "HITL RESUME: Load a saved checkpoint from Postgres using checkpointId and filter to the user's chosen state without calling external geocoding APIs.")
	public LocationMatch resumeHitlCheckpoint(
	    @ToolParam(description = "The checkpointId returned when the workflow paused") String checkpointId,
	    @ToolParam(description = "The U.S. state specified by the user, e.g., Tennessee") String chosenState
	) {
	    AgentCheckpoint checkpoint = checkpointRepo.findById(checkpointId)
	        .orElseThrow(() -> new IllegalArgumentException("Checkpoint not found: " + checkpointId));

	    try {
	        List<LocationMatch> saved = objectMapper.readValue(
	            checkpoint.getCandidatesJson(),
	            new TypeReference<List<LocationMatch>>() {}
	        );

	        LocationMatch match = saved.stream()
	            .filter(loc -> loc.state().equalsIgnoreCase(chosenState.trim()))
	            .findFirst()
	            .orElseThrow(() -> new IllegalArgumentException("State '" + chosenState + "' not found in checkpoint " + checkpointId));

	        checkpoint.setStatus("RESUMED");
	        checkpoint.setResumedAt(java.time.Instant.now());
	        checkpointRepo.save(checkpoint);

	        log.info("HITL CHECKPOINT RESUMED -> id={}, resolved={}", checkpointId, match);
	        return match;
	    } catch (Exception e) {
	        throw new RuntimeException("Failed to resume checkpoint: " + e.getMessage(), e);
	    }
	}	

	@Tool(description = "Look up U.S. locations matching a city name. Returns city, county, state, coordinates, and whether the state spans multiple time zones.")
	public List<LocationMatch> lookupLocation(
			@ToolParam(description = "The U.S. city name to search, e.g., Atlanta, Springfield, Gary") String city) {
		try {
			OpenMeteoResponse response = restClient.get()
				    .uri(uriBuilder -> uriBuilder
				        .scheme("https")
				        .host("geocoding-api.open-meteo.com")
				        .path("/v1/search")
				        .queryParam("name", city)
				        .queryParam("count", 10)
				        .queryParam("countryCode", "US")
				        .queryParam("format", "json")
				        .build())
				    .retrieve()
				    .body(OpenMeteoResponse.class);


			
			if (response != null && response.results() != null && !response.results().isEmpty()) {
				// Deduplicate by State so Supervisor fans out across distinct states
				Map<String, LocationMatch> byState = new LinkedHashMap<>();
				for (OpenMeteoResult r : response.results()) {
					if (r.admin1() != null && !byState.containsKey(r.admin1())) {
						byState.put(r.admin1(),
								new LocationMatch(r.name(), r.admin2() != null ? r.admin2() : "Unknown County",
										r.admin1(), r.latitude(), r.longitude(),
										SPLIT_TIMEZONE_STATES.contains(r.admin1())));
					}
				}
				log.info("LIVE Open-Meteo lookup for '{}' returned {} state matches", city, byState.size());
				return new ArrayList<>(byState.values());
			}
		} catch (Exception ex) {
			log.warn("Open-Meteo API failed for '{}', switching to local fallback: {}", city, ex.getMessage());
		}

		return fallbackLocations(city);
	}

    @Tool(description = "Resolves the authoritative IANA time zone ID for a U.S. coordinate pair, state, and county.")
    public TimeApiZoneResponse getTimeZone(
        @ToolParam(description = "Latitude") double latitude,
        @ToolParam(description = "Longitude") double longitude,
        @ToolParam(description = "U.S. State name") String state,
        @ToolParam(description = "U.S. County or Parish name") String county
    ) {
        try {
        	TimeApiZoneResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                    .scheme("https")
                    .host("timeapi.io")
                    .path("/api/TimeZone/coordinate")
                    .queryParam("latitude", latitude)
                    .queryParam("longitude", longitude)
                    .build())
                .retrieve()
                .body(TimeApiZoneResponse.class);

            if (response != null && response.timeZone() != null && !response.timeZone().isBlank()) {
                log.info("LIVE TimeAPI.io resolved ({}, {}) -> {}", state, county, response.timeZone());
                return new TimeApiZoneResponse(response.timeZone());
            }
        } catch (Exception ex) {
            log.warn("TimeAPI.io failed for ({}, {}), switching to local fallback: {}", state, county, ex.getMessage());
        }

        // Circuit-breaker fallback when TimeAPI.io is unreachable or times out!
        String fallbackZone = resolveFallbackZoneId(state, county);
        log.info("Fallback TimeZone resolved ({}, {}) -> {}", state, county, fallbackZone);
        return new TimeApiZoneResponse(fallbackZone);
    }


	@Tool(description = "Get the current local time in ISO-8601 format for a given IANA time zone ID (e.g., America/New_York).")
	public String getCurrentTime(
			@ToolParam(description = "Valid IANA time zone ID such as America/New_York or America/Chicago") String zoneId) {
		// Optional: Replace this with a RestClient call to your existing time-service
		// if desired
		ZonedDateTime now = ZonedDateTime.now(ZoneId.of(zoneId));
		return now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
	}

	public record ValidationResult(boolean valid, List<String> errors) {
	}

	@Tool(description = "MANDATORY: Validate your final LocationTimeReport JSON before returning it to the user. If valid is false, fix every listed error and re-validate.")
	public ValidationResult validateTimeReport(
			@ToolParam(description = "The draft report to validate") LocationTimeReport draft) {
		List<String> errors = new ArrayList<>();

		if (draft == null || draft.results() == null) {
			return new ValidationResult(false, List.of("Draft report or results list is null."));
		}

		if (!draft.isAmbiguous() && draft.results().size() > 1) {
			errors.add("isAmbiguous must be true when results contains multiple locations.");
		}

		for (ResolvedLocationTime item : draft.results()) {
			if (item.county() == null || item.county().isBlank() || item.county().equalsIgnoreCase("Unknown County")) {
				errors.add("Missing valid county for " + item.city() + ", " + item.state());
			}
			try {
				ZoneId zone = ZoneId.of(item.zoneId());
				OffsetDateTime parsedTime = OffsetDateTime.parse(item.localTimeIso());
				ZoneOffset expectedOffset = zone.getRules().getOffset(parsedTime.toInstant());
				if (!parsedTime.getOffset().equals(expectedOffset)) {
					errors.add("Offset mismatch for " + item.city() + ": " + item.localTimeIso() + " does not match "
							+ item.zoneId());
				}
			} catch (Exception e) {
				errors.add("Invalid zoneId or ISO timestamp for " + item.city() + ": " + e.getMessage());
			}

			if ("Indiana".equalsIgnoreCase(item.state()) && item.county() != null
					&& item.county().toLowerCase().contains("lake") && !"America/Chicago".equals(item.zoneId())) {
				errors.add("Split-timezone rule violation: Lake County, Indiana must use 'America/Chicago'.");
			}
		}

		return new ValidationResult(errors.isEmpty(), errors);
	}

	// --- Local Circuit-Breaker Fallbacks ---

	private List<LocationMatch> fallbackLocations(String city) {
		return switch (city.trim().toLowerCase()) {
		case "atlanta" -> List.of(new LocationMatch("Atlanta", "Fulton County", "Georgia", 33.749, -84.388, false));
		case "gary" -> List.of(new LocationMatch("Gary", "Lake County", "Indiana", 41.5934, -87.3464, true));
		case "springfield" ->
			List.of(new LocationMatch("Springfield", "Sangamon County", "Illinois", 39.7817, -89.6501, false),
					new LocationMatch("Springfield", "Greene County", "Missouri", 37.2090, -93.2923, false),
					new LocationMatch("Springfield", "Hampden County", "Massachusetts", 42.1015, -72.5898, false),
					new LocationMatch("Springfield", "Lane County", "Oregon", 44.0462, -123.0220, true));
		case "franklin" ->
			List.of(new LocationMatch("Franklin", "Williamson County", "Tennessee", 35.9251, -86.8689, true),
					new LocationMatch("Franklin", "Johnson County", "Indiana", 39.4806, -86.0549, true),
					new LocationMatch("Franklin", "Norfolk County", "Massachusetts", 42.0834, -71.3967, false),
					new LocationMatch("Franklin", "Warren County", "Ohio", 39.5589, -84.3041, false),
					new LocationMatch("Franklin", "Venango County", "Pennsylvania", 41.3978, -79.8314, false),
					new LocationMatch("Franklin", "Milwaukee County", "Wisconsin", 42.8886, -88.0384, false));
		default -> List.of();
		};
	}

	private TimeZoneInfo fallbackTimeZone(String state, String county) {
		String zone = switch (state.toLowerCase()) {
		case "georgia", "massachusetts", "ohio", "pennsylvania" -> "America/New_York";
		case "indiana" -> (county != null && county.toLowerCase().contains("lake")) ? "America/Chicago"
				: "America/Indiana/Indianapolis";
		case "illinois", "missouri", "tennessee", "wisconsin" -> "America/Chicago";
		case "oregon" -> "America/Los_Angeles";
		default -> "America/New_York";
		};
		String abbr = ZonedDateTime.now(ZoneId.of(zone)).format(DateTimeFormatter.ofPattern("zzz"));
		return new TimeZoneInfo(state, county, zone, abbr);
	}

    private String resolveFallbackZoneId(String state, String county) {
        if (state == null) return "America/New_York";
        String s = state.trim().toLowerCase();
        String c = (county != null) ? county.trim().toLowerCase() : "";

        // 1. Split-timezone county overrides
        if (s.equals("indiana") && (c.contains("lake") || c.contains("porter") || c.contains("laporte") || c.contains("jasper") || c.contains("newton") || c.contains("posey") || c.contains("vanderburgh") || c.contains("warrick") || c.contains("gibson") || c.contains("spencer") || c.contains("perry"))) {
            return "America/Chicago";
        }

        // 2. Canonical U.S. State -> Primary IANA Time Zone
        return switch (s) {
            case "alabama", "arkansas", "illinois", "iowa", "louisiana", "minnesota", "mississippi", "missouri", "oklahoma", "wisconsin", "texas", "kansas", "nebraska", "north dakota", "south dakota", "tennessee" -> "America/Chicago";
            case "colorado", "montana", "new mexico", "utah", "wyoming", "idaho" -> "America/Denver";
            case "arizona" -> "America/Phoenix";
            case "california", "nevada", "oregon", "washington" -> "America/Los_Angeles";
            case "alaska" -> "America/Anchorage";
            case "hawaii" -> "Pacific/Honolulu";
            default -> "America/New_York";
        };
    }
	
	// --- External API Response Records ---

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record OpenMeteoResponse(List<OpenMeteoResult> results) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record OpenMeteoResult(String name, String admin1, // State
			String admin2, // County
			double latitude, double longitude) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record TimeApiZoneResponse(String timeZone) {
	}
}
