      *****************************************************************
      *  CardDemo REST/JSON API - transaction detail + list
      *  Derived from CVTRA05Y (TRAN-RECORD, RECLN 350)
      *  SECURITY: card number masked to last-4
      *  TRAN-AMT signed implied-decimal S9(09)V99, scale 2
      *  LIST carried over CHANNEL/CONTAINER (COTRSVCC)
      *****************************************************************
       01  API-TRAN-RESPONSE.
           05  TRAN-ID                    PIC X(16).
           05  TRAN-TYPE-CD               PIC X(02).
           05  TRAN-CAT-CD                PIC 9(04).
           05  TRAN-SOURCE                PIC X(10).
           05  TRAN-DESC                  PIC X(100).
           05  TRAN-AMT                   PIC S9(09)V99.
           05  TRAN-MERCHANT-ID           PIC 9(09).
           05  TRAN-MERCHANT-NAME         PIC X(50).
           05  TRAN-MERCHANT-CITY         PIC X(50).
           05  TRAN-MERCHANT-ZIP          PIC X(10).
           05  TRAN-CARD-NUM-MASKED       PIC X(16).
           05  TRAN-ORIG-TS               PIC X(26).
           05  TRAN-PROC-TS               PIC X(26).
      *  Per-account list (CONTAINER transport, not COMMAREA)
       01  API-TRAN-LIST.
           05  TRAN-LIST-COUNT            PIC 9(04).
           05  TRAN-LIST-TRUNCATED        PIC X(01).
           88  TRAN-LIST-COMPLETE  VALUE 'N'.
      *  Reserved state: current COTRSVCC never produces 'Y'.
      *  COAPIRTR reads it to render the JSON truncated property.
           88  TRAN-LIST-WAS-TRUNCATED  VALUE 'Y'.
           05  TRAN-LIST-ACCT-ID          PIC 9(11).
           05  TRAN-LIST-ENTRY OCCURS 0 TO 500 TIMES
                               DEPENDING ON TRAN-LIST-COUNT.
               10  TRNL-ID                PIC X(16).
               10  TRNL-TYPE-CD           PIC X(02).
               10  TRNL-CAT-CD            PIC 9(04).
               10  TRNL-SOURCE            PIC X(10).
               10  TRNL-DESC              PIC X(100).
               10  TRNL-AMT               PIC S9(09)V99.
               10  TRNL-MERCHANT-ID       PIC 9(09).
               10  TRNL-MERCHANT-NAME     PIC X(50).
               10  TRNL-MERCHANT-CITY     PIC X(50).
               10  TRNL-MERCHANT-ZIP      PIC X(10).
               10  TRNL-CARD-NUM-MASKED   PIC X(16).
               10  TRNL-ORIG-TS           PIC X(26).
               10  TRNL-PROC-TS           PIC X(26).
      *  Container transport contract (COTRSVCC <-> COAPIRTR) on
      *  channel CDEMOAPILISTCH - shared so the producer service and
      *  the router use ONE layout (no hand-recreated structures):
      *    TRANLISTREQ = API-TRAN-LIST-REQUEST  (11 bytes)
      *    TRANLISTRSP = API-TRAN-LIST          (header + used ODO)
      *    TRANLISTSTA = API-TRAN-LIST-STATUS   (135 bytes)
       01  API-TRAN-LIST-REQUEST.
           05  TRLR-ACCT-ID               PIC 9(11).
       01  API-TRAN-LIST-STATUS.
           05  TRLS-HTTP-STATUS           PIC 9(03).
           05  TRLS-RETURN-CODE           PIC S9(04).
           05  TRLS-ERR-CODE              PIC X(08).
           05  TRLS-ERR-MESSAGE           PIC X(120).
