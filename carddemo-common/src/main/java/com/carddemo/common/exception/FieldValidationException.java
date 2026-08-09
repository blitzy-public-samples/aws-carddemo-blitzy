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
package com.carddemo.common.exception;

import java.util.List;

/**
 * :purpose: Signals that one named request field failed an input edit, carrying both the
 *  legacy message the screen shows on its error line and the identity of the field the
 *  edit rejected. The legacy online programs ran every edit inside the screen program, so
 *  a failed edit set both ``WS-RETURN-MSG`` and the field's own ``FLG-*-NOT-OK`` flag, and
 *  ``3300-SETUP-SCREEN-ATTRS`` then painted that field ``DFHRED`` and parked the cursor on
 *  it. Splitting the edits into a REST service loses the flag unless the field identity
 *  travels with the message, which is what this exception adds: the global handler copies
 *  {@link #getField()} into the ``fieldErrors`` member of the error body so the client can
 *  reproduce the highlight and the cursor placement.
 * :note: The field identity is the name of the member of the REQUEST body that failed, not
 *  a screen widget id — a screen that splits one request field across several 3270 fields
 *  (a date, an SSN, a phone number) owns that mapping.
 */
public class FieldValidationException extends CardDemoException {

    /** :purpose: Serialization version identifier for this exception type. */
    private static final long serialVersionUID = 1L;

    /**
     * :purpose: Names of the request-body members the edit rejected, in the order the
     *  legacy program sets their ``FLG-*-NOT-OK`` flags. A cross-field edit faults more
     *  than one member — ``1280-EDIT-US-STATE-ZIP-CD`` sets both ``FLG-STATE-NOT-OK``
     *  and ``FLG-ZIPCODE-NOT-OK`` — so the identity is a list rather than one name.
     */
    private final List<String> fields;

    /**
     * :purpose: Construct a field-edit failure.
     * :param field: name of the request-body member whose edit failed.
     * :param message: the legacy message the screen reports for the failure.
     */
    public FieldValidationException(String field, String message) {
        this(null, List.of(field), message);
    }

    /**
     * :purpose: Construct a cross-field edit failure that faults several members at once.
     * :param errorCode: the optional domain or legacy error code.
     * :param fields: the request-body members the edit rejected.
     * :param message: the legacy message the screen reports for the failure.
     */
    public FieldValidationException(String errorCode, List<String> fields, String message) {
        super(errorCode, message);
        this.fields = List.copyOf(fields);
    }

    /**
     * :purpose: Expose the first request-body member whose edit failed, which is the one
     *  the legacy screen parks the cursor on.
     * :returns: the field name, never ``null`` for an exception raised by an edit.
     */
    public String getField() {
        return fields.get(0);
    }

    /**
     * :purpose: Expose every request-body member the edit rejected.
     * :returns: an unmodifiable list holding at least one field name.
     */
    public List<String> getFields() {
        return fields;
    }
}
