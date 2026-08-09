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

import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * :purpose: Apply {@link LegacyTextNormalizer} to every JSON string value in both
 *     directions, so a text field holds and paints only what a fixed-width ``PIC X(n)``
 *     field on a 3270 device could.
 * :output: A Jackson module registering one string deserializer and one string
 *     serializer.
 * :note: Both directions are registered on purpose. Inbound closes the write path, so
 *     nothing new is stored; outbound covers what reached the database another way — a
 *     Flyway seed script or a fixed-width batch feed — which the inbound path never sees.
 * :note: The rationale for normalising at the JSON boundary rather than field by field,
 *     and for removing FORMAT characters while leaving CONTROL characters alone, is in
 *     ``docs/decision-log.md`` §47.9.
 */
public final class LegacyTextModule extends SimpleModule {

    /** :purpose: Serialization-compatibility marker for the module base class. */
    private static final long serialVersionUID = 1L;

    /**
     * :purpose: Register the string value handlers for both directions.
     */
    public LegacyTextModule() {
        super("carddemo-legacy-text");
        addDeserializer(String.class, new NormalizingStringDeserializer());
        addSerializer(String.class, new NormalizingStringSerializer());
    }

    /**
     * :purpose: Read a JSON string and normalise it before anything else sees it, so bean
     *     validation, the service edits and the persisted row all agree on one form.
     */
    private static final class NormalizingStringDeserializer extends ValueDeserializer<String> {

        /**
         * :purpose: Read and normalise the current token's text.
         * :param parser: the active parser.
         * :param context: the active deserialization context.
         * :returns: the normalised string value.
         */
        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) {
            return LegacyTextNormalizer.normalize(parser.getValueAsString());
        }
    }

    /**
     * :purpose: Write a JSON string normalised, so a value that entered the database
     *     outside the write path cannot paint a screen differently from how it is stored.
     */
    private static final class NormalizingStringSerializer extends ValueSerializer<String> {

        /**
         * :purpose: Write the normalised value.
         * :param value: the string being serialized.
         * :param gen: the JSON generator.
         * :param ctxt: the active serialization context.
         */
        @Override
        public void serialize(String value, JsonGenerator gen, SerializationContext ctxt) {
            gen.writeString(LegacyTextNormalizer.normalize(value));
        }
    }
}
