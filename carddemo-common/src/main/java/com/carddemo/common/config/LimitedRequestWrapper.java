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

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * :purpose: Wrap a request whose body length is not declared (chunked transfer) so the
 *     number of bytes actually read is counted and capped, closing the bypass where a
 *     caller omits ``Content-Length`` to defeat a header-only size check.
 * :output: The original body content, up to the configured cap; beyond it, reads raise
 *     {@link RequestSizeLimitFilter.RequestSizeExceededException} so the filter can
 *     answer ``413``.
 */
class LimitedRequestWrapper extends HttpServletRequestWrapper {

    private final long maxBodyBytes;
    private ServletInputStream limitedStream;
    private BufferedReader limitedReader;

    /**
     * :purpose: Wrap the request with a byte cap.
     * :param request: the request whose body must be bounded.
     * :param maxBodyBytes: maximum number of body bytes that may be read.
     */
    LimitedRequestWrapper(HttpServletRequest request, long maxBodyBytes) {
        super(request);
        this.maxBodyBytes = maxBodyBytes;
    }

    /**
     * :purpose: Return the counting input stream for the request body.
     * :returns: a {@link ServletInputStream} that fails once the cap is exceeded.
     * :raises IOException: if the underlying stream cannot be obtained.
     */
    @Override
    public ServletInputStream getInputStream() throws IOException {
        if (limitedStream == null) {
            limitedStream = new CountingServletInputStream(super.getInputStream(), maxBodyBytes);
        }
        return limitedStream;
    }

    /**
     * :purpose: Return a reader over the counting input stream so a text body is bounded
     *     the same way as a binary one.
     * :returns: a {@link BufferedReader} over the capped stream.
     * :raises IOException: if the underlying stream cannot be obtained.
     */
    @Override
    public BufferedReader getReader() throws IOException {
        if (limitedReader == null) {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            limitedReader = new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
        return limitedReader;
    }

    /**
     * :purpose: Delegating {@link ServletInputStream} that counts the bytes read and
     *     refuses to return more than the configured maximum.
     */
    private static final class CountingServletInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long maxBodyBytes;
        private long bytesRead;

        /**
         * :purpose: Wrap the container stream with a byte cap.
         * :param delegate: the container's request input stream.
         * :param maxBodyBytes: maximum number of bytes that may be read.
         */
        private CountingServletInputStream(ServletInputStream delegate, long maxBodyBytes) {
            this.delegate = delegate;
            this.maxBodyBytes = maxBodyBytes;
        }

        /**
         * :purpose: Read one byte, enforcing the cap.
         * :returns: the byte read, or ``-1`` at end of stream.
         * :raises IOException: if the underlying read fails.
         */
        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value != -1) {
                count(1);
            }
            return value;
        }

        /**
         * :purpose: Read into a buffer, enforcing the cap.
         * :param buffer: destination buffer.
         * :param offset: offset within the buffer.
         * :param length: maximum number of bytes to read.
         * :returns: the number of bytes read, or ``-1`` at end of stream.
         * :raises IOException: if the underlying read fails.
         */
        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = delegate.read(buffer, offset, length);
            if (read > 0) {
                count(read);
            }
            return read;
        }

        /**
         * :purpose: Accumulate the byte count and enforce the cap.
         * :param increment: number of bytes just read.
         */
        private void count(int increment) {
            bytesRead += increment;
            if (bytesRead > maxBodyBytes) {
                throw new RequestSizeLimitFilter.RequestSizeExceededException();
            }
        }

        /**
         * :returns: ``true`` when the underlying stream is fully consumed.
         */
        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        /**
         * :returns: ``true`` when the underlying stream can be read without blocking.
         */
        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        /**
         * :purpose: Register an asynchronous read listener on the underlying stream.
         * :param readListener: the listener to register.
         */
        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }

        /**
         * :purpose: Close the underlying stream.
         * :raises IOException: if closing fails.
         */
        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
