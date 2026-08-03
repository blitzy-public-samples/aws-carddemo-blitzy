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
package com.carddemo.common.config;

import io.micrometer.context.ThreadLocalAccessor;

/**
 * :purpose: Teach Micrometer's context-propagation library how to carry the CardDemo business
 *           correlation id across a thread boundary, so work handed to an executor keeps the
 *           correlation id of the request that submitted it.
 * :output: A ``ThreadLocalAccessor`` keyed on the MDC entry ``correlationId``. Registering it in
 *          the ``ContextRegistry`` makes every context-propagating construct — Spring's
 *          ``ContextPropagatingTaskDecorator`` (applied to the ``@Async`` executor and to the
 *          Spring Batch ``TaskExecutorJobLauncher``) and Reactor/RxJava context capture — restore
 *          the id on the receiving thread and clear it afterwards.
 * :note: Without this accessor the batch and asynchronous log lines carried no ``correlationId``
 *        at all: the MDC is a plain ``ThreadLocal``, and the tracing library only propagates
 *        ``traceId``/``spanId``. The value is written through {@link CorrelationIdContext} so the
 *        same sanitization and length bound apply on the receiving thread as on the request thread.
 */
public final class CorrelationIdThreadLocalAccessor implements ThreadLocalAccessor<String> {

    /**
     * :purpose: Context key under which the correlation id is captured and restored; deliberately
     *           identical to the MDC key so a captured context is self-describing in logs.
     */
    public static final String KEY = CorrelationIdContext.CORRELATION_ID_KEY;

    /**
     * :purpose: Identify this accessor's slot in a captured context snapshot.
     * :returns: the context key ``correlationId``.
     */
    @Override
    public Object key() {
        return KEY;
    }

    /**
     * :purpose: Capture the correlation id currently in scope on the submitting thread.
     * :returns: the current correlation id, or ``null`` when none is set (nothing is then captured).
     */
    @Override
    public String getValue() {
        return CorrelationIdContext.getCorrelationId();
    }

    /**
     * :purpose: Install a captured correlation id on the receiving thread.
     * :param value: the correlation id captured from the submitting thread.
     */
    @Override
    public void setValue(String value) {
        CorrelationIdContext.setCorrelationId(value);
    }

    /**
     * :purpose: Clear the correlation id on the receiving thread once the propagated scope closes,
     *           so a pooled thread never leaks one request's id into the next.
     */
    @Override
    public void setValue() {
        CorrelationIdContext.clear();
    }
}
