package com.carddemo.dto;

/**
 * Immutable descriptor for a single role-gated menu entry.
 *
 * <p>This DTO is the element type carried inside {@code MenuResponse}'s option list. It is built
 * server-side by {@code MenuService} from the legacy COBOL menu-option tables and returned to the
 * authenticated caller by the {@code MenuController}. It is the Spring Boot re-expression of one
 * occurrence of the COBOL menu-option tables that backed the CICS main/admin menu programs
 * ({@code COMEN01C} / {@code COADM01C}).</p>
 *
 * <h2>COBOL lineage</h2>
 * <p>Each instance maps one occurrence of either of the two source copybooks:</p>
 * <ul>
 *   <li><strong>{@code COMEN02Y}</strong> &mdash; the regular-user main menu (10 populated options),
 *       whose occurrence layout is
 *       {@code CDEMO-MENU-OPT-NUM PIC 9(02)},
 *       {@code CDEMO-MENU-OPT-NAME PIC X(35)},
 *       {@code CDEMO-MENU-OPT-PGMNAME PIC X(08)},
 *       {@code CDEMO-MENU-OPT-USRTYPE PIC X(01)}.</li>
 *   <li><strong>{@code COADM02Y}</strong> &mdash; the administrator menu (4 populated options),
 *       whose occurrence layout is
 *       {@code CDEMO-ADMIN-OPT-NUM PIC 9(02)},
 *       {@code CDEMO-ADMIN-OPT-NAME PIC X(35)},
 *       {@code CDEMO-ADMIN-OPT-PGMNAME PIC X(08)} (no user-type field).</li>
 * </ul>
 *
 * <h2>Field-type transformation rules</h2>
 * <ul>
 *   <li>{@code PIC 9(02)} (two-digit option number) &rarr; {@code int}.</li>
 *   <li>{@code PIC X(35)} (space-padded display label) &rarr; trimmed {@link String}.</li>
 *   <li>{@code PIC X(08)} (legacy program id) &rarr; {@link String} carried verbatim.</li>
 * </ul>
 *
 * <h2>Role gating</h2>
 * <p>The COBOL {@code CDEMO-MENU-OPT-USRTYPE} flag is deliberately <em>not</em> represented as a
 * field on this DTO. Role gating (regular users receive the 10-option main menu; administrators
 * receive the 4-option admin menu) is decided server-side in {@code MenuService}, which only ever
 * emits the options the authenticated caller is permitted to see. This DTO therefore carries no
 * authorization metadata &mdash; it simply lists an allowed option.</p>
 *
 * <h2>JSON contract</h2>
 * <p>Jackson serializes Java records by component name in declaration order, so an instance renders
 * as, for example:</p>
 * <pre>{@code {"optionNumber":1,"optionName":"Account View","targetProgram":"COACTVWC"}}</pre>
 *
 * <h2>Design constraints</h2>
 * <p>This is a Tier-0 type: a minimal, immutable Java&nbsp;17 {@code record} with no
 * {@code com.carddemo.*} imports, no Lombok, no framework annotations, and no business logic. The
 * {@code targetProgram} value is a transparent passthrough of the legacy program id (for example
 * {@code "COACTVWC"}); mapping a program id to its corresponding REST resource is the
 * responsibility of the client or {@code MenuService}, not of this descriptor.</p>
 *
 * @param optionNumber  the 1-based menu position, from {@code CDEMO-MENU-OPT-NUM} / {@code CDEMO-ADMIN-OPT-NUM}
 *                      ({@code PIC 9(02)}); for example {@code 1} for "Account View".
 * @param optionName    the human-readable menu label, from {@code CDEMO-MENU-OPT-NAME} /
 *                      {@code CDEMO-ADMIN-OPT-NAME} ({@code PIC X(35)}); the COBOL value is
 *                      space-padded to 35 characters and is expected to be trimmed of trailing
 *                      spaces by the producer (for example {@code "Account View"}).
 * @param targetProgram the legacy COBOL program id this option dispatches to, from
 *                      {@code CDEMO-MENU-OPT-PGMNAME} / {@code CDEMO-ADMIN-OPT-PGMNAME}
 *                      ({@code PIC X(08)}); carried verbatim (for example {@code "COACTVWC"}).
 */
public record MenuOptionDto(
        int optionNumber,
        String optionName,
        String targetProgram
) {
}
