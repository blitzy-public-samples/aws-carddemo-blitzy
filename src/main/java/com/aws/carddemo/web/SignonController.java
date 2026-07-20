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
package com.aws.carddemo.web;

import com.aws.carddemo.dto.PfKeyAction;
import com.aws.carddemo.dto.SignonRequest;
import com.aws.carddemo.dto.SignonResponse;
import com.aws.carddemo.mapper.SignonMapper;
import com.aws.carddemo.service.SignonService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * REST controller for the CardDemo Sign-on screen &mdash; the Java re-expression of the online
 * COBOL program {@code COSGN00C} (CICS transaction {@code CC00}, mapset {@code COSGN0A}), whose
 * source is retained for reference at {@code legacy/cbl/COSGN00C.cbl}.
 *
 * <p>This is the authentication entry point of the application and the <strong>only</strong>
 * unauthenticated controller: {@code config/SecurityConfig} grants {@code permitAll} to
 * {@code /api/v1/auth/**}, which is why every endpoint here is mapped beneath that prefix.</p>
 *
 * <h2>Pseudo-conversational translation (AAP &sect;0.7.1 hotspot H1)</h2>
 * <p>The legacy program is CICS pseudo-conversational: on first entry ({@code EIBCALEN = 0}) it
 * sends the blank sign-on map, and on re-entry it {@code RECEIVE}s the map and dispatches on the
 * Attention Identifier via {@code EVALUATE EIBAID}. That stateful terminal flow is translated to a
 * pair of stateless HTTP operations without changing the screen-entry behavior:</p>
 * <ul>
 *   <li><strong>{@code GET /api/v1/auth/signon}</strong> reproduces the {@code EIBCALEN = 0}
 *       first-entry {@code SEND}: it returns the blank sign-on screen (no error message, blank user
 *       id, current date/time in the header).</li>
 *   <li><strong>{@code POST /api/v1/auth/signon}</strong> reproduces the re-entry
 *       {@code RECEIVE MAP} plus {@code EVALUATE EIBAID}: it accepts the operator's input and the
 *       key that was pressed (the {@link SignonRequest#action() action}) and routes accordingly.</li>
 * </ul>
 *
 * <h2>{@code EVALUATE EIBAID} routing (parity)</h2>
 * <table border="1">
 *   <caption>COBOL AID branch &rarr; controller behavior</caption>
 *   <tr><th>COBOL branch</th><th>Controller behavior</th></tr>
 *   <tr><td>{@code WHEN DFHENTER}</td>
 *       <td>Delegate to {@link SignonService#signon(String, String)}. On success, echo the user id
 *           and emit the navigation headers (below); on failure, re-display the sign-on screen with
 *           the service's exact message and no navigation header (HTTP&nbsp;200).</td></tr>
 *   <tr><td>{@code WHEN DFHPF3}</td>
 *       <td>Reproduce the {@code SEND-PLAIN-TEXT} exit: return HTTP&nbsp;200 with the
 *           {@code CCDA-MSG-THANK-YOU} exit message and no navigation header (the session ends).</td></tr>
 *   <tr><td>{@code WHEN OTHER}</td>
 *       <td>Return HTTP&nbsp;200 with the {@code CCDA-MSG-INVALID-KEY} message and re-display the
 *           sign-on screen; the credential service is never called.</td></tr>
 * </table>
 *
 * <h2>Navigation ({@code EXEC CICS XCTL} &rarr; response headers)</h2>
 * <p>A successful sign-on ended the COBOL program with {@code EXEC CICS XCTL} to the Admin Menu
 * ({@code COADM01C}) for an administrator or the Main Menu ({@code COMEN01C}) for a standard user
 * ({@code legacy/cbl/COSGN00C.cbl} L231/L236). Because HTTP has no direct {@code XCTL} analogue, the
 * resolved next screen is surfaced as response headers &mdash; {@value #HEADER_NEXT_PROGRAM} carries
 * the target program name and {@value #HEADER_NEXT_TRANSACTION} carries the derived CICS transaction
 * id ({@code COADM01C}&rarr;{@code CA00}, {@code COMEN01C}&rarr;{@code CM00}) &mdash; leaving the
 * actual redirect to the client, exactly as the terminal user would next transmit that transaction.</p>
 *
 * <h2>Thin controller &amp; confidentiality (AAP &sect;0.7.3, &sect;0.9.3)</h2>
 * <p>The controller holds no business logic: it performs no credential comparison and no data
 * access &mdash; all of that lives in {@link SignonService}, which returns its outcome as a
 * {@link SignonService.SignonResult} value object rather than throwing, so this class contains no
 * {@code try}/{@code catch}. The submitted {@link SignonRequest#password() password} is passed
 * <em>only</em> to {@link SignonService#signon(String, String)} and is never read for any other
 * purpose, never logged, and never echoed: the {@link SignonResponse} contract has no password
 * field. No logging is performed in this class.</p>
 *
 * <p>Collaborators are supplied exclusively by constructor injection (no field injection, no
 * {@code @Autowired}, no Lombok), keeping the dependencies explicit, {@code final}, and testable.</p>
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Sign-on", description = "COSGN00C / CC00 \u2014 CardDemo authentication entry screen")
public class SignonController {

    /**
     * This program's name, reproducing the COBOL {@code WS-PGMNAME} literal
     * ({@code 'COSGN00C'}); echoed to the {@code PGMNAMEO} header field.
     */
    private static final String PROGRAM_NAME = "COSGN00C";

    /**
     * This program's CICS transaction id, reproducing the COBOL {@code WS-TRANID} literal
     * ({@code 'CC00'}); echoed to the {@code TRNNAMEO} header field.
     */
    private static final String TRANSACTION_NAME = "CC00";

    /**
     * Navigation target for an administrator, reproducing {@code EXEC CICS XCTL PROGRAM('COADM01C')}
     * ({@code legacy/cbl/COSGN00C.cbl} L231-L233). Kept identical to
     * {@link SignonService#PROGRAM_ADMIN_MENU} and used both as the emitted
     * {@value #HEADER_NEXT_PROGRAM} value and to derive the admin transaction id.
     */
    private static final String PROGRAM_ADMIN_MENU = "COADM01C";

    /**
     * CICS transaction id of the Admin Menu, emitted as {@value #HEADER_NEXT_TRANSACTION} when the
     * authenticated user is an administrator.
     */
    private static final String TRANID_ADMIN_MENU = "CA00";

    /**
     * Navigation target for a standard user, reproducing {@code EXEC CICS XCTL PROGRAM('COMEN01C')}
     * ({@code legacy/cbl/COSGN00C.cbl} L236-L238). Kept identical to
     * {@link SignonService#PROGRAM_MAIN_MENU}.
     */
    private static final String PROGRAM_MAIN_MENU = "COMEN01C";

    /**
     * CICS transaction id of the Main Menu, emitted as {@value #HEADER_NEXT_TRANSACTION} for any
     * non-administrator user.
     */
    private static final String TRANID_MAIN_MENU = "CM00";

    /**
     * First screen-header title line, reproducing {@code CCDA-TITLE01} from copybook
     * {@code legacy/cpy/COTTL01Y.cpy} verbatim as a fixed 40-character ({@code PIC X(40)}) value;
     * moved to {@code TITLE01O} by the legacy {@code POPULATE-HEADER-INFO} paragraph.
     */
    private static final String TITLE01 = "      AWS Mainframe Modernization       ";

    /**
     * Second screen-header title line, reproducing {@code CCDA-TITLE02} from copybook
     * {@code legacy/cpy/COTTL01Y.cpy} verbatim as a fixed 40-character ({@code PIC X(40)}) value;
     * moved to {@code TITLE02O} by {@code POPULATE-HEADER-INFO}.
     */
    private static final String TITLE02 = "              CardDemo                  ";

    /**
     * PF3 exit message, reproducing {@code CCDA-MSG-THANK-YOU} from copybook
     * {@code legacy/cpy/CSMSG01Y.cpy} (trailing pad trimmed). Surfaced on the {@code errorMessage}
     * status line when the operator presses PF3 to leave the application.
     */
    private static final String MSG_THANK_YOU = "Thank you for using CardDemo application...";

    /**
     * Invalid-key message, reproducing {@code CCDA-MSG-INVALID-KEY} from copybook
     * {@code legacy/cpy/CSMSG01Y.cpy} (trailing pad trimmed). Surfaced when the operator transmits
     * any key other than Enter or PF3 (the COBOL {@code WHEN OTHER} branch).
     */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    /**
     * CICS application identifier ({@code APPLIDO}). The legacy program obtained it at run time via
     * {@code EXEC CICS ASSIGN APPLID}; there is no such runtime source outside CICS, so this is an
     * explicit placeholder ({@code null}) &mdash; the single point at which a configuration-sourced
     * value can later be wired in. Per the migration plan this header value may be {@code null}/blank.
     */
    private static final String APPLID = null;

    /**
     * CICS system identifier ({@code SYSIDO}), the counterpart of {@link #APPLID}, obtained by the
     * legacy program via {@code EXEC CICS ASSIGN SYSID}. Also a placeholder ({@code null}) pending a
     * later configuration source.
     */
    private static final String SYSID = null;

    /**
     * Response header name carrying the resolved next program on a successful sign-on &mdash; the
     * stateless-HTTP stand-in for {@code EXEC CICS XCTL PROGRAM(...)}.
     */
    private static final String HEADER_NEXT_PROGRAM = "X-CardDemo-Next-Program";

    /**
     * Response header name carrying the CICS transaction id of the resolved next screen, so a client
     * can transmit it exactly as a terminal user would.
     */
    private static final String HEADER_NEXT_TRANSACTION = "X-CardDemo-Next-Transaction";

    /**
     * Sign-on business service (credential validation + navigation-target resolution); the Java
     * replacement for the COBOL {@code PROCESS-ENTER-KEY} / {@code READ-USER-SEC-FILE} paragraphs.
     */
    private final SignonService signonService;

    /**
     * Hand-written mapper that assembles the outbound {@link SignonResponse} (the {@code COSGN0AO}
     * symbolic map), keeping the sensitive-field discipline at a single auditable boundary.
     */
    private final SignonMapper signonMapper;

    /**
     * Creates the controller with its collaborators. Constructor injection is used exclusively (no
     * field {@code @Autowired}, no Lombok) so the dependencies are explicit, {@code final}, and
     * straightforward to supply in tests.
     *
     * @param signonService the sign-on business service; must not be {@code null}
     * @param signonMapper  the sign-on response mapper; must not be {@code null}
     */
    public SignonController(SignonService signonService, SignonMapper signonMapper) {
        this.signonService = signonService;
        this.signonMapper = signonMapper;
    }

    /**
     * Displays the blank sign-on screen, reproducing the COBOL first-entry path where
     * {@code EIBCALEN = 0} clears the map and issues {@code SEND-SIGNON-SCREEN}
     * ({@code legacy/cbl/COSGN00C.cbl} MAIN-PARA).
     *
     * <p>The returned {@link SignonResponse} carries the populated header (transaction name, program
     * name, both title lines, and the current date/time) with a blank user id and no error message.
     * No credential service is invoked and no navigation header is emitted.</p>
     *
     * @return HTTP&nbsp;200 with the blank sign-on screen
     */
    @GetMapping("/signon")
    @Operation(summary = "Display the blank sign-on screen",
            description = "Reproduces the COSGN00C first-entry SEND (EIBCALEN = 0): a blank sign-on "
                    + "screen with the populated header and current date/time.")
    public ResponseEntity<SignonResponse> signonScreen() {
        return ResponseEntity.ok(screen(null, null, LocalDateTime.now()));
    }

    /**
     * Processes a sign-on submission, reproducing the COBOL re-entry path
     * ({@code RECEIVE MAP} followed by {@code EVALUATE EIBAID}). The body carries the operator input
     * and the key that was pressed; routing is by {@link SignonRequest#action()}:
     * <ul>
     *   <li>{@link PfKeyAction#ENTER} (and a {@code null} action, treated as the default submit)
     *       &rarr; delegate to {@link SignonService#signon(String, String)} and, on success, emit
     *       the navigation headers; on failure re-display the screen with the service message.</li>
     *   <li>{@link PfKeyAction#PF3} &rarr; the exit path: HTTP&nbsp;200 with the thank-you message
     *       and no navigation header.</li>
     *   <li>any other key &rarr; HTTP&nbsp;200 with the invalid-key message; the service is not
     *       called.</li>
     * </ul>
     *
     * <p>The request body is intentionally <strong>not</strong> annotated {@code @Valid}: the COBOL
     * program treats a blank user id or blank password as same-screen business messages
     * (HTTP&nbsp;200), not hard rejections, so those cases are produced by {@link SignonService}
     * rather than by bean-validation failures. Every branch therefore returns HTTP&nbsp;200; the
     * distinction between a successful and an unsuccessful sign-on is conveyed by the presence of the
     * navigation headers and the {@code errorMessage} field, mirroring the terminal contract.</p>
     *
     * @param request the sign-on request carrying {@code userId}, {@code password}
     *                (write-only, never echoed), and the {@code action} (attention key)
     * @return HTTP&nbsp;200 with the resulting sign-on screen; on a successful Enter, additionally
     *         carrying the {@value #HEADER_NEXT_PROGRAM} and {@value #HEADER_NEXT_TRANSACTION} headers
     */
    @PostMapping("/signon")
    @Operation(summary = "Submit the sign-on screen",
            description = "Reproduces the COSGN00C re-entry EVALUATE EIBAID: ENTER validates "
                    + "credentials, PF3 exits, any other key is rejected. Blank user id/password are "
                    + "returned as HTTP 200 same-screen messages, not validation errors; a user id or "
                    + "password longer than the BMS X(8) field length is rejected with HTTP 400.")
    public ResponseEntity<SignonResponse> signon(@Valid @RequestBody SignonRequest request) {
        final LocalDateTime now = LocalDateTime.now();
        // A null action defaults to ENTER: the primary submit path (COBOL WHEN DFHENTER).
        final PfKeyAction action = (request.action() == null) ? PfKeyAction.ENTER : request.action();

        return switch (action) {
            // WHEN DFHENTER -> PROCESS-ENTER-KEY (credential validation + navigation).
            case ENTER -> handleEnter(request, now);
            // WHEN DFHPF3 -> SEND-PLAIN-TEXT with CCDA-MSG-THANK-YOU; the session ends, so no
            // navigation header is emitted and the (erased) screen echoes no user id.
            case PF3 -> ResponseEntity.ok(screen(null, MSG_THANK_YOU, now));
            // WHEN OTHER -> invalid key: re-display the sign-on screen with the message; the
            // credential service is deliberately not called.
            default -> ResponseEntity.ok(screen(request.userId(), MSG_INVALID_KEY, now));
        };
    }

    /**
     * Handles the Enter (submit) branch by delegating to {@link SignonService} and translating its
     * {@link SignonService.SignonResult} into an HTTP response.
     *
     * <p>On failure the sign-on screen is re-displayed with the service's exact message and no
     * navigation header (the COBOL same-screen {@code SEND-SIGNON-SCREEN}). On success the resolved
     * next program is emitted in {@value #HEADER_NEXT_PROGRAM} and its transaction id in
     * {@value #HEADER_NEXT_TRANSACTION} &mdash; {@code CA00} when the target is the Admin Menu,
     * otherwise {@code CM00} &mdash; and the (upper-cased) user id from the result is echoed with no
     * error message. The password is never referenced here; it was passed straight through to the
     * service and nowhere else.</p>
     *
     * @param request the sign-on request (its {@code userId} and {@code password} feed the service)
     * @param now     the timestamp used to render the screen header
     * @return the HTTP response for the Enter branch
     */
    private ResponseEntity<SignonResponse> handleEnter(SignonRequest request, LocalDateTime now) {
        final SignonService.SignonResult result =
                signonService.signon(request.userId(), request.password());

        if (!result.success()) {
            // Same-screen redisplay with the exact business message (blank id/password, user not
            // found, wrong password, or unable-to-verify). No navigation header.
            return ResponseEntity.ok(screen(request.userId(), result.message(), now));
        }

        // Success: resolve the next transaction id from the service-provided target program and
        // surface the XCTL destination as response headers.
        final String nextProgram = result.targetProgram();
        final String nextTransaction =
                PROGRAM_ADMIN_MENU.equals(nextProgram) ? TRANID_ADMIN_MENU : TRANID_MAIN_MENU;

        return ResponseEntity.ok()
                .header(HEADER_NEXT_PROGRAM, nextProgram)
                .header(HEADER_NEXT_TRANSACTION, nextTransaction)
                .body(screen(result.userId(), null, now));
    }

    /**
     * Assembles a sign-on screen response through {@link SignonMapper}, centralizing the single
     * nine-argument mapper invocation so the header constants and argument order are defined in one
     * place.
     *
     * <p>The mapper's {@code toErrorResponse} builder is used for every outcome, including success:
     * on this screen only the (non-secret) user id is echoed, so a {@code null} {@code errorMessage}
     * yields the success/blank screen and a non-{@code null} message yields an error screen. The
     * password is never involved because {@link SignonResponse} has no password field.</p>
     *
     * @param userId       the non-secret user id to echo ({@code null} for a blank/erased field)
     * @param errorMessage the status/error line, or {@code null} for none
     * @param now          the timestamp used to render the current date and time
     * @return a fully populated {@link SignonResponse}; never {@code null}
     */
    private SignonResponse screen(String userId, String errorMessage, LocalDateTime now) {
        return signonMapper.toErrorResponse(
                userId,
                APPLID,
                SYSID,
                errorMessage,
                now,
                TRANSACTION_NAME,
                TITLE01,
                PROGRAM_NAME,
                TITLE02);
    }
}
