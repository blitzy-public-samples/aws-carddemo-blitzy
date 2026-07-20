      *****************************************************************
      *  CardDemo REST/JSON API - customer response layout
      *  Derived from CVCUS01Y (CUSTOMER-RECORD, RECLN 500)
      *  PII MINIMIZED: SSN last-4 only; govt id last-4 only;
      *  EFT account id omitted; no full SSN/govt id present
      *****************************************************************
       01  API-CUST-RESPONSE.
           05  CUST-ID                    PIC 9(09).
           05  CUST-FIRST-NAME            PIC X(25).
           05  CUST-MIDDLE-NAME           PIC X(25).
           05  CUST-LAST-NAME             PIC X(25).
           05  CUST-ADDR-LINE-1           PIC X(50).
           05  CUST-ADDR-LINE-2           PIC X(50).
           05  CUST-ADDR-LINE-3           PIC X(50).
           05  CUST-ADDR-STATE-CD         PIC X(02).
           05  CUST-ADDR-COUNTRY-CD       PIC X(03).
           05  CUST-ADDR-ZIP              PIC X(10).
           05  CUST-PHONE-NUM-1           PIC X(15).
           05  CUST-PHONE-NUM-2           PIC X(15).
      *  Masked PII (never full values):
           05  CUST-SSN-MASKED            PIC X(11).
           05  CUST-GOVT-ID-MASKED        PIC X(04).
           05  CUST-DOB-YYYY-MM-DD        PIC X(10).
           05  CUST-PRI-CARD-HOLDER-IND   PIC X(01).
           05  CUST-FICO-CREDIT-SCORE     PIC 9(03).
