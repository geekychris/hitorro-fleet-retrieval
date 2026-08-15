/*
 * Copyright (c) 2006-2026 Chris Collins
 */
package com.hitorro.fleet.retrieval;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Bind {@code hitorro.fleet.retrieval.*} — the surface that toggles the
 * service between standalone and pipeline-fed modes and points at the
 * physical directories it should read from / write to.
 *
 * <p>Defaults reproduce the {@code ~/.hitorro/pipelines/} layout the mesh
 * agents write into, so a pipeline-fed deploy needs no config beyond
 * {@code mode=shared}.</p>
 */
@ConfigurationProperties(prefix = "hitorro.fleet.retrieval")
public class FleetRetrievalProperties {

    public enum Mode { standalone, shared }

    /** Standalone owns its own directories; shared reads pipeline-produced ones. */
    private Mode mode = Mode.standalone;

    /** Language passed to IndexManager and RetrievalConfig unless a query overrides. */
    private String defaultLanguage = "en";

    /** Root of ${hitorro.pipelines.home}-style layout — used for shared mode. */
    private String pipelinesHome = defaultPipelinesHome();

    /** Standalone-mode Lucene root. Ignored in shared mode. */
    private String standaloneLuceneHome = System.getProperty("user.home") + "/.hitorro/fleet-retrieval/lucene";

    /** Standalone-mode KV directory. Ignored in shared mode. */
    private String standaloneKvHome = System.getProperty("user.home") + "/.hitorro/fleet-retrieval/kv";

    /** Enable the KV-fallback DocumentStore in the retrieval pipeline. */
    private boolean kvEnabled = true;

    /** Enable REST-based ingestion (safe to leave on; ingestion only fires when called). */
    private boolean allowIngest = true;

    private static String defaultPipelinesHome() {
        String prop = System.getProperty("hitorro.pipelines.home");
        if (prop != null) return prop;
        String env = System.getenv("HITORRO_PIPELINES_HOME");
        if (env != null) return env;
        return System.getProperty("user.home") + "/.hitorro/pipelines";
    }

    /** Resolved Lucene root — depends on mode. */
    public Path luceneRoot() {
        return mode == Mode.shared
                ? Paths.get(pipelinesHome, "lucene")
                : Paths.get(standaloneLuceneHome);
    }

    /** Resolved KV root — depends on mode. */
    public Path kvRoot() {
        return mode == Mode.shared
                ? Paths.get(pipelinesHome, "kv")
                : Paths.get(standaloneKvHome);
    }

    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }
    public String getDefaultLanguage() { return defaultLanguage; }
    public void setDefaultLanguage(String defaultLanguage) { this.defaultLanguage = defaultLanguage; }
    public String getPipelinesHome() { return pipelinesHome; }
    public void setPipelinesHome(String pipelinesHome) { this.pipelinesHome = pipelinesHome; }
    public String getStandaloneLuceneHome() { return standaloneLuceneHome; }
    public void setStandaloneLuceneHome(String standaloneLuceneHome) { this.standaloneLuceneHome = standaloneLuceneHome; }
    public String getStandaloneKvHome() { return standaloneKvHome; }
    public void setStandaloneKvHome(String standaloneKvHome) { this.standaloneKvHome = standaloneKvHome; }
    public boolean isKvEnabled() { return kvEnabled; }
    public void setKvEnabled(boolean kvEnabled) { this.kvEnabled = kvEnabled; }
    public boolean isAllowIngest() { return allowIngest; }
    public void setAllowIngest(boolean allowIngest) { this.allowIngest = allowIngest; }
}
