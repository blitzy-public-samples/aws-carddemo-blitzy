/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo.account.config;

import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson deserialization hardening for the Account Management service (Feature F-003).
 *
 * <p>By default Jackson silently <em>coerces</em> loosely typed JSON tokens into the target
 * Java type: a floating-point literal such as {@code -0.9} is truncated into a {@code long},
 * and a quoted string such as {@code "5"} or {@code "194.00"} is parsed into a numeric field.
 * For an account-update contract that is invalid input, not a value to be silently reshaped &mdash;
 * the optimistic-lock {@code version} is a whole number and the monetary fields
 * ({@code currentBalance}, {@code creditLimit}, {@code cashCreditLimit}, {@code currentCycleCredit},
 * {@code currentCycleDebit}) are exact decimals whose scale and range the service validator owns.
 * Accepting a coerced token would let a request bypass the intended type contract and, for the
 * {@code version} token, silently corrupt the optimistic-lock comparison.</p>
 *
 * <p>This configuration therefore tightens Jackson's {@link com.fasterxml.jackson.databind.cfg
 * coercion configuration} so that mistyped numeric tokens are <strong>rejected</strong> at
 * deserialization time rather than coerced:</p>
 * <ul>
 *   <li><strong>{@link LogicalType#Integer}</strong> (the {@code Long version} token) &mdash;
 *       a {@link CoercionInputShape#Float} input (e.g. {@code 5.0}, {@code -0.9}) and a
 *       {@link CoercionInputShape#String} input (e.g. {@code "5"}) both {@link CoercionAction#Fail}.
 *       Only a genuine JSON integer is accepted.</li>
 *   <li><strong>{@link LogicalType#Float}</strong> (the {@code BigDecimal} money fields) &mdash;
 *       a {@link CoercionInputShape#String} input (e.g. {@code "194.00"}) {@link CoercionAction#Fail}.
 *       Genuine JSON numbers &mdash; whether written with a fraction ({@code 194.00}) or without
 *       ({@code 194}) &mdash; remain acceptable, so legitimate money values still deserialize.</li>
 * </ul>
 *
 * <p>A rejected coercion surfaces as a {@code com.fasterxml.jackson.databind.exc.MismatchedInputException}
 * that Spring MVC wraps in an {@code HttpMessageNotReadableException}; {@code GlobalExceptionHandler}
 * translates that into a sanitized {@code 400 Bad Request} whose body never echoes the offending
 * token (AAP &sect;0.6.6). The scope of this policy is intentionally global but narrow in effect:
 * the only integer body token in the API is {@code version} and the only float body tokens are the
 * five money fields, so no other endpoint contract is affected.</p>
 *
 * <p>The customizer runs as a {@code postConfigurer} on the Spring Boot
 * {@link org.springframework.http.converter.json.Jackson2ObjectMapperBuilder}, so it augments &mdash;
 * rather than replaces &mdash; Boot's auto-configured {@code ObjectMapper}, preserving the
 * {@code write-bigdecimal-as-plain} output setting declared in {@code application.yml} and the
 * auto-registered JSR-310 date handling.</p>
 */
@Configuration
public class JacksonConfig {

    /**
     * Registers a {@link Jackson2ObjectMapperBuilderCustomizer} that fails, rather than coerces,
     * mistyped numeric JSON tokens (fractional or string {@code version}; string money values).
     *
     * @return a customizer applied to Boot's auto-configured {@code ObjectMapper}
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer strictNumericCoercionCustomizer() {
        return builder -> builder.postConfigurer(mapper -> {
            // Integer-typed targets (the Long `version`): reject fractional and quoted-string tokens.
            mapper.coercionConfigFor(LogicalType.Integer)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.String, CoercionAction.Fail);
            // Float-typed targets (BigDecimal money): reject quoted-string tokens; numeric stays valid.
            mapper.coercionConfigFor(LogicalType.Float)
                    .setCoercion(CoercionInputShape.String, CoercionAction.Fail);
        });
    }
}
