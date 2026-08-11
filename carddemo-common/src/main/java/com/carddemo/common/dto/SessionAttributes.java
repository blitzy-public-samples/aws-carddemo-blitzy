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

/**
 * :purpose: Single source of truth for the ``HttpSession`` attribute names that
 *     carry the re-platformed CICS pseudo-conversational state across every
 *     CardDemo service. The legacy COMMAREA (``app/cpy/COCOM01Y.cpy``) is one
 *     record shared by every program, so the migrated
 *     {@link SessionContext} must likewise be stored under ONE key that every
 *     service reads and writes; two divergent keys split the navigation state
 *     and silently disable the role gates that depend on ``CDEMO-USER-TYPE``.
 * :output: Compile-time constants only; the class is not instantiable.
 */
public final class SessionAttributes {

    /**
     * :purpose: ``HttpSession`` attribute key holding the externalized
     *     {@link SessionContext} that replaces the legacy COMMAREA. Shared,
     *     verbatim, by the sign-on service, the API gateway's menu navigation
     *     and every business service.
     */
    public static final String SESSION_CONTEXT = "carddemoSessionContext";

    /**
     * :purpose: Prevent instantiation of this constant holder.
     */
    private SessionAttributes() {
    }
}
