package com.linguacart.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TranslationClientTest {

    @Test
    @DisplayName("TranslationClient delegates translate to ModelServiceClient")
    void testTranslateDelegation() {
        ModelServiceClient mockModelService = Mockito.mock(ModelServiceClient.class);
        when(mockModelService.translate("salt", "eng_Latn", "sna_Latn")).thenReturn("munyu");

        TranslationClient client = new TranslationClient(mockModelService);
        String result = client.translate("salt", "eng_Latn", "sna_Latn");

        assertEquals("munyu", result);
        verify(mockModelService).translate("salt", "eng_Latn", "sna_Latn");
    }

    @Test
    @DisplayName("TranslationClient translates all fields in map")
    void testTranslateFields() {
        ModelServiceClient mockModelService = Mockito.mock(ModelServiceClient.class);
        when(mockModelService.translate("salt", "eng_Latn", "sna_Latn")).thenReturn("munyu");
        when(mockModelService.translate("sugar", "eng_Latn", "sna_Latn")).thenReturn("shuga");

        TranslationClient client = new TranslationClient(mockModelService);
        Map<String, String> fields = Map.of("title", "salt", "desc", "sugar");

        Map<String, String> result = client.translateFields(fields, "eng_Latn", "sna_Latn");

        assertEquals("munyu", result.get("title"));
        assertEquals("shuga", result.get("desc"));
    }
}
