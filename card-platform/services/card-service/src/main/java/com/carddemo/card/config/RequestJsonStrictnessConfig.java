package com.carddemo.card.config;

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
 * <p>ADDITIVE. A 3270 terminal delivers fixed-width map fields under a Basic Mapping Support mapset,
 * so a field the map does not declare cannot arrive and a field cannot arrive carrying another type.
 * {@code app/bms/COCRDUP.bms} declares the five fields the card update reads, and
 * {@code CCUP-NEW-CARDDATA} at {@code app/cbl/COCRDUPC.cbl:L307-L313} holds all five as {@code PIC X}
 * items. {@code src/main/resources/openapi.yaml} makes the same two promises for this surface,
 * {@code additionalProperties: false} on {@code CardUpdateRequest} and {@code type: string} on every
 * one of its properties, and a reader left at its defaults keeps neither.
 *
 * <p>Two settings, one for each promise.
 *
 * <ul>
 * <li><b>An undeclared property is refused.</b>
 * {@link DeserializationFeature#FAIL_ON_UNKNOWN_PROPERTIES} is disabled by default, so a body
 * carrying an extra member bound and was written. Three cases show what that costs. A body naming
 * {@code cardNumber} was accepted and ignored, although the record declares no such component and the
 * path is the one place the card is named, so a caller could believe it had addressed one card while
 * the update rewrote another. A body naming {@code cardVerificationValue} was accepted and ignored,
 * although {@code entity/CardEntity#applyUpdate} changes that column never and
 * {@code app/bms/COCRDUP.bms} declares no field for it. A misspelled {@code activeStatus} was dropped
 * and then refused as absent, naming a field the caller believes it sent.</li>
 * <li><b>A property declared as text is refused when it arrives as a number or a boolean.</b> A
 * leading zero is part of the value in {@code expiryMonth} and {@code expiryDay}, both of which are
 * two characters at {@code app/cbl/COCRDUPC.cbl:L311-L312}, and a JSON number loses it: {@code 07}
 * arrives as {@code 7}, which the two-digit constraint then refuses. {@code activeStatus} holds
 * {@code 'Y'} or {@code 'N'} from {@code 88 FLG-YES-NO-VALID} at {@code app/cbl/COCRDUPC.cbl:L91},
 * and a JSON boolean is not one of those two characters. The rule is set for every textual target
 * rather than for those three properties.</li>
 * </ul>
 *
 * <p>Each refusal reaches the caller as {@code 400} carrying
 * {@code api/CardApiExceptionHandler.UNREADABLE_BODY_MESSAGE}, the answer this service already gives
 * a body it could not read. The parser message names the position it stopped at and quotes the
 * characters around it, and those characters can be a cardholder name, so no part of it reaches the
 * response.
 *
 * <p>Neither setting is a second validator. {@code domain/CardUpdateService} stays the one validator,
 * running the field edits in the order {@code app/cbl/COCRDUPC.cbl:L698-L708} performs them and
 * stopping at the first failure. These two settings act before binding completes, so no constraint
 * runs and no second message competes with the one {@code WS-RETURN-MSG PIC X(75)} at
 * {@code app/cbl/COCRDUPC.cbl:L173} carries.
 *
 * <p>This bean customizes the mapper of the web layer alone. Every event this service publishes is
 * written by the mapper {@code libs/event-contracts} builds, which disables the same unknown-property
 * check deliberately: a consumer has to tolerate a property a later schema version added, which is
 * the backward-compatibility guarantee the platform makes. The two mappers are separate instances, so
 * this class cannot narrow that guarantee.
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
