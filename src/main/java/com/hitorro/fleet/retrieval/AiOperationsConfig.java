/*
 * Copyright (c) 2006-2026 Chris Collins
 */
package com.hitorro.fleet.retrieval;

import com.hitorro.jsontypesystem.datamapper.AIOperations;
import com.hitorro.jsontypesystem.datamapper.HttpOllamaClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Wires an {@link AIOperations} bean when
 * {@code hitorro.fleet.retrieval.ollama.enabled=true} (the default).
 *
 * <p>The bean powers two things:</p>
 * <ol>
 *   <li>The {@code summarize} stage on {@code /api/retrieval/execute}
 *       requests — {@link com.hitorro.retrieval.pipeline.stages.SummarizationRetriever}
 *       reads the caller's {@code summarize.style} / {@code summarize.prompt}
 *       and dispatches to {@link AIOperations#withPrompt}.</li>
 *   <li>The direct-text endpoint {@code POST /api/retrieval/summarize}
 *       exposed by {@link com.hitorro.fleet.retrieval.api.SummarizeController} —
 *       for callers (analytics, notebooks, ad-hoc scripts) that already
 *       hold assembled content and want the same style catalogue without
 *       going through a search first.</li>
 * </ol>
 *
 * <p>When Ollama isn't reachable the bean's {@code isAvailable()} returns
 * false; downstream code degrades gracefully — the summarize stage
 * silently no-ops and the direct endpoint returns 503.</p>
 */
@Configuration
@ConditionalOnProperty(name = "hitorro.fleet.retrieval.ollama.enabled",
                       havingValue = "true", matchIfMissing = true)
public class AiOperationsConfig {

    private static final Logger log = LoggerFactory.getLogger(AiOperationsConfig.class);

    @Bean
    public AIOperations fleetAiOperations(FleetRetrievalProperties props) {
        FleetRetrievalProperties.Ollama cfg = props.getOllama();
        Duration timeout = Duration.ofSeconds(cfg.getTimeoutSeconds());
        HttpOllamaClient client = new HttpOllamaClient(cfg.getUrl(), cfg.getModel(), timeout);
        boolean up = client.isAvailable();
        log.info("fleet AIOperations: ollama={} model={} timeout={}s available={}",
                cfg.getUrl(), cfg.getModel(), cfg.getTimeoutSeconds(), up);
        if (!up) log.warn("Ollama at {} is unreachable — /api/retrieval/summarize will 503 " +
                          "and the summarize stage will be a no-op until it recovers.", cfg.getUrl());
        return client;
    }
}
