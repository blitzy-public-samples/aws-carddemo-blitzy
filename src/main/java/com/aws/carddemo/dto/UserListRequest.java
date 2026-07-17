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
package com.aws.carddemo.dto;

import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable request payload for the CardDemo <em>List Users</em> screen.
 *
 * <p>This DTO is the Java re-platform of the operator-input side of the 3270
 * map handled by the legacy online program {@code COUSR00C} (transaction
 * {@code CU00}) via BMS map {@code COUSR00} / mapset {@code COUSR0A}. It is
 * consumed by {@code UserListController} and drives the paged user browse that
 * {@code UserService} reproduces from the original {@code STARTBR}/
 * {@code READNEXT} logic over the {@code user_security} store. The screen is an
 * administrator-only function (AAP &sect;0.5.3).
 *
 * <p>Only the fields the terminal operator could actually type or transmit are
 * carried here, exactly as the migration re-expresses each 3270 panel as a REST
 * contract rather than a rendered terminal (AAP &sect;0.3.3). On the source map
 * the unprotected (operator-input) fields are the user-id search key
 * {@code USRIDIN} and the ten per-row selection markers
 * {@code SEL0001}&ndash;{@code SEL0010}; every other field on {@code COUSR00}
 * (the header, titles, page indicator, the per-row {@code USRIDnn}/
 * {@code FNAMEnn}/{@code LNAMEnn}/{@code UTYPEnn} output columns, the error line,
 * and the static PF-key hint) is output-only and therefore belongs to
 * {@link UserListResponse}, not to this request.
 *
 * <p>The field-level contract of the original screen is preserved: each
 * component below maps one-to-one to an input field of the {@code COUSR0AI}
 * symbolic copybook, retaining the field's name intent, maximum length, and
 * character (alphanumeric {@code PIC X}) type. The ten selection slots are kept
 * as an ordered, fixed-capacity list so a submitted {@code "U"} (update) or
 * {@code "D"} (delete) marker stays aligned with the corresponding user row of
 * the page &mdash; the routing semantics stated on the screen: <em>"Type 'U' to
 * Update or 'D' to Delete a User from the list"</em>. This DTO carries no
 * business logic; interpreting the markers and the paging action is the
 * responsibility of the controller and service layers.
 *
 * <p>Field-to-source mapping (component &rarr; {@code COUSR0AI} field &rarr;
 * COBOL picture):
 * <table border="1">
 *   <caption>List Users request field contract</caption>
 *   <tr><th>Component</th><th>COBOL field</th><th>Picture</th></tr>
 *   <tr><td>{@code userId}</td><td>{@code USRIDINI}</td><td>{@code PIC X(8)}</td></tr>
 *   <tr><td>{@code rowSelections}</td><td>{@code SEL0001I}&ndash;{@code SEL0010I}</td>
 *       <td>{@code PIC X(1)} &times; 10</td></tr>
 *   <tr><td>{@code action}</td><td>{@code EIBAID} (via {@code CSSTRPFY.cpy})</td>
 *       <td>&mdash;</td></tr>
 * </table>
 *
 * <p>The Attention Identifier the operator pressed is modelled by the shared
 * {@link PfKeyAction} enum (the single Java translation of {@code CSSTRPFY.cpy}).
 * The footer of {@code COUSR00} advertises the keys this screen honours:
 * {@code ENTER}=Continue, {@code F3}=Back, {@code F7}=Backward page and
 * {@code F8}=Forward page &mdash; that is, {@link PfKeyAction#ENTER},
 * {@link PfKeyAction#PF3}, {@link PfKeyAction#PF7} and {@link PfKeyAction#PF8}.
 * The value is optional (an absent action is treated as the default entry path
 * by the controller); enforcing which keys are meaningful is owned by the
 * controller, not by this transport object.
 *
 * <p>Instances are immutable value objects: the {@code rowSelections} list is
 * defensively copied into an unmodifiable list by the canonical constructor, so
 * callers cannot mutate the request after construction. A {@code null} list is
 * normalized to an empty list. Individual slot values may be {@code null} or
 * blank &mdash; that is the faithful representation of an unselected row on the
 * original map &mdash; so the copy intentionally tolerates {@code null}
 * elements.
 *
 * @param userId        the eight-character user-id search / start key typed by
 *                      the operator (legacy {@code USRIDINI}, {@code PIC X(8)});
 *                      may be blank to browse from the beginning
 * @param rowSelections the ordered per-row selection markers, one slot per
 *                      displayed user row, preserving the ten fixed slots of the
 *                      original screen (legacy {@code SEL0001I}&ndash;
 *                      {@code SEL0010I}, {@code PIC X(1)} each); never
 *                      {@code null} after construction
 * @param action        the Attention Identifier (PF key / Enter) the operator
 *                      transmitted (legacy {@code EIBAID} via
 *                      {@code CSSTRPFY.cpy}); may be {@code null}
 */
public record UserListRequest(
        @Size(max = 8) String userId,
        @Size(max = 10) List<String> rowSelections,
        PfKeyAction action) {

    /**
     * Canonical constructor that guarantees value-object immutability for the
     * {@code rowSelections} collection.
     *
     * <p>A {@code null} list is normalized to an empty list; any supplied list
     * is defensively copied into an unmodifiable snapshot. The copy deliberately
     * permits {@code null} (and blank) elements, because an empty selection slot
     * is the legitimate representation of an unselected row carried over from the
     * 3270 {@code SEL000n} fields. This keeps the request safe to share and
     * prevents post-construction mutation without introducing any business
     * behavior.
     */
    public UserListRequest {
        rowSelections = (rowSelections == null)
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(rowSelections));
    }
}
