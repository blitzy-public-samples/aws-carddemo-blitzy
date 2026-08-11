-- Card service, migration V10.
-- Records which card-token key and version each stored token belongs to, and makes a rotation an
-- audited act with a mapping other stores can be re-keyed from.
--
-- What a security review found. domain/CardTokenReconciler re-derived card.card_token at every
-- start-up whenever the derivation changed, and nothing recorded which key or version a stored token
-- belonged to. Three other places hold a token derived from the same key and hold no card number, so
-- none of them can re-derive its own rows: statement_transaction.card_token and
-- notification_log.card_token in the notification service, authorization_decision.card_token in the
-- authorization service, and a SCOPE_CARD authority an operator grants in configuration. A key
-- change therefore left those three naming a card nobody could reach, with no mapping back and no
-- way to undo the rewrite.
--
-- What this file declares. Two columns on card and two tables.
--
-- card.card_token_version is the version the stored token was taken under. It is what lets an
-- operator tell a token of the previous version from one of the current version without deriving
-- either, which is the column card-platform/docs/suggested-next-tasks.md said the platform needed.
--
-- card.card_token_provenance separates the two reasons a stored token can differ from the
-- derivation. A SEED row carries the literal V2__seed.sql checked in, derived under the build-scope
-- key card-platform/pom.xml supplies so that a test can compare a literal against the derivation; it
-- is a bootstrap and re-deriving it is not a rotation. A DERIVED row carries a token this deployment
-- derived, so a difference means the key or the version moved under it, and rewriting that is a
-- rotation an operator asks for rather than a consequence of a restart.
--
-- card_token_rotation is the audit record: one row per run that rewrote anything, naming when it
-- ran, the version it moved from and to, how many rows it read and rewrote, and the operating-system
-- identity of the process that ran it. Nothing deletes a row of it.
--
-- card_token_rotation_mapping is the artifact the other three stores are re-keyed from: for every
-- row a run rewrote, the token it carried and the token it now carries. The previous value is
-- derivable only because CARD_TOKEN_PREVIOUS_SECRET carries the key it was taken under, which is the
-- dual read the review asked for. Applying the mapping in reverse is the rollback.
--
-- A mapping row names its rotation and no constraint declares that relationship, because no
-- migration on this platform declares a foreign key. One code path writes both: the rotation row is
-- inserted before the first mapping row and closed after the last, so an orphan mapping row would
-- mean the writer inserted a child without its parent rather than that a constraint was missing.
--
-- Neither table holds a card number, a card verification value or key material. A mapping row states
-- that two tokens name one card, which is what an operator needs and what a token holder already
-- knows for the token they hold.
--
-- Idempotent in the sense Flyway needs: this file runs once, and a fresh database and a database
-- holding the fifty seeded rows both reach the same state, because the two column defaults describe
-- the seeded rows exactly. Rationale, alternatives considered and accepted risks:
-- card-platform/docs/decision-log.md. The procedure is card-platform/services/card-service/README.md.

ALTER TABLE card
    ADD COLUMN card_token_version VARCHAR(3) NOT NULL DEFAULT '1';

ALTER TABLE card
    ADD COLUMN card_token_provenance VARCHAR(7) NOT NULL DEFAULT 'SEED';

ALTER TABLE card
    ADD CONSTRAINT ck_card_card_token_version
        CHECK (card_token_version ~ '^[1-9][0-9]{0,2}$'),
    ADD CONSTRAINT ck_card_card_token_provenance
        CHECK (card_token_provenance IN ('SEED', 'DERIVED'));

COMMENT ON COLUMN card.card_token_version IS
    'The card-token version card_token was taken under, one to three digits. cobol-compat
     PanMasker folds the version into the message the code covers, so raising it rolls every token
     over under the same key. A row whose value is behind the configured version is a row a rotation
     has not reached, which is how a stale token is found without deriving anything.';

COMMENT ON COLUMN card.card_token_provenance IS
    'SEED where card_token is the literal V2__seed.sql checked in under the build-scope key, and
     DERIVED where this deployment derived it. The distinction is what separates a bootstrap from a
     rotation: domain/CardTokenReconciler re-derives a SEED row without being asked and refuses to
     rewrite a DERIVED row unless CARD_TOKEN_ROTATION_ENABLED states that an operator asked for it.';

CREATE TABLE card_token_rotation (
    rotation_id      UUID                        NOT NULL,
    started_at       TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    finished_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    from_version     VARCHAR(3)                  NOT NULL,
    to_version       VARCHAR(3)                  NOT NULL,
    rows_read        INTEGER                     NOT NULL,
    rows_rewritten   INTEGER                     NOT NULL,
    -- The identity the process ran under, which is the nearest thing to an actor a start-up task
    -- has. A rotation is performed by an operator setting a variable and restarting, so the useful
    -- record is which process applied it rather than which human decided it.
    actor            VARCHAR(64)                 NOT NULL,
    CONSTRAINT pk_card_token_rotation PRIMARY KEY (rotation_id),
    CONSTRAINT ck_card_token_rotation_from_version
        CHECK (from_version ~ '^[1-9][0-9]{0,2}$'),
    CONSTRAINT ck_card_token_rotation_to_version
        CHECK (to_version ~ '^[1-9][0-9]{0,2}$'),
    CONSTRAINT ck_card_token_rotation_counts
        CHECK (rows_read >= 0 AND rows_rewritten >= 0 AND rows_rewritten <= rows_read),
    CONSTRAINT ck_card_token_rotation_window
        CHECK (finished_at >= started_at)
);

COMMENT ON TABLE card_token_rotation IS
    'retention=permanent; purge_key=none; personal_data=no. One row per run of
     domain/CardTokenReconciler that rotated a token, which means it rewrote a token of DERIVED
     provenance. A run that only brought SEED rows onto the deployment key writes no row, because
     that is a bootstrap. The presence of a row therefore means a stored identity moved. Nothing
     expires a row: the mapping rows it owns are what another store is re-keyed from, and a rollback
     reads them in reverse.';

CREATE TABLE card_token_rotation_mapping (
    rotation_id          UUID     NOT NULL,
    previous_card_token  CHAR(64) NOT NULL,
    card_token           CHAR(64) NOT NULL,
    previous_version     VARCHAR(3) NOT NULL,
    version              VARCHAR(3) NOT NULL,
    CONSTRAINT pk_card_token_rotation_mapping
        PRIMARY KEY (rotation_id, previous_card_token),
    CONSTRAINT ck_card_token_rotation_mapping_previous_hex
        CHECK (previous_card_token ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_card_token_rotation_mapping_current_hex
        CHECK (card_token ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_card_token_rotation_mapping_moved
        CHECK (previous_card_token <> card_token),
    CONSTRAINT ck_card_token_rotation_mapping_previous_version
        CHECK (previous_version ~ '^[1-9][0-9]{0,2}$'),
    CONSTRAINT ck_card_token_rotation_mapping_version
        CHECK (version ~ '^[1-9][0-9]{0,2}$')
);

COMMENT ON TABLE card_token_rotation_mapping IS
    'retention=permanent; purge_key=none; personal_data=no. For every card a rotation re-keyed, the
     token the card carried and the token it carries now. It holds no card number and no key
     material. This is what statement_transaction, notification_log, authorization_decision and a
     granted SCOPE_CARD authority are re-keyed from, because none of those holds a card number and
     none can therefore derive its own replacement. Read in reverse it is the rollback.';

-- The re-key statement an operator applies to another schema reads this table by the value that
-- schema holds, so the previous token is the searched column.
CREATE INDEX ix_card_token_rotation_mapping_previous
    ON card_token_rotation_mapping (previous_card_token);
