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
package com.carddemo.common.batch;

import com.carddemo.common.exception.CardDemoException;
import jakarta.servlet.http.HttpServletRequest;

/**
 * :purpose: Guard the batch-launch endpoints against a request body they do not
 *  read.
 * :output: Nothing on success; a {@link CardDemoException} (HTTP 400) when the
 *  request carries a body.
 * :note: The launch endpoints take their entire contract in the path and the query
 *  string, so a body was simply never bound. A caller who sent
 *  ``{"outputFile":"../../tmp/pwned.txt"}`` therefore received 202 ACCEPTED and a
 *  run that used the DEFAULT file name - the request was accepted and the
 *  instruction in it discarded, which is indistinguishable at the caller from the
 *  instruction having been honoured. Answering 400 makes the mismatch visible at
 *  the point it happens instead of leaving the caller to infer it from the output.
 */
public final class BatchLaunchRequestGuard {

    /**
     * :purpose: Prevent instantiation of this stateless guard.
     */
    private BatchLaunchRequestGuard() {
    }

    /**
     * :purpose: Reject a launch request that carries a body.
     * :param request: the current servlet request.
     * :raises CardDemoException: when a body is present, so the caller is told the
     *  submission was refused rather than being told it was accepted and having its
     *  instruction dropped.
     * :note: Both signals are checked. ``Content-Length`` greater than zero covers a
     *  measured body; a ``Transfer-Encoding`` other than none covers a chunked body,
     *  whose length is -1 until it is read. A ``Content-Type`` alone is not treated
     *  as a body: a client may legitimately declare one on an empty POST.
     */
    public static void requireNoRequestBody(HttpServletRequest request) {
        if (request == null) {
            return;
        }
        boolean hasMeasuredBody = request.getContentLengthLong() > 0L;
        String transferEncoding = request.getHeader("Transfer-Encoding");
        boolean hasChunkedBody = transferEncoding != null && !transferEncoding.isBlank();
        if (hasMeasuredBody || hasChunkedBody) {
            throw new CardDemoException(
                    "This endpoint takes no request body: supply the job parameters as query "
                            + "parameters. A body would have been ignored, so the submission is "
                            + "refused rather than accepted with its instructions dropped.");
        }
    }
}
