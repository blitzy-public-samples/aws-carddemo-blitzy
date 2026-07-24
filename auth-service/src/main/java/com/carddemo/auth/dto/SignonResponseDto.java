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
package com.carddemo.auth.dto;

import com.carddemo.common.dto.SessionContext;

/**
 * :purpose: Success response body for ``POST /auth/signon``, re-platforming the successful-sign-on
 *           routing of legacy CICS program ``COSGN00C`` (transaction ``CC00``). Conveys the
 *           authenticated user id, the granted user type, and the post-login navigation target.
 *           This is a plain POJO serialized to JSON by Jackson; it carries no password or secret.
 * :note: The user type is published as the frozen one-character ``SEC-USR-TYPE`` wire code
 *        (``"A"`` / ``"U"``) via the shared {@link SessionContext.UserType} enum, so this response,
 *        the server-side {@code SessionContext}, and the React ``session.ts`` ``Role`` type all bind
 *        to a single JSON schema without field remapping (finding CR-05). The client derives the
 *        Spring Security authority (``ROLE_ADMIN`` / ``ROLE_USER``) from that code.
 */
public class SignonResponseDto {

    /**
     * :purpose: The authenticated user id echoed back to the client (legacy ``CDEMO-USER-ID`` /
     *           ``WS-USER-ID``).
     */
    private String userId;

    /**
     * :purpose: The granted user type as the frozen ``SEC-USR-TYPE`` wire code. Serializes to the
     *           single character ``"A"`` (administrator, ``CDEMO-USRTYP-ADMIN``) or ``"U"`` (standard
     *           user, ``CDEMO-USRTYP-USER``) through {@link SessionContext.UserType}. The client maps
     *           this code to the ``ROLE_ADMIN`` / ``ROLE_USER`` authority.
     */
    private SessionContext.UserType userType;

    /**
     * :purpose: The post-login navigation target as the legacy CICS menu transaction id: ``"CA00"``
     *           (administrator menu, legacy ``COADM01C``) or ``"CM00"`` (regular-user menu, legacy
     *           ``COMEN01C``), derived from the ``COSGN00C`` ``XCTL`` routing.
     */
    private String redirectTarget;

    /**
     * :purpose: Default no-argument constructor required for Jackson (de)serialization.
     */
    public SignonResponseDto() {
    }

    /**
     * :purpose: Construct a fully populated sign-on success response.
     * :param userId: the authenticated user id.
     * :param userType: the granted user type (``CDEMO_USRTYP_ADMIN`` / ``CDEMO_USRTYP_USER``),
     *                  serialized to the ``"A"`` / ``"U"`` wire code.
     * :param redirectTarget: the post-login menu transaction id (``"CA00"`` or ``"CM00"``).
     */
    public SignonResponseDto(String userId, SessionContext.UserType userType, String redirectTarget) {
        this.userId = userId;
        this.userType = userType;
        this.redirectTarget = redirectTarget;
    }

    /**
     * :purpose: Return the authenticated user id.
     * :returns: the signed-on user id.
     */
    public String getUserId() {
        return userId;
    }

    /**
     * :purpose: Set the authenticated user id.
     * :param userId: the signed-on user id.
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * :purpose: Return the granted user type.
     * :returns: the {@link SessionContext.UserType} that serializes to ``"A"`` or ``"U"``.
     */
    public SessionContext.UserType getUserType() {
        return userType;
    }

    /**
     * :purpose: Set the granted user type.
     * :param userType: the {@link SessionContext.UserType} (serialized as ``"A"`` / ``"U"``).
     */
    public void setUserType(SessionContext.UserType userType) {
        this.userType = userType;
    }

    /**
     * :purpose: Return the post-login navigation target.
     * :returns: the menu transaction id (``"CA00"`` or ``"CM00"``).
     */
    public String getRedirectTarget() {
        return redirectTarget;
    }

    /**
     * :purpose: Set the post-login navigation target.
     * :param redirectTarget: the menu transaction id (``"CA00"`` or ``"CM00"``).
     */
    public void setRedirectTarget(String redirectTarget) {
        this.redirectTarget = redirectTarget;
    }

    /**
     * :purpose: Human-readable representation over the three non-sensitive fields; carries no secrets.
     * :returns: a string containing ``userId``, ``userType``, and ``redirectTarget``.
     */
    @Override
    public String toString() {
        return "SignonResponseDto{"
                + "userId='" + userId + '\''
                + ", userType=" + userType
                + ", redirectTarget='" + redirectTarget + '\''
                + '}';
    }
}
