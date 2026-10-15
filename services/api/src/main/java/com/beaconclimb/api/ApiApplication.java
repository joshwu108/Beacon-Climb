package com.beaconclimb.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class ApiApplication {

  static final String IMPORT_PROFILE = "import";

  public static void main(String[] args) {
    ConfigurableApplicationContext context = SpringApplication.run(ApiApplication.class, args);
    // BC-0105: under the import profile the runner's ExitCodeGenerator decides the exit status.
    if (context.getEnvironment().matchesProfiles(IMPORT_PROFILE)) {
      System.exit(SpringApplication.exit(context));
    }
  }
}
