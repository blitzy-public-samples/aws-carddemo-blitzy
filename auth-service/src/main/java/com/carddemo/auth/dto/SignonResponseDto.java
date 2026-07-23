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

/**
 * :purpose: Success response body for ``POST /auth/signon``, re-platforming the successful-sign-on
 *           routing of legacy CICS program ``COSGN00C`` (transaction ``CC00``). Conveys the
 *           authenticated user id, the granted role authority, and the post-login navigation target.
 *           This is a plain POJO serialized to JSON by Jackson; it carries no password or secret.
 */
public class SignonResponseDto {

    /**
     * :purpose: The authenticated user id echoed back to the client (legacy ``CDEMO-USER-ID`` /
     *           ``WS-USER-ID``).
     */
    private String userId;

    /**
     * :purpose: The granted Spring Security authority. Legal values are ``"ROLE_ADMIN"`` (legacy
     *           ``SEC-USR-TYPE`` value ``'A'``, i.e. ``CDEMO-USRTYP-ADMIN``) or ``"ROLE_USER"``
     *           (legacy ``SEC-USR-TYPE`` value ``'U'``, i.e. ``CDEMO-USRTYP-USER``). The already-mapped
     *           authority string is supplied by the authentication service; this field is a passive
     *           carrier.
     */
    private String role;

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
     * :param role: the granted authority (``"ROLE_ADMIN"`` or ``"ROLE_USER"``).
     * :param redirectTarget: the post-login menu transaction id (``"CA00"`` or ``"CM00"``).
     */
    public SignonResponseDto(String userId, String role, String redirectTarget) {
        this.userId = userId;
        this.role = role;
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
     * :purpose: Return the granted authority.
     * :returns: ``"ROLE_ADMIN"`` or ``"ROLE_USER"``.
     */
    public String getRole() {
        return role;
    }

    /**
     * :purpose: Set the granted authority.
     * :param role: ``"ROLE_ADMIN"`` or ``"ROLE_USER"``.
     */
    public void setRole(String role) {
        this.role = role;
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
     * :returns: a string containing ``userId``, ``role``, and ``redirectTarget``.
     */
    @Override
    public String toString() {
        return "SignonResponseDto{"
                + "userId='" + userId + '\''
                + ", role='" + role + '\''
                + ", redirectTarget='" + redirectTarget + '\''
                + '}';
    }
}
