package com.carddemo.authorization.config;

import com.carddemo.events.serde.EventWireBounds;
import org.springframework.boot.jackson.autoconfigure.JsonFactoryBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Bounds what the request parser of the web layer will read.
 *
 * <p>ADDITIVE. A 3270 request carries fixed-width fields and a sized Communication Area, so the
 * source has no unbounded input to bound. A JavaScript Object Notation (JSON) request body has no
 * inherent bound at all.
 *
 * <p>{@link RequestBodyCeilingFilter} refuses a request that declares an oversized body, and this
 * class covers the request that declares no length. A chunked request reaches the parser with no
 * {@code Content-Length} to check, and the document-length limit below is what stops the parser
 * building it. The two together are the ceiling: one refuses before the stream is touched, the other
 * refuses while it is read.
 *
 * <p>{@link EventWireBounds#streamReadConstraints()} supplies the limits, so the request surface and
 * the event surface are bounded by one set of numbers. That set already bounds document length,
 * string length, number length, nesting depth and name length, and
 * {@code libs/event-contracts} owns it.
 *
 * <p>The customizer runs last, so a deployment that sets a {@code spring.jackson.read} property does
 * not silently widen the ceiling.
 *
 * <p>Decisions: {@code card-platform/docs/decision-log.md}.
 */
@Configuration
public class JsonReadCeilingConfig {

    /**
     * Applies the platform parser limits to the web layer's JSON factory.
     *
     * @return the customizer the framework applies while it builds that factory
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    public JsonFactoryBuilderCustomizer requestBodyReadConstraints() {
        return builder -> builder.streamReadConstraints(EventWireBounds.streamReadConstraints());
    }
}
