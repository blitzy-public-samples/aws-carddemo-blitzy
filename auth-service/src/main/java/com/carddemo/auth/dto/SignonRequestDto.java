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

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * :purpose: Request body for the CardDemo Authentication service endpoint
 *           ``POST /auth/signon``. Re-platforms the input side of the CICS
 *           ``COSGN0A`` map (mapset ``COSGN00``) received by legacy program
 *           ``COSGN00C`` (transaction ``CC00``); carries the user id and
 *           password entered on the sign-on screen.
 * :param userId:   user id entered on the sign-on screen; must not be blank and
 *                  is bounded to the frozen ``SEC-USR-ID PIC X(08)`` width.
 * :param password: password entered on the sign-on screen; must not be blank and
 *                  is bounded to the frozen ``SEC-USR-PWD PIC X(08)`` width so an
 *                  oversized value can never reach the adaptive password hasher.
 */
public class SignonRequestDto {

    @NotBlank(message = "Please enter User ID ...")
    @Size(max = 8, message = "User ID must be at most 8 characters")
    private String userId;

    @NotBlank(message = "Please enter Password ...")
    @Size(max = 8, message = "Password must be at most 8 characters")
    private String password;

    /**
     * :purpose: No-argument constructor required for JSON deserialization of
     *           the request body.
     */
    public SignonRequestDto() {
    }

    /**
     * :purpose: All-arguments constructor.
     * :param userId:   user id entered on the sign-on screen.
     * :param password: password entered on the sign-on screen.
     */
    public SignonRequestDto(String userId, String password) {
        this.userId = userId;
        this.password = password;
    }

    /**
     * :purpose: Read ``userId``.
     * :returns: the user id entered on the sign-on screen.
     */
    public String getUserId() {
        return userId;
    }

    /**
     * :purpose: Set ``userId``.
     * :param userId: user id entered on the sign-on screen.
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * :purpose: Read ``password``.
     * :returns: the password entered on the sign-on screen.
     */
    public String getPassword() {
        return password;
    }

    /**
     * :purpose: Set ``password``.
     * :param password: password entered on the sign-on screen.
     */
    public void setPassword(String password) {
        this.password = password;
    }
}
