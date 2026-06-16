package com.carddemo.dto;

import java.util.List;

/**
 * Immutable JSON response body returned by the role-gated menu endpoints
 * (for example {@code GET /menu}) of the CardDemo application.
 *
 * <p>This DTO is the Spring Boot re-expression of the two legacy CICS menu programs that
 * presented a 3270 option list and routed the operator to the chosen function:</p>
 * <ul>
 *   <li>{@code COMEN01C} &mdash; the regular-user "Main Menu", whose option table is the
 *       copybook {@code COMEN02Y} ({@code CARDDEMO-MAIN-MENU-OPTIONS}, 10 populated options).</li>
 *   <li>{@code COADM01C} &mdash; the administrator "Admin Menu", whose option table is the
 *       copybook {@code COADM02Y} ({@code CARDDEMO-ADMIN-MENU-OPTIONS}, 4 populated options).</li>
 * </ul>
 *
 * <p>The BMS screen maps {@code COMEN01} / {@code COADM01} that rendered these menus on the
 * 3270 terminal are <strong>retired</strong> in the migrated system; only their field
 * semantics survive, re-expressed as this JSON contract. The terminal is replaced by a REST
 * client that receives the option list and dispatches itself.</p>
 *
 * <h2>Purpose</h2>
 * <p>{@code MenuResponse} is a thin <em>envelope</em>: it pairs a {@link #menuType() menuType}
 * discriminator with the {@link #options() options} the authenticated caller is permitted to
 * use. It carries no behaviour and performs no authorization &mdash; it merely transports the
 * already-filtered option list that {@code MenuService} produced.</p>
 *
 * <h2>Role gating (decided server-side, not here)</h2>
 * <p>In the legacy programs the per-option {@code CDEMO-MENU-OPT-USRTYPE} flag gated which
 * entries a user could select. In the migrated design that gate lives entirely in
 * {@code MenuService}: a regular {@code USER} receives the 10-option main menu
 * ({@code menuType == "MAIN"}); an {@code ADMIN} receives the 4-option admin menu
 * ({@code menuType == "ADMIN"}) and, per the legacy behaviour, may additionally reach the main
 * menu. This DTO is deliberately built to contain <strong>only</strong> options the caller may
 * actually use, so it exposes no user-type field and embeds no filtering logic. Mirroring the
 * COBOL {@code CDEMO-MENU-OPT-USRTYPE} gate is the service's responsibility, not the DTO's.</p>
 *
 * <h2>Tier-0 dependency contract</h2>
 * <p>This is a Tier-0 type: a minimal, immutable Java&nbsp;17 {@code record} with no Lombok, no
 * framework annotations, and no business logic. Its only non-JDK reference is the sibling
 * {@link MenuOptionDto} in this same {@code com.carddemo.dto} package (the element type of the
 * option list); it imports no entities and nothing from any other application package, which
 * keeps the API contract decoupled from the persistence model and free of package cycles.</p>
 *
 * <h2>JSON contract</h2>
 * <p>Because this type is a Java&nbsp;17 {@code record}, Jackson (Spring Boot&nbsp;3.2 /
 * Jackson&nbsp;2.15+) serializes it natively &mdash; each record component name becomes a JSON
 * key in declaration order, so no {@code @JsonProperty} annotations are required. A regular-user
 * main menu therefore renders as:</p>
 * <pre>{@code
 * {
 *   "menuType": "MAIN",
 *   "options": [
 *     {"optionNumber": 1,  "optionName": "Account View",        "targetProgram": "COACTVWC"},
 *     {"optionNumber": 2,  "optionName": "Account Update",      "targetProgram": "COACTUPC"},
 *     {"optionNumber": 3,  "optionName": "Credit Card List",    "targetProgram": "COCRDLIC"},
 *     {"optionNumber": 4,  "optionName": "Credit Card View",    "targetProgram": "COCRDSLC"},
 *     {"optionNumber": 5,  "optionName": "Credit Card Update",  "targetProgram": "COCRDUPC"},
 *     {"optionNumber": 6,  "optionName": "Transaction List",    "targetProgram": "COTRN00C"},
 *     {"optionNumber": 7,  "optionName": "Transaction View",    "targetProgram": "COTRN01C"},
 *     {"optionNumber": 8,  "optionName": "Transaction Add",     "targetProgram": "COTRN02C"},
 *     {"optionNumber": 9,  "optionName": "Transaction Reports", "targetProgram": "CORPT00C"},
 *     {"optionNumber": 10, "optionName": "Bill Payment",        "targetProgram": "COBIL00C"}
 *   ]
 * }
 * }</pre>
 * <p>and an administrator admin menu renders as:</p>
 * <pre>{@code
 * {
 *   "menuType": "ADMIN",
 *   "options": [
 *     {"optionNumber": 1, "optionName": "User List (Security)",   "targetProgram": "COUSR00C"},
 *     {"optionNumber": 2, "optionName": "User Add (Security)",    "targetProgram": "COUSR01C"},
 *     {"optionNumber": 3, "optionName": "User Update (Security)", "targetProgram": "COUSR02C"},
 *     {"optionNumber": 4, "optionName": "User Delete (Security)", "targetProgram": "COUSR03C"}
 *   ]
 * }
 * }</pre>
 *
 * @param menuType the menu discriminator selected server-side from the authenticated role:
 *                 {@code "MAIN"} for the regular-user menu sourced from {@code COMEN01C} /
 *                 {@code COMEN02Y} (10 options), or {@code "ADMIN"} for the administrator menu
 *                 sourced from {@code COADM01C} / {@code COADM02Y} (4 options)
 * @param options  the role-appropriate, already-filtered list of selectable menu entries (one
 *                 {@link MenuOptionDto} per allowed option, in display order); the list contains
 *                 only options the caller is permitted to use because role gating is performed by
 *                 {@code MenuService} before this envelope is built
 */
public record MenuResponse(
        String menuType,
        List<MenuOptionDto> options
) {
}
