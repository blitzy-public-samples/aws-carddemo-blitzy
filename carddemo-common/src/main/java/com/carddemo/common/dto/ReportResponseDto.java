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
package com.carddemo.common.dto;

import jakarta.validation.constraints.Size;

/**
 * :purpose: Outbound response DTO for the CORPT00 report-request screen (CICS
 *  transaction ``CR00``, legacy program ``CORPT00C``). Echoes the report-type
 *  selectors and the custom start/end date parts back to the client and adds the
 *  response-only fields the reporting service populates: the error/status message
 *  (``ERRMSG``), the confirmation flag (``CONFIRMO``) and the screen header fields
 *  (titles, transaction name, program name, current date and time). Field widths
 *  preserve the legacy symbolic-map contract (``app/cpy-bms/CORPT00.CPY``).
 * :output: A mutable response carrier whose selector and date-part fields mirror
 *  {@link ReportRequestDto}, extended with the header and status/confirmation
 *  fields rendered on the 3270 map.
 */
public class ReportResponseDto {

    /** :purpose: MONTHLY selector flag echoed from the request (CORPT00 ``MONTHLYO`` PIC X(01)). */
    @Size(max = 1)
    private String monthly;

    /** :purpose: YEARLY selector flag echoed from the request (CORPT00 ``YEARLYO`` PIC X(01)). */
    @Size(max = 1)
    private String yearly;

    /** :purpose: CUSTOM selector flag echoed from the request (CORPT00 ``CUSTOMO`` PIC X(01)). */
    @Size(max = 1)
    private String custom;

    /** :purpose: Custom start-date month echoed from the request (CORPT00 ``SDTMMO`` PIC X(02)). */
    @Size(max = 2)
    private String startDateMonth;

    /** :purpose: Custom start-date day echoed from the request (CORPT00 ``SDTDDO`` PIC X(02)). */
    @Size(max = 2)
    private String startDateDay;

    /** :purpose: Custom start-date year echoed from the request (CORPT00 ``SDTYYYYO`` PIC X(04)). */
    @Size(max = 4)
    private String startDateYear;

    /** :purpose: Custom end-date month echoed from the request (CORPT00 ``EDTMMO`` PIC X(02)). */
    @Size(max = 2)
    private String endDateMonth;

    /** :purpose: Custom end-date day echoed from the request (CORPT00 ``EDTDDO`` PIC X(02)). */
    @Size(max = 2)
    private String endDateDay;

    /** :purpose: Custom end-date year echoed from the request (CORPT00 ``EDTYYYYO`` PIC X(04)). */
    @Size(max = 4)
    private String endDateYear;

    /** :purpose: Confirmation flag set by the service (CORPT00 ``CONFIRMO`` PIC X(01)). */
    @Size(max = 1)
    private String confirm;

    /** :purpose: Error / status message set by the service (CORPT00 ``ERRMSGO`` PIC X(78)). */
    @Size(max = 78)
    private String errorMessage;

    /** :purpose: Screen title line 1 (CORPT00 ``TITLE01O`` PIC X(40)). */
    @Size(max = 40)
    private String title01;

    /** :purpose: Screen title line 2 (CORPT00 ``TITLE02O`` PIC X(40)). */
    @Size(max = 40)
    private String title02;

    /** :purpose: Transaction name shown in the header (CORPT00 ``TRNNAMEO`` PIC X(04)). */
    @Size(max = 4)
    private String trnName;

    /** :purpose: Program name shown in the header (CORPT00 ``PGMNAMEO`` PIC X(08)). */
    @Size(max = 8)
    private String pgmName;

    /** :purpose: Current date shown in the header (CORPT00 ``CURDATEO`` PIC X(08)). */
    @Size(max = 8)
    private String currentDate;

    /** :purpose: Current time shown in the header (CORPT00 ``CURTIMEO`` PIC X(08)). */
    @Size(max = 8)
    private String currentTime;

    /**
     * :purpose: Create an empty response. Required for JSON (Jackson) deserialization.
     */
    public ReportResponseDto() {
    }

    /**
     * :purpose: Return the MONTHLY selector flag.
     * :output: the ``monthly`` flag value.
     */
    public String getMonthly() {
        return monthly;
    }

    /**
     * :purpose: Set the MONTHLY selector flag.
     * :param monthly: the ``monthly`` flag value.
     */
    public void setMonthly(String monthly) {
        this.monthly = monthly;
    }

    /**
     * :purpose: Return the YEARLY selector flag.
     * :output: the ``yearly`` flag value.
     */
    public String getYearly() {
        return yearly;
    }

    /**
     * :purpose: Set the YEARLY selector flag.
     * :param yearly: the ``yearly`` flag value.
     */
    public void setYearly(String yearly) {
        this.yearly = yearly;
    }

    /**
     * :purpose: Return the CUSTOM selector flag.
     * :output: the ``custom`` flag value.
     */
    public String getCustom() {
        return custom;
    }

    /**
     * :purpose: Set the CUSTOM selector flag.
     * :param custom: the ``custom`` flag value.
     */
    public void setCustom(String custom) {
        this.custom = custom;
    }

    /**
     * :purpose: Return the custom start-date month.
     * :output: the ``startDateMonth`` value.
     */
    public String getStartDateMonth() {
        return startDateMonth;
    }

    /**
     * :purpose: Set the custom start-date month.
     * :param startDateMonth: the ``startDateMonth`` value.
     */
    public void setStartDateMonth(String startDateMonth) {
        this.startDateMonth = startDateMonth;
    }

    /**
     * :purpose: Return the custom start-date day.
     * :output: the ``startDateDay`` value.
     */
    public String getStartDateDay() {
        return startDateDay;
    }

    /**
     * :purpose: Set the custom start-date day.
     * :param startDateDay: the ``startDateDay`` value.
     */
    public void setStartDateDay(String startDateDay) {
        this.startDateDay = startDateDay;
    }

    /**
     * :purpose: Return the custom start-date year.
     * :output: the ``startDateYear`` value.
     */
    public String getStartDateYear() {
        return startDateYear;
    }

    /**
     * :purpose: Set the custom start-date year.
     * :param startDateYear: the ``startDateYear`` value.
     */
    public void setStartDateYear(String startDateYear) {
        this.startDateYear = startDateYear;
    }

    /**
     * :purpose: Return the custom end-date month.
     * :output: the ``endDateMonth`` value.
     */
    public String getEndDateMonth() {
        return endDateMonth;
    }

    /**
     * :purpose: Set the custom end-date month.
     * :param endDateMonth: the ``endDateMonth`` value.
     */
    public void setEndDateMonth(String endDateMonth) {
        this.endDateMonth = endDateMonth;
    }

    /**
     * :purpose: Return the custom end-date day.
     * :output: the ``endDateDay`` value.
     */
    public String getEndDateDay() {
        return endDateDay;
    }

    /**
     * :purpose: Set the custom end-date day.
     * :param endDateDay: the ``endDateDay`` value.
     */
    public void setEndDateDay(String endDateDay) {
        this.endDateDay = endDateDay;
    }

    /**
     * :purpose: Return the custom end-date year.
     * :output: the ``endDateYear`` value.
     */
    public String getEndDateYear() {
        return endDateYear;
    }

    /**
     * :purpose: Set the custom end-date year.
     * :param endDateYear: the ``endDateYear`` value.
     */
    public void setEndDateYear(String endDateYear) {
        this.endDateYear = endDateYear;
    }

    /**
     * :purpose: Return the confirmation flag.
     * :output: the ``confirm`` flag value.
     */
    public String getConfirm() {
        return confirm;
    }

    /**
     * :purpose: Set the confirmation flag.
     * :param confirm: the ``confirm`` flag value.
     */
    public void setConfirm(String confirm) {
        this.confirm = confirm;
    }

    /**
     * :purpose: Return the error / status message.
     * :output: the ``errorMessage`` value shown on the screen.
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * :purpose: Set the error / status message.
     * :param errorMessage: the message shown on the screen.
     */
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    /**
     * :purpose: Return the screen title line 1.
     * :output: the ``title01`` value.
     */
    public String getTitle01() {
        return title01;
    }

    /**
     * :purpose: Set the screen title line 1.
     * :param title01: the ``title01`` value.
     */
    public void setTitle01(String title01) {
        this.title01 = title01;
    }

    /**
     * :purpose: Return the screen title line 2.
     * :output: the ``title02`` value.
     */
    public String getTitle02() {
        return title02;
    }

    /**
     * :purpose: Set the screen title line 2.
     * :param title02: the ``title02`` value.
     */
    public void setTitle02(String title02) {
        this.title02 = title02;
    }

    /**
     * :purpose: Return the header transaction name.
     * :output: the ``trnName`` value.
     */
    public String getTrnName() {
        return trnName;
    }

    /**
     * :purpose: Set the header transaction name.
     * :param trnName: the ``trnName`` value.
     */
    public void setTrnName(String trnName) {
        this.trnName = trnName;
    }

    /**
     * :purpose: Return the header program name.
     * :output: the ``pgmName`` value.
     */
    public String getPgmName() {
        return pgmName;
    }

    /**
     * :purpose: Set the header program name.
     * :param pgmName: the ``pgmName`` value.
     */
    public void setPgmName(String pgmName) {
        this.pgmName = pgmName;
    }

    /**
     * :purpose: Return the header current date.
     * :output: the ``currentDate`` value.
     */
    public String getCurrentDate() {
        return currentDate;
    }

    /**
     * :purpose: Set the header current date.
     * :param currentDate: the ``currentDate`` value.
     */
    public void setCurrentDate(String currentDate) {
        this.currentDate = currentDate;
    }

    /**
     * :purpose: Return the header current time.
     * :output: the ``currentTime`` value.
     */
    public String getCurrentTime() {
        return currentTime;
    }

    /**
     * :purpose: Set the header current time.
     * :param currentTime: the ``currentTime`` value.
     */
    public void setCurrentTime(String currentTime) {
        this.currentTime = currentTime;
    }
}
