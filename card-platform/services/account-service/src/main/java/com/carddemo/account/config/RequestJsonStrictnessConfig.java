package com.carddemo.account.config;

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
 * {@code app/cbl/COACTUPC.cbl:L1205-L1280} reads every submitted value out of
 * {@code ACUP-NEW-DETAILS}, whose components are all {@code PIC X}, and its edits convert text.
 * {@code src/main/resources/openapi.yaml} makes the same two promises for this surface —
 * {@code additionalProperties: false} on {@code AccountUpdateRequest}, {@code AccountDataRequest} and
 * {@code CustomerDataRequest}, and {@code type: string} on every property of all three — and a reader
 * left at its defaults keeps neither.
 *
 * <p>Two settings, one for each promise.
 *
 * <ul>
 * <li><b>An undeclared property is refused.</b>
 * {@link DeserializationFeature#FAIL_ON_UNKNOWN_PROPERTIES} is disabled by default, so a body
 * carrying an extra member bound and was written. Three cases show what that costs. A body naming
 * {@code accountId} was accepted and ignored, although the record declares no such component and the
 * path is the one place the identifier is named. A misspelled {@code groupId} carries no edit, so the
 * misspelling was dropped and the column kept its stored value with nothing said about it. A
 * misspelled mandatory component was dropped and then refused as absent, naming a field the caller
 * believes it sent.</li>
 * <li><b>A property declared as text is refused when it arrives as a number or a boolean.</b> The
 * platform rule is that money travels as a decimal string and never as a JSON number, because a
 * reader binds a JSON number through a binary floating-point type on the way to a string and the
 * whole correctness argument of this platform rests on fixed-point arithmetic with truncation
 * pinned. Five amounts of {@code AccountDataRequest} are the properties that matter, and the rule is
 * set for every textual target rather than for those five. A leading zero is part of the value in
 * {@code customerId}, in {@code eftAccountId} and in all three Social Security parts, and a JSON
 * number loses it. Each date arrives as eight characters, which a JSON number renders without its
 * leading zero for a month before October. {@code activeStatus} and
 * {@code primaryCardHolderIndicator} hold {@code 'Y'} or {@code 'N'} from
 * {@code 88 FLG-YES-NO-ISVALID} at {@code app/cbl/COACTUPC.cbl:L78}, and a JSON boolean is not one of
 * those two characters.</li>
 * </ul>
 *
 * <p>Each refusal reaches the caller as {@code 400} carrying
 * {@code api/ApiProblem.MALFORMED_REQUEST_DETAIL}, the answer this service already gives a body it
 * could not read, through {@code api/AccountApiExceptionHandler.onUnreadableBody}. The parser message
 * names the position it stopped at and quotes the characters around it, and those characters can be a
 * Social Security number or a date of birth, so no part of it reaches the response.
 *
 * <p>Neither setting is a second validator. {@code AccountUpdateRequest} declares no bean constraint
 * and no cascade marker, and {@code domain/AccountUpdateService.editMapInputs} stays the one
 * validator, running the field edits in source order and stopping at the first failure. These two
 * settings act before binding completes, so no {@code domain/validation/EditResult} is produced and
 * no second message competes with the one {@code WS-RETURN-MSG PIC X(75)} at
 * {@code app/cbl/COACTUPC.cbl:L479} carries.
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
