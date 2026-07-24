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
package com.carddemo.auth.mapper;

import com.carddemo.auth.dto.SignonResponseDto;
import com.carddemo.common.domain.SecurityUser;
import com.carddemo.common.dto.SessionContext;
import org.springframework.stereotype.Component;
import java.util.Locale;

/**
 * :purpose: Maps a verified {@link SecurityUser} to the sign-on response DTO and the
 *  externalized {@link SessionContext}, re-expressing the ``COSGN00C`` (CICS transaction
 *  ``CC00``) sign-on SUCCESS-branch COMMAREA population and post-login routing.
 * :note: Field mapping only. It performs no credential verification, no I/O, no
 *  persistence, and no logging, and it never reads the password.
 */
@Component
public class SignonMapper {

    /**
     * :purpose: Build the ``POST /auth/signon`` success-response body from a verified user.
     *  Administrator is the special case (mirroring COBOL ``IF CDEMO-USRTYP-ADMIN``); every
     *  other user type routes to the regular-user menu.
     * :param user: the already-verified security user.
     * :returns: a response carrying the userId, the userType derived from ``SEC-USR-TYPE``
     *  ``'A'``/``'U'`` (from which the ``ROLE_ADMIN``/``ROLE_USER`` authority is resolved
     *  downstream), and the redirectTarget (``CA00`` administrator menu / ``CM00`` regular-user
     *  menu).
     */
    public SignonResponseDto toSignonResponse(SecurityUser user) {
        SessionContext.UserType userType = SessionContext.UserType.fromCode(user.getSecUsrType());
        boolean admin = (userType == SessionContext.UserType.CDEMO_USRTYP_ADMIN);
        String redirectTarget = admin ? "CA00" : "CM00";
        return new SignonResponseDto(user.getSecUsrId(), userType, redirectTarget);
    }

    /**
     * :purpose: Assemble the externalized COMMAREA replacement seeded by the ``COSGN00C``
     *  sign-on SUCCESS branch, reproducing the moves to ``CDEMO-FROM-TRANID``,
     *  ``CDEMO-FROM-PROGRAM``, ``CDEMO-USER-ID``, ``CDEMO-USER-TYPE``, and
     *  ``CDEMO-PGM-CONTEXT``.
     * :param user: the already-verified security user.
     * :returns: a session context with fromTranid ``CC00``, fromProgram ``COSGN00C``, the
     *  upper-cased userId, the userType from ``SEC-USR-TYPE``, and programContext
     *  ``CDEMO_PGM_ENTER``.
     */
    public SessionContext toSessionContext(SecurityUser user) {
        SessionContext ctx = new SessionContext();
        ctx.setFromTranid("CC00");
        ctx.setFromProgram("COSGN00C");
        ctx.setUserId(user.getSecUsrId() == null ? null : user.getSecUsrId().toUpperCase(Locale.ROOT));
        ctx.setUserType(SessionContext.UserType.fromCode(user.getSecUsrType()));
        ctx.setProgramContext(SessionContext.ProgramContext.CDEMO_PGM_ENTER);
        return ctx;
    }
}
