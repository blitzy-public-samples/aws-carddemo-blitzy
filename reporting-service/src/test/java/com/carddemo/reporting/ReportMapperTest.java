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
package com.carddemo.reporting;

import com.carddemo.common.constant.Titles;
import com.carddemo.common.dto.ReportRequestDto;
import com.carddemo.common.dto.ReportResponseDto;
import com.carddemo.reporting.mapper.ReportMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * :purpose: Unit tests for {@link ReportMapper}, which echoes the CORPT00
 *  report-request screen fields onto the response and populates the screen header
 *  exactly as ``CORPT00C`` ``3000-SEND-MAP`` (L611-628) does.
 * :output: Verifies the selector and date-part echo, the two ``COTTL01Y`` titles,
 *  the ``CR00`` transaction name, the ``CORPT00C`` program name, and the
 *  ``CSDAT01Y`` ``mm/dd/yy`` and ``hh:mm:ss`` date and time layouts.
 */
class ReportMapperTest {

    /** :purpose: Fixed instant (2024-03-09 14:07:05 UTC) for a deterministic header. */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2024-03-09T14:07:05Z"), ZoneId.of("UTC"));

    /** :purpose: System under test bound to the fixed clock. */
    private final ReportMapper mapper = new ReportMapper(FIXED_CLOCK);

    /**
     * :purpose: A mapped response carries the two application titles from
     *  ``COTTL01Y`` verbatim, including their forty-character padding.
     */
    @Test
    @DisplayName("titles are the COTTL01Y literals verbatim")
    void titlesAreTheLegacyLiterals() {
        ReportResponseDto response = mapper.toResponse(new ReportRequestDto());

        assertThat(response.getTitle01()).isEqualTo(Titles.CCDA_TITLE01);
        assertThat(response.getTitle02()).isEqualTo(Titles.CCDA_TITLE02);
        assertThat(response.getTitle01()).hasSize(40);
        assertThat(response.getTitle02()).hasSize(40);
    }

    /**
     * :purpose: The transaction and program names are the ``CORPT00C``
     *  ``WS-TRANID``/``WS-PGMNAME`` literals.
     */
    @Test
    @DisplayName("trnName is CR00 and pgmName is CORPT00C")
    void transactionAndProgramNamesArePopulated() {
        ReportResponseDto response = mapper.toResponse(new ReportRequestDto());

        assertThat(response.getTrnName()).isEqualTo("CR00");
        assertThat(response.getPgmName()).isEqualTo("CORPT00C");
    }

    /**
     * :purpose: The header date and time use the ``CSDAT01Y``
     *  ``WS-CURDATE-MM-DD-YY`` and ``WS-CURTIME-HH-MM-SS`` layouts.
     */
    @Test
    @DisplayName("currentDate is mm/dd/yy and currentTime is hh:mm:ss")
    void dateAndTimeUseTheLegacyLayouts() {
        ReportResponseDto response = mapper.toResponse(new ReportRequestDto());

        assertThat(response.getCurrentDate()).isEqualTo("03/09/24");
        assertThat(response.getCurrentTime()).isEqualTo("14:07:05");
    }

    /**
     * :purpose: The header can be applied to an existing response, which the
     *  service does on the no-input path where no request DTO is available.
     */
    @Test
    @DisplayName("applyScreenHeader populates an existing response and tolerates null")
    void applyScreenHeaderPopulatesExistingResponse() {
        ReportResponseDto response = new ReportResponseDto();

        mapper.applyScreenHeader(response);
        mapper.applyScreenHeader(null);

        assertThat(response.getTitle01()).isEqualTo(Titles.CCDA_TITLE01);
        assertThat(response.getTrnName()).isEqualTo("CR00");
        assertThat(response.getCurrentDate()).isEqualTo("03/09/24");
    }

    /**
     * :purpose: The selector and custom date-part echo is unchanged by the header
     *  population, and a null request still maps to a null response.
     */
    @Test
    @DisplayName("selector and date-part echo is preserved; null request maps to null")
    void echoIsPreserved() {
        ReportRequestDto request = new ReportRequestDto();
        request.setCustom("Y");
        request.setStartDateMonth("03");
        request.setStartDateDay("01");
        request.setStartDateYear("2024");
        request.setEndDateMonth("03");
        request.setEndDateDay("31");
        request.setEndDateYear("2024");

        ReportResponseDto response = mapper.toResponse(request);

        assertThat(response.getCustom()).isEqualTo("Y");
        assertThat(response.getStartDateMonth()).isEqualTo("03");
        assertThat(response.getEndDateYear()).isEqualTo("2024");
        assertThat(mapper.toResponse(null)).isNull();
    }
}
