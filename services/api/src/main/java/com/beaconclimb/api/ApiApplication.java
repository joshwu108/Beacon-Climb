package com.beaconclimb.api;

import com.beaconclimb.api.dlq.DlqRedriveApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class ApiApplication {

  static final String IMPORT_PROFILE = "import";
  static final String RECOMPUTE_PROFILE = "recompute";
  static final String RECONCILE_PROFILE = "reconcile";
  static final String PARITY_PROFILE = "parity";

  public static void main(String[] args) {
    // BC-0302: the DLQ redrive CLI is a separate, datasource-free context (it must never touch
    // Postgres), so it is dispatched before the api context is even built.
    if (DlqRedriveApplication.requested(args)) {
      System.exit(DlqRedriveApplication.run(args));
      return;
    }
    ConfigurableApplicationContext context = SpringApplication.run(ApiApplication.class, args);
    // BC-0105 / BC-0113 / BC-0309 / BC-0306: under a CLI profile the runner's ExitCodeGenerator
    // decides the exit status (without SpringApplication.exit the JVM would end with 0 whatever
    // the runner recorded).
    if (context
        .getEnvironment()
        .matchesProfiles(IMPORT_PROFILE, RECOMPUTE_PROFILE, RECONCILE_PROFILE, PARITY_PROFILE)) {
      System.exit(SpringApplication.exit(context));
    }
  }
}
