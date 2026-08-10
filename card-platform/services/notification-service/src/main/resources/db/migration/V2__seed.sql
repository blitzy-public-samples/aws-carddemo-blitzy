-- Notification service, migration V2. Bootstraps cardholder_context, the account-keyed projection
-- every rendered alert reads.
--
-- Statements: one INSERT of fifty rows. V1__schema.sql creates the table and nothing else populates
-- it except messaging/CustomerContextChangedConsumer, which fills it from CustomerContextChanged --
-- an event the account service publishes only when a customer record changes.
--
-- Provenance. Every value is read from app/data/ASCII/custdata.txt, the fifty-record customer fixture
-- whose layout is 01 CUSTOMER-RECORD at app/cpy/CVCUS01Y.cpy:L4-L23 (RECLN 500). The ten fields kept
-- here are the ten 5000-CREATE-STATEMENT reads at app/cbl/CBSTM03A.CBL:L462-L485; this service owns no
-- customer record and keeps nothing else. Values are stored trimmed, as an event carries them, and a
-- render pads each field to the width its source field declares.
--
-- The account key. custdata.txt is keyed by CUST-ID PIC 9(09) and this table by account, so each
-- customer is resolved through XREF-CUST-ID and XREF-ACCT-ID of 01 CARD-XREF-RECORD at
-- app/cpy/CVACT03Y.cpy:L4-L8, read from app/data/ASCII/cardxref.txt at offsets 16 and 25. That
-- mapping is one to one across all fifty rows.
--
-- The epoch stamps. repository/CardholderContextRepository#applyContextChange replaces a row only
-- when the arriving change occurred strictly after the stored source_occurred_at, so every row here
-- carries 1970-01-01T00:00:00Z and the first real CustomerContextChanged for an account supersedes it.
-- observed_at carries the same instant, and it is the column a staleness report orders by. No
-- retention sweep deletes from this table.
--
-- Rationale, alternatives considered and accepted risks: card-platform/docs/decision-log.md.

INSERT INTO cardholder_context (account_id, first_name, middle_name, last_name,
                                address_line_1, address_line_2, address_line_3,
                                state_code, country_code, zip_code, fico_score,
                                source_occurred_at, observed_at)
VALUES
    ('00000000001', 'Immanuel', 'Madeline', 'Kessler', '618 Deshaun Route', 'Apt. 802', 'Altenwerthshire', 'NC', 'USA', '12546', '274', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000002', 'Enrico', 'April', 'Rosenbaum', '4917 Myrna Flats', 'Apt. 453', 'West Bernita', 'IN', 'USA', '22770', '268', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000003', 'Larry', 'Cody', 'Homenick', '362 Esta Parks', 'Apt. 390', 'New Gladys', 'GA', 'USA', '19852-6716', '616', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000004', 'Delbert', 'Kaia', 'Parisian', '638 Blanda Gateway', 'Apt. 076', 'Lake Virginie', 'MI', 'USA', '39035-0455', '776', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000005', 'Treva', 'Manley', 'Schowalter', '5653 Legros Plaza', 'Apt. 968', 'Alvinaport', 'MI', 'USA', '02251-1698', '529', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000006', 'Ignacio', 'Emery', 'Douglas', '3963 Yasmin Port', 'Suite 756', 'Port Josephstad', 'VI', 'USA', '46713-5148', '753', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000007', 'Cooper', 'Dennis', 'Mayert', '6490 Zakary Locks', 'Apt. 765', 'Madieport', 'AL', 'USA', '34206-2974', '499', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000008', 'Kelsie', 'Jordyn', 'Dicki', '0925 Welch Streets', 'Apt. 152', 'North Nanniestad', 'SC', 'USA', '27610', '051', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000009', 'Melvin', 'Regan', 'Ondricka', '87893 Samson Flats', 'Apt. 135', 'New Braden', 'VI', 'USA', '21113', '699', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000010', 'Maybell', 'Creola', 'Mann', '77933 Adah Dale', 'Suite 343', 'Andersonfurt', 'CT', 'USA', '44803-4279', '476', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000011', 'Hayden', 'Ressie', 'Pfannerstill', '14895 Everette Ridges', 'Apt. 443', 'Julianneburgh', 'WA', 'USA', '24984', '209', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000012', 'Maci', 'Alan', 'Robel', '80501 Isac Cliffs', 'Suite 623', 'Predovicton', 'MN', 'USA', '78861', '688', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000013', 'Mariane', 'Oma', 'Fadel', '2689 Derick Mission', 'Suite 055', 'Bruenfurt', 'OR', 'USA', '02322', '053', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000014', 'Chelsea', 'Ignacio', 'Marks', '747 Dino Lodge', 'Apt. 850', 'West Chase', 'RI', 'USA', '12914-8465', '243', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000015', 'Aubree', 'Elliot', 'Hermann', '36365 Ledner Drives', 'Suite 882', 'Port Efrainland', 'DE', 'USA', '63205-7014', '681', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000016', 'Carroll', 'Cicero', 'Bergstrom', '06988 Thiel Falls', 'Suite 148', 'Concepcionland', 'VT', 'USA', '84390', '326', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000017', 'Sigrid', 'Angeline', 'Mann', '95666 Dare Isle', 'Suite 286', 'New Presley', 'FM', 'USA', '56181-0584', '054', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000018', 'Emile', 'Jairo', 'White', '133 Bergnaum Square', 'Apt. 328', 'Hansenville', 'AP', 'USA', '96003-5867', '340', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000019', 'Hadley', 'Sigrid', 'Hamill', '6273 Ondricka Meadows', 'Apt. 130', 'New Arturoshire', 'RI', 'USA', '48161', '259', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000020', 'Carter', 'Oren', 'Veum', '5845 Allison Valleys', 'Suite 934', 'Mitchellmouth', 'MH', 'USA', '72362', '493', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000021', 'Jerrold', 'Adolphus', 'Maggio', '401 Haylie Crest', 'Apt. 320', 'North Myrnaton', 'CA', 'USA', '72407', '163', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000022', 'Allene', 'Icie', 'Brown', '4467 Donnie Crossroad', 'Apt. 437', 'Anabelton', 'MD', 'USA', '01993-9116', '597', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000023', 'Johnson', 'Blanca', 'Ruecker', '2433 Jacobi Forks', 'Apt. 845', 'Hendersonbury', 'KS', 'USA', '78239-9466', '337', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000024', 'Stefanie', 'Verla', 'Dickinson', '6367 Stracke River', 'Apt. 444', 'East Otho', 'KS', 'USA', '15414', '711', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000025', 'Elliott', 'Fermin', 'Howell', '9524 McKenzie Lakes', 'Suite 245', 'West Alexa', 'NH', 'USA', '75721-7382', '355', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000026', 'Marjory', 'Damien', 'Stracke', '30161 Bogan Canyon', 'Suite 916', 'Walshberg', 'IL', 'USA', '59945', '001', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000027', 'Ward', 'Henri', 'Jones', '210 Amaya Turnpike', 'Suite 180', 'Port Dwight', 'GU', 'USA', '07923-8822', '078', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000028', 'Hester', 'Vesta', 'Hane', '06816 Ursula Meadows', 'Suite 605', 'South Aurore', 'AS', 'USA', '77442-7954', '114', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000029', 'Rickie', 'Otho', 'Daugherty', '676 Funk Curve', 'Apt. 375', 'Hayesstad', 'NH', 'USA', '01226', '552', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000030', 'Layla', 'Dannie', 'Ullrich', '269 Eleazar Circle', 'Apt. 817', 'Kutchland', 'AK', 'USA', '64266', '133', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000031', 'Lucious', 'Otto', 'O''Connell', '919 Swift Valleys', 'Suite 548', 'Hermanborough', 'MS', 'USA', '56133-5636', '058', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000032', 'Stephany', 'Meda', 'Fisher', '63452 Kenny Streets', 'Apt. 116', 'Predovicburgh', 'AK', 'USA', '85943-7605', '221', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000033', 'Bernice', 'Norbert', 'Herman', '877 Kassandra Ranch', 'Suite 956', 'Haleyport', 'AR', 'USA', '19113-4329', '469', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000034', 'Faustino', 'Jess', 'Schmidt', '44132 Michel Square', 'Suite 007', 'South Margarettaburgh', 'ME', 'USA', '49544-2869', '104', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000035', 'Angelica', 'Damaris', 'Dach', '396 Pearl Loop', 'Suite 383', 'Pfefferhaven', 'LA', 'USA', '46142', '793', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000036', 'Toney', 'Emerald', 'Gerhold', '35943 Raleigh Harbor', 'Apt. 116', 'Lake Derekburgh', 'AL', 'USA', '10932-0480', '266', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000037', 'Shany', 'Darby', 'Walker', '91196 Heaney Turnpike', 'Suite 814', 'Lubowitzberg', 'NV', 'USA', '11857-8177', '653', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000038', 'Angela', 'Ceasar', 'Ankunding', '65482 Zoila Skyway', 'Apt. 054', 'East Malachi', 'VA', 'USA', '63928-0008', '446', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000039', 'Aliyah', 'Horace', 'Berge', '5761 Pasquale Trail', 'Apt. 616', 'New Sabryna', 'IA', 'USA', '74267', '475', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000040', 'Davon', 'Demond', 'Emmerich', '23499 Beer Views', 'Suite 816', 'Erniechester', 'TX', 'USA', '87156-8689', '284', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000041', 'Lucinda', 'Kiana', 'Dach', '3220 Yolanda Corner', 'Suite 649', 'East Harmonystad', 'VT', 'USA', '72971-7481', '725', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000042', 'Heather', 'Ericka', 'Nienow', '5523 Archibald Club', 'Apt. 358', 'Reillyland', 'FM', 'USA', '83589', '044', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000043', 'Britney', 'Jermain', 'Waters', '97765 Bernhard Fort', 'Apt. 666', 'South Marisaview', 'OK', 'USA', '10050-7980', '558', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000044', 'Irving', 'Kiera', 'Emard', '978 Fatima Stream', 'Apt. 110', 'Lake King', 'ID', 'USA', '05704-0501', '145', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000045', 'Dixie', 'Norris', 'Beier', '441 Levi Prairie', 'Suite 749', 'Abbottshire', 'NV', 'USA', '09048', '629', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000046', 'Cindy', 'Kira', 'Cremin', '494 Lang Avenue', 'Apt. 937', 'Alexandroview', 'PW', 'USA', '63082-4520', '514', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000047', 'Rigoberto', 'Savanna', 'Hoeger', '00097 Gleichner Spur', 'Apt. 932', 'Port Aidanborough', 'GU', 'USA', '31329-6973', '722', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000048', 'Lyric', 'Mackenzie', 'Pacocha', '453 Rosina Mountain', 'Apt. 011', 'Albertville', 'OR', 'USA', '83985-4937', '746', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000049', 'Immanuel', 'Ellie', 'Bednar', '5423 Esther Locks', 'Apt. 142', 'Langoshstad', 'GA', 'USA', '12288-3495', '148', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'),
    ('00000000050', 'Aniya', 'Alba', 'Von', '1588 Nienow Cape', 'Suite 187', 'New Aricchester', 'OR', 'USA', '04257', '623', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00');
