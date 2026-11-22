package com.example.geotime.web;

import com.example.geotime.tools.GeoTimeTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminRemediationController {

    private static final Logger log = LoggerFactory.getLogger(AdminRemediationController.class);
    private final GeoTimeTools geoTimeTools;

    public AdminRemediationController(GeoTimeTools geoTimeTools) {
        this.geoTimeTools = geoTimeTools;
    }

    public record RemediationCommand(String action, String component) {}

    @PostMapping("/remediate")
    public Map<String, Object> applyRemediation(@RequestBody RemediationCommand cmd) {
        log.warn("Received AIOps remediation command: action={}, component={}", cmd.action(), cmd.component());

        if ("OPEN_CIRCUIT_BREAKER".equalsIgnoreCase(cmd.action()) && "timeapi".equalsIgnoreCase(cmd.component())) {
            geoTimeTools.setTimeApiCircuitOpen(true);
            return Map.of("status", "APPLIED", "component", "timeapi", "circuitOpen", true);
        }
        if ("CLOSE_CIRCUIT_BREAKER".equalsIgnoreCase(cmd.action()) && "timeapi".equalsIgnoreCase(cmd.component())) {
            geoTimeTools.setTimeApiCircuitOpen(false);
            return Map.of("status", "APPLIED", "component", "timeapi", "circuitOpen", false);
        }
        return Map.of("status", "IGNORED", "reason", "Unsupported action/component");
    }
}
