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

/**
 * :purpose: Supply the legacy field message for a request property whose submitted value could not
 *  be bound to the property's declared type, so a wrong-typed value reports the SAME message the
 *  legacy screen edit would have reported for it.
 * :output: The caller-facing message for a property, or ``null`` when the implementation has none
 *  for that property.
 * :note: Why this indirection exists. A numeric property is bound by the JSON converter BEFORE any
 *  validator runs, so a value like ``"ABC"`` in a ``BigDecimal`` field fails during
 *  deserialization and never reaches the edit that owns its message. The generic
 *  ``'Malformed request body'`` that resulted told the caller nothing about WHICH field was wrong,
 *  and silently replaced a frozen legacy literal such as ``'Credit Limit is not valid'``.
 * :note: The frozen literals are NOT restated here or anywhere in this library. Each service
 *  contributes one implementation that maps its own property names to the message constants it
 *  ALREADY declares for its screen edits, so there is exactly one copy of every literal and the
 *  bind-time and validate-time messages cannot drift apart. A service that contributes no
 *  implementation keeps the generic malformed-body message.
 */
@FunctionalInterface
public interface TypeMismatchMessageResolver {

    /**
     * :purpose: Resolve the legacy message for a property that failed type conversion.
     * :param propertyPath: the JSON property name reported by the converter, for example
     *  ``acctCreditLimit``. Nested paths are joined with ``.``.
     * :returns: the caller-facing message for that property, or ``null`` when this resolver does
     *  not own it, in which case the generic malformed-body message is used.
     */
    String messageFor(String propertyPath);
}
