package com.carddemo.account.config;

import com.carddemo.events.serde.EventWireBounds;
import org.springframework.boot.jackson.autoconfigure.JsonFactoryBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Applies the platform stream constraints to JSON requests that declare no content length.
 */
@Configuration
public class JsonReadCeilingConfig {

    /**
     * Runs last so another property cannot silently widen the platform ceiling.
     *
     * @return customizer applying document, string, number, depth and name bounds
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    public JsonFactoryBuilderCustomizer requestBodyReadConstraints() {
        return builder -> builder.streamReadConstraints(EventWireBounds.streamReadConstraints());
    }
}
