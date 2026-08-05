-- Card service seed data, Flyway migration V2. Flyway applies V1__schema.sql first, then
-- loads the 100 rows below into two of the four tables that V1 defines. Table names stay
-- unqualified, and spring.flyway.schemas in src/main/resources/application.yml names the
-- schema.

-- 50 rows from app/data/ASCII/carddata.txt, 150 bytes per record, ordered on the card
-- number as the fixture holds them, with field positions from app/cpy/CVACT02Y.cpy:L5-L10.
-- The trailing FILLER PIC X(59) at :L11 holds spaces in all 50 records and gets no column.
-- Key width KEYS(16 0) at app/jcl/CARDFILE.jcl:L54, record width RECORDSIZE(150 150) at
-- :L55, load step REPRO INFILE(CARDDATA) OUTFILE(CARDVSAM) at :L75.
-- Five card numbers and eight card verification values open with a zero in that fixture,
-- and every identifier below is a quoted digit string at its declared width.
-- Each embossed name below is trimmed of the padding CARD-EMBOSSED-NAME PIC X(50) carries.
--
-- card_token carries no fixture value because app/cpy/CVACT02Y.cpy declares no such field. Each
-- literal below is the SHA-256 digest of the label CardDemo/card-token/v1: followed by the card
-- number on its own row, rendered as sixty-four lower-case hexadecimal characters. That is what
-- com.carddemo.cobol.PanMasker.cardToken derives, and that helper is the one derivation this
-- platform holds: the entity applies it to every row this service writes, and the literals below
-- carry it for the fifty rows this fixture loads. V1__schema.sql documents the column, and
-- CardRepositoryIT.everySeededTokenMatchesTheJavaDerivation compares all fifty literals against
-- that helper, so a mistyped literal fails a test rather than splitting one card's identity in two.
INSERT INTO card (card_number, account_id, card_verification_value, embossed_name,
    expiration_date, active_status, card_token) VALUES
    ('0500024453765740', '00000000050', '747', 'Aniya Von',           '2023-03-09', 'Y',
        'defe2b19b01114fb4cbfd18f8aed77dcf723d0b6d54fa9248aa44a499e8e9d3b'),
    ('0683586198171516', '00000000027', '567', 'Ward Jones',          '2025-07-13', 'Y',
        'e78842a47130beb7e68a80bcc2bd95c6245ee6a842502fdb935b25ea2d8e1f0f'),
    ('0923877193247330', '00000000002', '028', 'Enrico Rosenbaum',    '2024-08-11', 'Y',
        'e05050921b0230042b95624b5422f61879d03f19e91144d5b167cf01bf74812f'),
    ('0927987108636232', '00000000020', '003', 'Carter Veum',         '2024-03-13', 'Y',
        '0149bb5fcf2115a7a04493cac6f4bd3ea035a824a2721147bb97d91248577c6e'),
    ('0982496213629795', '00000000012', '075', 'Maci Robel',          '2023-07-07', 'Y',
        '7ad5092926cb783d05899228966909f35acf2f0b609863c6f9ed7c47e8283eed'),
    ('1014086565224350', '00000000044', '640', 'Irving Emard',        '2024-01-17', 'Y',
        '17e043e772ceb1d731cac1eb0b1d2ff1ee682a940dd759a3bb0ecfec8d182339'),
    ('1142167692878931', '00000000037', '625', 'Shany Walker',        '2023-10-24', 'Y',
        'e1967eabf3cba68b854f42194f27daf7047db7dffb9c5de8a209c62f80e86ef8'),
    ('1561409106491600', '00000000035', '031', 'Angelica Dach',       '2025-09-23', 'Y',
        '78e4a2997acb10a203609e0d30b6524b87c830af2caad70edb88a86391c3393e'),
    ('2745303720002090', '00000000039', '033', 'Aliyah Berge',        '2025-09-08', 'Y',
        '76c8b2435df8f97a1b4fe84aa6dd721299ef9eddd22b4e258776be8f414905a1'),
    ('2760836797107565', '00000000024', '859', 'Stefanie Dickinson',  '2025-02-11', 'Y',
        'ecb27347e54e02893da96c473365bd2453ec07adc1fe3e2cd69f272661bdbbed'),
    ('2871968252812490', '00000000006', '775', 'Ignacio Douglas',     '2025-10-08', 'Y',
        '593788c7c562c01fb38f436ccef713314cfa36e76a08c2f62bbcf641e47ce567'),
    ('2940139362300449', '00000000022', '876', 'Allene Brown',        '2025-12-28', 'Y',
        '9f43de741502635870b7fe69aaf775ec4c6d6a234f4c6def17a6465430cc87f6'),
    ('2988091353094312', '00000000004', '795', 'Delbert Parisian',    '2023-12-16', 'Y',
        'e3a3ab5ed53f797360377c7333aebaea3cced392e93c377e837d69d834a13123'),
    ('3260763612337560', '00000000010', '342', 'Maybell Mann',        '2023-01-27', 'Y',
        '6fa23c5ebf5a78b7c6d06001957dccb6a5ff9b173ece778bf0eb476150a03570'),
    ('3766281984155154', '00000000041', '622', 'Lucinda Dach',        '2023-04-24', 'Y',
        'cb4236576447644697c523a1033fd4d15aff35a03ca61b17902dc38522836f74'),
    ('3940246016141489', '00000000019', '375', 'Hadley Hamill',       '2025-07-23', 'Y',
        '6fa214a830788f2994e7067bf063c3576eb00d04a2abc4e20d037005679e0262'),
    ('3999169246375885', '00000000003', '317', 'Larry Homenick',      '2024-01-10', 'Y',
        '7c5a8e1ce266e05473d2f1546680c24319077f4bdcfce84ffff304c6a75de14f'),
    ('4011500891777367', '00000000013', '390', 'Mariane Fadel',       '2024-08-04', 'Y',
        '0e7aa987784327b405318300afa2411bd1dfd0df9d92e2d818f854d54b3301dc'),
    ('4385271476627819', '00000000034', '709', 'Faustino Schmidt',    '2025-10-06', 'Y',
        '473b0d879bb7c38d26c7adfcfe6ec2b1b6e535a82d6a055fb0312d7f71466962'),
    ('4534784102713951', '00000000036', '644', 'Toney Gerhold',       '2024-12-23', 'Y',
        'aa681a4aee7b5ab3b2a5263d395b126c97bc15d2520761694b9d311144bf9832'),
    ('4859452612877065', '00000000007', '321', 'Cooper Mayert',       '2024-12-13', 'Y',
        'f8da0217fb8bd2e172d427a2ef66d54656a59baa9fe8f9bc2ce9d383b90e1173'),
    ('5407099850479866', '00000000021', '524', 'Jerrold Maggio',      '2023-01-06', 'Y',
        'a905e24210948fb7e0694e0ced394e749c90c7ca560b1b81c16f12e1d09053ae'),
    ('5656830544981216', '00000000046', '196', 'Cindy Cremin',        '2025-06-20', 'Y',
        '658d08435ae304559574c558833336d81ba22bd8313a3ba84e9bdb917f5e6c01'),
    ('5671184478505844', '00000000018', '137', 'Emile White',         '2023-09-10', 'Y',
        '3bb4417c1d4b2554b7050e3339dcdaebb2272a0ce88941d4a461c25f3164962b'),
    ('5787351228879339', '00000000047', '067', 'Rigoberto Hoeger',    '2025-08-23', 'Y',
        '7aae1d3d3c214fefcf4a16b017f5868c43eb5e9232b06297f42d3ec51d18439c'),
    ('5975117516616077', '00000000042', '426', 'Heather Nienow',      '2025-09-19', 'Y',
        '1f58d91fc53b123fb3c0d2204b2ea503bc66fd90c2c0345a32960d2d4c1f235f'),
    ('6009619150674526', '00000000005', '021', 'Treva Schowalter',    '2025-03-09', 'Y',
        'e7c3637befffab1dfbabc17846a034c2cfb21db8fbc076080176c811e9e5711e'),
    ('6349250331648509', '00000000015', '735', 'Aubree Hermann',      '2025-06-09', 'Y',
        'bf8618700296e7e1ecf062bd52904f4b0eee218b26962e341b87c31d58f48203'),
    ('6503535181795992', '00000000048', '413', 'Lyric Pacocha',       '2025-02-06', 'Y',
        '754986e43eff87a64c5ce34da05228b0a62533910fbe6ce5229a8cf9b0c5d109'),
    ('6509230362553816', '00000000030', '236', 'Layla Ullrich',       '2024-06-27', 'Y',
        '9956d73feb643e62e34664c3e5cc51b5000d11d7b4a7fbce34ef4e0c5806d5c0'),
    ('6723000463207764', '00000000028', '486', 'Hester Hane',         '2024-05-09', 'Y',
        '97bac0f023873def502632b9072bed7b6394558d9410df964e5e1638d3ba47e9'),
    ('6727055190616014', '00000000016', '641', 'Carroll Bergstrom',   '2024-01-25', 'Y',
        '76339f91049e9121fd7cdd957bc31252fdf61ce702d196b17e7440d0dbf62e0a'),
    ('6832676047698087', '00000000033', '983', 'Bernice Herman',      '2025-10-07', 'Y',
        'dad9fea9d2ab8616b9b3728e6d21c79151a0d9ee92cf98399a05d2668f83cde9'),
    ('7026637615032277', '00000000031', '920', 'Lucious O''Connell',  '2025-06-08', 'Y',
        'db0853aaf333ebd4798023795656d4830ac9bdbe21686094c2048d886c403c90'),
    ('7058267261837752', '00000000043', '401', 'Britney Waters',      '2025-08-29', 'Y',
        'f8d1f4eadec51e230ac507d0d8991abcdaaf5cbb65db5e295d077afecc12568d'),
    ('7094142751055551', '00000000032', '659', 'Stephany Fisher',     '2025-05-19', 'Y',
        '735800405dfc572aeee3c2426cdd9ef58744dc866c8d8ea2f7c9a4c6d789256d'),
    ('7251508149188883', '00000000029', '717', 'Rickie Daugherty',    '2024-06-04', 'Y',
        'fa2de8c4c21241c5b254299a30de7b4c2dc97c0513006d494718783114016f6b'),
    ('7379335634661142', '00000000045', '134', 'Dixie Beier',         '2025-07-09', 'Y',
        '8c2259f6127d2c11e247e5535024a68063001aaac8037371b406d395baec5bdc'),
    ('7427684863423209', '00000000011', '892', 'Hayden Pfannerstill', '2025-03-12', 'Y',
        '01b92a3a5ed1cebb214abd04aad58c6fc9a8da6f4cb4cb97f0c37bc53e64c442'),
    ('7443870988897530', '00000000038', '708', 'Angela Ankunding',    '2023-07-23', 'Y',
        '6491335950b6aff04ae9c466de56d472de18b5c954d771f10b9a4c2d8667433c'),
    ('8040580410348680', '00000000026', '971', 'Marjory Stracke',     '2024-12-19', 'Y',
        'a70e0b5f2ad5986e6e7c7793771419863c56a15d4cc34d60cbda9098818f37d6'),
    ('8112545834239735', '00000000023', '440', 'Johnson Ruecker',     '2025-03-18', 'Y',
        'b4e83db2acb6e056218d0e5b520f4ecc1651c49c53ef7ef38faaef28dfbb33e1'),
    ('8262593602473076', '00000000049', '457', 'Immanuel Bednar',     '2023-09-17', 'Y',
        'a8d769a5453761efd771e6f717449d6417a084bbfc51ee52d6cb156b4be72ce2'),
    ('8517866958206008', '00000000014', '955', 'Chelsea Marks',       '2025-12-11', 'Y',
        'e6b0fc2d550b23d19098f82c6563fd0a4be1d12ea4daea2c3d229204bf9f4ce0'),
    ('8931369351894783', '00000000008', '230', 'Kelsie Dicki',        '2024-05-20', 'Y',
        '649295faaec70501e13843f04e01b424810fee241b8d1b63b1d786edca3450e2'),
    ('9056297931664011', '00000000025', '931', 'Elliott Howell',      '2025-07-10', 'Y',
        '5e274bb98d7ca765aac2bc65e91bd6c29dcf02816fd3b8e0ff8dcc3bd363b12d'),
    ('9349107475869214', '00000000017', '218', 'Sigrid Mann',         '2025-03-01', 'Y',
        'd97cd166a477f4765ae4e9d812b023e726cc055c9a2b2f61ee0df72fcd018b1f'),
    ('9501733721429893', '00000000009', '725', 'Melvin Ondricka',     '2024-12-27', 'Y',
        '50a30c050a81755a192bc281b7abbec2c27bd8a4afd68b9b03d667e981e1a03c'),
    ('9680294154603697', '00000000001', '045', 'Immanuel Kessler',    '2025-05-20', 'Y',
        '1134636222d1a2485d20203d0e970c72124893eb01be73a5fde5fdcccc2c4ac9'),
    ('9805583408996588', '00000000040', '908', 'Davon Emmerich',      '2023-10-27', 'Y',
        '300238d5256802351a10f4e1f597935fe1f5b0943c7534880d9c55237e8a0f34');

-- 50 rows from app/data/ASCII/cardxref.txt, 36 bytes per record, in the same card-number
-- order, with field positions from app/cpy/CVACT03Y.cpy:L5-L7.
-- The fixture carries 36 bytes against the 50 that RECORDSIZE(50 50) at
-- app/jcl/XREFFILE.jcl:L44 declares, and the FILLER PIC X(14) at :L8 reaches neither the
-- fixture nor the table.
-- Key width KEYS(16 0) at app/jcl/XREFFILE.jcl:L43, load step REPRO INFILE(XREFDATA)
-- OUTFILE(XREFVSAM) at :L64.
-- Both identifiers open with a zero in all 50 records and arrive quoted at their declared
-- widths.
-- The three remaining columns take what V1__schema.sql gives a seeded row: NULL, NULL and
-- DEFAULT CURRENT_TIMESTAMP.
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
