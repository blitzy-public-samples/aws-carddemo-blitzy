package com.carddemo.authorization.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

/**
 * Holds the request reader to the property set and the property types the published contract
 * declares.
 *
 * <p>ADDITIVE. A 3270 terminal delivers fixed-width map fields under a Basic Mapping Support
 * mapset, so a field the map does not declare cannot arrive and a field cannot arrive carrying
 * another type. {@code src/main/resources/openapi.yaml} makes the same two promises for this
 * surface — {@code additionalProperties: false} on {@code AuthorizationRequest} and
 * {@code type: string} on every property of it — and a reader left at its defaults keeps neither.
 *
 * <p>Two settings, one for each promise.
 *
 * <ul>
 * <li><b>An undeclared property is refused.</b> {@link DeserializationFeature#FAIL_ON_UNKNOWN_PROPERTIES}
 * is disabled by default, so a body carrying an extra member bound and authorized. A caller that
 * misspells {@code cardNumber} would then have its card number ignored and the account branch taken
 * instead, and the response would say nothing about it. The sibling rule of the same schema,
 * {@code not: required [transactionId]}, is enforced by {@code api/AuthorizationRequest}, so this
 * setting is what makes the whole schema hold rather than half of it.</li>
 * <li><b>A property declared as text is refused when it arrives as a number or a boolean.</b> The
 * platform rule is that money travels as a decimal string and never as a JSON number, because a
 * reader binds a JSON number through a binary floating-point type on the way to a string and the
 * whole correctness argument of this platform rests on fixed-point arithmetic with truncation
 * pinned. {@code amount} is the property that matters, and the rule is set for every textual target
 * rather than for that one property: {@code merchantId}, both timestamps and both identifiers are
 * declared as text for the same reason, which is that a leading zero is part of the value.</li>
 * </ul>
 *
 * <p>Each refusal reaches the caller as {@code 400} carrying
 * {@code api/GlobalExceptionHandler.UNREADABLE_BODY_MESSAGE}, the answer this service already gives
 * a body it could not read. The parser message names the position it stopped at and quotes the
 * characters around it, and those characters can be a card number, so no part of it reaches the
 * response.
 *
 * <p>This bean customizes the mapper of the web layer alone. Every event this service publishes or
 * consumes is read by the mapper {@code libs/event-contracts} builds, which disables the same
 * unknown-property check deliberately: a consumer has to tolerate a property a later schema version
 * added, which is the backward-compatibility guarantee the platform makes. The two mappers are
 * separate instances, so this class cannot narrow that guarantee.
 *
 * <p>The customizer runs last, so a deployment that sets a {@code spring.jackson} property cannot
 * silently relax either setting. {@link JsonReadCeilingConfig} bounds the same reader by size.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@Configuration
public class RequestJsonStrictnessConfig {

    /**
     * Applies the two strictness settings to the web layer's JSON mapper.
     *
     * @return the customizer the framework applies while it builds that mapper
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    public JsonMapperBuilderCustomizer requestBodyStrictness() {
        return builder -> builder
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .withCoercionConfig(LogicalType.Textual, config -> config
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }
}
