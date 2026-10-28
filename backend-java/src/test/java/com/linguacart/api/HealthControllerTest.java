package com.linguacart.api;

import com.linguacart.client.ModelServiceClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthController.class)
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ModelServiceClient modelServiceClient;

    @Test
    @DisplayName("GET /api/v1/health returns ok when model service is healthy")
    void testHealthOk() throws Exception {
        when(modelServiceClient.isHealthy()).thenReturn(true);

        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.modelService").value("ok"));
    }

    @Test
    @DisplayName("GET /api/v1/health returns down when model service is unhealthy")
    void testHealthDown() throws Exception {
        when(modelServiceClient.isHealthy()).thenReturn(false);

        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.modelService").value("down"));
    }
}
