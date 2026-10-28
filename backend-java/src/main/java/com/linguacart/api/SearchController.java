package com.linguacart.api;

import com.linguacart.dto.ProductResult;
import com.linguacart.dto.SearchRequest;
import com.linguacart.dto.SearchResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class SearchController {

    @PostMapping("/search")
    public SearchResponse search(@Valid @RequestBody SearchRequest request) {
        // STUB: one fixed result so the extension developer can integrate now.
        ProductResult stub = new ProductResult(
                "product_12",
                "Iodized table salt, 1 kg",
                "[sn] Iodized table salt, 1 kg",
                "$2.99",
                0.87,
                "https://example.com/product/12");
        return new SearchResponse(request.query(), List.of(stub));
    }
}