package com.linguacart.api;

import com.linguacart.dto.HealthResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    @GetMapping("/health")
    public HealthResponse health() {
        // TODO Phase 6: ping the Python model service via ModelServiceClient
        return new HealthResponse("ok", "unknown");
    }
}