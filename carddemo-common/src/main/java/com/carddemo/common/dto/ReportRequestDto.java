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
 * :purpose: Inbound request DTO for the CORPT00 report-request screen (CICS
 *  transaction ``CR00``, legacy program ``CORPT00C``). Carries the report-type
 *  selector flags and the optional custom start/end date parts exactly as
 *  entered on the 3270 map, preserving the legacy symbolic-map field widths
 *  (``app/cpy-bms/CORPT00.CPY``) so the JSON contract is byte-faithful to the
 *  screen. Field values are single/two/four character ``String`` flags rather
 *  than parsed numerics or booleans, matching the COBOL ``PIC X`` fields; parsing
 *  and validation of the date window belong to the reporting service, not this
 *  carrier.
 * :output: A mutable request carrier with the report-type selectors
 *  (``monthly``/``yearly``/``custom``), the custom start-date parts
 *  (``startDateMonth``/``startDateDay``/``startDateYear``), the custom end-date
 *  parts (``endDateMonth``/``endDateDay``/``endDateYear``) and the confirmation
 *  flag.
 */
public class ReportRequestDto {

    /** :purpose: MONTHLY selector flag (CORPT00 ``MONTHLYI`` PIC X(01)). */
    @Size(max = 1)
    private String monthly;

    /** :purpose: YEARLY selector flag (CORPT00 ``YEARLYI`` PIC X(01)). */
    @Size(max = 1)
    private String yearly;

    /** :purpose: CUSTOM selector flag (CORPT00 ``CUSTOMI`` PIC X(01)). */
    @Size(max = 1)
    private String custom;

    /** :purpose: Custom start-date month (CORPT00 ``SDTMMI`` PIC X(02)). */
    @Size(max = 2)
    private String startDateMonth;

    /** :purpose: Custom start-date day (CORPT00 ``SDTDDI`` PIC X(02)). */
    @Size(max = 2)
    private String startDateDay;

    /** :purpose: Custom start-date year (CORPT00 ``SDTYYYYI`` PIC X(04)). */
    @Size(max = 4)
    private String startDateYear;

    /** :purpose: Custom end-date month (CORPT00 ``EDTMMI`` PIC X(02)). */
    @Size(max = 2)
    private String endDateMonth;

    /** :purpose: Custom end-date day (CORPT00 ``EDTDDI`` PIC X(02)). */
    @Size(max = 2)
    private String endDateDay;

    /** :purpose: Custom end-date year (CORPT00 ``EDTYYYYI`` PIC X(04)). */
    @Size(max = 4)
    private String endDateYear;

    /** :purpose: Confirmation flag (CORPT00 ``CONFIRMI`` PIC X(01)). */
    @Size(max = 1)
    private String confirm;

    /**
     * :purpose: Create an empty request. Required for JSON (Jackson) deserialization.
     */
    public ReportRequestDto() {
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
}
