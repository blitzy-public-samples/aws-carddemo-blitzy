      *****************************************************************
      *  CardDemo REST/JSON API - sign-on request/response + token
      *  Derived from CSUSR01Y (SEC-USR-ID/PWD/TYPE), read-only auth
      *  SECURITY: password is in REQUEST only, never in response
      *****************************************************************
       01  API-SIGNON-REQUEST.
           05  SGNI-USER-ID               PIC X(08).
           05  SGNI-PASSWORD              PIC X(08).
      *  Response layout - contains no password field
       01  API-SIGNON-RESPONSE.
           05  SGNO-TOKEN                 PIC X(64).
           05  SGNO-USER-ID               PIC X(08).
           05  SGNO-USER-TYPE             PIC X(01).
           88  SGNO-USER-ADMIN  VALUE 'A'.
           88  SGNO-USER-REGULAR  VALUE 'U'.
           05  SGNO-TOKEN-EXPIRY-TS       PIC X(26).
