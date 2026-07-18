/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Part of the Java / Spring Boot re-platform of the AWS CardDemo mainframe
 * application. This type re-expresses a BMS 3270 screen field contract as a
 * REST request DTO, preserving the original field names, lengths, and types
 * with no feature expansion.
 */
package com.aws.carddemo.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request payload for the Transaction Reports screen.
 *
 * <p>Re-platforms the input group of BMS map {@code CORPT00} (mapset
 * {@code CORPT0A}, symbolic map {@code CORPT0AI} in
 * {@code app/cpy-bms/CORPT00.CPY}), driven by online transaction {@code CR00} /
 * program {@code CORPT00C} and exposed by {@code TransactionReportController}.
 * The screen lets an operator request a transaction report over one of three
 * mutually exclusive ranges &mdash; the current month, the current year, or a
 * custom start/end date range &mdash; and then confirm submission of the report
 * job. The report itself is produced by the batch layer
 * ({@code ReportService} &rarr; {@code TransactionReportJob}); this DTO carries
 * only the operator's selection and confirmation.</p>
 *
 * <p><strong>Field contract.</strong> Every component below maps one-to-one to
 * an unprotected ({@code UNPROT}) input field of the legacy map and preserves
 * the original {@code PIC X(n)} length via {@link Size}. The three date-part
 * groups are kept as discrete month / day / year fields exactly as the 3270
 * screen presents them &mdash; they are intentionally <em>not</em> collapsed
 * into a single ISO date string. The numeric date parts additionally carry a
 * {@link Pattern} that admits only digits (and permits the field to be blank,
 * mirroring the space-filled 3270 buffer). Composition of the discrete parts
 * into a validated calendar range, and enforcement of the mutual exclusivity of
 * the {@code monthly} / {@code yearly} / {@code custom} selectors, are performed
 * by the {@code service/rule/} layer, not here; this DTO contains no business
 * logic.</p>
 *
 * <p>The {@code action} component carries the operator's Attention Identifier
 * (the {@code EIBAID} / {@code CSSTRPFY.cpy} contract) as a transport-neutral
 * {@link PfKeyAction}. Per the map footer {@code "ENTER=Continue  F3=Back"}, the
 * meaningful keys for this screen are {@link PfKeyAction#ENTER} (continue /
 * submit) and {@link PfKeyAction#PF3} (back); it is optional and therefore
 * unconstrained.</p>
 *
 * @param monthly    monthly-report selection marker; maps to {@code MONTHLYI},
 *                   {@code PIC X(1)}
 * @param yearly     yearly-report selection marker; maps to {@code YEARLYI},
 *                   {@code PIC X(1)}
 * @param custom     custom-range selection marker; maps to {@code CUSTOMI},
 *                   {@code PIC X(1)}
 * @param startMonth custom range start month (MM); maps to {@code SDTMMI},
 *                   {@code PIC X(2)}
 * @param startDay   custom range start day (DD); maps to {@code SDTDDI},
 *                   {@code PIC X(2)}
 * @param startYear  custom range start year (YYYY); maps to {@code SDTYYYYI},
 *                   {@code PIC X(4)}
 * @param endMonth   custom range end month (MM); maps to {@code EDTMMI},
 *                   {@code PIC X(2)}
 * @param endDay     custom range end day (DD); maps to {@code EDTDDI},
 *                   {@code PIC X(2)}
 * @param endYear    custom range end year (YYYY); maps to {@code EDTYYYYI},
 *                   {@code PIC X(4)}
 * @param confirm    Y/N confirmation to submit the report job; maps to
 *                   {@code CONFIRMI}, {@code PIC X(1)}
 * @param action     the Attention Identifier (PF key / Enter) the operator
 *                   pressed; {@link PfKeyAction#ENTER} continues,
 *                   {@link PfKeyAction#PF3} goes back; optional
 */
public record TransactionReportRequest(

        @Size(max = 1)
        String monthly,

        @Size(max = 1)
        String yearly,

        @Size(max = 1)
        String custom,

        @Size(max = 2)
        @Pattern(regexp = "^\\d{0,2}$")
        String startMonth,

        @Size(max = 2)
        @Pattern(regexp = "^\\d{0,2}$")
        String startDay,

        @Size(max = 4)
        @Pattern(regexp = "^\\d{0,4}$")
        String startYear,

        @Size(max = 2)
        @Pattern(regexp = "^\\d{0,2}$")
        String endMonth,

        @Size(max = 2)
        @Pattern(regexp = "^\\d{0,2}$")
        String endDay,

        @Size(max = 4)
        @Pattern(regexp = "^\\d{0,4}$")
        String endYear,

        // CONFIRMI PIC X(1): only the single-character length is enforced here; the Y/N value
        // EVALUATE is a service-level same-screen edit (ReportService.handleConfirmation), so an
        // invalid one-character confirm returns HTTP 200 with '"x" is not a valid value to confirm...'.
        @Size(max = 1)
        String confirm,

        PfKeyAction action) {
}
