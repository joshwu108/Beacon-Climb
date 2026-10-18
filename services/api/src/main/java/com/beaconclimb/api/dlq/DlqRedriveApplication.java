package com.beaconclimb.api.dlq;

import com.beaconclimb.api.outbox.StreamProperties;
import com.beaconclimb.events.EventValidator;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.Banner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * The {@code redrive} profile (BC-0302, {@code make dlq-redrive} / {@code make dlq-status}): a
 * minimal Spring context built from this class alone, with NO auto-configuration, so no datasource,
 * Flyway, web server or scheduled publisher exists. The property files still load
 * (application.yaml's {@code beacon.stream.*} block and application-redrive.yaml), and the Postgres
 * variables in .env are never read. {@code ApiApplication.main} dispatches here before the api
 * context would be built; this class also carries {@code @Profile} so the api's component scan
 * ignores it on every other profile.
 */
@Configuration(proxyBeanMethods = false)
@Profile(DlqRedriveApplication.PROFILE)
@EnableConfigurationProperties({StreamProperties.class, DlqRedriveProperties.class})
public class DlqRedriveApplication {

  public static final String PROFILE = "redrive";
  static final String PROFILES_ARG = "spring.profiles.active";
  static final String PROFILES_ENV = "SPRING_PROFILES_ACTIVE";
  static final Duration SEND_TIMEOUT = Duration.ofSeconds(15);

  /** True when the command line or SPRING_PROFILES_ACTIVE activates the redrive profile. */
  public static boolean requested(String[] args) {
    List<String> fromArgs = new DefaultApplicationArguments(args).getOptionValues(PROFILES_ARG);
    String fromEnv = System.getenv(PROFILES_ENV);
    return names(fromArgs).contains(PROFILE)
        || names(List.of(fromEnv == null ? "" : fromEnv)).contains(PROFILE);
  }

  /** Runs the CLI to completion and returns its exit code (0 ok, 1 skipped records, 2 usage). */
  public static int run(String[] args) {
    SpringApplication app = new SpringApplication(DlqRedriveApplication.class);
    app.setWebApplicationType(WebApplicationType.NONE);
    app.setBannerMode(Banner.Mode.OFF);
    app.setAdditionalProfiles(PROFILE);
    try (ConfigurableApplicationContext context = app.run(args)) {
      return SpringApplication.exit(context);
    }
  }

  @Bean
  EventValidator eventValidator() {
    return new EventValidator();
  }

  @Bean
  DlqRedriveService dlqRedriveService(StreamProperties stream, EventValidator validator) {
    return new DlqRedriveService(stream.bootstrapServers(), validator, SEND_TIMEOUT);
  }

  @Bean
  DlqRedriveRunner dlqRedriveRunner(
      DlqRedriveService service, DlqRedriveProperties options, StreamProperties stream) {
    return new DlqRedriveRunner(service, options, stream, System.out, System.err);
  }

  private static List<String> names(List<String> values) {
    if (values == null) {
      return List.of();
    }
    return values.stream()
        .flatMap(v -> Arrays.stream(v.split(",")))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .toList();
  }
}
