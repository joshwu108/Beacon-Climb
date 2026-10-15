package com.beaconclimb.api.catalog.importer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * {@code --spring.profiles.active=import --import.file=<path> [--import.acknowledge-audit=true]}.
 * Runs one import, prints the contracted summary lines and exposes the exit code: 0 clean, 2 rows
 * rejected (accepted rows committed, see the report), 3 licence gate, 4 content_sha256 mismatch, 5
 * not a valid catalog file, 1 unexpected failure.
 */
@Configuration
@Profile("import")
@EnableConfigurationProperties(ImportProperties.class)
class CatalogImportRunner implements ApplicationRunner, ExitCodeGenerator {

  private static final Logger log = LoggerFactory.getLogger(CatalogImportRunner.class);
  private static final int EXIT_FAILURE = 1;

  private final CatalogImporter importer;
  private final ImportProperties properties;
  private int exitCode = EXIT_FAILURE;

  CatalogImportRunner(CatalogImporter importer, ImportProperties properties) {
    this.importer = importer;
    this.properties = properties;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (properties.file() == null) {
      System.err.println("import: --import.file=<catalog.jsonl> is required");
      return;
    }
    try {
      ImportResult result =
          importer.importFile(
              properties.file(), properties.acknowledgeAudit(), properties.reportDir());
      if (result.reportFile() != null) {
        System.err.println("import: rejected rows listed in " + result.reportFile());
      }
      System.out.println(result.summary());
      exitCode = result.exitCode();
    } catch (ImportRefusedException e) {
      log.warn("import refused ({}): {}", e.reason(), e.getMessage());
      System.err.println("import refused (" + e.reason() + "): " + e.getMessage());
      exitCode = e.exitCode();
    } catch (RuntimeException e) {
      log.error("import failed", e);
      System.err.println("import failed: " + e.getMessage());
    }
  }

  @Override
  public int getExitCode() {
    return exitCode;
  }
}
