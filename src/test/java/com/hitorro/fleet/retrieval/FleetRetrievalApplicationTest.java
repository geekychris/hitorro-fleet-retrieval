/*
 * Copyright (c) 2006-2026 Chris Collins
 */
package com.hitorro.fleet.retrieval;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Starts the whole application offline (standalone mode, Lucene and KV under a
 * temporary directory, Ollama disabled) and pins the Spring Boot 4 / Jackson 3
 * setup: a controller returning a {@code JsonNode} must write it as plain JSON.
 */
@SpringBootTest(properties = {
        "hitorro.fleet.retrieval.mode=standalone",
        "hitorro.fleet.retrieval.ollama.enabled=false",
})
@AutoConfigureMockMvc
class FleetRetrievalApplicationTest {

    private static final Path TMP;
    static {
        try {
            TMP = Files.createTempDirectory("fleet-retrieval-test");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("hitorro.fleet.retrieval.standalone-lucene-home", () -> TMP.resolve("lucene").toString());
        registry.add("hitorro.fleet.retrieval.standalone-kv-home", () -> TMP.resolve("kv").toString());
        registry.add("hitorro.fleet.retrieval.pipelines-home", () -> TMP.resolve("pipelines").toString());
    }

    private final ObjectMapper json = com.hitorro.util.json.JsonMappers.json();

    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;
    @Autowired FleetKvService kv;

    @Test
    void contextLoads() throws Exception {
        assertThat(context.getBean(FleetCoordinatorService.class)).isNotNull();
        mvc.perform(get("/api/retrieval/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.mode").value("standalone"));
    }

    @Test
    void springMvcUsesJackson3() {
        // 4.0.0: HiTorro hands out Jackson 3 nodes, so Spring MVC uses Boot's Jackson 3 converter
        // (configured for Jackson 2's format, see Jackson2CompatConfig); the jackson2 bridge is gone.
        assertThat(context.getBean(RequestMappingHandlerAdapter.class).getMessageConverters())
                .anyMatch(c -> c instanceof JacksonJsonHttpMessageConverter)
                .noneMatch(c -> c.getClass().getName().contains("Jackson2"));
    }

    @Test
    void jsonNodeResponseIsPlainJson() throws Exception {
        String doc = "{\"id\":\"doc-1\",\"title\":\"Hello\",\"tags\":[\"a\",\"b\"],\"meta\":{\"n\":3,\"ok\":true}}";
        JsonNode stored = json.readTree(doc);
        var store = kv.openOrCreate("jsontest");
        assertThat(store).isNotNull();
        store.put("doc-1", stored);

        String body = mvc.perform(get("/api/retrieval/documents/jsontest/doc-1"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.title").value("Hello"))
                .andExpect(jsonPath("$.tags[1]").value("b"))
                .andExpect(jsonPath("$.meta.n").value(3))
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(body)).isEqualTo(stored);
        assertThat(body).doesNotContain("nodeType").doesNotContain("containerNode");
    }

    /**
     * The KV scan writes one NDJSON line per entry with the app's mapper, also for a value nested
     * 600 levels deep (Jackson 2 allowed 1000; Jackson 3's {@code JsonNode.toString()} throws past 500).
     */
    @Test
    void kvScanWritesDeepValues() throws Exception {
        String deep = "[".repeat(600) + "]".repeat(600);
        var store = kv.openOrCreate("deeptest");
        store.put("k1", json.readTree("{\"id\":\"k1\",\"deep\":" + deep + "}"));
        var started = mvc.perform(get("/api/retrieval/kv/deeptest/scan"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted())
                .andReturn();
        String body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).isEqualTo("{\"key\":\"k1\",\"value\":{\"id\":\"k1\",\"deep\":" + deep + "}}\n");
    }
}
