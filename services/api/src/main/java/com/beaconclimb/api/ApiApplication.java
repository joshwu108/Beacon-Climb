package com.beaconclimb.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class ApiApplication {

  static final String IMPORT_PROFILE = "import";
  static final String RECOMPUTE_PROFILE = "recompute";

  public static void main(String[] args) {
    ConfigurableApplicationContext context = SpringApplication.run(ApiApplication.class, args);
    // BC-0105 / BC-0113: under a CLI profile the runner's ExitCodeGenerator decides the exit
    // status.
    if (context.getEnvironment().matchesProfiles(IMPORT_PROFILE, RECOMPUTE_PROFILE)) {
      System.exit(SpringApplication.exit(context));
    }
  }
}
