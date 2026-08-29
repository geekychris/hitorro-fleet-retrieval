/*
 * Copyright (c) 2006-2026 Chris Collins
 */
package com.hitorro.fleet.retrieval.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hitorro.fleet.retrieval.FleetCoordinatorService;
import com.hitorro.jsontypesystem.datamapper.AIOperations;
import com.hitorro.retrieval.pipeline.stages.SummaryStyles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SummarizeController.class)
class SummarizeControllerTest {

    @Autowired MockMvc mvc;
    @MockBean FleetCoordinatorService coordinator;
    @MockBean AIOperations ai;   // matches type of what coordinator.aiOperations() returns

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void wireDefaultAvailableAi() {
        when(coordinator.aiOperations()).thenReturn(ai);
        when(ai.isAvailable()).thenReturn(true);
    }

    @AfterEach
    void resetMocks() { reset(coordinator, ai); }

    @Test
    void styles_endpoint_lists_all_registered() throws Exception {
        mvc.perform(get("/api/retrieval/summary-styles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='BRIEF')]").exists())
                .andExpect(jsonPath("$[?(@.id=='CONTRIBUTIONS')]").exists())
                .andExpect(jsonPath("$[?(@.id=='ACTION_ITEMS')]").exists());
    }

    @Test
    void summarize_returns_503_when_ai_unavailable() throws Exception {
        when(ai.isAvailable()).thenReturn(false);
        mvc.perform(post("/api/retrieval/summarize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("text", "hi", "style", "BRIEF"))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").exists());
        // Never reached the model.
        verify(ai, org.mockito.Mockito.never()).summarize(anyString(), anyInt());
        verify(ai, org.mockito.Mockito.never()).withPrompt(anyString(), anyString(), anyInt());
    }

    @Test
    void summarize_returns_503_when_no_ai_bean_is_wired() throws Exception {
        when(coordinator.aiOperations()).thenReturn(null);
        mvc.perform(post("/api/retrieval/summarize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("text", "hi"))))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void summarize_rejects_missing_text() throws Exception {
        mvc.perform(post("/api/retrieval/summarize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("style", "BRIEF"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("text is required"));
        // The controller MAY call isAvailable() during the availability check
        // before validating the body — that's fine. What matters is no model
        // call was made.
        verify(ai, org.mockito.Mockito.never()).summarize(anyString(), anyInt());
        verify(ai, org.mockito.Mockito.never()).withPrompt(anyString(), anyString(), anyInt());
    }

    @Test
    void summarize_rejects_unknown_style() throws Exception {
        mvc.perform(post("/api/retrieval/summarize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("text", "hi", "style", "NOT_A_STYLE"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("unknown style")));
    }

    @Test
    void summarize_with_style_calls_withPrompt_and_returns_style_in_response() throws Exception {
        when(ai.withPrompt(anyString(), anyString(), anyInt())).thenReturn("fake summary");
        mvc.perform(post("/api/retrieval/summarize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("text", "hi", "style", "BRIEF"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value("fake summary"))
                .andExpect(jsonPath("$.style").value("BRIEF"))
                .andExpect(jsonPath("$.elapsedMs").isNumber());
        verify(ai).withPrompt(any(), any(), anyInt());
    }

    @Test
    void summarize_with_inline_prompt_reports_style_as_CUSTOM() throws Exception {
        when(ai.withPrompt(anyString(), anyString(), anyInt())).thenReturn("custom out");
        mvc.perform(post("/api/retrieval/summarize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "text", "hi",
                                "prompt", "Do a specific thing."))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.style").value("CUSTOM"))
                .andExpect(jsonPath("$.summary").value("custom out"));
    }

    @Test
    void summarize_with_no_style_and_no_prompt_uses_legacy_summarize() throws Exception {
        when(ai.summarize(anyString(), anyInt())).thenReturn("legacy out");
        mvc.perform(post("/api/retrieval/summarize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("text", "hi"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value("legacy out"));
        verify(ai).summarize(anyString(), anyInt());
    }

    @Test
    void summarize_returns_502_when_ai_call_throws() throws Exception {
        when(ai.summarize(anyString(), anyInt())).thenThrow(new RuntimeException("ollama down"));
        mvc.perform(post("/api/retrieval/summarize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("text", "hi"))))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("ollama down")));
    }

    @Test
    void runtime_registered_style_is_visible_via_endpoint() throws Exception {
        SummaryStyles.register("TEST_ROOT_CAUSE", "Find root cause.");
        try {
            mvc.perform(get("/api/retrieval/summary-styles"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.id=='TEST_ROOT_CAUSE')]").exists());
        } finally { SummaryStyles.unregister("TEST_ROOT_CAUSE"); }
    }
}
