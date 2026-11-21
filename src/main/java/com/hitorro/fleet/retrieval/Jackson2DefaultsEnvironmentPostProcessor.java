/*
 * Copyright (c) 2006-2025 Chris Collins
 */
package com.hitorro.fleet.retrieval;

import org.apache.commons.logging.Log;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * The Spring settings that keep this app's JSON in Jackson 2's format (see {@link Jackson2CompatConfig}),
 * as the lowest-precedence property source. They are in application.yml too, but a deployment that starts
 * the app with {@code --spring.config.location} (the hitorro-mesh Helm chart does) replaces that file, and
 * without them the app writes Jackson 3's format. Any configuration or environment variable still
 * overrides them.
 *
 * <p>One setting is corrected instead: {@code spring.http.converters.preferred-json-mapper=jackson2}, which
 * 3.x deployments set (cloud-stack's deploy/mesh overlay and image do) to pick Boot's Jackson 2 mapper. This
 * app has no Jackson 2 mapper, and the value makes Boot skip its Jackson 3 converter, so Spring MVC falls back
 * to a {@code JsonMapper} with Jackson 3's defaults and none of the settings above. It is mapped to
 * {@code jackson}, with a warning.
 */
public class Jackson2DefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String SOURCE_NAME = "hitorroJackson2Defaults";
    static final String OVERRIDE_NAME = "hitorroJackson2Overrides";
    static final String PREFERRED = "spring.http.converters.preferred-json-mapper";

    private final Log log;

    public Jackson2DefaultsEnvironmentPostProcessor(DeferredLogFactory logs) {
        this.log = logs.getLog(Jackson2DefaultsEnvironmentPostProcessor.class);
    }

    static final Map<String, Object> DEFAULTS = Map.ofEntries(
            Map.entry("spring.jackson.use-jackson2-defaults", "true"),
            Map.entry("spring.jackson.factory.constraints.read.max-nesting-depth", "1000"),
            Map.entry("spring.jackson.factory.constraints.read.max-string-length", "20000000"),
            Map.entry("spring.jackson.factory.constraints.write.max-nesting-depth", "1000"),
            Map.entry("management.endpoints.jackson.isolated-json-mapper", "false"),
            Map.entry("spring.jackson.default-property-inclusion", "non_null"));

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getPropertySources().contains(SOURCE_NAME)) {
            environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, DEFAULTS));
        }
        if ("jackson2".equalsIgnoreCase(environment.getProperty(PREFERRED, "").trim())
                && !environment.getPropertySources().contains(OVERRIDE_NAME)) {
            log.warn(PREFERRED + "=jackson2 is a 3.x setting (this app has no Jackson 2 mapper); using jackson."
                    + " Remove it from the deployment (env SPRING_HTTP_CONVERTERS_PREFERREDJSONMAPPER).");
            environment.getPropertySources().addFirst(new MapPropertySource(OVERRIDE_NAME, Map.of(PREFERRED, "jackson")));
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;   // after config data, so addLast is really last
    }
}
