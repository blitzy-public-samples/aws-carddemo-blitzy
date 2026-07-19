      *****************************************************************
      *  CardDemo REST/JSON API - card cross-reference response
      *  Derived from CVACT03Y (CARD-XREF-RECORD, RECLN 50)
      *  SECURITY: card number masked to last-4
      *****************************************************************
       01  API-XREF-RESPONSE.
           05  XREF-CARD-NUM-MASKED       PIC X(16).
           05  XREF-ACCT-ID               PIC 9(11).
           05  XREF-CUST-ID               PIC 9(09).
