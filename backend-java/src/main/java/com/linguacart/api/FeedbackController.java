package com.linguacart.api;

import com.linguacart.dto.FeedbackRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class FeedbackController {

    private static final Logger log = LoggerFactory.getLogger(FeedbackController.class);

    @PostMapping("/feedback")
    public ResponseEntity<Map<String, String>> feedback(@Valid @RequestBody FeedbackRequest request) {
        log.info("Feedback received: {}", request);
        return ResponseEntity.ok(Map.of("status", "received"));
    }
}