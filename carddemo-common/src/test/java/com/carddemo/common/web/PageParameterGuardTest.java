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
package com.carddemo.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.exception.CardDemoException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for :class:`PageParameterGuard`.
 *
 * :purpose: Pin the paged-list boundary contract. A page ordinal below one identifies no
 *     screen, and both list endpoints silently clamped such a value to the first page:
 *     ``page=-1`` and ``page=1`` returned byte-identical responses, so a client paging
 *     defect was indistinguishable from correct behaviour and an operator could be shown
 *     the first screen while believing they were elsewhere.
 * :output: Assertions that an absent ordinal resolves to the first page, that any value
 *     below one is refused with a message naming the parameter and the value, and that a
 *     usable ordinal is returned unchanged.
 */
@DisplayName("PageParameterGuard — paged list boundary")
class PageParameterGuardTest {

    @Test
    @DisplayName("an omitted page resolves to the first page")
    void omittedPageResolvesToTheFirstPage() {
        assertThat(PageParameterGuard.requirePositivePage(null, "page")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 7, 1_000, Integer.MAX_VALUE})
    @DisplayName("a usable page ordinal is returned unchanged")
    void usablePageIsReturnedUnchanged(int page) {
        assertThat(PageParameterGuard.requirePositivePage(page, "page")).isEqualTo(page);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -7, Integer.MIN_VALUE})
    @DisplayName("a page ordinal below one is refused rather than clamped")
    void nonPositivePageIsRefused(int page) {
        assertThatThrownBy(() -> PageParameterGuard.requirePositivePage(page, "page"))
                .isInstanceOf(CardDemoException.class)
                .hasMessageContaining("page must be 1 or greater")
                .hasMessageContaining(String.valueOf(page))
                .hasMessageContaining("identifies no page of results");
    }

    @Test
    @DisplayName("the refusal names the parameter the caller actually supplied")
    void refusalNamesTheSuppliedParameter() {
        // The two list endpoints spell the ordinal differently -- `page` on the card browse
        // and `pageNumber` on the transaction browse -- so the message has to name the one
        // the caller sent, or it points them at a parameter their request does not carry.
        assertThatThrownBy(() -> PageParameterGuard.requirePositivePage(0, "pageNumber"))
                .isInstanceOf(CardDemoException.class)
                .hasMessageStartingWith("pageNumber must be 1 or greater");
    }
}
