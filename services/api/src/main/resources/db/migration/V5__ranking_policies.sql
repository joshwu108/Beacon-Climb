-- BC-0110 V5: versioned ranking policies (planning/06 section 9, ADR-0003 "Faithful replay").
--
--   * policy_version is the stable name a decision record cites ("heuristic-v1").
--   * serialized_config is the RFC 8785 canonical form of the policy resource
--     (services/api/src/main/resources/policies/<version>.json) at the time it was registered.
--   * code_digest is sha256 over the compiled classes of com.beaconclimb.api.ranking, computed by
--     PolicyLoader at startup, so a replay can tell whether the implementation that produced a
--     decision is the one currently deployed (POLICY_CODE_CHANGED otherwise).
--   * PolicyRegistry upserts the bundled policy at startup and marks it active; BC-0111 reads the
--     active policy from the registry, BC-0402 compares code_digest on replay.

CREATE TABLE ranking_policies (
  policy_version    text PRIMARY KEY,
  serialized_config jsonb NOT NULL,
  code_digest       char(64) NOT NULL,
  created_at        timestamptz NOT NULL,
  active            boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE ranking_policies IS
  'Versioned ranking policy configurations with the digest of the ranking code that applies them; the active row is the policy recommendations are ranked with (planning/06 section 9, ADR-0003)';
