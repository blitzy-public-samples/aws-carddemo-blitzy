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
package com.carddemo.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.FlashMapManager;

/**
 * :purpose: Verify the shared servlet hardening that every CardDemo service imports. The
 *  flash-map manager is the behaviour under test here: ``DispatcherServlet`` consults it
 *  on every request, and the MVC default reads the caller's session to do so, which made
 *  each hop write the shared Redis session back when the response committed. That write
 *  failed with ``IllegalStateException: Session was invalidated`` on a request whose
 *  upstream had just rotated the session id, and returned HTTP 500 for a sign-on that had
 *  already succeeded.
 * :output: Asserts the manager is published under the name ``DispatcherServlet`` looks up,
 *  reports no input flash map, never touches the session, and discards the output map.
 */
@DisplayName("WebHardeningConfig")
class WebHardeningConfigTest {

    private final WebHardeningConfig config = new WebHardeningConfig();

    /**
     * :purpose: The bean must be published under the exact name ``DispatcherServlet``
     *  resolves, or the MVC default silently stays in place.
     */
    @Test
    @DisplayName("the flash-map manager bean name is the one DispatcherServlet looks up")
    void flashMapManagerBeanNameIsTheOneDispatcherServletResolves() {
        assertEquals("flashMapManager", DispatcherServlet.FLASH_MAP_MANAGER_BEAN_NAME,
                "the bean name is the framework's lookup key and must not be renamed");
    }

    /**
     * :purpose: Retrieving must report no flash map AND must leave the session alone: an
     *  existing session must not become owned by this hop just because the dispatcher ran.
     */
    @Test
    @DisplayName("retrieving reports no flash map and does not read the session")
    void retrievingReportsNothingAndLeavesTheSessionAlone() {
        FlashMapManager manager = config.flashMapManager();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/signon");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("probe", "value");
        request.setSession(session);

        assertNull(manager.retrieveAndUpdate(request, new MockHttpServletResponse()),
                "no input flash map must be reported");
        assertEquals("value", session.getAttribute("probe"),
                "the session must be left exactly as it was");
    }

    /**
     * :purpose: Saving must discard the map rather than persist it, so no request writes
     *  the session for the sake of an empty flash map.
     */
    @Test
    @DisplayName("saving discards the output flash map instead of persisting it")
    void savingDiscardsTheOutputFlashMap() {
        FlashMapManager manager = config.flashMapManager();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/accounts/1");
        FlashMap flashMap = new FlashMap();
        flashMap.put("message", "ignored");

        manager.saveOutputFlashMap(flashMap, request, new MockHttpServletResponse());

        assertNull(request.getSession(false),
                "saving must not create a session to hold the flash map");
    }
}
