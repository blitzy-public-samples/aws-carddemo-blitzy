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
package com.carddemo.common.json;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

/**
 * :purpose: Make the JSON boundary reject a scalar of the wrong TYPE for a textual member,
 *     instead of silently converting it.
 * :output: A {@link JsonMapperBuilderCustomizer} that sets the coercion of JSON integer,
 *     floating-point and boolean input into a textual target to {@link CoercionAction#Fail}
 *     for every service's shared mapper.
 * :note: Jackson's default is to coerce any scalar into a ``String``, so ``{"userId":
 *     12345678, "password": 87654321}`` deserialized into the sign-on DTO as the strings
 *     "12345678"/"87654321" and was processed as a normal credential; the same applied to the
 *     ``COTRN02`` add-transaction members, where a numeric ``tranSource`` or ``tranDesc``
 *     reached the confirmation step. Neither is a value the 3270 map could ever have produced
 *     - every one of those fields is a ``PIC X(n)`` character field the operator types - so
 *     accepting it widened the wire contract past the frozen screen contract and made a client
 *     type error look like valid input.
 * :note: The failure is reported by the shared exception handler as the same type-mismatch
 *     error any other wrong-typed member produces, so a caller is told which member was wrong
 *     rather than being handed a framework message.
 * :note: Only NUMBER-into-text and BOOLEAN-into-text are refused. String-into-number is
 *     left at Jackson's default because the wire contract for the money and identifier fields
 *     is deliberately textual in places (zero-padded ``PIC 9(n)`` keys), and ``null``/absent
 *     handling is unchanged, so no existing valid request shape is affected.
 */
@AutoConfiguration
@ConditionalOnClass({ObjectMapper.class, JsonMapperBuilderCustomizer.class})
public class StrictScalarCoercionAutoConfiguration {

    /**
     * :purpose: Contribute the strict textual-coercion policy to the mapper Spring
     *  Boot builds for every CardDemo service.
     * :returns: the customizer that refuses number- and boolean-shaped input for a
     *  textual target.
     */
    @Bean
    JsonMapperBuilderCustomizer strictScalarCoercionCustomizer() {
        return builder -> builder.withCoercionConfig(LogicalType.Textual, config -> config
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }
}
