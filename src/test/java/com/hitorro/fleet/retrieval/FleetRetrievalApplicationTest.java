/*
 * Copyright (c) 2006-2026 Chris Collins
 */
package com.hitorro.fleet.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
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
 * temporary directory, Ollama disabled) and pins the Spring Boot 4 / Jackson 2
 * setup: a controller returning a Jackson 2 {@code JsonNode} must write it as
 * plain JSON (Jackson 3 would serialize it as a bean).
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

    private final ObjectMapper json = new ObjectMapper();

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
    void springMvcUsesJackson2() {
        assertThat(context.getBean(RequestMappingHandlerAdapter.class).getMessageConverters())
                .anyMatch(c -> c instanceof MappingJackson2HttpMessageConverter);
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
}
