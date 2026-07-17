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
package com.aws.carddemo.mapper;

import com.aws.carddemo.common.util.DateUtils;
import com.aws.carddemo.domain.UserSecurity;
import com.aws.carddemo.dto.SignonResponse;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

/**
 * Hand-written mapper that assembles the outbound {@link SignonResponse} for the
 * CardDemo Sign-on screen.
 *
 * <p>This component is the field-level bridge between the {@link UserSecurity}
 * JPA entity (migrated from copybook {@code legacy/cpy/CSUSR01Y.cpy}, group item
 * {@code SEC-USER-DATA}) and the REST response DTO derived from the BMS symbolic
 * copybook {@code legacy/cpy-bms/COSGN00.CPY} (output group {@code COSGN0AO}). It
 * corresponds to the online COBOL program {@code COSGN00C} (CICS transaction
 * {@code CC00}), which populated {@code COSGN0AO} before issuing
 * {@code EXEC CICS SEND MAP}.</p>
 *
 * <p><strong>Intentionally thin.</strong> The sign-on screen echoes almost no
 * persisted data: the <em>only</em> entity-derived value on the response is the
 * user id ({@code USERIDO} / {@code SEC-USR-ID}). Every other field is either a
 * screen constant, a runtime environment value (APPLID / SYSID), a formatted
 * clock value, or the status/error line &mdash; all supplied by the caller. This
 * mapper therefore exists primarily to enforce field-level traceability and the
 * sensitive-field discipline described below at a single, auditable boundary,
 * rather than to perform a large field-by-field copy.</p>
 *
 * <p><strong>Sensitive-field discipline (absolute).</strong> The
 * {@link UserSecurity} record carries a sensitive credential and other personal
 * fields (first name, last name, role). None of them are read here: the sign-on
 * response contract ({@link SignonResponse}) deliberately has no credential
 * field, and this mapper never invokes the entity's sensitive credential
 * accessor and never echoes an inbound credential onto the outbound path. The
 * only accessor called on the entity is {@link UserSecurity#getSecUsrId()}.</p>
 *
 * <p><strong>Date / time formatting.</strong> All clock rendering is delegated
 * to {@link DateUtils} so the {@code MM/DD/YY} and {@code HH:MM:SS} display masks
 * of {@code legacy/cpy/CSDAT01Y.cpy} are applied in exactly one place; this class
 * never constructs a formatter inline.</p>
 *
 * <p><strong>Design constraints.</strong> The mapper is a stateless Spring
 * {@link Component} with no instance fields and no injected collaborators
 * ({@link DateUtils} is a static utility invoked statically, per AAP 0.6.5, which
 * prescribes hand-written mappers rather than an annotation-processing library).
 * Its methods are pure, null-safe, and free of business logic: credential
 * verification and PF-key routing belong to {@code SignonService} and
 * {@code SignonController} respectively, not here.</p>
 */
@Component
public class SignonMapper {

    /**
     * Builds a fully-populated {@link SignonResponse} for a caller that has
     * already authenticated a user (the success / re-display path of
     * {@code COSGN00C}).
     *
     * <p>The user id echoed onto the screen is taken from
     * {@link UserSecurity#getSecUsrId()} &mdash; the single entity-derived field
     * on this screen. No other property of {@code user} is read; in particular
     * the entity's sensitive credential is never accessed.</p>
     *
     * <p>The current date and time are formatted from {@code now} through
     * {@link DateUtils}; the screen-constant header fields and the CICS
     * environment values are passed straight through from the caller (this
     * mapper does not fabricate business or environment values).</p>
     *
     * <p>The method is null-safe: a {@code null} {@code user} yields a
     * {@code null} user id, and a {@code null} {@code now} yields {@code null}
     * date and time strings rather than throwing.</p>
     *
     * @param user            the authenticated user record; its
     *                        {@link UserSecurity#getSecUsrId() id} is echoed to
     *                        {@code USERIDO} ({@code PIC X(8)}). May be
     *                        {@code null}, in which case the echoed id is
     *                        {@code null}.
     * @param applId          the CICS application identifier for {@code APPLIDO}
     *                        ({@code PIC X(8)}); supplied by the runtime
     *                        environment.
     * @param sysId           the CICS system identifier for {@code SYSIDO}
     *                        ({@code PIC X(8)}); supplied by the runtime
     *                        environment.
     * @param errorMessage    the status / error line for {@code ERRMSGO}
     *                        ({@code PIC X(78)}); may be {@code null} or blank on
     *                        the success path.
     * @param now             the timestamp used to render {@code CURDATEO}
     *                        ({@code mm/dd/yy}) and {@code CURTIMEO}
     *                        ({@code hh:mm:ss}); may be {@code null}.
     * @param transactionName the transaction-name header for {@code TRNNAMEO}
     *                        ({@code PIC X(4)}); a screen constant supplied by
     *                        the caller.
     * @param title01         the first title line for {@code TITLE01O}
     *                        ({@code PIC X(40)}); a screen constant supplied by
     *                        the caller.
     * @param programName     the program-name header for {@code PGMNAMEO}
     *                        ({@code PIC X(8)}); a screen constant supplied by
     *                        the caller.
     * @param title02         the second title line for {@code TITLE02O}
     *                        ({@code PIC X(40)}); a screen constant supplied by
     *                        the caller.
     * @return a populated {@link SignonResponse}; never {@code null}
     */
    public SignonResponse toResponse(UserSecurity user,
                                     String applId,
                                     String sysId,
                                     String errorMessage,
                                     LocalDateTime now,
                                     String transactionName,
                                     String title01,
                                     String programName,
                                     String title02) {
        String echoedUserId = (user == null) ? null : user.getSecUsrId();
        return build(echoedUserId, applId, sysId, errorMessage, now,
                transactionName, title01, programName, title02);
    }

    /**
     * Builds a {@link SignonResponse} for a failed or pre-authentication attempt
     * (the error path of {@code COSGN00C}), where no {@link UserSecurity} entity
     * is available because credentials were not validated.
     *
     * <p>Only the non-secret user id the operator typed is echoed back to
     * {@code USERIDO}; the inbound credential is never referenced on this path,
     * so it can never be reflected to the client. The remaining header,
     * environment, and clock fields are handled exactly as on the success
     * path.</p>
     *
     * <p>The method is null-safe: a {@code null} {@code now} yields {@code null}
     * date and time strings rather than throwing.</p>
     *
     * @param enteredUserId   the user id the operator entered ({@code USERIDI});
     *                        echoed verbatim to {@code USERIDO} ({@code PIC
     *                        X(8)}). May be {@code null}.
     * @param applId          the CICS application identifier for {@code APPLIDO}
     *                        ({@code PIC X(8)}).
     * @param sysId           the CICS system identifier for {@code SYSIDO}
     *                        ({@code PIC X(8)}).
     * @param errorMessage    the error line for {@code ERRMSGO}
     *                        ({@code PIC X(78)}); typically the reason the
     *                        sign-on was rejected.
     * @param now             the timestamp used to render {@code CURDATEO}
     *                        ({@code mm/dd/yy}) and {@code CURTIMEO}
     *                        ({@code hh:mm:ss}); may be {@code null}.
     * @param transactionName the transaction-name header for {@code TRNNAMEO}
     *                        ({@code PIC X(4)}).
     * @param title01         the first title line for {@code TITLE01O}
     *                        ({@code PIC X(40)}).
     * @param programName     the program-name header for {@code PGMNAMEO}
     *                        ({@code PIC X(8)}).
     * @param title02         the second title line for {@code TITLE02O}
     *                        ({@code PIC X(40)}).
     * @return a populated {@link SignonResponse}; never {@code null}
     */
    public SignonResponse toErrorResponse(String enteredUserId,
                                          String applId,
                                          String sysId,
                                          String errorMessage,
                                          LocalDateTime now,
                                          String transactionName,
                                          String title01,
                                          String programName,
                                          String title02) {
        return build(enteredUserId, applId, sysId, errorMessage, now,
                transactionName, title01, programName, title02);
    }

    /**
     * Central, single-point assembly of a {@link SignonResponse}. Both public
     * builders funnel through here so the field-by-field mapping&mdash;including
     * the exact canonical-constructor argument order of {@link SignonResponse}
     * ({@code COSGN0AO} field order)&mdash;is defined in one place.
     *
     * <p>The current date and time are formatted through {@link DateUtils} only
     * when {@code now} is non-{@code null}; a {@code null} {@code now} produces
     * {@code null} strings, keeping the method free of the
     * {@link NullPointerException} that {@link DateUtils#formatDateMmDdYy} and
     * {@link DateUtils#formatTimeHhMmSs} raise on a {@code null} argument.</p>
     *
     * @param userId          the already-resolved, non-secret user id to echo
     * @param applId          the CICS application identifier
     * @param sysId           the CICS system identifier
     * @param errorMessage    the status / error line
     * @param now             the timestamp to format, or {@code null}
     * @param transactionName the transaction-name header constant
     * @param title01         the first title line constant
     * @param programName     the program-name header constant
     * @param title02         the second title line constant
     * @return a populated {@link SignonResponse}; never {@code null}
     */
    private SignonResponse build(String userId,
                                 String applId,
                                 String sysId,
                                 String errorMessage,
                                 LocalDateTime now,
                                 String transactionName,
                                 String title01,
                                 String programName,
                                 String title02) {
        String currentDate = (now == null) ? null : DateUtils.formatDateMmDdYy(now.toLocalDate());
        String currentTime = (now == null) ? null : DateUtils.formatTimeHhMmSs(now.toLocalTime());

        return new SignonResponse(
                transactionName,
                title01,
                currentDate,
                programName,
                title02,
                currentTime,
                applId,
                sysId,
                userId,
                errorMessage);
    }
}
