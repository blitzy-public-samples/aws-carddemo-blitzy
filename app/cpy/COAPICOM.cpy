      *****************************************************************
      *  CardDemo REST/JSON API - shared COMMAREA/channel contract
      *  NEW structure, independent of COCOM01Y (CARDDEMO-COMMAREA)
      *  Single-record ops use COMMAREA; TRAN LIST uses CONTAINER
      *  LIST channel  = CDEMOAPILISTCH
      *  LIST req ctnr = TRANLISTREQ / rsp ctnr = TRANLISTRSP
      *****************************************************************
       01  API-COMMAREA.
      *  Request: route selector + generic single-record key
           05  API-REQUEST.
               10  API-SERVICE-CODE       PIC X(08).
               10  API-HTTP-METHOD        PIC X(04).
               10  API-REQ-ACCT-ID        PIC 9(11).
               10  API-REQ-CUST-ID        PIC 9(09).
               10  API-REQ-CARD-NUM       PIC X(16).
               10  API-REQ-TRAN-ID        PIC X(16).
      *  Response status: service RC + HTTP status intent
           05  API-RESPONSE-STATUS.
               10  API-RETURN-CODE        PIC S9(04).
               10  API-HTTP-STATUS        PIC 9(03).
               88  API-HTTP-OK  VALUE 200.
               88  API-HTTP-BAD-REQUEST  VALUE 400.
               88  API-HTTP-UNAUTHORIZED  VALUE 401.
               88  API-HTTP-NOT-FOUND  VALUE 404.
               88  API-HTTP-SERVER-ERROR  VALUE 500.
      *  Bearer token (backs COAPISEC issue/validate)
           05  API-TOKEN.
               10  API-TOKEN-VALUE        PIC X(64).
               10  API-TOKEN-USER-ID      PIC X(08).
               10  API-TOKEN-USER-TYPE    PIC X(01).
               88  API-TOKEN-ADMIN  VALUE 'A'.
               88  API-TOKEN-USER  VALUE 'U'.
               10  API-TOKEN-EXPIRY-TS    PIC X(26).
      *  Error envelope: JSON code / message / requestId
      *  API-ERR-CODE carries ONE canonical internal vocabulary shared
      *  by every service. Each 8-char token maps to the public OpenAPI
      *  error code the router (COAPIRTR) emits in JSON:
      *    'BADREQ'   -> BAD_REQUEST     (HTTP 400)
      *    'UNAUTH'   -> UNAUTHORIZED    (HTTP 401)
      *    'NOTFOUND' -> NOT_FOUND       (HTTP 404)
      *    'INTERNAL' -> INTERNAL_ERROR  (HTTP 500)
      *  API-ERR-REQUEST-ID is owned and populated by the router
      *  (COAPIRTR); service programs must never set it.
           05  API-ERROR.
               10  API-ERR-CODE           PIC X(08).
               88  API-ERR-BAD-REQUEST  VALUE 'BADREQ'.
               88  API-ERR-UNAUTHORIZED  VALUE 'UNAUTH'.
               88  API-ERR-NOT-FOUND  VALUE 'NOTFOUND'.
               88  API-ERR-SERVER-ERROR  VALUE 'INTERNAL'.
               10  API-ERR-MESSAGE        PIC X(120).
               10  API-ERR-REQUEST-ID     PIC X(36).
      *  Generic single-record response payload buffer;
      *  per-service response copybooks redefine this area.
           05  API-PAYLOAD                PIC X(1000).
