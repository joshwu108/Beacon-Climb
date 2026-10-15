package com.beaconclimb.api.catalog.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.catalog.CatalogSeed;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * {@code make bench-catalog} (BC-0106 acceptance: p95 < 300 ms on the demo board, hardware
 * recorded). Skipped unless {@code -Dbench=true}; sequential HTTP calls over loopback on the random
 * port against the Testcontainers Postgres, so the numbers include Docker's network stack. Prints
 * only; the single assertion is a loose sanity bound so a broken build cannot pass as "fast".
 */
class CatalogBenchIT extends CatalogTestSupport {

  private static final int WARMUP = 50;
  private static final int MEASURED = 300;
  private static final double SANITY_P95_MS = 2000;
  private static final String LIST_PATH =
      "/v1/routes?board_id=" + CatalogSeed.BOARD_ID + "&angle_deg=40&limit=20";

  @Test
  void benchListAndDetail() throws IOException {
    Assumptions.assumeTrue(Boolean.getBoolean("bench"), "run with -Dbench=true");
    String detailPath = "/v1/routes/" + firstRouteId();

    double[] list = measure(LIST_PATH);
    double[] detail = measure(detailPath);

    System.out.println("bench-catalog hardware: " + hardware());
    System.out.println("bench-catalog GET " + LIST_PATH + " " + summarize(list));
    System.out.println("bench-catalog GET " + detailPath + " " + summarize(detail));
    assertThat(percentile(list, 95)).isLessThan(SANITY_P95_MS);
    assertThat(percentile(detail, 95)).isLessThan(SANITY_P95_MS);
  }

  private String firstRouteId() {
    ResponseEntity<String> page = get(LIST_PATH, token);
    assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode items = body(page).get("items");
    assertThat(items).isNotEmpty();
    return items.get(0).get("route_variant_id").asString();
  }

  private double[] measure(String path) {
    for (int i = 0; i < WARMUP; i++) {
      call(path);
    }
    double[] millis = new double[MEASURED];
    for (int i = 0; i < MEASURED; i++) {
      long start = System.nanoTime();
      call(path);
      millis[i] = (System.nanoTime() - start) / 1_000_000.0;
    }
    return millis;
  }

  private void call(String path) {
    ResponseEntity<String> response = get(path, token);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  private static String summarize(double[] millis) {
    return String.format(
        Locale.ROOT,
        "n=%d p50=%.2fms p95=%.2fms max=%.2fms",
        millis.length,
        percentile(millis, 50),
        percentile(millis, 95),
        Arrays.stream(millis).max().orElse(Double.NaN));
  }

  /** Nearest-rank percentile over a copy of the samples. */
  private static double percentile(double[] millis, int p) {
    double[] sorted = millis.clone();
    Arrays.sort(sorted);
    int rank = (int) Math.ceil(p / 100.0 * sorted.length);
    return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
  }

  private static String hardware() throws IOException {
    String cpu = sysctl("machdep.cpu.brand_string");
    String memory = sysctl("hw.memsize");
    return cpu
        + " / memsize="
        + memory
        + " / "
        + System.getProperty("os.name")
        + " "
        + System.getProperty("os.version")
        + " / cores="
        + Runtime.getRuntime().availableProcessors()
        + " / java="
        + System.getProperty("java.version");
  }

  private static String sysctl(String key) throws IOException {
    try {
      Process process = new ProcessBuilder(List.of("sysctl", "-n", key)).start();
      String out = new String(process.getInputStream().readAllBytes()).trim();
      return out.isEmpty() ? "unavailable" : out;
    } catch (IOException e) {
      return "unavailable";
    }
  }
}
