/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Per-operation JSON serialization contract test for {@link UserDeleteRequest}, the request DTO of
 * the CardDemo <em>Delete User</em> screen (BMS map {@code COUSR03} / mapset {@code COUSR3A},
 * transaction {@code CU03}, program {@code COUSR03C}).
 *
 * <p><strong>Why this test exists (finding API-1).</strong> The migration contract requires the
 * Java DTO and the published OpenAPI document to describe <em>one</em> BMS-grounded canonical JSON
 * contract so that generated clients bind reliably (AAP &sect;0.9.2 field-contract parity; &sect;0.7.1
 * hotspot H2 &mdash; a dropped PF-key path is a behavioral regression). The runtime wire shape is
 * produced by Jackson from this record; the static {@code openapi.yaml} {@code UserDeleteRequest}
 * schema documents it. Because nothing in the build or runtime cross-checks the two (the static
 * OpenAPI file is served verbatim), this test is the guard that proves and locks the agreement:
 * <ul>
 *   <li>the request serializes to exactly two properties, {@code userId} and {@code action}
 *       &mdash; matching the OpenAPI {@code UserDeleteRequest} schema property set;</li>
 *   <li>the PF-key field is named {@code action} (the canonical name used by every online request
 *       DTO in this package via the shared {@link PfKeyAction}), <em>never</em> {@code pfKey};</li>
 *   <li>{@code action} serializes as its enum constant name (e.g. {@code PF5}, {@code ENTER}),
 *       matching the OpenAPI {@code PfKeyAction} string enum;</li>
 *   <li>a client request shaped exactly like the OpenAPI schema deserializes back into the record
 *       for every PF key the Delete User screen supports (ENTER=Fetch, PF3=Back, PF4=Clear,
 *       PF5=Delete).</li>
 * </ul>
 *
 * <p>The {@link ObjectMapper} under test uses Jackson's default property naming and enum handling
 * (which produce the {@code userId}/{@code action} names and enum-constant strings asserted here)
 * plus {@link JsonInclude.Include#NON_NULL} inclusion, matching the application's REST mapper as
 * declared in {@code application.yml} ({@code spring.jackson.default-property-inclusion: non_null})
 * and reinforced by {@code JacksonConfig}. The {@code BigDecimal}/date features of that config are
 * irrelevant here because this DTO carries neither a monetary nor a temporal field. The test needs
 * no database, servlet, or Spring context, so it runs as a fast, deterministic unit test (mirroring
 * {@code JacksonConfigTest}).
 */
class UserDeleteRequestSerializationTest {

    /** An example user id used throughout, matching the OpenAPI {@code UserDeleteRequest} example. */
    private static final String USER_ID = "USER0001";

    /**
     * Builds an {@code ObjectMapper} configured like the application's REST mapper for the properties
     * this contract depends on: Jackson-default property naming and enum-as-name, plus
     * {@link JsonInclude.Include#NON_NULL} inclusion (the application's declared inclusion policy).
     *
     * @return an {@code ObjectMapper} whose wire behavior matches the application's REST boundary
     */
    private static ObjectMapper appMapper() {
        return new Jackson2ObjectMapperBuilder()
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .build();
    }

    // -------------------------------------------------------------------------
    // Serialization: the canonical wire shape
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("serializes to exactly {userId, action} — the canonical name is 'action', never 'pfKey'")
    void serializes_toCanonicalUserIdAndActionProperties() throws Exception {
        ObjectMapper mapper = appMapper();

        JsonNode json = mapper.readTree(
                mapper.writeValueAsString(new UserDeleteRequest(USER_ID, PfKeyAction.PF5)));

        // Property set is exactly the OpenAPI UserDeleteRequest schema's properties.
        assertThat(json.size()).isEqualTo(2);
        assertThat(json.has("userId")).isTrue();
        assertThat(json.has("action")).isTrue();

        // Values are correct and the PF-key field is the canonical 'action', not 'pfKey'.
        assertThat(json.get("userId").asText()).isEqualTo(USER_ID);
        assertThat(json.get("action").asText()).isEqualTo("PF5");
        assertThat(json.has("pfKey")).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = PfKeyAction.class, names = {"ENTER", "PF3", "PF4", "PF5"})
    @DisplayName("action serializes as its enum constant name for every key the Delete User screen supports")
    void action_serializesAsEnumConstantName(PfKeyAction key) throws Exception {
        ObjectMapper mapper = appMapper();

        JsonNode json = mapper.readTree(
                mapper.writeValueAsString(new UserDeleteRequest(USER_ID, key)));

        assertThat(json.get("action").asText()).isEqualTo(key.name());
    }

    @Test
    @DisplayName("a null action is omitted (NON_NULL), leaving only the required userId")
    void nullAction_isOmittedUnderNonNullInclusion() throws Exception {
        ObjectMapper mapper = appMapper();

        JsonNode json = mapper.readTree(
                mapper.writeValueAsString(new UserDeleteRequest(USER_ID, null)));

        assertThat(json.get("userId").asText()).isEqualTo(USER_ID);
        assertThat(json.has("action")).isFalse();
    }

    // -------------------------------------------------------------------------
    // Deserialization: OpenAPI-shaped client requests bind into the record
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("deserializes an OpenAPI-shaped request body into the record")
    void deserializes_fromOpenApiShapedBody() throws Exception {
        ObjectMapper mapper = appMapper();

        UserDeleteRequest request = mapper.readValue(
                "{\"userId\":\"" + USER_ID + "\",\"action\":\"PF5\"}", UserDeleteRequest.class);

        assertThat(request.userId()).isEqualTo(USER_ID);
        assertThat(request.action()).isEqualTo(PfKeyAction.PF5);
    }

    @ParameterizedTest
    @EnumSource(value = PfKeyAction.class, names = {"ENTER", "PF3", "PF4", "PF5"})
    @DisplayName("round-trips (serialize then deserialize) to an equal value for every supported key")
    void roundTrips_forEverySupportedKey(PfKeyAction key) throws Exception {
        ObjectMapper mapper = appMapper();
        UserDeleteRequest original = new UserDeleteRequest(USER_ID, key);

        UserDeleteRequest roundTripped =
                mapper.readValue(mapper.writeValueAsString(original), UserDeleteRequest.class);

        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    @DisplayName("deserializes a body with only the required userId (action absent) to a null action")
    void deserializes_bodyWithoutAction() throws Exception {
        ObjectMapper mapper = appMapper();

        UserDeleteRequest request =
                mapper.readValue("{\"userId\":\"" + USER_ID + "\"}", UserDeleteRequest.class);

        assertThat(request.userId()).isEqualTo(USER_ID);
        assertThat(request.action()).isNull();
    }
}
