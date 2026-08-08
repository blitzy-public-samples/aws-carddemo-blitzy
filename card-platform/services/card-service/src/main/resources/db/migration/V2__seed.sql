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
-- A keyed value belongs to one key and one version, and the key these literals belong to is the
-- BUILD-SCOPE key card-platform/pom.xml supplies to Surefire and Failsafe. It is not a deployment
-- key: card-platform/.env.example and deploy/k8s/31-secret.example.yaml both carry a placeholder,
-- and every service refuses to start until a deployment supplies a key of its own.
--
-- These literals are therefore a bootstrap and not a live identity. They satisfy card_token
-- NOT NULL, they let CardRepositoryIT and CardSeedEquivalenceTest compare a checked-in value
-- against the one derivation this platform holds, and they are replaced on first start-up:
-- domain.CardTokenReconciler re-derives card_token for every row of this table under the key the
-- deployment supplied. Nothing outside this build therefore names a card by a token derived under
-- a key this repository publishes.
--
-- CARD_TOKEN_VERSION is not key material and is not a placeholder. Raising it rolls every token
-- over under the same key, and the reconciler applies that rollover on the next start-up too.
-- card-platform/docs/suggested-next-tasks.md carries the rotation procedure.
INSERT INTO card (card_number, account_id, card_verification_value, embossed_name,
    expiration_date, active_status, card_token) VALUES
    ('0500024453765740', '00000000050', '747', 'Aniya Von',           '2023-03-09', 'Y',
        'f3edaad87f2db2be526d75776f210ed3ef82bf8794f80c2699d523498b381cf6'),
    ('0683586198171516', '00000000027', '567', 'Ward Jones',          '2025-07-13', 'Y',
        'aba9b184138ab512ef4a9f8aa8b006df6dca3f7a399ecd58f109e047ea1d56e7'),
    ('0923877193247330', '00000000002', '028', 'Enrico Rosenbaum',    '2024-08-11', 'Y',
        '505ed64a98b414a208086fbba86e6c9d7511d1d90cda9c80c06e07ccc33b76e7'),
    ('0927987108636232', '00000000020', '003', 'Carter Veum',         '2024-03-13', 'Y',
        'f308ed856c7b66a9f91888403b16dce4a001bff1f0036bf6bd310720d71b5ad3'),
    ('0982496213629795', '00000000012', '075', 'Maci Robel',          '2023-07-07', 'Y',
        '83a1b59b91edcc7911f5d84dca08637d27fde001ef9f9da33dbcfd6df1bdeab0'),
    ('1014086565224350', '00000000044', '640', 'Irving Emard',        '2024-01-17', 'Y',
        '6700f746fbe697b7cd562c336337319f04f67363a3f7f995a1e9f4c75fa94a6f'),
    ('1142167692878931', '00000000037', '625', 'Shany Walker',        '2023-10-24', 'Y',
        '20c19032b6b0e4681cf58514459c58ce8dc4de962809984f96cab87fc2dc7642'),
    ('1561409106491600', '00000000035', '031', 'Angelica Dach',       '2025-09-23', 'Y',
        '809d819f2524cf1c99fd27f3c27c06f72b921fd251af603f6cd8e63b76c2393a'),
    ('2745303720002090', '00000000039', '033', 'Aliyah Berge',        '2025-09-08', 'Y',
        '210a61be0222a8240c0042dfdf6cd5db73194276439c3e284111e444aac72f27'),
    ('2760836797107565', '00000000024', '859', 'Stefanie Dickinson',  '2025-02-11', 'Y',
        '532c3268e3c6dedfcce32303a287ef59c7da780426fc8804b5a91db8c6ca4d94'),
    ('2871968252812490', '00000000006', '775', 'Ignacio Douglas',     '2025-10-08', 'Y',
        '24cc25406ce200fa3132871b83545b36fdd60532beb297168f3c33ffef1a519f'),
    ('2940139362300449', '00000000022', '876', 'Allene Brown',        '2025-12-28', 'Y',
        '13accdbd2c50212c95b20006cc1031cea7cb0ad45d953051fcec074bcdd8144d'),
    ('2988091353094312', '00000000004', '795', 'Delbert Parisian',    '2023-12-16', 'Y',
        'd55e0ea4edb8e9887ee8d8827c619bca10ec8ff91d1033652891958e2abef589'),
    ('3260763612337560', '00000000010', '342', 'Maybell Mann',        '2023-01-27', 'Y',
        '934c5b3d7c781913d6f3ca6f3dd1905d6eb18ee5c7cf996f6e74d213b330d5fb'),
    ('3766281984155154', '00000000041', '622', 'Lucinda Dach',        '2023-04-24', 'Y',
        '6a5806f88edc7511ba55ad021de9ada736d8695c5a2ff4ff73ebf6e455449c54'),
    ('3940246016141489', '00000000019', '375', 'Hadley Hamill',       '2025-07-23', 'Y',
        '6b2e79e948841198ae4e24c8d2975d9c174b54cab35b565a054c4a2bae3875b1'),
    ('3999169246375885', '00000000003', '317', 'Larry Homenick',      '2024-01-10', 'Y',
        'a1502f757e32e6a74a444ce613eeabe1d66f0ccfe5b7997bdaf8265d96ef84b5'),
    ('4011500891777367', '00000000013', '390', 'Mariane Fadel',       '2024-08-04', 'Y',
        '1be02814338bf1d6e4d699115bdf6e673c84de1a3f9135d9da33dd4ce61a11ac'),
    ('4385271476627819', '00000000034', '709', 'Faustino Schmidt',    '2025-10-06', 'Y',
        'd7a2e0d5d228c103fba35f5fb257ed22f13b35b087d8bd0d34d0c6fbdd32dcb2'),
    ('4534784102713951', '00000000036', '644', 'Toney Gerhold',       '2024-12-23', 'Y',
        '8e96e2745ba241f009a5ebf9d7737fb4e0aa16af8c60410b910a9bcbf1802cb8'),
    ('4859452612877065', '00000000007', '321', 'Cooper Mayert',       '2024-12-13', 'Y',
        'e34cbfe7629f9f0291ef7b3c1e0798ce033ee1f6d7b6b8119765f10375172d76'),
    ('5407099850479866', '00000000021', '524', 'Jerrold Maggio',      '2023-01-06', 'Y',
        '55e5103f831451dec14a76121bf54f5a48635560249e7c8dec99cf2402719ec6'),
    ('5656830544981216', '00000000046', '196', 'Cindy Cremin',        '2025-06-20', 'Y',
        'f2da91894b96daf3c293fff581e3265790a681e33c32b25281563c1573ba28c9'),
    ('5671184478505844', '00000000018', '137', 'Emile White',         '2023-09-10', 'Y',
        '301545c85432d518c7a9697eef8b0899dd118328ff25a8b36c561ab22a833b51'),
    ('5787351228879339', '00000000047', '067', 'Rigoberto Hoeger',    '2025-08-23', 'Y',
        'd43e900a5e9fa6414bb000e6056ec6a2d1416db85b639c951d71a8355e55aa06'),
    ('5975117516616077', '00000000042', '426', 'Heather Nienow',      '2025-09-19', 'Y',
        '9da2888d5fed0befe76f992a2f0ba0911251b9132016548cea45243792c821da'),
    ('6009619150674526', '00000000005', '021', 'Treva Schowalter',    '2025-03-09', 'Y',
        '4eb7909d8083adb127b4d3d652d00300085ea62268a2a41e9748bd8732f01d1c'),
    ('6349250331648509', '00000000015', '735', 'Aubree Hermann',      '2025-06-09', 'Y',
        'f406b19d6c150d9c54c64989e6fdadc824859e18efeb99bbb0e2f0b88c3ae683'),
    ('6503535181795992', '00000000048', '413', 'Lyric Pacocha',       '2025-02-06', 'Y',
        'e8662274c72d484ad7cca8ec863993703f3c2ff3b8ba5802ca32f640c7d5f92b'),
    ('6509230362553816', '00000000030', '236', 'Layla Ullrich',       '2024-06-27', 'Y',
        'cf69201522e6f773c5f6925673c57ac578f48277850a9da42aeba67e688d8666'),
    ('6723000463207764', '00000000028', '486', 'Hester Hane',         '2024-05-09', 'Y',
        '8cdb3ceb54f4429e5419a7a518f4e2831b4add56ef6f85311db79fa09b604038'),
    ('6727055190616014', '00000000016', '641', 'Carroll Bergstrom',   '2024-01-25', 'Y',
        '25e2be79c32cd088d8b0b4ea85f3aa0b35961fb37afef094c8cdb0a25a1ac2db'),
    ('6832676047698087', '00000000033', '983', 'Bernice Herman',      '2025-10-07', 'Y',
        '9d6ff4eefaa31986e0bbb15941bcb3424b6898df16c666f02296b762719e6f1c'),
    ('7026637615032277', '00000000031', '920', 'Lucious O''Connell',  '2025-06-08', 'Y',
        '1bba7f9090f6d6f79fab59c84d1bfc0e94b844a6e6f8f91edf7d132e1988407a'),
    ('7058267261837752', '00000000043', '401', 'Britney Waters',      '2025-08-29', 'Y',
        '4373b1bb692d10b6f0ce35d213baf903b02120d6058ce57909b1a705073d54a8'),
    ('7094142751055551', '00000000032', '659', 'Stephany Fisher',     '2025-05-19', 'Y',
        'a64e84e36fece46d3f5b5d55c5b60da2d2a19e209a43762cca1befc4da3a935f'),
    ('7251508149188883', '00000000029', '717', 'Rickie Daugherty',    '2024-06-04', 'Y',
        'a57d78a9ef2543ae7b8034f24276798ae5089461a207603b3ac986aa94fb94e8'),
    ('7379335634661142', '00000000045', '134', 'Dixie Beier',         '2025-07-09', 'Y',
        '3444edd374a2cfea1719ca0867db0c6ec7fc5f0092894ebe881015b2e3cc09c2'),
    ('7427684863423209', '00000000011', '892', 'Hayden Pfannerstill', '2025-03-12', 'Y',
        '139cf8e78ad7296481477ec6a196f8553aaa22721d21ad28d419b419f7a1a4e1'),
    ('7443870988897530', '00000000038', '708', 'Angela Ankunding',    '2023-07-23', 'Y',
        'b0f2d4e4dab1bb7cdc140a70fb4d301fa0404d8f2e2abc057abf6cad0ae0fa5e'),
    ('8040580410348680', '00000000026', '971', 'Marjory Stracke',     '2024-12-19', 'Y',
        '9262a2b4a6774c43e3c70931dc96b6f45d1b00d1c57e14b1e5c6407a432cceb4'),
    ('8112545834239735', '00000000023', '440', 'Johnson Ruecker',     '2025-03-18', 'Y',
        '6da69239e798eec300f3d0b7ba4350e24a9e29530b2e87f7925c7fd305f3fbb4'),
    ('8262593602473076', '00000000049', '457', 'Immanuel Bednar',     '2023-09-17', 'Y',
        '5e1034d273278a6314dd16276f85ba9f04038c253b0e07f833124b94d02c59af'),
    ('8517866958206008', '00000000014', '955', 'Chelsea Marks',       '2025-12-11', 'Y',
        '58a59ee9606c79fc02d8a1401ef43b5d57d37fac29139ef2b1166b68c42633f8'),
    ('8931369351894783', '00000000008', '230', 'Kelsie Dicki',        '2024-05-20', 'Y',
        'd3c8d0f36c9ddcda04df81ea3295c6aefa02d8e3c397b5883518126e284b95ee'),
    ('9056297931664011', '00000000025', '931', 'Elliott Howell',      '2025-07-10', 'Y',
        'e20ca12d6ea1b1cc3f1c0e349abb9073245b2d65797efb1ec07c3caeaa0d3a53'),
    ('9349107475869214', '00000000017', '218', 'Sigrid Mann',         '2025-03-01', 'Y',
        '8f4b23956f6fd70632d4de5e4d2d2a66ac65aad17ab6ec2fc80471b8ea66129e'),
    ('9501733721429893', '00000000009', '725', 'Melvin Ondricka',     '2024-12-27', 'Y',
        '17f7979e587ea473e7ec2654fe36459a886e301ac1a0b83faa707f9d8a7d5cf7'),
    ('9680294154603697', '00000000001', '045', 'Immanuel Kessler',    '2025-05-20', 'Y',
        '115aba96cf484cf4477a43a9c8c0ea4d5d23223f7fa0e89cbccf034403f5fc18'),
    ('9805583408996588', '00000000040', '908', 'Davon Emmerich',      '2023-10-27', 'Y',
        '2108f682c3ff0471fdbeadb87640b1b6e38637895e747bfa86a5bb73bd1f52ba');

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
