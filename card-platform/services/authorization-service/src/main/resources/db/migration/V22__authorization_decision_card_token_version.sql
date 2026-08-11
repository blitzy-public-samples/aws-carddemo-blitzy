-- Authorization service, migration V22.
-- Records which card-token version the token on each decision row was taken under.
--
-- What a security review found. authorization_decision.card_token holds a keyed code over a card
-- number, derived by cobol-compat PanMasker under CARD_TOKEN_SECRET and CARD_TOKEN_VERSION. This
-- table holds no card number, so it cannot re-derive its own rows, and nothing recorded which key or
-- version a stored token belonged to. A rotation therefore left every earlier row naming a card
-- nobody could resolve, with no way to tell a row a rotation had reached from one it had not.
--
-- What this file declares. One column and one check. The column is NOT NULL with a default of '1',
-- which is the version card-platform/.env.example and deploy/k8s/30-configmap.yaml ship and the only
-- version any existing row can have been written under: this platform has published one version and
-- the two token-deriving services read it from one key.
--
-- What it is for. A row whose value is behind the configured version carries a token a rotation has
-- not reached. That is what makes a stale diagnostic identifiable without deriving anything, and it
-- is the column card-platform/docs/suggested-next-tasks.md recorded the platform as needing. The
-- re-key itself reads the mapping the card service writes: a rotation there records the token each
-- card carried and the token it carries now, in card_token_rotation_mapping, because that service is
-- the only one holding a card number. card-platform/services/card-service/README.md carries the statement
-- an operator applies to this table.
--
-- What it is not. It is not a decision field. No rule reads it, no response carries it, and no event
-- carries it. entity/AuthorizationDecisionEntity fills it from the configured version at the moment
-- the row is written, which is the only moment the value is knowable here.
--
-- Idempotent in the sense Flyway needs: this file runs once, and the default describes every row an
-- earlier version of this service wrote. Rationale, alternatives considered and accepted risks:
-- card-platform/docs/decision-log.md.

ALTER TABLE authorization_decision
    ADD COLUMN card_token_version VARCHAR(3) NOT NULL DEFAULT '1';

ALTER TABLE authorization_decision
    ADD CONSTRAINT ck_authorization_decision_card_token_version
        CHECK (card_token_version ~ '^[1-9][0-9]{0,2}$');

COMMENT ON COLUMN authorization_decision.card_token_version IS
    'The card-token version card_token was taken under, one to three digits. cobol-compat PanMasker
     folds the version into the message the code covers, so raising it rolls every token over under
     the same key. A row whose value is behind the configured version carries a token a rotation has
     not reached, which is how a stale diagnostic is found without deriving anything. The card
     service holds the previous-to-current mapping this column is re-keyed from, because it is the
     only service that holds a card number.';

COMMENT ON CONSTRAINT ck_authorization_decision_card_token_version ON authorization_decision IS
    'One to three digits opening with a non-zero digit, the shape cobol-compat PanMasker holds a
     configured card-token version to. A value outside it names no version this platform ever
     derived under.';
