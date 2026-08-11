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

import com.carddemo.common.exception.CardDemoException;

/**
 * :purpose: Guard the paged list endpoints against a page number that identifies no
 *  screen.
 * :output: Nothing when the value is absent or identifies a screen; a
 *  {@link CardDemoException} (HTTP 400) when it is zero or negative.
 * :note: The page number is a REST-only parameter: the legacy screens paged with PF7
 *  and PF8 from the keys of the displayed rows and never carried a page ordinal, so
 *  there is no legacy literal for a bad one. It was silently clamped to the first
 *  page, which makes a caller's mistake invisible - ``page=-1`` and ``page=1`` returned
 *  byte-identical responses, so a paging bug in a client read as working software and
 *  an operator could be shown the first screen while believing they were elsewhere.
 *  Answering 400 reports the value as unusable at the point it is supplied.
 */
public final class PageParameterGuard {

    /** :purpose: Lowest page ordinal that identifies a screen. */
    private static final int FIRST_PAGE = 1;

    /**
     * :purpose: Prevent instantiation of this stateless guard.
     */
    private PageParameterGuard() {
    }

    /**
     * :purpose: Reject a supplied page ordinal that is zero or negative, and resolve an
     *  absent one to the first page.
     * :param page: the supplied page ordinal, or ``null`` when the caller omitted it.
     * :param parameterName: the query-parameter name to name in the refusal, so the
     *  caller is told which value was unusable.
     * :returns: the page ordinal to use: the supplied value, or the first page when it
     *  was omitted.
     * :raises CardDemoException: when the value is present and not at least one.
     */
    public static int requirePositivePage(Integer page, String parameterName) {
        if (page == null) {
            return FIRST_PAGE;
        }
        if (page < FIRST_PAGE) {
            throw new CardDemoException(parameterName + " must be " + FIRST_PAGE
                    + " or greater; " + page + " identifies no page of results.");
        }
        return page;
    }
}
