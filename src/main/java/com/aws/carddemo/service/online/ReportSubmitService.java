/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.service.online;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionException;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

import com.aws.carddemo.dto.CardDemoContext;
import com.aws.carddemo.dto.screen.CORPT00Form;
import com.aws.carddemo.util.DateConversionService;
import com.aws.carddemo.util.DateConversionService.DateValidationResult;

/**
 * Transaction-report submission online service, the Java migration of the CICS COBOL program
 * {@code CORPT00C} (the AWS CardDemo online "Print Transaction reports" transaction).
 *
 * <p><b>Origin:</b> {@code legacy/cbl/CORPT00C.cbl} &mdash; program {@code CORPT00C}, CICS
 * transaction id {@code CR00}. This service preserves the original program's control flow
 * one-for-one: each business COBOL paragraph becomes exactly one Java method (AAP &sect;0.3.3,
 * &sect;0.4.1).</p>
 *
 * <h2>What the program does (COBOL parity)</h2>
 * <p>{@code CORPT00C} performs <em>no</em> VSAM/file I/O. Its sole job is to build a JCL job stream
 * and submit it to the JES2 internal reader through an extra-partition transient-data queue
 * ({@code EXEC CICS WRITEQ TD QUEUE('JOBS')} in {@code WIRTE-JOBSUB-TDQ}). In the modernized
 * architecture that submission becomes a single {@link JobLauncher#run(Job, JobParameters)} call
 * that launches the Spring Batch job produced by
 * {@code com.aws.carddemo.batch.TransactionReportJobConfig} (bean {@code transactionReportJob},
 * itself the migration of {@code CBTRN03C} + {@code TRANREPT.jcl}/{@code TRANREPT.prc}). Launching a
 * pre-defined batch job introduces <b>no new external interface</b> (no MQ, no REST) &mdash; it is a
 * job-launch only, exactly as the COBOL enqueued JCL to the internal reader (AAP &sect;0.2.2,
 * &sect;0.6.3, &sect;0.6.4).</p>
 *
 * <h2>Paragraph &rarr; method mapping</h2>
 * <ul>
 *   <li>{@code MAIN-PARA} &rarr; {@link #mainEntry(AidKey, CORPT00Form)}</li>
 *   <li>{@code PROCESS-ENTER-KEY} &rarr; {@link #processEnterKey(CORPT00Form)}</li>
 *   <li>{@code SUBMIT-JOB-TO-INTRDR} &rarr; {@link #submitJobToIntrdr(CORPT00Form, String, String,
 *       String)}</li>
 *   <li>{@code WIRTE-JOBSUB-TDQ} (COBOL misspelling of "write") &rarr;
 *       {@link #writeJobSubmitTdq(String, String, String)}</li>
 *   <li>{@code RETURN-TO-PREV-SCREEN} &rarr; {@link #returnToPrevScreen()}</li>
 *   <li>{@code INITIALIZE-ALL-FIELDS} &rarr; {@link #initializeAllFields(CORPT00Form)}</li>
 * </ul>
 * <p>The presentation paragraphs {@code SEND-TRNRPT-SCREEN}, {@code RECEIVE-TRNRPT-SCREEN},
 * {@code POPULATE-HEADER-INFO} and {@code RETURN-TO-CICS} are 3270/BMS plumbing and belong to the
 * paired {@code ReportController}, not to this service (AAP &sect;0.3.4).</p>
 *
 * <h2>Pseudo-conversational termination semantics</h2>
 * <p>In the COBOL, {@code SEND-TRNRPT-SCREEN} ends with {@code GO TO RETURN-TO-CICS}, which executes
 * {@code EXEC CICS RETURN}; therefore <em>every</em> {@code PERFORM SEND-TRNRPT-SCREEN} terminates
 * the transaction. Consequently the first validation/confirmation branch that would send the screen
 * wins and no later branch executes. This service reproduces that behavior by <b>returning a
 * {@link ReportSubmitResult} immediately</b> from every such branch (the same convention used by the
 * sibling online services).</p>
 *
 * <h2>Report types (paragraph {@code PROCESS-ENTER-KEY})</h2>
 * <ul>
 *   <li><b>Monthly</b> &mdash; the current calendar month: {@code start} = the first day of the
 *       current month, {@code end} = the last day of the current month (the COBOL adds one month to
 *       the first-of-month and subtracts one day).</li>
 *   <li><b>Yearly</b> &mdash; the current calendar year: {@code start} = {@code YYYY-01-01},
 *       {@code end} = {@code YYYY-12-31}.</li>
 *   <li><b>Custom</b> &mdash; a user-entered start/end range whose MM/DD/YYYY parts are validated in
 *       the exact COBOL order (empty checks, then numeric/range checks, then a
 *       {@link DateConversionService} calendar check that mirrors {@code CALL 'CSUTLDTC'}).</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * <p>This bean is stateless with respect to instance fields: it holds only immutable collaborators
 * (the session-scoped {@link CardDemoContext} proxy, the date-conversion service, the job launcher
 * and the report {@link Job}). All per-interaction state lives in method-local variables and in the
 * supplied {@link CORPT00Form} / session context.</p>
 */
@Service
public class ReportSubmitService {

    /** Logger for structured, non-sensitive operational events (job submission, launch failures). */
    private static final Logger LOGGER = LoggerFactory.getLogger(ReportSubmitService.class);

    /** COBOL {@code WS-PGMNAME PIC X(08) VALUE 'CORPT00C'} - this program's name. */
    private static final String PROGRAM_NAME = "CORPT00C";

    /** COBOL {@code WS-TRANID PIC X(04) VALUE 'CR00'} - this program's CICS transaction id. */
    private static final String TRANSACTION_ID = "CR00";

    /**
     * Sign-on program name (COBOL {@code 'COSGN00C'}), the {@code CDEMO-TO-PROGRAM} target when the
     * transaction is entered with no COMMAREA ({@code EIBCALEN = 0}) and the
     * {@code RETURN-TO-PREV-SCREEN} default when no target is set.
     */
    private static final String SIGNON_PROGRAM = "COSGN00C";

    /**
     * Main-menu program name (COBOL {@code 'COMEN01C'}). {@code CORPT00C} hard-codes this as the PF3
     * ("back") target ({@code MOVE 'COMEN01C' TO CDEMO-TO-PROGRAM} in {@code MAIN-PARA}); it does
     * <em>not</em> consult {@code CDEMO-FROM-PROGRAM}. That literal behavior is preserved for parity.
     */
    private static final String MENU_PROGRAM = "COMEN01C";

    /** COBOL {@code 'Monthly'} report name ({@code WS-REPORT-NAME}). */
    private static final String REPORT_MONTHLY = "Monthly";

    /** COBOL {@code 'Yearly'} report name ({@code WS-REPORT-NAME}). */
    private static final String REPORT_YEARLY = "Yearly";

    /** COBOL {@code 'Custom'} report name ({@code WS-REPORT-NAME}). */
    private static final String REPORT_CUSTOM = "Custom";

    /**
     * Date picture mask passed to {@link DateConversionService#validateDate(String, String)},
     * matching COBOL {@code WS-DATE-FORMAT PIC X(10) VALUE 'YYYY-MM-DD'}.
     */
    private static final String DATE_FORMAT_MASK = "YYYY-MM-DD";

    /**
     * The {@code CEEDAYS} feedback message number ({@code 2513}, "unsupported range" - a well-formed
     * date before {@code 1582-10-15}) that {@code CORPT00C} explicitly tolerates: when the date
     * check reports a non-zero severity but this specific message number, the COBOL treats the date
     * as acceptable and proceeds to submit. Preserved as a quirk (AAP &sect;0.6.9).
     */
    private static final int CEE_MSG_UNSUPP_RANGE = 2513;

    /** COBOL severity code {@code '0000'} ({@code CSUTLDTC-RESULT-SEV-CD}) - a valid date. */
    private static final int SEVERITY_OK = 0;

    /** Two-character COBOL literal {@code '12'} - the maximum month, compared as a string. */
    private static final String MAX_MONTH = "12";

    /** Two-character COBOL literal {@code '31'} - the maximum day, compared as a string. */
    private static final String MAX_DAY = "31";

    /** Width of the {@code PIC 99} month/day parts after NUMVAL-C normalization (zero-padded). */
    private static final int PART_WIDTH_MMDD = 2;

    /** Width of the {@code PIC 9999} year part after NUMVAL-C normalization (zero-padded). */
    private static final int PART_WIDTH_YYYY = 4;

    /** Modulus applied when moving a NUMVAL-C result into a {@code PIC 99} field (keeps 2 digits). */
    private static final long PART_MOD_MMDD = 100L;

    /** Modulus applied when moving a NUMVAL-C result into a {@code PIC 9999} field (keeps 4 digits). */
    private static final long PART_MOD_YYYY = 10000L;

    /** Spring Batch job parameter key carrying the report type ({@code Monthly}/{@code Yearly}/{@code Custom}). */
    private static final String PARAM_REPORT_TYPE = "reportType";

    /** Spring Batch job parameter key for the inclusive window start ({@code yyyy-MM-dd}). */
    private static final String PARAM_START_DATE = "startDate";

    /** Spring Batch job parameter key for the inclusive window end ({@code yyyy-MM-dd}). */
    private static final String PARAM_END_DATE = "endDate";

    /**
     * Spring Batch job parameter key for the per-submission uniqueness token. Each submission adds a
     * distinct timestamp so it becomes a fresh {@link org.springframework.batch.core.JobInstance},
     * reproducing the COBOL behavior of enqueuing a new job to the internal reader on every submit.
     */
    private static final String PARAM_SUBMIT_TIMESTAMP = "submitTimestamp";

    /**
     * Spring Batch job parameter key carrying a per-submission universally-unique identifier. A fresh
     * {@link UUID} is added on every submit so each launch is guaranteed a distinct
     * {@link org.springframework.batch.core.JobInstance} even when two submissions occur within the
     * same millisecond (which {@link #PARAM_SUBMIT_TIMESTAMP} alone cannot guarantee). This reproduces
     * the COBOL behavior of enqueuing a brand-new job to the JES2 internal reader on every request and
     * keeps concurrent submissions collision-free (review finding&nbsp;#34, and the concurrency
     * hardening behind finding&nbsp;#15).
     */
    private static final String PARAM_SUBMIT_ID = "submitId";

    // --- Exact COBOL screen messages (WS-MESSAGE literals) ------------------

    /** COBOL {@code CCDA-MSG-INVALID-KEY} (copybook {@code CSMSG01Y}). */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    /** COBOL {@code 'Select a report type to print report...'} (the {@code WHEN OTHER} branch). */
    private static final String MSG_SELECT_REPORT_TYPE = "Select a report type to print report...";

    /** COBOL {@code 'Start Date - Month can NOT be empty...'}. */
    private static final String MSG_START_MONTH_EMPTY = "Start Date - Month can NOT be empty...";

    /** COBOL {@code 'Start Date - Day can NOT be empty...'}. */
    private static final String MSG_START_DAY_EMPTY = "Start Date - Day can NOT be empty...";

    /** COBOL {@code 'Start Date - Year can NOT be empty...'}. */
    private static final String MSG_START_YEAR_EMPTY = "Start Date - Year can NOT be empty...";

    /** COBOL {@code 'End Date - Month can NOT be empty...'}. */
    private static final String MSG_END_MONTH_EMPTY = "End Date - Month can NOT be empty...";

    /** COBOL {@code 'End Date - Day can NOT be empty...'}. */
    private static final String MSG_END_DAY_EMPTY = "End Date - Day can NOT be empty...";

    /** COBOL {@code 'End Date - Year can NOT be empty...'}. */
    private static final String MSG_END_YEAR_EMPTY = "End Date - Year can NOT be empty...";

    /** COBOL {@code 'Start Date - Not a valid Month...'}. */
    private static final String MSG_START_MONTH_INVALID = "Start Date - Not a valid Month...";

    /** COBOL {@code 'Start Date - Not a valid Day...'}. */
    private static final String MSG_START_DAY_INVALID = "Start Date - Not a valid Day...";

    /** COBOL {@code 'Start Date - Not a valid Year...'}. */
    private static final String MSG_START_YEAR_INVALID = "Start Date - Not a valid Year...";

    /** COBOL {@code 'End Date - Not a valid Month...'}. */
    private static final String MSG_END_MONTH_INVALID = "End Date - Not a valid Month...";

    /** COBOL {@code 'End Date - Not a valid Day...'}. */
    private static final String MSG_END_DAY_INVALID = "End Date - Not a valid Day...";

    /** COBOL {@code 'End Date - Not a valid Year...'}. */
    private static final String MSG_END_YEAR_INVALID = "End Date - Not a valid Year...";

    /** COBOL {@code 'Start Date - Not a valid date...'} (the {@code CSUTLDTC} start-date failure). */
    private static final String MSG_START_DATE_INVALID = "Start Date - Not a valid date...";

    /** COBOL {@code 'End Date - Not a valid date...'} (the {@code CSUTLDTC} end-date failure). */
    private static final String MSG_END_DATE_INVALID = "End Date - Not a valid date...";

    /** COBOL {@code 'Unable to Write TDQ (JOBS)...'} (the job-submission failure line). */
    private static final String MSG_UNABLE_TO_WRITE_TDQ = "Unable to Write TDQ (JOBS)...";

    /** Prefix of the COBOL confirm prompt: {@code 'Please confirm to print the '}. */
    private static final String CONFIRM_PROMPT_PREFIX = "Please confirm to print the ";

    /** Suffix of the COBOL confirm prompt: {@code ' report...'}. */
    private static final String CONFIRM_PROMPT_SUFFIX = " report...";

    /** Suffix of the COBOL invalid-confirmation message: {@code '" is not a valid value to confirm...'}. */
    private static final String INVALID_CONFIRM_SUFFIX = "\" is not a valid value to confirm...";

    /** Suffix of the COBOL green success line: {@code ' report submitted for printing ...'}. */
    private static final String SUCCESS_MSG_SUFFIX = " report submitted for printing ...";

    /**
     * Formatter producing the {@code yyyy-MM-dd} strings consumed by the batch job's
     * {@code startDate}/{@code endDate} parameters, matching COBOL {@code WS-START-DATE}/
     * {@code WS-END-DATE} ({@code X(4)-X(2)-X(2)}). {@link DateTimeFormatter#ISO_LOCAL_DATE} renders
     * exactly ten characters for four-digit years.
     */
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    /**
     * Session-scoped navigation and selection context, the modern replacement for the COBOL COMMAREA
     * ({@code COCOM01Y}). Injected as a Spring session-scoped proxy.
     */
    private final CardDemoContext context;

    /**
     * Date-validation service reproducing the COBOL {@code CALL 'CSUTLDTC'} used for the custom
     * report range (AAP &sect;0.6.9).
     */
    private final DateConversionService dateConversionService;

    /**
     * Bounded, <em>asynchronous</em> Spring Batch launcher used to submit the transaction-report job
     * (the {@code EXEC CICS WRITEQ TD QUEUE('JOBS')} equivalent). Injected by name via
     * {@link Qualifier} as the {@code reportJobLauncher} bean (declared in
     * {@code com.aws.carddemo.config.BatchConfig}), <em>not</em> the synchronous primary launcher.
     * Launching on this bean returns immediately once the job is accepted and durably recorded,
     * reproducing the COBOL enqueue-and-return semantics rather than blocking the HTTP worker thread
     * until the batch job finishes (review finding&nbsp;#34).
     */
    private final JobLauncher jobLauncher;

    /**
     * The transaction-report {@link Job} produced by
     * {@code com.aws.carddemo.batch.TransactionReportJobConfig}. Because several {@code Job} beans
     * exist in the application, it is injected by bean name via {@link Qualifier}.
     */
    private final Job transactionReportJob;

    /**
     * Creates the report-submission service via Spring constructor injection.
     *
     * <p>A single constructor means no {@code @Autowired} annotation is required. No argument is
     * dereferenced here, so the constructor introduces no {@code this}-escape.</p>
     *
     * @param context               the session-scoped CardDemo context (COMMAREA replacement); must
     *                              not be {@code null}
     * @param dateConversionService the {@code CSUTLDTC} date-validation service; must not be
     *                              {@code null}
     * @param jobLauncher           the bounded <em>asynchronous</em> Spring Batch launcher (bean
     *                              {@code reportJobLauncher}); must not be {@code null}
     * @param transactionReportJob  the transaction-report batch job (bean
     *                              {@code transactionReportJob}); must not be {@code null}
     */
    public ReportSubmitService(CardDemoContext context,
                               DateConversionService dateConversionService,
                               @Qualifier("reportJobLauncher") JobLauncher jobLauncher,
                               @Qualifier("transactionReportJob") Job transactionReportJob) {
        this.context = context;
        this.dateConversionService = dateConversionService;
        this.jobLauncher = jobLauncher;
        this.transactionReportJob = transactionReportJob;
    }

    /**
     * Attention-identifier (AID) keys handled by {@link #mainEntry(AidKey, CORPT00Form)}, mirroring
     * the COBOL {@code EVALUATE EIBAID} of {@code MAIN-PARA}.
     *
     * <p>The paired {@code ReportController} maps the inbound HTTP submission (the pressed button or
     * PF key) to one of these constants before delegating to the service. Only the keys with
     * distinct behavior in {@code CORPT00C} are modeled: {@link #ENTER} ({@code DFHENTER}) and
     * {@link #PF3} ({@code DFHPF3}); every other key collapses to {@link #OTHER}, matching the COBOL
     * {@code WHEN OTHER} default.</p>
     */
    public enum AidKey {

        /** COBOL {@code DFHENTER} &mdash; the ENTER key; processes the report request. */
        ENTER,

        /** COBOL {@code DFHPF3} &mdash; the PF3 key; returns to the main menu. */
        PF3,

        /** COBOL {@code WHEN OTHER} &mdash; any other key; yields the invalid-key message. */
        OTHER
    }

    /**
     * Severity (and thus rendering) of the message a {@link ReportSubmitResult} carries, capturing
     * the COBOL screen-coloring distinctions of {@code CORPT00C}.
     *
     * <p>{@code CORPT00C} produces two visible message states plus "no message":</p>
     * <ul>
     *   <li>{@link #NONE} &mdash; no message (a blank or freshly cleared screen; e.g. the confirm
     *       {@code 'N'} path, which clears all fields and shows nothing).</li>
     *   <li>{@link #ERROR} &mdash; an error / prompt line. Every COBOL path that sets
     *       {@code WS-ERR-FLG = 'Y'} maps here: the invalid-key message, "select a report type",
     *       each empty/invalid date message, the invalid-confirmation message, the confirm prompt
     *       (which the COBOL also flags as an error, so it renders in the default {@code ERRMSG}
     *       color), and the job-submission failure line.</li>
     *   <li>{@link #SUCCESS} &mdash; the green "submitted for printing" line, which the COBOL colors
     *       green ({@code MOVE DFHGREEN TO ERRMSGC}).</li>
     * </ul>
     *
     * <p>Selecting the concrete style/color for each severity is a presentation concern owned by the
     * controller; this enum only conveys the COBOL semantics.</p>
     */
    public enum MessageSeverity {

        /** No message to display. */
        NONE,

        /** An error or prompt line (a COBOL {@code WS-ERR-FLG = 'Y'} path). */
        ERROR,

        /** The green success line (COBOL {@code DFHGREEN}). */
        SUCCESS
    }

    /**
     * Immutable outcome of a report-submission interaction, describing what the controller should do
     * next: perform a redirect, or redisplay the report screen with (optionally) a message.
     *
     * <p>Exactly one of two shapes is produced:</p>
     * <ul>
     *   <li><b>Redirect</b> &mdash; {@link #targetProgram()} is set (and {@link #isRedirect()} is
     *       {@code true}); the controller redirects to the route mapped from that program name. This
     *       reproduces the COBOL {@code XCTL} performed by {@code RETURN-TO-PREV-SCREEN}.</li>
     *   <li><b>Redisplay</b> &mdash; {@link #targetProgram()} is {@code null}; the controller
     *       redisplays the report screen. {@link #message()} is the line to show (possibly empty)
     *       and {@link #severity()} says how to render it, reproducing the COBOL {@code WS-MESSAGE} /
     *       {@code ERRMSGC} distinctions.</li>
     * </ul>
     *
     * @param targetProgram the target program name for a redirect, or {@code null} for a redisplay
     *                      outcome
     * @param message       the message to redisplay; never {@code null} (normalized to the empty
     *                      string), and empty when there is nothing to show
     * @param severity      how the message should be rendered; never {@code null} (normalized to
     *                      {@link MessageSeverity#NONE})
     */
    public record ReportSubmitResult(String targetProgram, String message, MessageSeverity severity) {

        /**
         * Canonical constructor normalizing {@code null} inputs so consumers never have to
         * null-check: a {@code null} message becomes the empty string and a {@code null} severity
         * becomes {@link MessageSeverity#NONE}.
         *
         * @param targetProgram the redirect target, or {@code null} for a redisplay
         * @param message       the message text, or {@code null} (treated as empty)
         * @param severity      the message severity, or {@code null} (treated as
         *                      {@link MessageSeverity#NONE})
         */
        public ReportSubmitResult {
            if (message == null) {
                message = "";
            }
            if (severity == null) {
                severity = MessageSeverity.NONE;
            }
        }

        /**
         * Reports whether this outcome is a redirect (the COBOL {@code XCTL} equivalent).
         *
         * @return {@code true} when a non-blank {@link #targetProgram()} is present
         */
        public boolean isRedirect() {
            return targetProgram != null && !targetProgram.isBlank();
        }

        /**
         * Reports whether this outcome carries a message to redisplay.
         *
         * @return {@code true} when {@link #message()} is non-empty
         */
        public boolean hasMessage() {
            return !message.isEmpty();
        }

        /**
         * Creates a redirect outcome targeting the given program (the COBOL {@code XCTL}
         * destination).
         *
         * @param targetProgram the target program name
         * @return a redirect outcome
         */
        private static ReportSubmitResult redirect(String targetProgram) {
            return new ReportSubmitResult(targetProgram, "", MessageSeverity.NONE);
        }

        /**
         * Creates a redisplay outcome with no message (a blank or cleared screen), reproducing the
         * COBOL confirm {@code 'N'} path that clears every field and shows nothing.
         *
         * @return a message-free redisplay outcome
         */
        private static ReportSubmitResult showScreen() {
            return new ReportSubmitResult(null, "", MessageSeverity.NONE);
        }

        /**
         * Creates an error/prompt (redisplay) outcome, reproducing a COBOL {@code WS-ERR-FLG = 'Y'}
         * path.
         *
         * @param message the error or prompt message to redisplay
         * @return an error outcome
         */
        private static ReportSubmitResult error(String message) {
            return new ReportSubmitResult(null, message, MessageSeverity.ERROR);
        }

        /**
         * Creates a success (redisplay) outcome, reproducing the COBOL green ({@code DFHGREEN})
         * "submitted for printing" line.
         *
         * @param message the success message to redisplay
         * @return a success outcome
         */
        private static ReportSubmitResult success(String message) {
            return new ReportSubmitResult(null, message, MessageSeverity.SUCCESS);
        }
    }

    /**
     * Handles a report-submission interaction, the Java migration of paragraph {@code MAIN-PARA} in
     * {@code legacy/cbl/CORPT00C.cbl}.
     *
     * <p>Reproduces the COBOL control flow one-for-one:</p>
     * <ul>
     *   <li>On first entry with no COMMAREA (COBOL {@code EIBCALEN = 0}, reproduced by
     *       {@link CardDemoContext#isNew()}) control returns to the sign-on screen
     *       ({@code MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM} then {@code PERFORM
     *       RETURN-TO-PREV-SCREEN}).</li>
     *   <li>On the first entry <em>within</em> the conversation (COBOL {@code IF NOT
     *       CDEMO-PGM-REENTER}) the program is marked re-entered and the empty report screen is
     *       shown ({@code MOVE LOW-VALUES TO CORPT0AO}, {@code MOVE -1 TO MONTHLYL}, {@code PERFORM
     *       SEND-TRNRPT-SCREEN}). Unlike some sibling programs, {@code CORPT00C} performs no
     *       pre-processing here.</li>
     *   <li>On re-entry the pressed AID key is evaluated: {@code ENTER} processes the request;
     *       {@code PF3} returns to the main menu ({@code COMEN01C}); any other key yields the
     *       invalid-key message.</li>
     * </ul>
     *
     * <p>This method performs no VSAM/JPA I/O and is therefore <b>not</b> {@code @Transactional}
     * (AAP &sect;0.2.2): its only side effect is potentially launching a batch job, whose own
     * transaction boundary is managed by the Spring Batch {@code JobRepository}.</p>
     *
     * @param aid  the attention-identifier key pressed; a {@code null} value is treated as
     *             {@link AidKey#OTHER}, matching the COBOL {@code WHEN OTHER} default
     * @param form the submitted report screen form (map {@code CORPT0A} of mapset {@code CORPT00});
     *             must not be {@code null}
     * @return the interaction outcome: a redirect target, or a screen to redisplay (optionally with
     *         a message)
     */
    public ReportSubmitResult mainEntry(AidKey aid, CORPT00Form form) {
        // MAIN-PARA: SET ERR-FLG-OFF / SEND-ERASE-YES and MOVE SPACES TO WS-MESSAGE, ERRMSGO are
        // represented by producing a fresh ReportSubmitResult per call (no residual state here).

        // IF EIBCALEN = 0 -> bounce back to the sign-on screen.
        if (context.isNew()) {
            context.setToProgram(SIGNON_PROGRAM);
            return returnToPrevScreen();
        }

        // ELSE: a COMMAREA is present. IF NOT CDEMO-PGM-REENTER -> first display of this
        // transaction; mark re-enter and show the empty report screen (no pre-processing).
        if (context.isProgramEnter()) {
            context.markReenter();
            return ReportSubmitResult.showScreen();
        }

        // ELSE (CDEMO-PGM-REENTER): RECEIVE-TRNRPT-SCREEN then EVALUATE EIBAID.
        // A null AID collapses to the WHEN OTHER branch.
        AidKey effectiveAid = (aid == null) ? AidKey.OTHER : aid;
        return switch (effectiveAid) {
            case ENTER -> processEnterKey(form);
            case PF3 -> {
                // DFHPF3: MOVE 'COMEN01C' TO CDEMO-TO-PROGRAM (hard-coded; no from-program lookup).
                context.setToProgram(MENU_PROGRAM);
                yield returnToPrevScreen();
            }
            case OTHER -> ReportSubmitResult.error(MSG_INVALID_KEY);
        };
    }

    /**
     * Records the hand-off state and yields a redirect, the Java migration of paragraph
     * {@code RETURN-TO-PREV-SCREEN} in {@code legacy/cbl/CORPT00C.cbl}.
     *
     * <p>Reproduces the COBOL exactly: when the target program is unset it defaults to the sign-on
     * program ({@code IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES MOVE 'COSGN00C'}); the
     * from-transaction and from-program are set to this program's ids ({@code MOVE WS-TRANID TO
     * CDEMO-FROM-TRANID}, {@code MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM}); and the program context is
     * reset to enter ({@code MOVE ZEROS TO CDEMO-PGM-CONTEXT}, reproduced by
     * {@link CardDemoContext#markEnter()}). The COBOL {@code XCTL PROGRAM(...)} is expressed as a
     * redirect outcome naming the target program.</p>
     *
     * @return a redirect outcome naming the (possibly defaulted) target program
     */
    private ReportSubmitResult returnToPrevScreen() {
        if (isBlankOrLowValues(context.getToProgram())) {
            context.setToProgram(SIGNON_PROGRAM);
        }
        context.setFromTranid(TRANSACTION_ID);
        context.setFromProgram(PROGRAM_NAME);
        context.markEnter();
        return ReportSubmitResult.redirect(context.getToProgram());
    }

    /**
     * Determines the requested report type, computes (and for custom, validates) the reporting date
     * window, then submits the batch job or reports a validation outcome. The Java migration of
     * paragraph {@code PROCESS-ENTER-KEY} in {@code legacy/cbl/CORPT00C.cbl}.
     *
     * <p>Reproduces the COBOL {@code EVALUATE TRUE} one-for-one, honoring its first-match semantics
     * (if more than one report-type flag were somehow set, {@code Monthly} wins, then
     * {@code Yearly}, then {@code Custom}):</p>
     * <ul>
     *   <li><b>Monthly</b> ({@code MONTHLYI NOT = SPACES AND LOW-VALUES}) &mdash; window =
     *       first&hellip;last day of the current month.</li>
     *   <li><b>Yearly</b> ({@code YEARLYI ...}) &mdash; window = {@code YYYY-01-01} &hellip;
     *       {@code YYYY-12-31} of the current year.</li>
     *   <li><b>Custom</b> ({@code CUSTOMI ...}) &mdash; the MM/DD/YYYY parts are validated in the
     *       COBOL order (empty checks, then NUMVAL-C normalization + numeric/range checks, then a
     *       {@code CSUTLDTC} calendar check); the first failure returns its exact COBOL message.</li>
     *   <li><b>Otherwise</b> ({@code WHEN OTHER}) &mdash; no report type selected; returns
     *       {@code 'Select a report type to print report...'}.</li>
     * </ul>
     *
     * <p>Because the COBOL {@code SEND-TRNRPT-SCREEN} terminates the transaction, the first branch
     * that would send the screen returns immediately here. When the report type resolves cleanly the
     * job submission is attempted via {@link #submitJobToIntrdr(CORPT00Form, String, String,
     * String)}; a present result from that step is a terminal outcome (confirm prompt, cleared
     * screen, invalid confirmation, or launch failure) and is returned as-is, while an empty result
     * means the submission succeeded and this method emits the COBOL green success line ({@code IF
     * NOT ERR-FLG-ON ... 'report submitted for printing ...'}).</p>
     *
     * @param form the submitted report screen form supplying the report-type flags, the custom date
     *             parts and the confirmation; also receiving the NUMVAL-C-normalized date parts and
     *             the cleared fields on success; must not be {@code null}
     * @return the interaction outcome: an error/prompt, a cleared screen, or the green success line
     */
    public ReportSubmitResult processEnterKey(CORPT00Form form) {
        LOGGER.debug("CR00 PROCESS-ENTER-KEY");

        String reportName;
        String startDate;
        String endDate;

        // EVALUATE TRUE (first-match wins, mirroring the COBOL).
        if (isSelected(form.getMonthly())) {
            // WHEN MONTHLYI NOT = SPACES AND LOW-VALUES: current-month window.
            reportName = REPORT_MONTHLY;
            LocalDate today = currentDate();
            LocalDate firstOfMonth = today.withDayOfMonth(1);
            // Start = first of month; End = (first of next month) - 1 day = last of current month.
            startDate = firstOfMonth.format(ISO_DATE);
            endDate = firstOfMonth.plusMonths(1).minusDays(1).format(ISO_DATE);
        } else if (isSelected(form.getYearly())) {
            // WHEN YEARLYI ...: current-year window (YYYY-01-01 .. YYYY-12-31).
            reportName = REPORT_YEARLY;
            int year = currentDate().getYear();
            startDate = LocalDate.of(year, 1, 1).format(ISO_DATE);
            endDate = LocalDate.of(year, 12, 31).format(ISO_DATE);
        } else if (isSelected(form.getCustom())) {
            // WHEN CUSTOMI ...: validate the user-entered parts, then assemble the window.
            reportName = REPORT_CUSTOM;
            CustomRange range = validateCustomRange(form);
            if (range.error() != null) {
                // A validation branch performed SEND-TRNRPT-SCREEN (terminal).
                return range.error();
            }
            startDate = range.startDate();
            endDate = range.endDate();
        } else {
            // WHEN OTHER: no report type selected.
            return ReportSubmitResult.error(MSG_SELECT_REPORT_TYPE);
        }

        // PERFORM SUBMIT-JOB-TO-INTRDR. A present result is terminal (the COBOL SEND path); an empty
        // result means the job launched and control fell through to the success block.
        Optional<ReportSubmitResult> submitOutcome = submitJobToIntrdr(form, reportName, startDate, endDate);
        if (submitOutcome.isPresent()) {
            return submitOutcome.get();
        }

        // IF NOT ERR-FLG-ON: INITIALIZE-ALL-FIELDS; MOVE DFHGREEN TO ERRMSGC;
        // STRING WS-REPORT-NAME ' report submitted for printing ...' -> green success line.
        initializeAllFields(form);
        return ReportSubmitResult.success(reportName + SUCCESS_MSG_SUFFIX);
    }

    /**
     * Internal carrier for the outcome of custom-range validation: either a terminal validation
     * {@link ReportSubmitResult} error, or the assembled {@code startDate}/{@code endDate} strings
     * ({@code yyyy-MM-dd}). Exactly one shape is populated.
     *
     * @param error     the validation error to return, or {@code null} when validation succeeded
     * @param startDate the assembled inclusive start date, or {@code null} on error
     * @param endDate   the assembled inclusive end date, or {@code null} on error
     */
    private record CustomRange(ReportSubmitResult error, String startDate, String endDate) {

        /**
         * Creates a failed custom-range outcome carrying the validation error.
         *
         * @param error the validation error
         * @return a failed outcome
         */
        private static CustomRange failed(ReportSubmitResult error) {
            return new CustomRange(error, null, null);
        }

        /**
         * Creates a successful custom-range outcome carrying the assembled window.
         *
         * @param startDate the inclusive start date ({@code yyyy-MM-dd})
         * @param endDate   the inclusive end date ({@code yyyy-MM-dd})
         * @return a successful outcome
         */
        private static CustomRange of(String startDate, String endDate) {
            return new CustomRange(null, startDate, endDate);
        }
    }

    /**
     * Validates the custom start/end date parts and assembles the reporting window, reproducing the
     * {@code WHEN CUSTOMI} branch of paragraph {@code PROCESS-ENTER-KEY} in
     * {@code legacy/cbl/CORPT00C.cbl}.
     *
     * <p>The COBOL performs the checks in a fixed order, and because each failing check runs
     * {@code PERFORM SEND-TRNRPT-SCREEN} (which terminates the transaction), the first failure wins.
     * This method reproduces that order and returns the first error encountered:</p>
     * <ol>
     *   <li><b>Empty checks</b> ({@code EVALUATE TRUE}) - each of {@code SDTMM}, {@code SDTDD},
     *       {@code SDTYYYY}, {@code EDTMM}, {@code EDTDD}, {@code EDTYYYY}, in that order, must not be
     *       spaces/low-values, each with its exact {@code '... can NOT be empty...'} message.</li>
     *   <li><b>NUMVAL-C normalization</b> - each part is converted with a NUMVAL-C equivalent and
     *       moved back into the (zero-padded) {@code PIC 99}/{@code PIC 9999} form field, matching
     *       {@code COMPUTE WS-NUM-99 = FUNCTION NUMVAL-C(...)} / {@code MOVE WS-NUM-99 TO ...}.</li>
     *   <li><b>Numeric / range checks</b> - {@code IF part IS NOT NUMERIC OR part > '12'} (month) /
     *       {@code > '31'} (day); years are only checked for numericity. After normalization the
     *       parts are always numeric, so these reduce to the string {@code > '12'}/{@code > '31'}
     *       comparisons the COBOL performs on the zero-padded fields.</li>
     *   <li><b>Calendar check</b> - the assembled {@code YYYY-MM-DD} start and end are each validated
     *       via {@link DateConversionService#validateDate(String, String)} (the {@code CALL
     *       'CSUTLDTC'} equivalent). A non-zero severity is an error <em>unless</em> the message
     *       number is {@link #CEE_MSG_UNSUPP_RANGE} ({@code 2513}), which the COBOL explicitly
     *       tolerates and proceeds to submit (preserved quirk, AAP &sect;0.6.9).</li>
     * </ol>
     *
     * @param form the report screen form whose {@code sdt*}/{@code edt*} parts are validated and
     *             normalized in place; must not be {@code null}
     * @return a {@link CustomRange} carrying the first validation error, or the assembled window
     */
    private CustomRange validateCustomRange(CORPT00Form form) {
        // 1. Empty checks - EVALUATE TRUE, first empty part wins.
        if (isBlankOrLowValues(form.getSdtmm())) {
            return CustomRange.failed(ReportSubmitResult.error(MSG_START_MONTH_EMPTY));
        }
        if (isBlankOrLowValues(form.getSdtdd())) {
            return CustomRange.failed(ReportSubmitResult.error(MSG_START_DAY_EMPTY));
        }
        if (isBlankOrLowValues(form.getSdtyyyy())) {
            return CustomRange.failed(ReportSubmitResult.error(MSG_START_YEAR_EMPTY));
        }
        if (isBlankOrLowValues(form.getEdtmm())) {
            return CustomRange.failed(ReportSubmitResult.error(MSG_END_MONTH_EMPTY));
        }
        if (isBlankOrLowValues(form.getEdtdd())) {
            return CustomRange.failed(ReportSubmitResult.error(MSG_END_DAY_EMPTY));
        }
        if (isBlankOrLowValues(form.getEdtyyyy())) {
            return CustomRange.failed(ReportSubmitResult.error(MSG_END_YEAR_EMPTY));
        }

        // 2. NUMVAL-C normalization + MOVE back into the zero-padded PIC 99 / PIC 9999 fields.
        String startMonth = normalizePart(form.getSdtmm(), PART_WIDTH_MMDD, PART_MOD_MMDD);
        String startDay = normalizePart(form.getSdtdd(), PART_WIDTH_MMDD, PART_MOD_MMDD);
        String startYear = normalizePart(form.getSdtyyyy(), PART_WIDTH_YYYY, PART_MOD_YYYY);
        String endMonth = normalizePart(form.getEdtmm(), PART_WIDTH_MMDD, PART_MOD_MMDD);
        String endDay = normalizePart(form.getEdtdd(), PART_WIDTH_MMDD, PART_MOD_MMDD);
        String endYear = normalizePart(form.getEdtyyyy(), PART_WIDTH_YYYY, PART_MOD_YYYY);

        form.setSdtmm(startMonth);
        form.setSdtdd(startDay);
        form.setSdtyyyy(startYear);
        form.setEdtmm(endMonth);
        form.setEdtdd(endDay);
        form.setEdtyyyy(endYear);

        // 3. Numeric / range checks - separate IFs in the COBOL, each terminal; first failure wins.
        if (!isNumeric(startMonth) || startMonth.compareTo(MAX_MONTH) > 0) {
            return CustomRange.failed(ReportSubmitResult.error(MSG_START_MONTH_INVALID));
        }
        if (!isNumeric(startDay) || startDay.compareTo(MAX_DAY) > 0) {
            return CustomRange.failed(ReportSubmitResult.error(MSG_START_DAY_INVALID));
        }
        if (!isNumeric(startYear)) {
            return CustomRange.failed(ReportSubmitResult.error(MSG_START_YEAR_INVALID));
        }
        if (!isNumeric(endMonth) || endMonth.compareTo(MAX_MONTH) > 0) {
            return CustomRange.failed(ReportSubmitResult.error(MSG_END_MONTH_INVALID));
        }
        if (!isNumeric(endDay) || endDay.compareTo(MAX_DAY) > 0) {
            return CustomRange.failed(ReportSubmitResult.error(MSG_END_DAY_INVALID));
        }
        if (!isNumeric(endYear)) {
            return CustomRange.failed(ReportSubmitResult.error(MSG_END_YEAR_INVALID));
        }

        // 4. Assemble WS-START-DATE / WS-END-DATE (X(4)-X(2)-X(2)).
        String startDate = startYear + "-" + startMonth + "-" + startDay;
        String endDate = endYear + "-" + endMonth + "-" + endDay;

        // 5. CSUTLDTC calendar check on the start date (tolerating message 2513).
        DateValidationResult startResult = dateConversionService.validateDate(startDate, DATE_FORMAT_MASK);
        if (startResult.severity() != SEVERITY_OK && startResult.msgNo() != CEE_MSG_UNSUPP_RANGE) {
            return CustomRange.failed(ReportSubmitResult.error(MSG_START_DATE_INVALID));
        }

        // ... then the end date (tolerating message 2513).
        DateValidationResult endResult = dateConversionService.validateDate(endDate, DATE_FORMAT_MASK);
        if (endResult.severity() != SEVERITY_OK && endResult.msgNo() != CEE_MSG_UNSUPP_RANGE) {
            return CustomRange.failed(ReportSubmitResult.error(MSG_END_DATE_INVALID));
        }

        return CustomRange.of(startDate, endDate);
    }

    /**
     * Confirms the submission and, when confirmed, launches the transaction-report batch job. The
     * Java migration of paragraph {@code SUBMIT-JOB-TO-INTRDR} in {@code legacy/cbl/CORPT00C.cbl}.
     *
     * <p>Reproduces the COBOL control flow one-for-one:</p>
     * <ol>
     *   <li>If the confirmation field is blank ({@code CONFIRMI = SPACES OR LOW-VALUES}) the confirm
     *       prompt {@code 'Please confirm to print the ' + reportName + ' report...'} is returned
     *       (the COBOL flags this as an error and sends the screen). The report-type and date fields
     *       are intentionally <em>not</em> cleared, so the user can confirm and resubmit.</li>
     *   <li>{@code EVALUATE CONFIRMI}: {@code 'Y'}/{@code 'y'} proceeds to submit;
     *       {@code 'N'}/{@code 'n'} clears all fields ({@code INITIALIZE-ALL-FIELDS}) and returns a
     *       message-free cleared screen; any other value returns {@code '"' + value + '" is not a
     *       valid value to confirm...'}.</li>
     *   <li>On {@code 'Y'}/{@code 'y'} the COBOL {@code PERFORM VARYING} loop that writes the JCL
     *       lines to the {@code JOBS} internal-reader TDQ collapses into a single
     *       {@link #writeJobSubmitTdq(String, String, String)} call (one {@link JobLauncher#run}
     *       launch replaces enqueuing the whole job stream).</li>
     * </ol>
     *
     * <p>The {@link Optional} return distinguishes the two COBOL control-flow shapes: a
     * <em>present</em> result is a terminal {@code SEND-TRNRPT-SCREEN} outcome (confirm prompt,
     * cleared screen, invalid confirmation, or a launch failure) that {@link
     * #processEnterKey(CORPT00Form)} returns as-is; an <em>empty</em> result means the job launched
     * cleanly and control falls through to the COBOL success block.</p>
     *
     * @param form       the report screen form supplying the confirmation and (on the {@code 'N'}
     *                   path) receiving the cleared fields; must not be {@code null}
     * @param reportName the resolved report name ({@code Monthly}/{@code Yearly}/{@code Custom}) used
     *                   in the confirm prompt and in the job parameters; must not be {@code null}
     * @param startDate  the inclusive window start ({@code yyyy-MM-dd}); must not be {@code null}
     * @param endDate    the inclusive window end ({@code yyyy-MM-dd}); must not be {@code null}
     * @return an {@link Optional} terminal outcome (present) or {@link Optional#empty()} when the job
     *         launched successfully
     */
    private Optional<ReportSubmitResult> submitJobToIntrdr(CORPT00Form form, String reportName,
            String startDate, String endDate) {
        String confirm = form.getConfirm();

        // IF CONFIRMI = SPACES OR LOW-VALUES -> confirm prompt (error flag on; fields not cleared).
        if (isBlankOrLowValues(confirm)) {
            return Optional.of(ReportSubmitResult.error(
                    CONFIRM_PROMPT_PREFIX + reportName + CONFIRM_PROMPT_SUFFIX));
        }

        // EVALUATE TRUE on the confirmation value.
        if ("Y".equals(confirm) || "y".equals(confirm)) {
            // CONTINUE -> submit the job (the WIRTE-JOBSUB-TDQ loop).
            return writeJobSubmitTdq(reportName, startDate, endDate);
        }
        if ("N".equals(confirm) || "n".equals(confirm)) {
            // INITIALIZE-ALL-FIELDS; MOVE 'Y' TO WS-ERR-FLG; SEND -> cleared screen, no message.
            initializeAllFields(form);
            return Optional.of(ReportSubmitResult.showScreen());
        }

        // WHEN OTHER -> invalid confirmation value.
        return Optional.of(ReportSubmitResult.error("\"" + confirm + INVALID_CONFIRM_SUFFIX));
    }

    /**
     * Submits the transaction-report batch job, the Java migration of paragraph
     * {@code WIRTE-JOBSUB-TDQ} in {@code legacy/cbl/CORPT00C.cbl} (the COBOL paragraph name preserves
     * its original misspelling of "write"; the Java method is named sensibly).
     *
     * <p>The COBOL {@code EXEC CICS WRITEQ TD QUEUE('JOBS')} enqueues one 80-byte JCL record to the
     * JES2 internal reader and returns to the terminal immediately; the surrounding
     * {@code PERFORM VARYING} loop writes the whole job stream, after which JES2 runs the job
     * asynchronously. In the modernized architecture the entire enqueue collapses into a single
     * {@link JobLauncher#run(Job, JobParameters)} on the bounded <em>asynchronous</em>
     * {@code reportJobLauncher}: {@code run(...)} validates the parameters and creates the durable
     * {@link org.springframework.batch.core.JobExecution} synchronously, then dispatches the job to a
     * background worker and returns immediately with an {@code STARTING}/{@code STARTED} execution
     * &mdash; it does <em>not</em> block the HTTP worker thread until the batch job finishes. The
     * report type and window are passed as job parameters, and a per-submission {@link UUID}
     * ({@link #PARAM_SUBMIT_ID}) alongside a timestamp guarantees a distinct
     * {@link org.springframework.batch.core.JobInstance} on every submit &mdash; even under concurrent
     * submissions &mdash; reproducing the COBOL behavior of enqueuing a new job on each request.</p>
     *
     * <p>The COBOL {@code EVALUATE WS-RESP-CD} response handling is preserved: {@code DFHRESP(NORMAL)}
     * (the job was accepted for asynchronous execution) falls through to success
     * ({@link Optional#empty()}); a launch failure maps to the COBOL {@code WHEN OTHER} branch and
     * returns {@code 'Unable to Write TDQ (JOBS)...'}. Two failure families are caught: the checked
     * {@link JobExecutionException} family thrown synchronously by {@code run(...)} (job already
     * running, restart failure, instance already complete, or invalid parameters) and the
     * {@link TaskRejectedException} thrown when the bounded launcher's pool and queue are saturated.
     * Consistent with the CICS enqueue-and-return semantics, an <em>accepted</em> job is treated as
     * submitted regardless of its later batch outcome: a step that fails after acceptance is recorded
     * asynchronously in the durable {@link JobExecution} (queryable via {@code JobExplorer}), not
     * thrown from this method.</p>
     *
     * @param reportName the report name recorded as the {@code reportType} job parameter; must not be
     *                   {@code null}
     * @param startDate  the inclusive window start ({@code yyyy-MM-dd}) job parameter; must not be
     *                   {@code null}
     * @param endDate    the inclusive window end ({@code yyyy-MM-dd}) job parameter; must not be
     *                   {@code null}
     * @return {@link Optional#empty()} on a clean launch (COBOL {@code NORMAL}); a present error
     *         outcome on any launch failure (COBOL {@code WHEN OTHER})
     */
    private Optional<ReportSubmitResult> writeJobSubmitTdq(String reportName, String startDate,
            String endDate) {
        JobParameters parameters = new JobParametersBuilder()
                .addString(PARAM_REPORT_TYPE, reportName)
                .addString(PARAM_START_DATE, startDate)
                .addString(PARAM_END_DATE, endDate)
                .addLong(PARAM_SUBMIT_TIMESTAMP, System.currentTimeMillis())
                .addString(PARAM_SUBMIT_ID, UUID.randomUUID().toString())
                .toJobParameters();

        try {
            // Bounded ASYNC launch: run(...) validates and creates the JobExecution synchronously
            // (so a launch failure still surfaces here), then hands job.execute(...) to the bounded
            // background executor and returns an STARTING/STARTED execution without waiting for the
            // batch job to finish - the modern equivalent of EXEC CICS WRITEQ TD returning to the
            // terminal while JES2 runs the job later.
            JobExecution execution = jobLauncher.run(transactionReportJob, parameters);
            LOGGER.info("Accepted transaction report job '{}' (execution id {}, status {}), type={}, window {}..{}",
                    transactionReportJob.getName(), execution.getId(), execution.getStatus(),
                    reportName, startDate, endDate);
            return Optional.empty();
        } catch (JobExecutionException | TaskRejectedException ex) {
            // JobExecutionException: the pre-flight failure family (already running, restart failure,
            // instance already complete, invalid parameters). TaskRejectedException: the bounded
            // executor's pool and queue are saturated. Both map to the COBOL EVALUATE WS-RESP-CD
            // WHEN OTHER branch and return the 'Unable to Write TDQ (JOBS)...' error line.
            LOGGER.error("Unable to submit transaction report job (type={}, window {}..{})",
                    reportName, startDate, endDate, ex);
            return Optional.of(ReportSubmitResult.error(MSG_UNABLE_TO_WRITE_TDQ));
        }
    }

    /**
     * Resets the editable screen fields to their initial (blank) state, the Java migration of
     * paragraph {@code INITIALIZE-ALL-FIELDS} in {@code legacy/cbl/CORPT00C.cbl}.
     *
     * <p>The COBOL moves {@code -1} to {@code MONTHLYL} (positioning the cursor) and
     * {@code INITIALIZE}s the report-type flags ({@code MONTHLYI}, {@code YEARLYI}, {@code CUSTOMI}),
     * the six date parts ({@code SDTMMI}, {@code SDTDDI}, {@code SDTYYYYI}, {@code EDTMMI},
     * {@code EDTDDI}, {@code EDTYYYYI}), the confirmation ({@code CONFIRMI}) and {@code WS-MESSAGE}.
     * The cursor position and the message are presentation concerns owned by the controller (the
     * message is conveyed through the returned {@link ReportSubmitResult}), so here only the
     * editable input fields are cleared, to the empty string.</p>
     *
     * @param form the screen form whose input fields are cleared; must not be {@code null}
     */
    private void initializeAllFields(CORPT00Form form) {
        form.setMonthly("");
        form.setYearly("");
        form.setCustom("");
        form.setSdtmm("");
        form.setSdtdd("");
        form.setSdtyyyy("");
        form.setEdtmm("");
        form.setEdtdd("");
        form.setEdtyyyy("");
        form.setConfirm("");
    }

    /**
     * Returns the current date, the seam for the COBOL {@code FUNCTION CURRENT-DATE} used by the
     * Monthly and Yearly report-type branches.
     *
     * <p>Package-private (rather than a direct {@link LocalDate#now()} call at each use site) so that
     * unit tests can pin "today" via a spy and assert the computed Monthly/Yearly windows
     * deterministically.</p>
     *
     * @return today's date in the system default time-zone
     */
    LocalDate currentDate() {
        return LocalDate.now();
    }

    /**
     * Reports whether a single-character report-type selection flag is set, reproducing the COBOL
     * combined condition {@code flag NOT = SPACES AND flag NOT = LOW-VALUES}.
     *
     * @param value the flag field value (e.g. {@code MONTHLYI}); may be {@code null}
     * @return {@code true} when the flag holds a real (non-space, non-low-values) character
     */
    private static boolean isSelected(String value) {
        return !isBlankOrLowValues(value);
    }

    /**
     * Reports whether a screen field is effectively unset, reproducing the COBOL class tests
     * {@code field = SPACES} and {@code field = LOW-VALUES}.
     *
     * <p>A {@code null}, empty, all-spaces, or all-{@code NUL} ({@code x'00'}, the ASCII rendering of
     * COBOL {@code LOW-VALUES}) value is treated as unset.</p>
     *
     * @param value the field value to test; may be {@code null}
     * @return {@code true} when the value is blank or low-values
     */
    private static boolean isBlankOrLowValues(String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch != ' ' && ch != '\0') {
                return false;
            }
        }
        return true;
    }

    /**
     * Reproduces {@code COMPUTE WS-NUM = FUNCTION NUMVAL-C(field)} followed by {@code MOVE WS-NUM TO
     * field} for a fixed-width numeric date part.
     *
     * <p>Extracts the numeric value from {@code raw} the way COBOL {@code FUNCTION NUMVAL-C} does for
     * these digit fields (ignoring spaces and other non-digit noise; no digits yields {@code 0}),
     * applies the modulus of the target {@code PIC} field (so a value wider than the field keeps its
     * low-order digits, exactly as a numeric {@code MOVE} truncates), and renders the result
     * zero-padded to {@code width} characters. {@link Math#floorMod(long, long)} keeps the result
     * non-negative for the (unexpected) signed case.</p>
     *
     * @param raw   the raw field value entered on the screen; may be {@code null}
     * @param width the target field width (2 for {@code PIC 99} month/day, 4 for {@code PIC 9999}
     *              year)
     * @param modulus the modulus of the target field ({@code 100} for {@code PIC 99}, {@code 10000}
     *                for {@code PIC 9999})
     * @return the zero-padded, normalized numeric string
     */
    private static String normalizePart(String raw, int width, long modulus) {
        long value = Math.floorMod(numvalC(raw), modulus);
        return padLeadingZeros(Long.toString(value), width);
    }

    /**
     * Extracts the integer numeric value of a string the way COBOL {@code FUNCTION NUMVAL-C} does for
     * the numeric date-part fields: digits are collected and parsed, while spaces and any other
     * non-digit characters are ignored; a value with no digits evaluates to {@code 0}.
     *
     * @param raw the raw string; may be {@code null} (treated as empty)
     * @return the parsed non-negative numeric value, or {@code 0} when no digits are present
     */
    private static long numvalC(String raw) {
        if (raw == null || raw.isEmpty()) {
            return 0L;
        }
        StringBuilder digits = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch >= '0' && ch <= '9') {
                digits.append(ch);
            }
        }
        if (digits.length() == 0) {
            return 0L;
        }
        try {
            return Long.parseLong(digits.toString());
        } catch (NumberFormatException ex) {
            // Only reachable if the collected digit run overflows a long; the field widths in play
            // (<= 4 digits) make this unreachable in practice, but guard defensively.
            return 0L;
        }
    }

    /**
     * Left-pads a numeric string with leading zeros to the requested width, reproducing the storage
     * of a value in a fixed-width COBOL {@code PIC 9(n)} field.
     *
     * @param value the numeric string (already non-negative and no wider than {@code width})
     * @param width the target width
     * @return the value left-padded with {@code '0'} to exactly {@code width} characters
     */
    private static String padLeadingZeros(String value, int width) {
        if (value.length() >= width) {
            return value;
        }
        StringBuilder padded = new StringBuilder(width);
        for (int i = value.length(); i < width; i++) {
            padded.append('0');
        }
        padded.append(value);
        return padded.toString();
    }

    /**
     * Reports whether every character of a field is a digit, reproducing the COBOL {@code field IS
     * NUMERIC} class test for the normalized (zero-padded) date parts.
     *
     * @param value the field value to test; may be {@code null}
     * @return {@code true} when {@code value} is non-empty and contains only digits
     */
    private static boolean isNumeric(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < '0' || ch > '9') {
                return false;
            }
        }
        return true;
    }
}
