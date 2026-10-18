package com.beaconclimb.api.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beaconclimb.api.AbstractPostgresIT;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * BC-0101/BC-0107/BC-0109/BC-0113/BC-0110/BC-0111/BC-0123/BC-0123c/BC-0305/BC-0309: every Flyway
 * migration applies on an empty Postgres 17 as beacon_migrate, the runtime role beacon_app gets DML
 * and nothing more (and no UPDATE or DELETE on the append-only ledger and audit tables), the
 * constraints from planning/06 sections 1, 6 and 7 hold, and the golden schema document
 * docs/db/schema-v1.md matches pg_dump of the live database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MigrationIT extends AbstractPostgresIT {

  /** Expected (version, script) pairs in order; extend when adding a migration. */
  private static final List<Map.Entry<String, String>> MIGRATIONS =
      List.of(
          Map.entry("1", "V1__identity_catalog_provenance.sql"),
          Map.entry("2", "V2__sessions.sql"),
          Map.entry("3", "V3__attempts_outbox.sql"),
          Map.entry("4", "V4__feature_snapshots.sql"),
          Map.entry("5", "V5__ranking_policies.sql"),
          Map.entry("6", "V6__decisions.sql"),
          Map.entry("7", "V7__attempt_revisions_append_only.sql"),
          Map.entry("8", "V8__account_creations.sql"),
          Map.entry("9", "V9__feature_snapshots_stream.sql"),
          Map.entry("10", "V10__reconciliation.sql"));

  private static final List<String> ALL_TABLES =
      List.of(
          "users",
          "consents",
          "user_preferences",
          "provenance_sources",
          "boards",
          "holds",
          "routes",
          "route_variants",
          "route_placements",
          "sessions",
          "attempts",
          "attempt_revisions",
          "attempt_event_outbox",
          "user_feature_snapshots",
          "ranking_policies",
          "recommendation_decisions",
          "recommendation_exposures",
          "account_creations",
          "reconciliation_events");

  private static final UUID PROVENANCE_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");
  private static final String BOARD_ID = "migration-it-board";
  private static final UUID HOLD_ID = UUID.fromString("00000000-0000-7000-8000-000000000002");
  private static final UUID ROUTE_ID = UUID.fromString("00000000-0000-7000-8000-000000000003");
  private static final UUID VARIANT_ID = UUID.fromString("00000000-0000-7000-8000-000000000004");
  private static final UUID OTHER_VARIANT_ID =
      UUID.fromString("00000000-0000-7000-8000-000000000005");

  @Autowired DataSource dataSource;

  private JdbcTemplate jdbc() {
    return new JdbcTemplate(dataSource);
  }

  @Test
  void flywayHistoryRecordsEveryMigrationAsSuccessWithLatestVersionTen() {
    List<Map<String, Object>> rows =
        jdbc()
            .queryForList(
                "select version, success, script from flyway_schema_history"
                    + " where version is not null order by installed_rank");
    assertThat(rows).extracting(row -> row.get("success")).containsOnly(true);
    assertThat(rows)
        .extracting(row -> Map.entry(row.get("version").toString(), row.get("script").toString()))
        .containsExactlyElementsOf(MIGRATIONS);
    assertThat(rows.getLast().get("version").toString()).isEqualTo("10");
  }

  @Test
  void allTablesExistInPublicAndAreOwnedByMigrateRole() {
    List<Map<String, Object>> rows =
        jdbc()
            .queryForList(
                "select tablename, tableowner from pg_tables where schemaname = 'public'"
                    + " and tablename <> 'flyway_schema_history' order by tablename");
    assertThat(rows)
        .extracting(row -> row.get("tablename"))
        .containsExactlyInAnyOrderElementsOf(ALL_TABLES);
    assertThat(rows).extracting(row -> row.get("tableowner")).containsOnly("beacon_migrate");
  }

  @Test
  void sessionsTableHasUserIndexAndRequiresExistingUser() {
    String definition =
        jdbc()
            .queryForObject(
                "select indexdef from pg_indexes where schemaname = 'public'"
                    + " and indexname = 'sessions_user_id_idx'",
                String.class);
    assertThat(definition).contains("ON public.sessions USING btree (user_id)");
    assertThatThrownBy(
            () ->
                jdbc()
                    .update(
                        "insert into sessions (token_hash, user_id, created_at, expires_at)"
                            + " values (?, ?, now(), now())",
                        "f".repeat(64),
                        UUID.fromString("00000000-0000-7000-8000-0000000000ac")))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("sessions_user_id_fkey");
  }

  @Test
  void appRoleHasDmlOnUsersButCannotCreateTables() {
    UUID userId = UUID.fromString("00000000-0000-7000-8000-0000000000aa");
    jdbc()
        .update(
            "insert into users (id, username, password_hash) values (?, ?, ?)",
            userId,
            "migration-it-user",
            "argon2id$placeholder");
    assertThat(jdbc().update("update users set role = 'ADMIN' where id = ?", userId)).isEqualTo(1);
    assertThat(jdbc().queryForObject("select role from users where id = ?", String.class, userId))
        .isEqualTo("ADMIN");
    assertThat(jdbc().update("delete from users where id = ?", userId)).isEqualTo(1);

    assertThatThrownBy(() -> jdbc().execute("create table beacon_app_should_not (id int)"))
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .hasMessageContaining("permission denied");
  }

  @Test
  @Transactional
  void duplicateRouteAngleVariantIsRejectedByUniqueConstraint() {
    seedProvenanceBoardHoldRoute();
    insertVariant(VARIANT_ID, 40);
    assertThatThrownBy(() -> insertVariant(OTHER_VARIANT_ID, 40))
        .isInstanceOf(DuplicateKeyException.class)
        .hasMessageContaining("route_variants_route_id_angle_deg_key");
  }

  @Test
  @Transactional
  void usersRoleCheckRejectsUnknownRole() {
    assertThatThrownBy(
            () ->
                jdbc()
                    .update(
                        "insert into users (id, username, password_hash, role) values (?, ?, ?, ?)",
                        UUID.fromString("00000000-0000-7000-8000-0000000000ab"),
                        "root-wannabe",
                        "x",
                        "ROOT"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("users_role_check");
  }

  @Test
  @Transactional
  void provenanceLicenseReviewStatusCheckRejectsUnknownValue() {
    assertThatThrownBy(() -> insertProvenance(PROVENANCE_ID, "WHATEVER"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("provenance_sources_license_review_status_check");
  }

  @Test
  @Transactional
  void routePlacementRoleCheckRejectsUnknownRole() {
    seedProvenanceBoardHoldRoute();
    insertVariant(VARIANT_ID, 40);
    assertThatThrownBy(
            () ->
                jdbc()
                    .update(
                        "insert into route_placements (variant_id, hold_id, role) values (?, ?, ?)",
                        VARIANT_ID,
                        HOLD_ID,
                        "KNEE"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("route_placements_role_check");
  }

  @Test
  void partialSearchIndexOnActiveVariantsExists() {
    String definition =
        jdbc()
            .queryForObject(
                "select indexdef from pg_indexes where schemaname = 'public'"
                    + " and indexname = 'route_variants_search_idx'",
                String.class);
    assertThat(definition)
        .contains("ON public.route_variants USING btree (angle_deg, grade_numeric, variant_id)")
        .contains("WHERE (validation_status = 'ACTIVE'::text)");
  }

  @Test
  void attemptsIndexesAndOutboxPartialIndexExist() {
    assertThat(indexDefinition("attempts_user_history_idx"))
        .contains("ON public.attempts USING btree (user_id, occurred_at DESC, attempt_id DESC)");
    assertThat(indexDefinition("attempts_user_variant_idx"))
        .contains("ON public.attempts USING btree (user_id, variant_id, occurred_at DESC)");
    assertThat(indexDefinition("attempts_user_id_submission_idempotency_key_key"))
        .contains("UNIQUE INDEX")
        .contains("(user_id, submission_idempotency_key)");
    assertThat(indexDefinition("attempt_event_outbox_unpublished_idx"))
        .contains("ON public.attempt_event_outbox USING btree (created_at)")
        .contains("WHERE (published_at IS NULL)");
  }

  @Test
  void decisionLedgerHasOwnerIndexAndDeniesUpdateAndDeleteToAppRole() {
    assertThat(indexDefinition("recommendation_decisions_user_idx"))
        .contains("ON public.recommendation_decisions USING btree (user_id, created_at DESC)");
    List<Map<String, Object>> grants =
        jdbc()
            .queryForList(
                "select table_name, privilege_type from information_schema.role_table_grants"
                    + " where grantee = 'beacon_app' and table_name in"
                    + " ('recommendation_decisions', 'recommendation_exposures')"
                    + " order by table_name, privilege_type");
    assertThat(grants)
        .extracting(row -> row.get("table_name") + ":" + row.get("privilege_type"))
        .containsExactly(
            "recommendation_decisions:INSERT",
            "recommendation_decisions:SELECT",
            "recommendation_exposures:INSERT",
            "recommendation_exposures:SELECT");
  }

  /** BC-0123 F6 (V7): the audit trail is append-only for the API role; policies stay writable. */
  @Test
  void attemptRevisionsDenyUpdateAndDeleteToAppRoleWhileRankingPoliciesStayWritable() {
    assertThat(privilegesOf("attempt_revisions")).containsExactly("INSERT", "SELECT");
    assertThat(privilegesOf("ranking_policies"))
        .containsExactly("DELETE", "INSERT", "SELECT", "UPDATE");
  }

  /**
   * BC-0123c (V8): the per-address registration counter is append-only for the API role (DELETE
   * stays for retention) and is read through its (client_ip, created_at) index.
   */
  @Test
  void accountCreationsDenyUpdateToAppRoleAndHaveAddressIndex() {
    assertThat(privilegesOf("account_creations")).containsExactly("DELETE", "INSERT", "SELECT");
    assertThat(indexDefinition("account_creations_ip_idx"))
        .contains("ON public.account_creations USING btree (client_ip, created_at)");
  }

  /**
   * BC-0305 (V9): one SYNC and one STREAM row per (user_id, feature_schema_version) coexist under
   * the widened primary key, a second STREAM row is refused, and the app role keeps full DML on the
   * table. (Each expected constraint error aborts the test transaction, so the position CHECK has
   * its own tests below.)
   */
  @Test
  @Transactional
  void featureSnapshotsSyncAndStreamRowsCoexistUnderTheWidenedKey() {
    assertThat(indexDefinition("user_feature_snapshots_pkey"))
        .contains("UNIQUE INDEX")
        .contains("(user_id, feature_schema_version, source)");
    assertThat(privilegesOf("user_feature_snapshots"))
        .containsExactly("DELETE", "INSERT", "SELECT", "UPDATE");
    UUID userId = insertSnapshotUser("00000000-0000-7000-8000-0000000000af", "snapshots-1");
    insertSnapshot(userId, "SYNC", null, null);
    insertSnapshot(userId, "STREAM", 2, 10L);
    assertThat(
            jdbc()
                .queryForObject(
                    "select count(*) from user_feature_snapshots where user_id = ?",
                    Integer.class,
                    userId))
        .isEqualTo(2);
    assertThatThrownBy(() -> insertSnapshot(userId, "STREAM", 2, 11L))
        .isInstanceOf(DuplicateKeyException.class)
        .hasMessageContaining("user_feature_snapshots_pkey");
  }

  /** BC-0305 (V9): a STREAM row must carry its Kafka position. */
  @Test
  @Transactional
  void featureSnapshotsStreamRowWithoutPositionIsRejected() {
    UUID userId = insertSnapshotUser("00000000-0000-7000-8000-0000000000b0", "snapshots-2");
    assertThatThrownBy(() -> insertSnapshot(userId, "STREAM", null, null))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("user_feature_snapshots_source_position_check");
  }

  /** BC-0305 (V9): a SYNC row must not carry a Kafka position. */
  @Test
  @Transactional
  void featureSnapshotsSyncRowWithPositionIsRejected() {
    UUID userId = insertSnapshotUser("00000000-0000-7000-8000-0000000000b1", "snapshots-3");
    assertThatThrownBy(() -> insertSnapshot(userId, "SYNC", 0, 0L))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("user_feature_snapshots_source_position_check");
  }

  private UUID insertSnapshotUser(String id, String username) {
    UUID userId = UUID.fromString(id);
    jdbc()
        .update(
            "insert into users (id, username, password_hash) values (?, ?, ?)",
            userId,
            "migration-it-" + username,
            "argon2id$placeholder");
    return userId;
  }

  private void insertSnapshot(UUID userId, String source, Integer partition, Long offset) {
    jdbc()
        .update(
            "insert into user_feature_snapshots (user_id, feature_schema_version, cutoff_w,"
                + " feature_as_of, aggregates_json, provenance_checksum, source, source_partition,"
                + " source_offset, computed_at) values (?, 1, now(), null, '{}'::jsonb, ?, ?, ?, ?,"
                + " now())",
            userId,
            "0".repeat(64),
            source,
            partition,
            offset);
  }

  /**
   * BC-0309 (V10): the open-rows partial index and the user index exist, the app role keeps full
   * DML, and erasing the user cascades to the rows. (Each expected constraint error aborts the test
   * transaction, so the unique and CHECK violations have their own tests below.)
   */
  @Test
  @Transactional
  void reconciliationEventsAreIndexedAndCascadeOnUserDelete() {
    assertThat(indexDefinition("reconciliation_events_event_id_key"))
        .contains("UNIQUE INDEX")
        .contains("(event_id)");
    assertThat(indexDefinition("reconciliation_events_open_idx"))
        .contains("ON public.reconciliation_events USING btree (received_at)")
        .contains("WHERE (resolved_at IS NULL)");
    assertThat(indexDefinition("reconciliation_events_user_idx"))
        .contains("ON public.reconciliation_events USING btree (user_id)");
    assertThat(privilegesOf("reconciliation_events"))
        .containsExactly("DELETE", "INSERT", "SELECT", "UPDATE");
    UUID userId = insertSnapshotUser("00000000-0000-7000-8000-0000000000b2", "reconciliation-1");
    UUID eventId = UUID.fromString("00000000-0000-7000-8000-0000000000b3");
    insertReconciliation(userId, eventId, "LATE_BEYOND_WINDOW");
    assertThat(jdbc().update("delete from users where id = ?", userId)).isEqualTo(1);
    assertThat(
            jdbc()
                .queryForObject(
                    "select count(*) from reconciliation_events where user_id = ?",
                    Integer.class,
                    userId))
        .isZero();
  }

  /**
   * BC-0309 (V10): one reconciliation row per event_id; a redelivery is the unique violation the
   * consumer turns into ON CONFLICT (event_id) DO NOTHING.
   */
  @Test
  @Transactional
  void reconciliationEventsAreUniquePerEventId() {
    UUID userId = insertSnapshotUser("00000000-0000-7000-8000-0000000000bb", "reconciliation-5");
    UUID eventId = UUID.fromString("00000000-0000-7000-8000-0000000000bc");
    insertReconciliation(userId, eventId, "LATE_BEYOND_WINDOW");
    assertThatThrownBy(() -> insertReconciliation(userId, eventId, "LATE_BEYOND_WINDOW"))
        .isInstanceOf(DuplicateKeyException.class)
        .hasMessageContaining("reconciliation_events_event_id_key");
  }

  /** BC-0309 (V10): a closed row must carry a terminal resolution, its cutoff and checksum. */
  @Test
  @Transactional
  void reconciliationEventsRejectResolvedRowWithoutTerminalResolution() {
    UUID userId = insertSnapshotUser("00000000-0000-7000-8000-0000000000b4", "reconciliation-2");
    UUID eventId = UUID.fromString("00000000-0000-7000-8000-0000000000b5");
    insertReconciliation(userId, eventId, "LATE_BEYOND_WINDOW");
    assertThatThrownBy(
            () ->
                jdbc()
                    .update(
                        "update reconciliation_events set resolved_at = now()"
                            + " where event_id = ?",
                        eventId))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("reconciliation_events_resolution_state_check");
  }

  /** BC-0309 (V10): ERROR is recorded on an open row (resolved_at stays NULL) and is retried. */
  @Test
  @Transactional
  void reconciliationEventsAllowErrorOnOpenRowOnly() {
    UUID userId = insertSnapshotUser("00000000-0000-7000-8000-0000000000b6", "reconciliation-3");
    UUID eventId = UUID.fromString("00000000-0000-7000-8000-0000000000b7");
    insertReconciliation(userId, eventId, "LATE_BEYOND_WINDOW");
    assertThat(
            jdbc()
                .update(
                    "update reconciliation_events set resolution = 'ERROR' where event_id = ?",
                    eventId))
        .isEqualTo(1);
    assertThatThrownBy(
            () ->
                jdbc()
                    .update(
                        "update reconciliation_events set resolved_at = now(),"
                            + " resolved_cutoff_w = now(), resolved_checksum = ?"
                            + " where event_id = ?",
                        "0".repeat(64),
                        eventId))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("reconciliation_events_resolution_state_check");
  }

  /** BC-0309 (V10): the reason is a closed set. */
  @Test
  @Transactional
  void reconciliationEventsRejectUnknownReason() {
    UUID userId = insertSnapshotUser("00000000-0000-7000-8000-0000000000b8", "reconciliation-4");
    UUID eventId = UUID.fromString("00000000-0000-7000-8000-0000000000b9");
    assertThatThrownBy(() -> insertReconciliation(userId, eventId, "LATE"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("reconciliation_events_reason_check");
  }

  private void insertReconciliation(UUID userId, UUID eventId, String reason) {
    jdbc()
        .update(
            "insert into reconciliation_events (id, user_id, attempt_id, attempt_revision,"
                + " event_id, event_type, occurred_at, source_watermark, reason, original_json,"
                + " source_partition, source_offset) values (gen_random_uuid(), ?, ?, 1, ?,"
                + " 'ATTEMPT_RECORDED', now() - interval '3 days', now(), ?, '{}'::jsonb, 0, 0)",
            userId,
            UUID.fromString("00000000-0000-7000-8000-0000000000ba"),
            eventId,
            reason);
  }

  @Test
  @Transactional
  void attemptsCountCheckRejectsZero() {
    UUID userId = seedUserBoardAndVariant();
    assertThatThrownBy(() -> insertAttempt(userId, 0, "ACTIVE"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("attempts_attempts_count_check");
  }

  @Test
  @Transactional
  void attemptsStatusCheckRejectsUnknownStatus() {
    UUID userId = seedUserBoardAndVariant();
    assertThatThrownBy(() -> insertAttempt(userId, 1, "DELETED"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("attempts_status_check");
  }

  @Test
  void goldenSchemaDocMatchesLiveDatabase() throws Exception {
    String live = SchemaGolden.dumpNormalisedSchema(SchemaGolden.sharedContainer());
    assertThat(live)
        .contains("CREATE TABLE public.route_placements")
        .contains("CREATE TABLE public.sessions")
        .contains("CREATE TABLE public.attempts")
        .contains("CREATE TABLE public.attempt_revisions")
        .contains("CREATE TABLE public.attempt_event_outbox")
        .contains("CREATE TABLE public.user_feature_snapshots")
        .contains("CREATE TABLE public.ranking_policies")
        .contains("CREATE TABLE public.recommendation_decisions")
        .contains("CREATE TABLE public.recommendation_exposures")
        .contains("CREATE TABLE public.account_creations")
        .contains("CREATE TABLE public.reconciliation_events");
    if (SchemaGolden.shouldUpdate()) {
      SchemaGolden.writeDoc(live);
      return;
    }
    assertThat(SchemaGolden.readDocSql())
        .as(
            "%s is out of date; regenerate with %s=1 ./gradlew :api:test --tests '*MigrationIT*'",
            SchemaGolden.DOC_PATH, SchemaGolden.UPDATE_ENV_VAR)
        .isEqualTo(live);
  }

  private List<String> privilegesOf(String table) {
    return jdbc()
        .queryForList(
            "select privilege_type from information_schema.role_table_grants"
                + " where grantee = 'beacon_app' and table_name = ? order by privilege_type",
            String.class,
            table);
  }

  private String indexDefinition(String indexName) {
    return jdbc()
        .queryForObject(
            "select indexdef from pg_indexes where schemaname = 'public' and indexname = ?",
            String.class,
            indexName);
  }

  private UUID seedUserBoardAndVariant() {
    UUID userId = UUID.fromString("00000000-0000-7000-8000-0000000000ad");
    jdbc()
        .update(
            "insert into users (id, username, password_hash) values (?, ?, ?)",
            userId,
            "migration-it-attempts",
            "argon2id$placeholder");
    seedProvenanceBoardHoldRoute();
    insertVariant(VARIANT_ID, 40);
    return userId;
  }

  private void insertAttempt(UUID userId, int attemptsCount, String status) {
    jdbc()
        .update(
            "insert into attempts (attempt_id, user_id, variant_id, route_variant_public_id,"
                + " occurred_at, received_at, attempts_count, sent, status,"
                + " submission_idempotency_key, request_fingerprint, response_body_json,"
                + " updated_at) values (?, ?, ?, ?, now(), now(), ?, true, ?, ?, ?, '{}'::jsonb,"
                + " now())",
            UUID.fromString("00000000-0000-7000-8000-0000000000ae"),
            userId,
            VARIANT_ID,
            "demo-route-001:40",
            attemptsCount,
            status,
            "k-" + status + "-" + attemptsCount,
            "0".repeat(64));
  }

  private void seedProvenanceBoardHoldRoute() {
    insertProvenance(PROVENANCE_ID, "SYNTHETIC");
    jdbc()
        .update(
            "insert into boards (board_id, provider, layout_id, size_id, canonical_config_json,"
                + " synthetic, provenance_id) values (?, ?, ?, ?, ?::jsonb, ?, ?)",
            BOARD_ID,
            "synthetic",
            "layout-1",
            "size-1",
            "{}",
            true,
            PROVENANCE_ID);
    jdbc()
        .update(
            "insert into holds (hold_id, board_id, provider_hold_id, x, y) values (?, ?, ?, ?, ?)",
            HOLD_ID,
            BOARD_ID,
            "h1",
            1.5,
            2.5);
    jdbc()
        .update(
            "insert into routes (route_id, board_id, provider_route_uuid, provenance_id)"
                + " values (?, ?, ?, ?)",
            ROUTE_ID,
            BOARD_ID,
            "demo-route-001",
            PROVENANCE_ID);
  }

  private void insertProvenance(UUID id, String licenseReviewStatus) {
    jdbc()
        .update(
            "insert into provenance_sources (provenance_id, source, content_sha256, synthetic,"
                + " license_review_status, imported_at) values (?, ?, ?, ?, ?, now())",
            id,
            "migration-it",
            "0".repeat(64),
            true,
            licenseReviewStatus);
  }

  private void insertVariant(UUID variantId, int angleDeg) {
    jdbc()
        .update(
            "insert into route_variants (variant_id, route_id, angle_deg) values (?, ?, ?)",
            variantId,
            ROUTE_ID,
            angleDeg);
  }
}
