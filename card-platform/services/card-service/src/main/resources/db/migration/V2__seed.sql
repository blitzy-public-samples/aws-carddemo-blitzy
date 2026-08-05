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
-- literal below is the HmacSHA256 code, taken under the configured card-token key, over the label
-- CardDemo/card-token/v1: followed by the card number on its own row, rendered as sixty-four
-- lower-case hexadecimal characters. That is what com.carddemo.cobol.PanMasker.cardToken derives,
-- and that helper is the one derivation this platform holds: the entity applies it to every row
-- this service writes, and the literals below carry it for the fifty rows this fixture loads.
-- V1__schema.sql documents the column, and
-- CardRepositoryIT.everySeededTokenMatchesTheJavaDerivation compares all fifty literals against
-- that helper, so a mistyped literal fails a test rather than splitting one card's identity in two.
--
-- The key is a keyed value rather than a public digest, so these fifty literals belong to one key
-- and one version. They are derived under the CARD_TOKEN_SECRET and CARD_TOKEN_VERSION values
-- card-platform/.env.example declares, which deploy/k8s/31-secret.example.yaml and
-- deploy/k8s/30-configmap.yaml repeat and card-platform/pom.xml supplies to every test run.
-- Turning either over re-derives every token, so a deployment that changes them re-derives these
-- literals and every granted SCOPE_CARD authority with them. The procedure is in
-- card-platform/docs/suggested-next-tasks.md.
INSERT INTO card (card_number, account_id, card_verification_value, embossed_name,
    expiration_date, active_status, card_token) VALUES
    ('0500024453765740', '00000000050', '747', 'Aniya Von',           '2023-03-09', 'Y',
        'd29277ff9f4215818ca524cbf2e94927149958ef6c6a9f49242ffa18a484fe9d'),
    ('0683586198171516', '00000000027', '567', 'Ward Jones',          '2025-07-13', 'Y',
        'b6767443fb0552699815ef56767b75525474b43411f79653bae4fc87f4c3bd75'),
    ('0923877193247330', '00000000002', '028', 'Enrico Rosenbaum',    '2024-08-11', 'Y',
        'f13dd9beb1ac9b6f8e83b03a5f7dc095f5f908675f261c72df1904d5a7e55760'),
    ('0927987108636232', '00000000020', '003', 'Carter Veum',         '2024-03-13', 'Y',
        '4c80166a24e7511b27bfd0e8d92a710e1a8817d8257372eec608a20c2fd21160'),
    ('0982496213629795', '00000000012', '075', 'Maci Robel',          '2023-07-07', 'Y',
        '8085c6fc2163f63f0d4f79ec1b58d4c017ba73f37574e2174b8d91ad7e810620'),
    ('1014086565224350', '00000000044', '640', 'Irving Emard',        '2024-01-17', 'Y',
        '1674b1f92276bfa20fd41650261474698db31fa1c5cd586820e6630bad17b823'),
    ('1142167692878931', '00000000037', '625', 'Shany Walker',        '2023-10-24', 'Y',
        'ec1b13e57441572b260e64bb7e1b794b8bbe9f099980117d1b9a1099c5f941dc'),
    ('1561409106491600', '00000000035', '031', 'Angelica Dach',       '2025-09-23', 'Y',
        '07c79562d40585d7d004eaaee1f839f38480743dff5e24054cc469549f327822'),
    ('2745303720002090', '00000000039', '033', 'Aliyah Berge',        '2025-09-08', 'Y',
        '16654aa5b0d5079f7776c3079ef2ebfd79816d33ac550dcff06013fdc76ae0b8'),
    ('2760836797107565', '00000000024', '859', 'Stefanie Dickinson',  '2025-02-11', 'Y',
        'e9b3f7f1623794a4af3e223b3f1c49a5ac0d17a77f2be9f7c747df4fd58f73d6'),
    ('2871968252812490', '00000000006', '775', 'Ignacio Douglas',     '2025-10-08', 'Y',
        'c213c99ece981aea5721391520126ed13a2cfa76d1822124ca4bd6a62a7edb27'),
    ('2940139362300449', '00000000022', '876', 'Allene Brown',        '2025-12-28', 'Y',
        '6173851314feebe975b34d50e7aaf19dd7b6a286c5d5fe59a77f1c72667a504e'),
    ('2988091353094312', '00000000004', '795', 'Delbert Parisian',    '2023-12-16', 'Y',
        '020234976383cf452daa48d27bde0625dd1380351e00f2ebf8225f5715c8af31'),
    ('3260763612337560', '00000000010', '342', 'Maybell Mann',        '2023-01-27', 'Y',
        '3b375d7f233d64d8f194c6626b629d9ef519561c56bd010612226aa877306fd3'),
    ('3766281984155154', '00000000041', '622', 'Lucinda Dach',        '2023-04-24', 'Y',
        'c1e74eda91c57e428088c791a14cd73bdaece02619a82592fff60e6ead3d4e36'),
    ('3940246016141489', '00000000019', '375', 'Hadley Hamill',       '2025-07-23', 'Y',
        '21e8c084b7361a9fee9471c14b405b27aec6376a97a757faa45397c95d02567d'),
    ('3999169246375885', '00000000003', '317', 'Larry Homenick',      '2024-01-10', 'Y',
        'af1a8324e831f3897242c410fa91571730d91da5ff11eed0be929e058d6907ef'),
    ('4011500891777367', '00000000013', '390', 'Mariane Fadel',       '2024-08-04', 'Y',
        '24f782703726c26743d1d2572471bc7f19434f03ed3af3983d9f130f2ae983eb'),
    ('4385271476627819', '00000000034', '709', 'Faustino Schmidt',    '2025-10-06', 'Y',
        'e53aa527016aa7aea7fe28fa974a91166e2760d2822985d7154e7767d1631160'),
    ('4534784102713951', '00000000036', '644', 'Toney Gerhold',       '2024-12-23', 'Y',
        '7ec4832235f83ec898ce2a39695b43c27a6a9fcfd26d6a9eb0467de693812517'),
    ('4859452612877065', '00000000007', '321', 'Cooper Mayert',       '2024-12-13', 'Y',
        '60628a15d4ee3589bede34459a1d80437b5273de072ef89536460b793c9bda07'),
    ('5407099850479866', '00000000021', '524', 'Jerrold Maggio',      '2023-01-06', 'Y',
        '83e9d3362fe6c257f7ef48216555328ec423b4b08a6eb82642c9d73f28c1105a'),
    ('5656830544981216', '00000000046', '196', 'Cindy Cremin',        '2025-06-20', 'Y',
        '83c4e995b84eb4976c1902ba1a85e731ae0c132cdab9cc042b367d62081a0b4c'),
    ('5671184478505844', '00000000018', '137', 'Emile White',         '2023-09-10', 'Y',
        '15fc939bc74b4c351b9aac8fbbdeae48917cf17e697912ea34f8462d45cbe71b'),
    ('5787351228879339', '00000000047', '067', 'Rigoberto Hoeger',    '2025-08-23', 'Y',
        'cedceef387b1ffbeefbfb7d4b83d3d8888e8a7ceb814447ed37354ad66edb021'),
    ('5975117516616077', '00000000042', '426', 'Heather Nienow',      '2025-09-19', 'Y',
        '31fde41150453756ae7119520a4c14214ec5fba35e7c0588d05efe8b363a58de'),
    ('6009619150674526', '00000000005', '021', 'Treva Schowalter',    '2025-03-09', 'Y',
        'a18afc5271dfaf7f3f9bcd72ec7c7b4fa680010152026b83d2a386d29f3698bb'),
    ('6349250331648509', '00000000015', '735', 'Aubree Hermann',      '2025-06-09', 'Y',
        '1981a1764d75153059833aee5d0e87ac0f52af1373f8ecb5c69d1678533df0f7'),
    ('6503535181795992', '00000000048', '413', 'Lyric Pacocha',       '2025-02-06', 'Y',
        '21dfc68d5d4fe1f91fb0edab90960051fe8ae027816df9558ef7aac09a70d15e'),
    ('6509230362553816', '00000000030', '236', 'Layla Ullrich',       '2024-06-27', 'Y',
        'a386358c5d5457e5df8f45ce1b2e122d367bb36435764329b23190be138872da'),
    ('6723000463207764', '00000000028', '486', 'Hester Hane',         '2024-05-09', 'Y',
        'a9fe55500561843f0525981f465bb56318ffcfd6f234f90b9426392ac4fb1321'),
    ('6727055190616014', '00000000016', '641', 'Carroll Bergstrom',   '2024-01-25', 'Y',
        '851b20725ec198f1034ec5a675fd11f2e2819004d0aca9794a6fc99b83968c9d'),
    ('6832676047698087', '00000000033', '983', 'Bernice Herman',      '2025-10-07', 'Y',
        '0e19f37c0ba1d807f720e7934351dd46e1042c4693c7b1692272a02e2251cc53'),
    ('7026637615032277', '00000000031', '920', 'Lucious O''Connell',  '2025-06-08', 'Y',
        '78a24aebed9a6a4aa9e0409f26811d37c4d696ff5dd768ff513269fe794edecb'),
    ('7058267261837752', '00000000043', '401', 'Britney Waters',      '2025-08-29', 'Y',
        'c3e332d4524e5b3bceeea7854b655887227de3c20c71de51d97e4f8c1bc98966'),
    ('7094142751055551', '00000000032', '659', 'Stephany Fisher',     '2025-05-19', 'Y',
        '260e360d0c681ee7ea01d135da6ecfba5546be8126fcacf5ed56eb21e5d83746'),
    ('7251508149188883', '00000000029', '717', 'Rickie Daugherty',    '2024-06-04', 'Y',
        'd89061a28805c2f7aca4e027fa8241ae1c80d1ab66cfb7e6429b497ac6283747'),
    ('7379335634661142', '00000000045', '134', 'Dixie Beier',         '2025-07-09', 'Y',
        '560a4b30412aae05133096c98903d8dfa228f3f43dea3d0f31a947dbd748f1e9'),
    ('7427684863423209', '00000000011', '892', 'Hayden Pfannerstill', '2025-03-12', 'Y',
        '2f5f9555b65602ba229385d737d0e7d034ad549e9ba66353adf7d92713c3be4e'),
    ('7443870988897530', '00000000038', '708', 'Angela Ankunding',    '2023-07-23', 'Y',
        '1c442b856f95810293b8c7f4bf1b50041cf0444fcab1334a73fb60d46d84e311'),
    ('8040580410348680', '00000000026', '971', 'Marjory Stracke',     '2024-12-19', 'Y',
        '87742ca2a4f8f136b778ea1afa70b5fcfa0adf828d2944a453e36b78db31ca2e'),
    ('8112545834239735', '00000000023', '440', 'Johnson Ruecker',     '2025-03-18', 'Y',
        'd2af3b870a017d98f9c18dfcd4450a8241c46a0f3899e32a9efa022f40d4f942'),
    ('8262593602473076', '00000000049', '457', 'Immanuel Bednar',     '2023-09-17', 'Y',
        'd148661b3d2ac3344fc158795f109148f89e63bf6d71f778ec1902054f3940bd'),
    ('8517866958206008', '00000000014', '955', 'Chelsea Marks',       '2025-12-11', 'Y',
        '92875f9cb0d2918357a08baa53cf2ce7c56452d64a4cb59cc58e8607894d80ad'),
    ('8931369351894783', '00000000008', '230', 'Kelsie Dicki',        '2024-05-20', 'Y',
        '7af4c119f1e5a6c96a5fe7148d4c1d84cc3caef5bd35da9b307a59d84457e59f'),
    ('9056297931664011', '00000000025', '931', 'Elliott Howell',      '2025-07-10', 'Y',
        'fff2fccda559940277771d539f9c973c4eb71fb20c6f31e47e98f45cb7bbc499'),
    ('9349107475869214', '00000000017', '218', 'Sigrid Mann',         '2025-03-01', 'Y',
        '508f402412871d59506f0451409e77fceb19b90bc2d2e28950644f32687d0313'),
    ('9501733721429893', '00000000009', '725', 'Melvin Ondricka',     '2024-12-27', 'Y',
        '728eb9c19f770b77d55d2755ece1e021b3f35d13ddf8574469a1f88b5cdbf1c1'),
    ('9680294154603697', '00000000001', '045', 'Immanuel Kessler',    '2025-05-20', 'Y',
        '98433fc178365d966539a9156365078b3b382bbb8d75143e37e49a18290d58c0'),
    ('9805583408996588', '00000000040', '908', 'Davon Emmerich',      '2023-10-27', 'Y',
        '4b694f4e149f74fd1dcf319959014750c9cf92333167e35928038f892171d872');

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
