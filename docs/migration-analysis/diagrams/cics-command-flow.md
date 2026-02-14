# CICS Command Migration Flow — Sequence Diagram

This document contains a Mermaid sequence diagram illustrating the migration path from
CICS EXEC commands used across the 16 online CardDemo COBOL programs to their Java Spring
equivalents. The diagram covers all five CICS API command categories identified in the
proprietary utility inventory.

> **Referenced by:** [Migration Strategy — CICS Command Category](../03-migration-strategy.md)
>
> **Canonical catalog:** [Proprietary Utility Inventory — CICS API Commands](../01-proprietary-utility-inventory.md)

---

## Diagram: CICS Command-to-Java Spring Migration Flow

```mermaid
sequenceDiagram
    title CICS Command Migration Flow — CardDemo to Java Spring

    %% ---------------------------------------------------------------
    %% Participants / Actors
    %% ---------------------------------------------------------------
    participant COBOL as COBOL CICS Program<br/>(16 Online Programs)
    participant FC as CICS File Control API<br/>→ Spring Data JPA / JDBC
    participant PC as CICS Program Control API<br/>→ Spring MVC Controller
    participant TC as CICS Terminal Control API<br/>→ REST API / HTML Templates
    participant SS as CICS System Services API<br/>→ Java System APIs
    participant TDQ as CICS TDQ API<br/>→ JMS / Spring Batch
    participant VSAM as VSAM KSDS Files<br/>→ RDBMS Tables (PostgreSQL)

    %% ===============================================================
    %% SECTION 1 — FILE CONTROL MIGRATION
    %% ===============================================================
    Note over COBOL,VSAM: === 1. File Control Migration ===<br/>EXEC CICS READ / WRITE / REWRITE / DELETE / STARTBR / READNEXT / READPREV / ENDBR<br/>→ Spring Data JPA Repository + JDBC with HikariCP connection pool

    rect rgb(232, 245, 255)
        Note right of COBOL: READ — Single record retrieval by key

        COBOL->>FC: EXEC CICS READ<br/>FILE('ACCTDAT') INTO(record)<br/>RIDFLD(key) RESP(WS-RESP-CD)
        FC->>VSAM: VSAM KSDS keyed read<br/>(ACCTDAT, CARDDAT, CUSTDAT,<br/>USRSEC, TRANSACT, CCXREF,<br/>CARDAIX, CXACAIX)
        VSAM-->>FC: Record data or NOTFND
        FC-->>COBOL: WS-RESP-CD = DFHRESP(NORMAL)<br/>or DFHRESP(NOTFND)

        Note right of FC: Java Equivalent
        FC->>VSAM: accountRepository.findById(key)<br/>→ SELECT * FROM accounts<br/>WHERE account_id = ?
        VSAM-->>FC: Optional<Account> entity<br/>or empty (→ EntityNotFoundException)
    end

    rect rgb(232, 245, 255)
        Note right of COBOL: WRITE — Insert a new record

        COBOL->>FC: EXEC CICS WRITE<br/>FILE('TRANSACT') FROM(record)<br/>RIDFLD(TRAN-ID) RESP(WS-RESP-CD)
        FC->>VSAM: VSAM KSDS write (insert)
        VSAM-->>FC: NORMAL or DUPREC

        Note right of FC: Java Equivalent
        FC->>VSAM: transactionRepository.save(entity)<br/>→ INSERT INTO transactions (...)
        VSAM-->>FC: Persisted entity<br/>(DuplicateKeyException on conflict)
    end

    rect rgb(232, 245, 255)
        Note right of COBOL: REWRITE — Update existing record (with read-for-update)

        COBOL->>FC: EXEC CICS REWRITE<br/>FILE('ACCTDAT') FROM(record)<br/>RESP(WS-RESP-CD)
        FC->>VSAM: VSAM record update (must hold lock)
        VSAM-->>FC: NORMAL or error

        Note right of FC: Java Equivalent
        FC->>VSAM: accountRepository.save(entity)<br/>→ UPDATE accounts SET ...<br/>WHERE account_id = ?<br/>(@Version for optimistic locking)
        VSAM-->>FC: Updated entity<br/>(OptimisticLockingFailureException)
    end

    rect rgb(232, 245, 255)
        Note right of COBOL: DELETE — Remove a record by key

        COBOL->>FC: EXEC CICS DELETE<br/>FILE('USRSEC')<br/>RESP(WS-RESP-CD)
        FC->>VSAM: VSAM record delete
        VSAM-->>FC: NORMAL or NOTFND

        Note right of FC: Java Equivalent
        FC->>VSAM: userSecurityRepository.deleteById(key)<br/>→ DELETE FROM user_security<br/>WHERE user_id = ?
        VSAM-->>FC: void<br/>(EmptyResultDataAccessException if missing)
    end

    rect rgb(232, 245, 255)
        Note right of COBOL: BROWSE — Sequential cursor traversal

        COBOL->>FC: EXEC CICS STARTBR<br/>FILE('CARDDAT') RIDFLD(key) GTEQ
        FC->>VSAM: Open VSAM browse cursor
        VSAM-->>FC: Cursor positioned

        COBOL->>FC: EXEC CICS READNEXT / READPREV<br/>INTO(CARD-RECORD)<br/>RIDFLD(WS-CARD-RID)
        FC->>VSAM: Fetch next/prev record
        VSAM-->>FC: Record or ENDFILE

        COBOL->>FC: EXEC CICS ENDBR<br/>FILE('CARDDAT')
        FC->>VSAM: Close browse cursor

        Note right of FC: Java Equivalent
        FC->>VSAM: cardRepository.findByCardNumberGreaterThanEqual(<br/>key, PageRequest.of(page, size, Sort.by("cardNumber")))<br/>→ SELECT * FROM cards<br/>WHERE card_number >= ?<br/>ORDER BY card_number<br/>LIMIT ? OFFSET ?
        VSAM-->>FC: Page<Card> result set
    end

    rect rgb(255, 240, 230)
        Note right of COBOL: Error Handling Migration

        COBOL->>FC: EVALUATE WS-RESP-CD<br/>WHEN DFHRESP(NORMAL) ...<br/>WHEN DFHRESP(NOTFND) ...<br/>WHEN DFHRESP(DUPREC) ...<br/>WHEN OTHER ...

        Note right of FC: Java Equivalent
        FC->>FC: try { repository.operation() }<br/>catch (DataAccessException ex) {<br/>  // Spring exception hierarchy:<br/>  // EntityNotFoundException<br/>  // DuplicateKeyException<br/>  // OptimisticLockingFailureException<br/>  // DataIntegrityViolationException<br/>}
    end

    %% ===============================================================
    %% SECTION 2 — PROGRAM CONTROL MIGRATION
    %% ===============================================================
    Note over COBOL,VSAM: === 2. Program Control Migration ===<br/>EXEC CICS XCTL / RETURN / LINK with COMMAREA<br/>→ Spring MVC @Controller routing with HttpSession state

    rect rgb(230, 255, 230)
        Note right of COBOL: XCTL — Transfer control with COMMAREA

        COBOL->>PC: EXEC CICS XCTL<br/>PROGRAM(CDEMO-MENU-OPT-PGMNAME)<br/>COMMAREA(CARDDEMO-COMMAREA)
        PC->>PC: Load target program<br/>Pass 1024-byte COMMAREA<br/>(COCOM01Y.cpy layout)

        Note right of PC: Java Equivalent (COMEN01C menu routing)
        PC->>PC: @Controller MenuController<br/>@PostMapping("/menu/select")<br/>session.setAttribute("commarea", dto)<br/>return "forward:/target-controller"
    end

    rect rgb(230, 255, 230)
        Note right of COBOL: RETURN — End transaction, set next TRANSID

        COBOL->>PC: EXEC CICS RETURN<br/>TRANSID(WS-TRANID)<br/>COMMAREA(CARDDEMO-COMMAREA)
        PC->>PC: Suspend task, store COMMAREA<br/>Await next terminal input<br/>Resume with TRANSID

        Note right of PC: Java Equivalent
        PC->>PC: session.setAttribute("commarea", dto)<br/>session.setAttribute("nextTransId", "CM00")<br/>return new ModelAndView("current-view", model)
    end

    rect rgb(230, 255, 230)
        Note right of COBOL: LINK — Call subroutine (if used)

        COBOL->>PC: EXEC CICS LINK<br/>PROGRAM('subroutine')
        PC->>PC: Invoke and return to caller

        Note right of PC: Java Equivalent
        PC->>PC: @Autowired SubroutineService service<br/>service.execute(params)<br/>// Direct method call replaces LINK
    end

    rect rgb(230, 255, 230)
        Note right of COBOL: COMMAREA State Object Migration

        COBOL->>PC: CARDDEMO-COMMAREA (COCOM01Y.cpy)<br/>─ CDEMO-FROM-TRANID    PIC X(04)<br/>─ CDEMO-FROM-PROGRAM   PIC X(08)<br/>─ CDEMO-TO-PROGRAM     PIC X(08)<br/>─ CDEMO-USER-ID        PIC X(08)<br/>─ CDEMO-USER-TYPE      PIC X(01)<br/>─ CDEMO-PGM-CONTEXT    PIC 9(01)<br/>─ CDEMO-ACCT-ID        PIC 9(11)<br/>─ CDEMO-CARD-NUM       PIC 9(16)

        Note right of PC: Java Equivalent
        PC->>PC: @SessionScope CardDemoSession {<br/>  String fromTransId;<br/>  String fromProgram;<br/>  String toProgram;<br/>  String userId;<br/>  UserType userType; // ENUM(ADMIN,USER)<br/>  int pgmContext;<br/>  Long accountId;<br/>  Long cardNumber;<br/>}
    end

    %% ===============================================================
    %% SECTION 3 — TERMINAL CONTROL MIGRATION
    %% ===============================================================
    Note over COBOL,VSAM: === 3. Terminal Control Migration ===<br/>EXEC CICS SEND MAP / RECEIVE MAP / SEND TEXT<br/>→ REST API + Thymeleaf/HTML templates with CSS styling

    rect rgb(255, 255, 220)
        Note right of COBOL: SEND MAP — Render screen to 3270 terminal

        COBOL->>TC: EXEC CICS SEND<br/>MAP('COMEN1A')<br/>MAPSET('COMEN01')<br/>FROM(COMEN1AO) ERASE
        TC->>TC: BMS formats map data<br/>Sends 3270 data stream<br/>Applies DFHBMSCA attributes<br/>(color, protection, intensity)

        Note right of TC: Java Equivalent
        TC->>TC: @GetMapping("/menu")<br/>model.addAttribute("menuData", dto)<br/>return new ModelAndView("comen01/menu", model)<br/><br/>Thymeleaf template:<br/>&lt;form th:object="${menuData}"&gt;<br/>&lt;input th:field="*{option}" class="field-input"/&gt;
    end

    rect rgb(255, 255, 220)
        Note right of COBOL: RECEIVE MAP — Read user input from terminal

        COBOL->>TC: EXEC CICS RECEIVE<br/>MAP('COMEN1A')<br/>MAPSET('COMEN01')<br/>INTO(COMEN1AI)<br/>RESP(WS-RESP-CD)
        TC->>TC: Parse 3270 AID byte (EIBAID)<br/>Extract modified fields<br/>Populate input map area

        Note right of TC: Java Equivalent
        TC->>TC: @PostMapping("/menu")<br/>public ModelAndView processMenu(<br/>  @ModelAttribute MenuForm form,<br/>  BindingResult result) {<br/>  // form fields auto-bound<br/>  // validation via @Valid<br/>}
    end

    rect rgb(255, 255, 220)
        Note right of COBOL: SEND TEXT — Write plain text to terminal

        COBOL->>TC: EXEC CICS SEND TEXT<br/>FROM(WS-RETURN-MSG)<br/>LENGTH(msg-len) ERASE FREEKB
        TC->>TC: Send raw text to 3270 screen

        Note right of TC: Java Equivalent
        TC->>TC: response.setContentType("text/plain")<br/>response.getWriter().write(message)<br/>// Or return ResponseEntity.ok(message)
    end

    rect rgb(255, 255, 220)
        Note right of COBOL: BMS Macro → HTML/CSS Mapping

        TC->>TC: DFHMSD TYPE=MAP → HTML &lt;html&gt; page<br/>DFHMDI SIZE=(24,80) → &lt;div class="screen"&gt;<br/>DFHMDF POS=(r,c),ATTRB=(ASKIP,BRT)<br/>→ &lt;span class="bright protected"&gt;<br/>DFHMDF POS=(r,c),ATTRB=(UNPROT,IC)<br/>→ &lt;input class="editable focus"&gt;

        Note right of TC: DFHBMSCA Attribute → CSS Class
        TC->>TC: DFHBMASK (autoskip) → .field-protected<br/>DFHBMPRF (protected) → .field-readonly<br/>DFHBMFSE (unprotected) → .field-input<br/>DFHRED / DFHGREEN / DFHBLUE<br/>→ .text-red / .text-green / .text-blue<br/>DFHBMBRY (bright) → .text-bright
    end

    %% ===============================================================
    %% SECTION 4 — SYSTEM SERVICES MIGRATION
    %% ===============================================================
    Note over COBOL,VSAM: === 4. System Services Migration ===<br/>EXEC CICS ASSIGN / ASKTIME / FORMATTIME<br/>→ Java system properties and java.time API

    rect rgb(245, 230, 255)
        Note right of COBOL: ASSIGN — Retrieve system identification

        COBOL->>SS: EXEC CICS ASSIGN<br/>APPLID(APPLIDO OF COSGN0AO)
        SS-->>COBOL: CICS Application ID value

        COBOL->>SS: EXEC CICS ASSIGN<br/>SYSID(SYSIDO OF COSGN0AO)
        SS-->>COBOL: CICS System ID value

        Note right of SS: Java Equivalent
        SS->>SS: String appId = env.getProperty("app.name")<br/>// or InetAddress.getLocalHost().getHostName()<br/>String sysId = env.getProperty("server.id")<br/>// Spring @Value("${app.name}") injection
    end

    rect rgb(245, 230, 255)
        Note right of COBOL: ASKTIME + FORMATTIME — Get and format timestamp

        COBOL->>SS: EXEC CICS ASKTIME<br/>ABSTIME(WS-ABS-TIME)
        SS-->>COBOL: Absolute time (packed decimal)

        COBOL->>SS: EXEC CICS FORMATTIME<br/>ABSTIME(WS-ABS-TIME)<br/>YYYYMMDD(WS-CUR-DATE)<br/>DATESEP('-')<br/>TIME(WS-CUR-TIME) TIMESEP(':')
        SS-->>COBOL: Formatted: 2024-01-15 14:30:00

        Note right of SS: Java Equivalent
        SS->>SS: LocalDateTime now = LocalDateTime.now();<br/>String date = now.format(<br/>  DateTimeFormatter.ofPattern("yyyy-MM-dd"));<br/>String time = now.format(<br/>  DateTimeFormatter.ofPattern("HH:mm:ss"));
    end

    %% ===============================================================
    %% SECTION 5 — TDQ MIGRATION
    %% ===============================================================
    Note over COBOL,VSAM: === 5. Transient Data Queue (TDQ) Migration ===<br/>EXEC CICS WRITEQ TD QUEUE('JOBS')<br/>→ JMS message queue or Spring Batch job launcher

    rect rgb(255, 230, 230)
        Note right of COBOL: WRITEQ TD — Submit JCL via TDQ to JES Internal Reader

        COBOL->>TDQ: EXEC CICS WRITEQ TD<br/>QUEUE('JOBS')<br/>FROM(JCL-RECORD)<br/>LENGTH(jcl-len)<br/>RESP(WS-RESP-CD)
        TDQ->>TDQ: Write JCL record to<br/>Transient Data Queue<br/>→ JES Internal Reader<br/>→ Batch job submission

        Note right of TDQ: Java Equivalent (Option A: JMS)
        TDQ->>TDQ: @Autowired JmsTemplate jmsTemplate;<br/>jmsTemplate.convertAndSend(<br/>  "batch-jobs", new BatchJobRequest(<br/>    reportType, dateRange, params));<br/><br/>// JMS listener triggers Spring Batch

        Note right of TDQ: Java Equivalent (Option B: Spring Batch)
        TDQ->>TDQ: @Autowired JobLauncher launcher;<br/>JobParameters params = new JobParametersBuilder()<br/>  .addString("reportType", type)<br/>  .addDate("runDate", new Date())<br/>  .toJobParameters();<br/>launcher.run(reportJob, params);

        Note right of TDQ: Java Equivalent (Option C: AWS Cloud)
        TDQ->>TDQ: AWSBatch client = AWSBatchClient.create();<br/>client.submitJob(SubmitJobRequest.builder()<br/>  .jobName("card-report")<br/>  .jobQueue("batch-queue")<br/>  .build());
    end
```

---

## VSAM File-to-Repository Mapping

The following table maps CICS File Control dataset names (used in `EXEC CICS READ/WRITE`
statements) to their Spring Data JPA repository equivalents:

| VSAM File (FCT Name) | VSAM Dataset | Spring Repository | JPA Entity | Primary Key |
|---|---|---|---|---|
| `ACCTDAT` | AWS.M2.CARDDEMO.ACCTDATA.PS | `AccountRepository` | `Account` | `accountId (PIC 9(11))` |
| `CARDDAT` | AWS.M2.CARDDEMO.CARDDATA.PS | `CardRepository` | `Card` | `cardNumber (PIC 9(16))` |
| `CUSTDAT` | AWS.M2.CARDDEMO.CUSTDATA.PS | `CustomerRepository` | `Customer` | `customerId (PIC 9(09))` |
| `USRSEC` | AWS.M2.CARDDEMO.USRSEC.PS | `UserSecurityRepository` | `UserSecurity` | `userId (PIC X(08))` |
| `TRANSACT` | AWS.M2.CARDDEMO.TRANSACT.PS | `TransactionRepository` | `Transaction` | `transactionId (PIC 9(16))` |
| `CCXREF` | AWS.M2.CARDDEMO.CARDXREF.PS | `CardXrefRepository` | `CardXref` | `cardNumber (PIC 9(16))` |
| `CARDAIX` | AWS.M2.CARDDEMO.CARDDATA.AIX.PS | `CardRepository` (alt index query) | `Card` | Alternate: `accountId` |
| `CXACAIX` | AWS.M2.CARDDEMO.CARDXREF.AIX.PS | `CardXrefRepository` (alt index query) | `CardXref` | Alternate: `accountId` |

## Program-to-CICS-Command Cross-Reference

The following table identifies which CICS command categories each of the 16 online programs uses:

| Program | File Control | Program Control | Terminal Control | System Services | TDQ |
|---|---|---|---|---|---|
| `COACTVWC` | READ, STARTBR, READNEXT, ENDBR | XCTL, RETURN | SEND MAP, RECEIVE MAP, SEND TEXT | — | — |
| `COACTUPC` | READ, REWRITE, SYNCPOINT | XCTL, RETURN | SEND MAP, RECEIVE MAP | — | — |
| `COADM01C` | — | XCTL, RETURN | SEND MAP, RECEIVE MAP | — | — |
| `COBIL00C` | READ, WRITE, REWRITE, STARTBR, READPREV, ENDBR | XCTL, RETURN | SEND MAP, RECEIVE MAP | ASKTIME, FORMATTIME | — |
| `COCRDLIC` | STARTBR, READNEXT, READPREV, ENDBR | XCTL, RETURN | SEND MAP, RECEIVE MAP, SEND TEXT | — | — |
| `COCRDSLC` | READ | XCTL, RETURN | SEND MAP, RECEIVE MAP, SEND TEXT | — | — |
| `COCRDUPC` | READ, REWRITE | XCTL, RETURN | SEND MAP, RECEIVE MAP | — | — |
| `COMEN01C` | — | XCTL, RETURN | SEND MAP, RECEIVE MAP | — | — |
| `CORPT00C` | — | XCTL, RETURN | SEND MAP, RECEIVE MAP | — | WRITEQ TD |
| `COSGN00C` | READ | RETURN | SEND MAP, RECEIVE MAP, SEND TEXT | ASSIGN | — |
| `COTRN00C` | READ, STARTBR, READNEXT, READPREV, ENDBR | XCTL, RETURN | SEND MAP, RECEIVE MAP | — | — |
| `COTRN01C` | READ | XCTL, RETURN | SEND MAP, RECEIVE MAP | — | — |
| `COTRN02C` | READ, WRITE, REWRITE, STARTBR, READPREV, ENDBR | XCTL, RETURN | SEND MAP, RECEIVE MAP | — | — |
| `COUSR00C` | — | XCTL, RETURN | SEND MAP, RECEIVE MAP | — | — |
| `COUSR01C` | READ, STARTBR, READNEXT, ENDBR | XCTL, RETURN | SEND MAP, RECEIVE MAP | — | — |
| `COUSR02C` | READ, REWRITE | XCTL, RETURN | SEND MAP, RECEIVE MAP | — | — |
| `COUSR03C` | READ, DELETE | XCTL, RETURN | SEND MAP, RECEIVE MAP | — | — |

## Migration Rationale Notes

### File Control → Spring Data JPA / JDBC

CICS File Control commands provide synchronous, single-record VSAM I/O with explicit
cursor management (STARTBR/READNEXT/ENDBR). The Spring Data JPA equivalent replaces this
with repository pattern methods (`findById`, `save`, `deleteById`) backed by HikariCP
connection pooling. Browse operations (cursor traversal) map to JPA `Pageable` queries or
JDBC `ResultSet` cursors. The CICS `RESP`/`RESP2` error model maps to Spring's
`DataAccessException` hierarchy, providing equivalent error discrimination without
numeric response codes.

Source: `app/cbl/COACTUPC.cbl:3654-3670` (READ with RIDFLD), `app/cbl/COTRN02C.cbl:713-720` (WRITE), `app/cbl/COACTUPC.cbl:4065-4071` (REWRITE), `app/cbl/COUSR03C.cbl:307-311` (DELETE), `app/cbl/COCRDLIC.cbl:1129-1170` (STARTBR/READNEXT browse loop)

### Program Control → Spring MVC Controller

CICS pseudo-conversational architecture uses `XCTL` (transfer control) and `RETURN TRANSID`
(suspend and resume) to implement screen-to-screen navigation with COMMAREA-based state
passing. The `CARDDEMO-COMMAREA` structure (defined in `app/cpy/COCOM01Y.cpy`) carries
user identity, navigation context, and business entity keys across program boundaries.
Spring MVC replaces this with `@Controller` classes, `forward:`/`redirect:` navigation,
and `HttpSession` or `@SessionScope` beans for state management. The COMEN01C menu
router's dynamic `XCTL PROGRAM(option)` pattern maps to a Spring MVC dispatcher that
delegates to controller methods based on user selection.

Source: `app/cbl/COMEN01C.cbl:152-155` (XCTL with dynamic program name), `app/cbl/COMEN01C.cbl:107-110` (RETURN TRANSID with COMMAREA), `app/cpy/COCOM01Y.cpy:19-47` (COMMAREA layout)

### Terminal Control → REST API + HTML Templates

All 16 CICS programs use `SEND MAP`/`RECEIVE MAP` for 3270 screen I/O through BMS
(Basic Mapping Support). The 17 BMS mapsets define screen layouts using DFHMSD (mapset),
DFHMDI (map/screen), and DFHMDF (field) macros, with visual attributes from the
DFHBMSCA copybook (color, protection, intensity). The AID key handling via DFHAID
(PF keys, ENTER, CLEAR) maps to HTML form buttons and JavaScript key handlers.
Spring's Thymeleaf template engine replaces BMS mapsets, with HTML forms replacing
3270 data entry screens and CSS classes replacing DFHBMSCA attribute bytes.

Source: `app/cbl/COMEN01C.cbl:189-194` (SEND MAP), `app/cbl/COMEN01C.cbl:201-207` (RECEIVE MAP), `app/cbl/COACTVWC.cbl:878-883` (SEND TEXT), `app/cpy/CSSTRPFY.cpy:17-60` (AID key mapping), `app/cpy/CSSETATY.cpy:17-27` (DFHBMSCA attribute usage)

### System Services → Java System APIs and java.time

COSGN00C uses `EXEC CICS ASSIGN APPLID` and `ASSIGN SYSID` to retrieve the CICS
application and system identifiers for display on the sign-on screen. COBIL00C uses
`EXEC CICS ASKTIME` to capture the current absolute time and `FORMATTIME` to convert it
to a human-readable date/time string with custom separators. These map directly to Java's
`System.getProperty()` or Spring `Environment` for system identification, and
`java.time.LocalDateTime` with `DateTimeFormatter` for date/time operations.

Source: `app/cbl/COSGN00C.cbl:198-204` (ASSIGN APPLID and SYSID), `app/cbl/COBIL00C.cbl:251-261` (ASKTIME and FORMATTIME with DATESEP/TIMESEP)

### TDQ → JMS / Spring Batch

CORPT00C dynamically generates JCL records and writes them to the `JOBS` Transient Data
Queue (TDQ), which routes to the JES Internal Reader for batch job submission. This
asynchronous job submission pattern maps to three Java alternatives: (1) JMS messaging
with a Spring Batch listener for on-premise deployments, (2) direct Spring Batch
`JobLauncher` invocation for simple cases, or (3) AWS Batch `submitJob()` for
cloud-native deployments. The TDQ error handling via `RESP`/`RESP2` maps to JMS
exception handling or Spring Batch `JobExecutionException`.

Source: `app/cbl/CORPT00C.cbl:517-523` (WRITEQ TD to JOBS queue), `app/cbl/CORPT00C.cbl:525-535` (RESP/RESP2 error evaluation)

---

## Source Citations

| Source File | Lines | Content Referenced |
|---|---|---|
| `app/cbl/COSGN00C.cbl` | 198–204 | `EXEC CICS ASSIGN APPLID` and `ASSIGN SYSID` |
| `app/cbl/COBIL00C.cbl` | 251–261 | `EXEC CICS ASKTIME` and `FORMATTIME` with DATESEP/TIMESEP |
| `app/cbl/CORPT00C.cbl` | 517–523 | `EXEC CICS WRITEQ TD QUEUE('JOBS')` |
| `app/cbl/CORPT00C.cbl` | 525–535 | TDQ RESP/RESP2 error evaluation |
| `app/cbl/COMEN01C.cbl` | 107–110 | `EXEC CICS RETURN TRANSID` with COMMAREA |
| `app/cbl/COMEN01C.cbl` | 152–155 | `EXEC CICS XCTL PROGRAM` (dynamic menu routing) |
| `app/cbl/COMEN01C.cbl` | 189–207 | `SEND MAP` / `RECEIVE MAP` with COMEN01 mapset |
| `app/cbl/COACTUPC.cbl` | 3654–3670 | `EXEC CICS READ DATASET` with RIDFLD and RESP |
| `app/cbl/COACTUPC.cbl` | 4065–4099 | `EXEC CICS REWRITE` with SYNCPOINT ROLLBACK |
| `app/cbl/COTRN02C.cbl` | 713–720 | `EXEC CICS WRITE DATASET` for transaction insert |
| `app/cbl/COUSR03C.cbl` | 307–311 | `EXEC CICS DELETE DATASET` for user removal |
| `app/cbl/COCRDLIC.cbl` | 1129–1170 | `STARTBR`/`READNEXT` browse loop with GTEQ |
| `app/cbl/COACTVWC.cbl` | 878–883 | `EXEC CICS SEND TEXT` for plain text output |
| `app/cpy/COCOM01Y.cpy` | 19–47 | CARDDEMO-COMMAREA structure (1024-byte layout) |
| `app/cpy/CSSTRPFY.cpy` | 17–60 | AID key mapping (EIBAID to DFHAID constants) |
| `app/cpy/CSSETATY.cpy` | 17–27 | DFHBMSCA attribute usage for screen field styling |
