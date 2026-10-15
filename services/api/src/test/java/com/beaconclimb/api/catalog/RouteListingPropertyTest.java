package com.beaconclimb.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.AbstractPostgresIT;
import com.beaconclimb.api.catalog.api.RouteListResponse;
import com.beaconclimb.api.catalog.api.RouteVariantSummaryView;
import com.beaconclimb.api.catalog.importer.CatalogImporter;
import com.beaconclimb.api.catalog.importer.CatalogRepository;
import com.beaconclimb.api.catalog.importer.CatalogSchema;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.BeforeContainer;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * BC-0106 acceptance: 100% of returned routes match the requested board, angle and grade range and
 * a retired, invalid or out-of-window variant is never returned. Runs the service in-process (no
 * HTTP) against the shared Postgres, migrated and seeded here because jqwik does not take part in
 * the Spring test lifecycle. The forbidden set is derived from the tables, not hard-coded.
 */
class RouteListingPropertyTest {

  private static final List<Integer> ANGLES = List.of(20, 30, 40, 45, 50, 35, 0, -10);
  private static final BigDecimal GRADE_LOW = new BigDecimal("10");
  private static final BigDecimal GRADE_HIGH = new BigDecimal("35");

  private static CatalogService service;
  private static Set<String> hidden;

  @BeforeContainer
  static void migrateAndSeed() {
    String url = AbstractPostgresIT.POSTGRES.getJdbcUrl();
    Flyway.configure()
        .dataSource(url, "beacon_migrate", AbstractPostgresIT.MIGRATE_PASSWORD)
        .load()
        .migrate();
    DataSource app =
        new DriverManagerDataSource(url, "beacon_app", AbstractPostgresIT.APP_PASSWORD);
    JdbcClient jdbc = JdbcClient.create(app);
    CatalogImporter importer =
        new CatalogImporter(
            new CatalogSchema(),
            new CatalogRepository(jdbc),
            new TransactionTemplate(new DataSourceTransactionManager(app)),
            Clock.systemUTC());
    CatalogSeed.seed(importer, jdbc);
    hidden = CatalogSeed.hiddenPublicIds(jdbc);
    assertThat(hidden)
        .contains("test-retired:40", "test-invalid:40", "test-expired:40", "test-future:40")
        .contains(CatalogSeed.FIXTURE_RETIRED_ID);
    service =
        new CatalogService(
            new CatalogQueryRepository(jdbc), Clock.systemUTC(), JsonMapper.builder().build());
  }

  @Property(tries = 200)
  void everyReturnedVariantMatchesTheFiltersAndIsVisible(
      @ForAll boolean restrictBoard,
      @ForAll("angles") Integer angle,
      @ForAll("grades") BigDecimal gradeMin,
      @ForAll("grades") BigDecimal gradeMax,
      @ForAll @IntRange(min = 1, max = 100) int limit) {
    String board = restrictBoard ? CatalogSeed.BOARD_ID : null;
    RouteQuery query = new RouteQuery(board, angle, gradeMin, gradeMax, null, limit);

    RouteListResponse first = service.listRoutes(query);
    assertPage(first, query);
    if (first.nextCursor() != null) {
      RouteCursor cursor = RouteCursor.decode(first.nextCursor()).orElseThrow();
      RouteListResponse second = service.listRoutes(query.withCursor(cursor));
      assertPage(second, query);
      RouteVariantSummaryView lastOfFirst = first.items().get(first.items().size() - 1);
      assertThat(second.items()).doesNotContain(lastOfFirst);
      if (!second.items().isEmpty() && second.items().get(0).gradeNumeric() != null) {
        assertThat(second.items().get(0).gradeNumeric())
            .isGreaterThanOrEqualTo(lastOfFirst.gradeNumeric());
      }
    }
  }

  private static void assertPage(RouteListResponse page, RouteQuery query) {
    assertThat(page.items()).hasSizeLessThanOrEqualTo(query.limit());
    if (page.items().size() < query.limit()) {
      assertThat(page.nextCursor()).isNull();
    }
    for (RouteVariantSummaryView item : page.items()) {
      assertThat(hidden).doesNotContain(item.routeVariantId());
      if (query.boardId() != null) {
        assertThat(item.boardId()).isEqualTo(query.boardId());
      }
      if (query.angleDeg() != null) {
        assertThat(item.angleDeg()).isEqualTo(query.angleDeg());
      }
      if (query.gradeMin() != null) {
        assertThat(item.gradeNumeric()).isNotNull();
        assertThat(BigDecimal.valueOf(item.gradeNumeric()))
            .isGreaterThanOrEqualTo(query.gradeMin());
      }
      if (query.gradeMax() != null) {
        assertThat(item.gradeNumeric()).isNotNull();
        assertThat(BigDecimal.valueOf(item.gradeNumeric())).isLessThanOrEqualTo(query.gradeMax());
      }
    }
  }

  @Provide
  Arbitrary<Integer> angles() {
    return Arbitraries.of(ANGLES).injectNull(0.25);
  }

  @Provide
  Arbitrary<BigDecimal> grades() {
    return Arbitraries.bigDecimals().between(GRADE_LOW, GRADE_HIGH).ofScale(1).injectNull(0.3);
  }
}
