package com.carddemo.card.config;

import com.carddemo.events.serde.EventWireBounds;
import org.springframework.boot.jackson.autoconfigure.JsonFactoryBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Applies the platform stream constraints to chunked JSON requests.
 */
@Configuration
public class JsonReadCeilingConfig {

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    public JsonFactoryBuilderCustomizer requestBodyReadConstraints() {
        return builder -> builder.streamReadConstraints(EventWireBounds.streamReadConstraints());
    }
}
