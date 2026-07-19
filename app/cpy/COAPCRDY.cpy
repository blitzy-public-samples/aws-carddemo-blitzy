      *****************************************************************
      *  CardDemo REST/JSON API - card response layout
      *  Derived from CVACT02Y (CARD-RECORD, RECLN 150)
      *  SECURITY: card number masked to last-4 (CARD-NUM-MASKED);
      *  card security code intentionally omitted, never serialized
      *****************************************************************
       01  API-CARD-RESPONSE.
           05  CARD-NUM-MASKED            PIC X(16).
           05  CARD-ACCT-ID               PIC 9(11).
           05  CARD-EMBOSSED-NAME         PIC X(50).
           05  CARD-EXPIRAION-DATE        PIC X(10).
           05  CARD-ACTIVE-STATUS         PIC X(01).
