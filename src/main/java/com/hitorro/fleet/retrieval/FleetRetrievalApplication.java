/*
 * Copyright (c) 2006-2026 Chris Collins
 */
package com.hitorro.fleet.retrieval;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * hitorro-fleet-retrieval — first member of the hitorro-fleet family of
 * Spring Boot runtimes that deploy onto Orion mesh or Kubernetes.
 *
 * <p>Hosts the full hitorro retrieval coordination runtime
 * ({@code RetrievalPipelineBuilder}: Index → Document → Fixup →
 * Pagination → Facet → Summarization) with SearchSummary / Facet /
 * Summarization aggregates and multi-index merging.</p>
 *
 * <p>Dual mode selected via {@code hitorro.fleet.retrieval.mode}:</p>
 * <ul>
 *   <li>{@code standalone} — service owns Lucene + KV; ingest via REST.</li>
 *   <li>{@code shared} — reads pipeline-produced Lucene from
 *       {@code ${hitorro.pipelines.home}/lucene/*} and KV from
 *       {@code ${hitorro.pipelines.home}/kv/*}. Mesh writes, retrieval reads.</li>
 * </ul>
 */
@SpringBootApplication
@EnableConfigurationProperties(FleetRetrievalProperties.class)
public class FleetRetrievalApplication {
    public static void main(String[] args) {
        SpringApplication.run(FleetRetrievalApplication.class, args);
    }
}
