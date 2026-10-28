package com.linguacart.api;

import com.linguacart.client.ModelServiceClient;
import com.linguacart.dto.HealthResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final ModelServiceClient modelServiceClient;

    public HealthController(ModelServiceClient modelServiceClient) {
        this.modelServiceClient = modelServiceClient;
    }

    @GetMapping("/health")
    public HealthResponse health() {
        boolean healthy = false;
        try {
            healthy = modelServiceClient != null && modelServiceClient.isHealthy();
        } catch (Exception ignored) {
        }
        return new HealthResponse("ok", healthy ? "ok" : "down");
    }
}