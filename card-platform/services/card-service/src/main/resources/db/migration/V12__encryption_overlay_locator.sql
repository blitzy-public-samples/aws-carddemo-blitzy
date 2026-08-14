-- Card service, migration V12. Corrects the encryption-overlay locator the exception record cites.
--
-- Statements: one COMMENT ON COLUMN. No table, column, index, constraint or row is declared, altered
-- or removed, and every sentence V11__card_verification_value_exception.sql wrote about this column
-- is carried across unchanged. The classification tokens
-- equivalence-tests/SubjectDataGovernanceContractTest reads, personal_data=yes and
-- authentication_data=yes, and the sentence stating that no application path reads the column, are
-- re-issued word for word.
--
-- What moved. The first of the four compensating controls is encryption at rest, and the artifact
-- that delivers it is a Kustomize overlay binding an encrypted storage class to both persistent
-- claims. V11 cited that overlay at card-platform/deploy/k8s/overlays/encrypted-storage, which is
-- where it stood when V11 was applied. It now stands at card-platform/deploy/overlays/encrypted-
-- storage, beside the base folder rather than under it.
--
-- What Kustomize requires of an overlay's location. It refuses a root contained by a base it names
-- and calls that a cycle, so an overlay inside deploy/k8s naming deploy/k8s as its base does not
-- render: `kubectl kustomize` and `kubectl apply -k` both exited 1 before reading a manifest.
-- Naming the eleven base manifests one file at a time is refused as well, by the load restrictor,
-- because each path leaves the overlay's own tree. The overlay now sits beside the base and names it
-- as one entry. equivalence-tests/KubernetesDeploymentContractTest holds it there.
--
-- What Flyway compares at start-up. The checksum of every applied file, so an edit to V11 refuses to
-- start against an existing volume and a correction arrives as a new file instead. V6 of the ledger
-- schema and V8 of this one are the same shape. card-platform/services/card-service/README.md, under
-- "Stored card verification value", carries the same rule for a deployment that drops the column.
--
-- Design decisions: card-platform/docs/decision-log.md, under
-- "The stored card verification value keeps a formal exception".

COMMENT ON COLUMN card.card_verification_value IS
    'personal_data=yes; authentication_data=yes. CARD-CVV-CD PIC 9(03) at app/cpy/CVACT02Y.cpy:L7.
     CHAR(3) rather than NUMERIC(3,0) because the Picture clause is a display field: a numeric column
     returns 7 for a stored 007, which is a different card verification value. NO application path
     reads this column. No query names it, no request or response body carries it, no event schema
     declares it, and entity/CardEntity exposes no accessor. Held under the formal exception the
     header of V11__card_verification_value_exception.sql records, whose four controls are encryption
     at rest, minimal access, build-enforced audit and a published destruction procedure. The
     encryption control is delivered by card-platform/deploy/overlays/encrypted-storage, which binds
     an encrypted storage class to both persistent claims in one command; V11 cited that overlay at
     its former path under deploy/k8s, and this comment carries the current one.';
