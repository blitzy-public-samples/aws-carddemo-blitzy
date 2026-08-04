-- Card service, migration V2.
-- Seed rows for two of the four tables that V1__schema.sql defines.
-- Flyway 12.4.0 applies it after V1, on PostgreSQL 18.4, inside the schema that
-- spring.flyway.schemas names. Every table name below stays unqualified.

-- Each block names its source fixture under app/data/ASCII/ and the COBOL (Common
-- Business Oriented Language) record layout that fixes its field positions.
-- The same block names the Job Control Language (JCL) member that loads that
-- fixture on z/OS through the dataset utility IDCAMS.

-- Rationale for these seed rows: card-platform/docs/decision-log.md (planned).
-- Flagged source rules: card-platform/docs/business-rule-flags.md (planned).
-- Field-by-field source mapping, the omitted FILLER included:
-- card-platform/docs/traceability-matrix.md (planned).

-- outbox_event and processed_event receive no row here. The card update path writes
-- an outbox_event row per mutation at run time, and processed_event carries the
-- idempotency markers of the events this service handles.


-- 50 rows from app/data/ASCII/carddata.txt, 150 bytes per line, in fixture order on
-- the card number.
-- Fields: CARD-NUM PIC X(16) at app/cpy/CVACT02Y.cpy:L5, CARD-ACCT-ID PIC 9(11) at
-- :L6, CARD-CVV-CD PIC 9(03) at :L7, CARD-EMBOSSED-NAME PIC X(50) at :L8,
-- CARD-EXPIRAION-DATE PIC X(10) at :L9, then CARD-ACTIVE-STATUS PIC X(01) at :L10.
-- Key width from KEYS(16 0) at app/jcl/CARDFILE.jcl:L54, record width from
-- RECORDSIZE(150 150) at :L55, load step REPRO INFILE(CARDDATA) OUTFILE(CARDVSAM)
-- at :L76.
-- The trailing FILLER PIC X(59) at app/cpy/CVACT02Y.cpy:L11 holds only spaces in all
-- 50 records and gets no column.
-- Five card numbers open with a zero and eight verification values do, so every
-- identifier lands as a quoted fixed-width value and keeps the digits the fixture
-- wrote. The embossed name keeps the 50 characters of its source field, space padding
-- included, matching embossed_name CHAR(50).
INSERT INTO card (card_number, account_id, card_verification_value, embossed_name,
    expiration_date, active_status) VALUES
    ('0500024453765740', '00000000050', '747', 'Aniya Von                                         ',
        '2023-03-09', 'Y'),
    ('0683586198171516', '00000000027', '567', 'Ward Jones                                        ',
        '2025-07-13', 'Y'),
    ('0923877193247330', '00000000002', '028', 'Enrico Rosenbaum                                  ',
        '2024-08-11', 'Y'),
    ('0927987108636232', '00000000020', '003', 'Carter Veum                                       ',
        '2024-03-13', 'Y'),
    ('0982496213629795', '00000000012', '075', 'Maci Robel                                        ',
        '2023-07-07', 'Y'),
    ('1014086565224350', '00000000044', '640', 'Irving Emard                                      ',
        '2024-01-17', 'Y'),
    ('1142167692878931', '00000000037', '625', 'Shany Walker                                      ',
        '2023-10-24', 'Y'),
    ('1561409106491600', '00000000035', '031', 'Angelica Dach                                     ',
        '2025-09-23', 'Y'),
    ('2745303720002090', '00000000039', '033', 'Aliyah Berge                                      ',
        '2025-09-08', 'Y'),
    ('2760836797107565', '00000000024', '859', 'Stefanie Dickinson                                ',
        '2025-02-11', 'Y'),
    ('2871968252812490', '00000000006', '775', 'Ignacio Douglas                                   ',
        '2025-10-08', 'Y'),
    ('2940139362300449', '00000000022', '876', 'Allene Brown                                      ',
        '2025-12-28', 'Y'),
    ('2988091353094312', '00000000004', '795', 'Delbert Parisian                                  ',
        '2023-12-16', 'Y'),
    ('3260763612337560', '00000000010', '342', 'Maybell Mann                                      ',
        '2023-01-27', 'Y'),
    ('3766281984155154', '00000000041', '622', 'Lucinda Dach                                      ',
        '2023-04-24', 'Y'),
    ('3940246016141489', '00000000019', '375', 'Hadley Hamill                                     ',
        '2025-07-23', 'Y'),
    ('3999169246375885', '00000000003', '317', 'Larry Homenick                                    ',
        '2024-01-10', 'Y'),
    ('4011500891777367', '00000000013', '390', 'Mariane Fadel                                     ',
        '2024-08-04', 'Y'),
    ('4385271476627819', '00000000034', '709', 'Faustino Schmidt                                  ',
        '2025-10-06', 'Y'),
    ('4534784102713951', '00000000036', '644', 'Toney Gerhold                                     ',
        '2024-12-23', 'Y'),
    ('4859452612877065', '00000000007', '321', 'Cooper Mayert                                     ',
        '2024-12-13', 'Y'),
    ('5407099850479866', '00000000021', '524', 'Jerrold Maggio                                    ',
        '2023-01-06', 'Y'),
    ('5656830544981216', '00000000046', '196', 'Cindy Cremin                                      ',
        '2025-06-20', 'Y'),
    ('5671184478505844', '00000000018', '137', 'Emile White                                       ',
        '2023-09-10', 'Y'),
    ('5787351228879339', '00000000047', '067', 'Rigoberto Hoeger                                  ',
        '2025-08-23', 'Y'),
    ('5975117516616077', '00000000042', '426', 'Heather Nienow                                    ',
        '2025-09-19', 'Y'),
    ('6009619150674526', '00000000005', '021', 'Treva Schowalter                                  ',
        '2025-03-09', 'Y'),
    ('6349250331648509', '00000000015', '735', 'Aubree Hermann                                    ',
        '2025-06-09', 'Y'),
    ('6503535181795992', '00000000048', '413', 'Lyric Pacocha                                     ',
        '2025-02-06', 'Y'),
    ('6509230362553816', '00000000030', '236', 'Layla Ullrich                                     ',
        '2024-06-27', 'Y'),
    ('6723000463207764', '00000000028', '486', 'Hester Hane                                       ',
        '2024-05-09', 'Y'),
    ('6727055190616014', '00000000016', '641', 'Carroll Bergstrom                                 ',
        '2024-01-25', 'Y'),
    ('6832676047698087', '00000000033', '983', 'Bernice Herman                                    ',
        '2025-10-07', 'Y'),
    ('7026637615032277', '00000000031', '920', 'Lucious O''Connell                                 ',
        '2025-06-08', 'Y'),
    ('7058267261837752', '00000000043', '401', 'Britney Waters                                    ',
        '2025-08-29', 'Y'),
    ('7094142751055551', '00000000032', '659', 'Stephany Fisher                                   ',
        '2025-05-19', 'Y'),
    ('7251508149188883', '00000000029', '717', 'Rickie Daugherty                                  ',
        '2024-06-04', 'Y'),
    ('7379335634661142', '00000000045', '134', 'Dixie Beier                                       ',
        '2025-07-09', 'Y'),
    ('7427684863423209', '00000000011', '892', 'Hayden Pfannerstill                               ',
        '2025-03-12', 'Y'),
    ('7443870988897530', '00000000038', '708', 'Angela Ankunding                                  ',
        '2023-07-23', 'Y'),
    ('8040580410348680', '00000000026', '971', 'Marjory Stracke                                   ',
        '2024-12-19', 'Y'),
    ('8112545834239735', '00000000023', '440', 'Johnson Ruecker                                   ',
        '2025-03-18', 'Y'),
    ('8262593602473076', '00000000049', '457', 'Immanuel Bednar                                   ',
        '2023-09-17', 'Y'),
    ('8517866958206008', '00000000014', '955', 'Chelsea Marks                                     ',
        '2025-12-11', 'Y'),
    ('8931369351894783', '00000000008', '230', 'Kelsie Dicki                                      ',
        '2024-05-20', 'Y'),
    ('9056297931664011', '00000000025', '931', 'Elliott Howell                                    ',
        '2025-07-10', 'Y'),
    ('9349107475869214', '00000000017', '218', 'Sigrid Mann                                       ',
        '2025-03-01', 'Y'),
    ('9501733721429893', '00000000009', '725', 'Melvin Ondricka                                   ',
        '2024-12-27', 'Y'),
    ('9680294154603697', '00000000001', '045', 'Immanuel Kessler                                  ',
        '2025-05-20', 'Y'),
    ('9805583408996588', '00000000040', '908', 'Davon Emmerich                                    ',
        '2023-10-27', 'Y');


-- 50 rows from app/data/ASCII/cardxref.txt, 36 bytes per line, in fixture order on the
-- card number. This table is a replica private to the card service: the authorization
-- service owns its own copy, and no service reads another service's schema.
-- Fields: XREF-CARD-NUM PIC X(16) at app/cpy/CVACT03Y.cpy:L5, XREF-CUST-ID PIC 9(09)
-- at :L6, then XREF-ACCT-ID PIC 9(11) at :L7.
-- Key width from KEYS(16 0) at app/jcl/XREFFILE.jcl:L43, record width from
-- RECORDSIZE(50 50) at :L44, load step REPRO INFILE(XREFDATA) OUTFILE(XREFVSAM) at
-- :L64.
-- The trailing FILLER PIC X(14) at app/cpy/CVACT03Y.cpy:L8 holds no characters in the
-- fixture and gets no column.
-- Every one of the 50 rows opens both identifiers with a zero.
INSERT INTO card_xref (card_number, customer_id, account_id) VALUES
    ('0500024453765740', '000000050', '00000000050'),
    ('0683586198171516', '000000027', '00000000027'),
    ('0923877193247330', '000000002', '00000000002'),
    ('0927987108636232', '000000020', '00000000020'),
    ('0982496213629795', '000000012', '00000000012'),
    ('1014086565224350', '000000044', '00000000044'),
    ('1142167692878931', '000000037', '00000000037'),
    ('1561409106491600', '000000035', '00000000035'),
    ('2745303720002090', '000000039', '00000000039'),
    ('2760836797107565', '000000024', '00000000024'),
    ('2871968252812490', '000000006', '00000000006'),
    ('2940139362300449', '000000022', '00000000022'),
    ('2988091353094312', '000000004', '00000000004'),
    ('3260763612337560', '000000010', '00000000010'),
    ('3766281984155154', '000000041', '00000000041'),
    ('3940246016141489', '000000019', '00000000019'),
    ('3999169246375885', '000000003', '00000000003'),
    ('4011500891777367', '000000013', '00000000013'),
    ('4385271476627819', '000000034', '00000000034'),
    ('4534784102713951', '000000036', '00000000036'),
    ('4859452612877065', '000000007', '00000000007'),
    ('5407099850479866', '000000021', '00000000021'),
    ('5656830544981216', '000000046', '00000000046'),
    ('5671184478505844', '000000018', '00000000018'),
    ('5787351228879339', '000000047', '00000000047'),
    ('5975117516616077', '000000042', '00000000042'),
    ('6009619150674526', '000000005', '00000000005'),
    ('6349250331648509', '000000015', '00000000015'),
    ('6503535181795992', '000000048', '00000000048'),
    ('6509230362553816', '000000030', '00000000030'),
    ('6723000463207764', '000000028', '00000000028'),
    ('6727055190616014', '000000016', '00000000016'),
    ('6832676047698087', '000000033', '00000000033'),
    ('7026637615032277', '000000031', '00000000031'),
    ('7058267261837752', '000000043', '00000000043'),
    ('7094142751055551', '000000032', '00000000032'),
    ('7251508149188883', '000000029', '00000000029'),
    ('7379335634661142', '000000045', '00000000045'),
    ('7427684863423209', '000000011', '00000000011'),
    ('7443870988897530', '000000038', '00000000038'),
    ('8040580410348680', '000000026', '00000000026'),
    ('8112545834239735', '000000023', '00000000023'),
    ('8262593602473076', '000000049', '00000000049'),
    ('8517866958206008', '000000014', '00000000014'),
    ('8931369351894783', '000000008', '00000000008'),
    ('9056297931664011', '000000025', '00000000025'),
    ('9349107475869214', '000000017', '00000000017'),
    ('9501733721429893', '000000009', '00000000009'),
    ('9680294154603697', '000000001', '00000000001'),
    ('9805583408996588', '000000040', '00000000040');
