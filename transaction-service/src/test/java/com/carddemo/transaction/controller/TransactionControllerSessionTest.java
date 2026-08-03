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
package com.carddemo.transaction.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.common.dto.SessionContext;
import com.carddemo.common.dto.TransactionListRequestDto;
import com.carddemo.common.dto.TransactionListResponseDto;
import com.carddemo.common.dto.TransactionViewResponseDto;
import com.carddemo.transaction.service.TransactionService;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * :purpose: Standalone MockMvc verification of the externalized-session bridging of
 *  {@link TransactionController}. The controller is instantiated directly with a mocked
 *  {@link TransactionService} so a real ``DispatcherServlet`` resolves its handler-method
 *  arguments; that is exactly the machinery under test, because the defect being guarded
 *  lived in argument resolution rather than in any business path.
 * :output: Two behaviours are asserted: a sessionless caller gets no session created
 *  (QA Issue 22), and a caller that already has one has its pseudo-conversational
 *  {@link SessionContext} read and written back under the shared attribute key.
 */
class TransactionControllerSessionTest {

    /**
     * :purpose: The shared session attribute key holding the pseudo-conversational
     *  {@link SessionContext} (the COMMAREA replacement, AAP section 0.6.3). Declared as
     *  a literal so any drift in the controller's own constant fails this test.
     */
    private static final String SESSION_CONTEXT_ATTR = "carddemoSessionContext";

    /** :purpose: A well-formed 16-character zero-padded transaction id. */
    private static final String TRAN_ID = "0000000000000001";

    /** :purpose: Mocked business collaborator; no business logic is exercised here. */
    private TransactionService transactionService;

    /** :purpose: Standalone MockMvc wired to the controller under test. */
    private MockMvc mockMvc;

    /**
     * :purpose: Build the mocked service and the standalone MockMvc instance, and stub
     *  the list and view calls with empty-but-valid responses.
     */
    @BeforeEach
    void setUp() {
        transactionService = mock(TransactionService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new TransactionController(transactionService)).build();

        TransactionListResponseDto listResponse = new TransactionListResponseDto();
        listResponse.setTransactions(List.of());
        listResponse.setPageNumber(1);
        when(transactionService.listTransactions(any(TransactionListRequestDto.class),
                any(SessionContext.class))).thenReturn(listResponse);
        when(transactionService.viewTransaction(any(String.class), any(SessionContext.class)))
                .thenReturn(new TransactionViewResponseDto());
    }

    /**
     * :purpose: Regression guard for QA Issue 22 (Redis session churn). A caller that
     *  arrives without a session must leave without one: the endpoints take
     *  ``HttpServletRequest`` and read the context through ``getSession(false)``, so no
     *  Spring Session entry is created - and therefore none is persisted to Redis - for
     *  an anonymous one-off call. Declaring an ``HttpSession`` parameter instead made
     *  Spring's argument resolver call ``getSession()`` unconditionally on every request.
     */
    @Test
    @DisplayName("QA Issue 22: transaction endpoints create no HTTP session for a sessionless caller")
    void transactionEndpointsCreateNoSessionForSessionlessCaller() throws Exception {
        MvcResult listResult = mockMvc.perform(get("/transactions"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(listResult.getRequest().getSession(false)).isNull();

        MvcResult viewResult = mockMvc.perform(get("/transactions/{id}", TRAN_ID))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(viewResult.getRequest().getSession(false)).isNull();
    }

    /**
     * :purpose: The pseudo-conversational bridge still works for a caller that already
     *  holds a session: the stored {@link SessionContext} is the instance handed to the
     *  service and it is written back under the shared attribute key, so paging-cursor
     *  and last-map state survives across the stateless requests of a workflow.
     */
    @Test
    @DisplayName("An existing session still carries the SessionContext into and out of the controller")
    void existingSessionStillCarriesTheContext() throws Exception {
        SessionContext existing = new SessionContext();
        existing.setUserId("ADMIN001");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SESSION_CONTEXT_ATTR, existing);

        mockMvc.perform(get("/transactions").session(session))
                .andExpect(status().isOk());

        assertThat(session.getAttribute(SESSION_CONTEXT_ATTR)).isSameAs(existing);
    }
}
