package com.linguacart.api;

import com.linguacart.dto.ProductResult;
import com.linguacart.dto.SearchResponse;
import com.linguacart.search.SearchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SearchController.class)
class SearchControllerMvcTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    SearchService searchService;

    @Test
    void returnsRankedResults() throws Exception {
        when(searchService.search(eq("munyu"), eq("sna_Latn"), anyInt())).thenReturn(new SearchResponse("munyu",
                List.of(new ProductResult("p1", "Salt", null, "$1.00", 0.9, "https://example.com/p1",
                        List.of("glossary:munyu")))));
        mockMvc.perform(post("/api/v1/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"munyu\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].productId").value("p1"))
                .andExpect(jsonPath("$.results[0].matchedOn[0]").value("glossary:munyu"))
                .andExpect(jsonPath("$.results[0].titleSn").doesNotExist());
    }

    @Test
    void blankQueryIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void topKOutOfRangeIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"munyu\",\"topK\":500}"))
                .andExpect(status().isBadRequest());
    }
}
