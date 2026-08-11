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
package com.carddemo.common.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * :purpose: Pin the declared-width bounds on the card update request, so a value too long for its
 *   column is refused at the edge as a field error.
 * :note: These bounds exist because an unbounded value reached the database and the constraint
 *   violation it raised surfaced to the caller as a ``500`` data-access error -- a client mistake
 *   reported as a server fault, on a write path with nothing guarding it. Every fixed-width field
 *   of the card record is covered, new-value and display-time snapshot alike, because the snapshot
 *   half is bound from the same request body and reaches the same columns.
 */
class CardUpdateRequestDtoTest {

    /** :purpose: Validator factory for the bean-validation constraints under test. */
    private static ValidatorFactory factory;

    /** :purpose: The validator applied to each request instance. */
    private static Validator validator;

    /**
     * :purpose: Build the validator once for the whole class.
     */
    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    /**
     * :purpose: Release the validator factory.
     */
    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    /**
     * :purpose: An embossed name one character past ``CARD-EMBOSSED-NAME PIC X(50)`` /
     *   ``VARCHAR(50)`` must be reported as a field error rather than reaching the column.
     */
    @Test
    @DisplayName("a 51-character embossed name is a field error, not a database failure")
    void overLongEmbossedNameIsAFieldError() {
        CardUpdateRequestDto request = new CardUpdateRequestDto();
        request.setCardEmbossedName("A".repeat(51));

        Set<ConstraintViolation<CardUpdateRequestDto>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anySatisfy(violation -> {
            assertThat(violation.getPropertyPath()).hasToString("cardEmbossedName");
            assertThat(violation.getMessage())
                    .isEqualTo("Card Embossed Name must be at most 50 characters");
        });
    }

    /**
     * :purpose: The boundary value itself is legal, so the bound refuses only what the column
     *   genuinely cannot hold.
     */
    @Test
    @DisplayName("a 50-character embossed name is accepted")
    void embossedNameAtTheBoundaryIsAccepted() {
        CardUpdateRequestDto request = new CardUpdateRequestDto();
        request.setCardEmbossedName("A".repeat(50));

        assertThat(validator.validate(request)).isEmpty();
    }

    /**
     * :purpose: Every fixed-width field of the card record is bounded, in both its new-value and
     *   its display-time snapshot form, so no single unbounded field is left to reach a column.
     * :param property: the request property under test.
     * :param value: a value one character past that property's declared width.
     */
    @ParameterizedTest(name = "[{index}] {0} is bounded")
    @CsvSource({
            "cardEmbossedName,51",
            "oldCardEmbossedName,51",
            "cardActiveStatus,2",
            "oldCardActiveStatus,2",
            "cardExpiraionDate,11",
            "oldCardExpiraionDate,11"
    })
    void everyFixedWidthFieldIsBounded(String property, int overLongLength) {
        CardUpdateRequestDto request = new CardUpdateRequestDto();
        String tooLong = "A".repeat(overLongLength);
        switch (property) {
            case "cardEmbossedName" -> request.setCardEmbossedName(tooLong);
            case "oldCardEmbossedName" -> request.setOldCardEmbossedName(tooLong);
            case "cardActiveStatus" -> request.setCardActiveStatus(tooLong);
            case "oldCardActiveStatus" -> request.setOldCardActiveStatus(tooLong);
            case "cardExpiraionDate" -> request.setCardExpiraionDate(tooLong);
            case "oldCardExpiraionDate" -> request.setOldCardExpiraionDate(tooLong);
            default -> throw new IllegalArgumentException("unmapped property " + property);
        }

        Set<ConstraintViolation<CardUpdateRequestDto>> violations = validator.validate(request);

        assertThat(violations)
                .as("%s must be bounded at its declared width", property)
                .anySatisfy(violation ->
                        assertThat(violation.getPropertyPath()).hasToString(property));
    }

    /**
     * :purpose: A request carrying only legal values raises nothing, so the added bounds do not
     *   reject an ordinary submission.
     */
    @Test
    @DisplayName("an ordinary submission raises no violation")
    void ordinarySubmissionIsAccepted() {
        CardUpdateRequestDto request = new CardUpdateRequestDto();
        request.setCardEmbossedName("JOHN SMITH");
        request.setCardActiveStatus("Y");
        request.setCardExpiraionDate("2030-05-20");
        request.setCardCvvCd("123");

        assertThat(validator.validate(request)).isEmpty();
    }
}
