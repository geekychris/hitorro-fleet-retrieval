/*
 * Copyright (c) 2006-2026 Chris Collins
 */
package com.hitorro.fleet.retrieval.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.hitorro.fleet.retrieval.FleetCoordinatorService;
import com.hitorro.jsontypesystem.datamapper.AIOperations;
import com.hitorro.retrieval.pipeline.stages.SummaryStyles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Direct-text summarization surface. Bypasses the retrieval pipeline —
 * the caller supplies the corpus verbatim and picks a style from the
 * shipped {@link SummaryStyles} catalogue (or an inline
 * {@code prompt}).
 *
 * <p>Powers analytics's Thread summaries + any other fleet member that
 * already holds an assembled body of text (mail thread, notebook
 * scratchpad, log tail, ...). Callers going through
 * {@code /api/retrieval/execute} with a {@code summarize} block do NOT
 * need this endpoint — the pipeline stage already honours the same
 * style/prompt fields.</p>
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code GET  /api/retrieval/summary-styles} — enumerate registered
 *       styles (built-in + runtime-registered via
 *       {@code SummaryStyles.register}).</li>
 *   <li>{@code POST /api/retrieval/summarize} — body:
 *       {@code {text, style?, prompt?, maxWords?}}. Returns
 *       {@code {summary, style, model, elapsedMs}}.</li>
 * </ul>
 *
 * <p>Returns {@code 503 Service Unavailable} when the {@link AIOperations}
 * bean isn't wired or Ollama is unreachable — callers should treat this
 * as "summarization currently disabled" rather than a fatal error.</p>
 */
@RestController
@RequestMapping("/api/retrieval")
public class SummarizeController {

    private static final Logger log = LoggerFactory.getLogger(SummarizeController.class);
    private static final int DEFAULT_MAX_WORDS = 300;

    private final FleetCoordinatorService coordinator;

    public SummarizeController(FleetCoordinatorService coordinator) {
        this.coordinator = coordinator;
    }

    @GetMapping("/summary-styles")
    public List<Map<String, String>> styles() {
        List<Map<String, String>> out = new java.util.ArrayList<>();
        for (String name : SummaryStyles.knownStyles()) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("id", name);
            row.put("prompt", SummaryStyles.promptFor(name));
            out.add(row);
        }
        return out;
    }

    @PostMapping("/summarize")
    public ResponseEntity<Map<String, Object>> summarize(@RequestBody JsonNode body) {
        AIOperations ai = coordinator.aiOperations();
        if (ai == null || !ai.isAvailable()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "AI summarization not available — check " +
                            "hitorro.fleet.retrieval.ollama.enabled and that Ollama is reachable"));
        }

        String text = body.path("text").asText("");
        if (text.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "text is required"));
        }
        String style = body.path("style").asText("").trim();
        String promptOverride = body.hasNonNull("prompt") ? body.get("prompt").asText() : null;
        int maxWords = body.path("maxWords").asInt(DEFAULT_MAX_WORDS);

        // Resolve prompt: explicit `prompt` wins; else look up `style`; else fall
        // back to the legacy summarize(text, maxWords) shortcut.
        String prompt = promptOverride;
        String resolvedStyle = "BRIEF";
        if (prompt == null && !style.isEmpty()) {
            prompt = SummaryStyles.promptFor(style);
            if (prompt == null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "unknown style '" + style + "' — known: " + SummaryStyles.knownStyles()));
            }
            resolvedStyle = style.toUpperCase();
        } else if (prompt != null) {
            resolvedStyle = "CUSTOM";
        }

        long t0 = System.currentTimeMillis();
        String summary;
        try {
            summary = (prompt == null)
                    ? ai.summarize(text, maxWords)
                    : ai.withPrompt(text, prompt, maxWords);
        } catch (Exception e) {
            log.warn("summarize failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "summarize failed: " + e.getMessage()));
        }
        long ms = System.currentTimeMillis() - t0;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", summary == null ? "" : summary);
        out.put("style", resolvedStyle);
        out.put("elapsedMs", ms);
        out.put("charCount", text.length());
        return ResponseEntity.ok(out);
    }
}
