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
package com.carddemo.account.service;

import com.carddemo.common.config.TypeMismatchMessageResolver;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * :purpose: Contribute the account service's own field messages to the shared error handling, so a
 *  request that carries a non-numeric value in a numeric account field reports the frozen
 *  ``COACTUPC`` literal for that field instead of a generic malformed-body message.
 * :output: For every numeric property of the account update request, the message its screen edit
 *  reports for an invalid value; ``null`` for any other property, which leaves the generic
 *  malformed-body message in place.
 * :note: The messages are read from {@link AccountUpdateValidator#typeMismatchMessages()} rather
 *  than restated here, so the message a field reports when it fails to BIND and the message it
 *  reports when it binds and then fails its EDIT are the same string by construction.
 * :note: Also covers the snapshot (``old*``) counterparts of those properties. A full-snapshot
 *  submission carries each amount twice, and a wrong-typed value in the snapshot half must name the
 *  field just as clearly as one in the new-value half.
 */
@Component
public class AccountTypeMismatchMessageResolver implements TypeMismatchMessageResolver {

    /** :purpose: Prefix the request uses for the display-time snapshot of a field. */
    private static final String SNAPSHOT_PREFIX = "old";

    /** :purpose: Property-name-to-message map, including the snapshot counterparts. */
    private final Map<String, String> messages;

    /**
     * :purpose: Build the resolver, deriving the snapshot property names from the new-value ones.
     */
    public AccountTypeMismatchMessageResolver() {
        Map<String, String> base = AccountUpdateValidator.typeMismatchMessages();
        Map<String, String> all = new HashMap<>(base);
        base.forEach((property, message) -> all.put(snapshotNameOf(property), message));
        this.messages = Map.copyOf(all);
    }

    /**
     * :purpose: Derive the snapshot property name for a new-value property, matching the request's
     *  ``oldAcctCurrBal`` style of naming.
     * :param property: the new-value property name, for example ``acctCurrBal``.
     * :returns: the snapshot property name, for example ``oldAcctCurrBal``.
     */
    private static String snapshotNameOf(String property) {
        return SNAPSHOT_PREFIX + Character.toUpperCase(property.charAt(0)) + property.substring(1);
    }

    /**
     * :purpose: Resolve the legacy message for an account property that failed type conversion.
     * :param propertyPath: the JSON property name reported by the converter.
     * :returns: the frozen message for that property, or ``null`` when this service does not own it.
     */
    @Override
    public String messageFor(String propertyPath) {
        return propertyPath == null ? null : messages.get(propertyPath);
    }
}
