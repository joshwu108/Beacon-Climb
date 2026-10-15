package com.beaconclimb.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beaconclimb.api.platform.web.ApiException;
import com.beaconclimb.api.platform.web.ErrorCode;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** BC-0123 F1: the Argon2 gate refuses with 503 and Retry-After once every permit is taken. */
class HashGateTest {

  @Test
  void refusesWithServiceUnavailableWhenNoPermitFreesUpInTime() {
    HashGate gate = new HashGate(1, Duration.ofMillis(20));
    gate.acquire();

    assertThatThrownBy(gate::acquire)
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
              assertThat(e.code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
              assertThat(e.headers()).containsEntry("Retry-After", "1");
              assertThat(e.getMessage()).doesNotContainIgnoringCase("password");
            });

    gate.release();
    gate.acquire();
    gate.release();
    assertThat(gate.maxObservedConcurrency()).isEqualTo(1);
  }

  @Test
  void tracksTheHighestNumberOfSimultaneousHolders() {
    HashGate gate = new HashGate(3, Duration.ofMillis(20));
    gate.acquire();
    gate.acquire();
    gate.release();
    gate.acquire();
    gate.acquire();

    assertThat(gate.maxObservedConcurrency()).isEqualTo(3);
    assertThatThrownBy(gate::acquire).isInstanceOf(ApiException.class);
  }

  @Test
  void rejectsAZeroPermitGate() {
    assertThatThrownBy(() -> new HashGate(0, Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
