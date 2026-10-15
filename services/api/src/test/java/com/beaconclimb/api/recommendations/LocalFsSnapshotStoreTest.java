package com.beaconclimb.api.recommendations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beaconclimb.api.platform.json.Jcs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The local store's contract (ADR-0003): canonical check, layout, atomic write, idempotent put,
 * quota.
 */
class LocalFsSnapshotStoreTest {

  private static final byte[] CANONICAL =
      "{\"a\":1,\"b\":[true,null]}".getBytes(StandardCharsets.UTF_8);

  @TempDir Path dir;

  @Test
  void putStoresUnderTwoHexPrefixAndReturnsTheSha256() throws IOException {
    LocalFsSnapshotStore store = new LocalFsSnapshotStore(dir, 10);

    String digest = store.put(CANONICAL);

    assertThat(digest).isEqualTo(Jcs.sha256Hex(CANONICAL));
    Path expected = dir.resolve(digest.substring(0, 2)).resolve(digest + ".json");
    assertThat(Files.readAllBytes(expected)).isEqualTo(CANONICAL);
    assertThat(store.exists(digest)).isTrue();
    assertThat(store.get(digest)).contains(CANONICAL);
    assertThat(SnapshotStore.uri(digest))
        .isEqualTo("data/decisions/" + digest.substring(0, 2) + "/" + digest + ".json");
    try (Stream<Path> files = Files.walk(dir)) {
      assertThat(files.filter(Files::isRegularFile)).containsExactly(expected);
    }
  }

  @Test
  void nonCanonicalBytesAreRefusedBeforeAnythingIsWritten() throws IOException {
    LocalFsSnapshotStore store = new LocalFsSnapshotStore(dir, 10);

    assertThatThrownBy(() -> store.put("{\"b\":1,\"a\":2}".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical");
    assertThatThrownBy(() -> store.put("{ \"a\": 1 }".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.put("not json".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.put(new byte[0])).isInstanceOf(IllegalArgumentException.class);
    try (Stream<Path> files = Files.walk(dir)) {
      assertThat(files.filter(Files::isRegularFile)).isEmpty();
    }
  }

  @Test
  void putIsIdempotentForIdenticalBytesAndNeverLeavesTempFiles() throws IOException {
    LocalFsSnapshotStore store = new LocalFsSnapshotStore(dir, 1);

    String first = store.put(CANONICAL);
    String second = store.put(CANONICAL);

    assertThat(second).isEqualTo(first);
    assertThat(store.fileCount()).isEqualTo(1);
    try (Stream<Path> files = Files.walk(dir)) {
      assertThat(files.filter(Files::isRegularFile).map(p -> p.getFileName().toString()))
          .containsExactly(first + ".json");
    }
  }

  @Test
  void quotaRefusesANewFileButNotAnExistingOne() {
    LocalFsSnapshotStore store = new LocalFsSnapshotStore(dir, 1);
    store.put(CANONICAL);

    assertThatThrownBy(() -> store.put("{\"c\":2}".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(SnapshotQuotaExceededException.class);
    assertThat(store.put(CANONICAL)).isEqualTo(Jcs.sha256Hex(CANONICAL));
    assertThat(store.fileCount()).isEqualTo(1);
  }

  @Test
  void quotaCountsFilesAlreadyOnDiskAndDeleteReleasesThem() throws IOException {
    LocalFsSnapshotStore first = new LocalFsSnapshotStore(dir, 2);
    String digest = first.put(CANONICAL);
    LocalFsSnapshotStore reopened = new LocalFsSnapshotStore(dir, 2);

    assertThat(reopened.fileCount()).isEqualTo(1);
    reopened.put("{\"c\":2}".getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(() -> reopened.put("{\"d\":3}".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(SnapshotQuotaExceededException.class);

    reopened.delete(digest);
    reopened.delete(digest);

    assertThat(reopened.exists(digest)).isFalse();
    assertThat(reopened.get(digest)).isEmpty();
    assertThat(reopened.fileCount()).isEqualTo(1);
    assertThat(reopened.put("{\"d\":3}".getBytes(StandardCharsets.UTF_8))).isNotBlank();
  }

  @Test
  void onlyLowercaseHexDigestsAddressTheStore() {
    LocalFsSnapshotStore store = new LocalFsSnapshotStore(dir, 10);

    assertThatThrownBy(() -> store.get("../etc/passwd"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.exists("F".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.delete("0".repeat(63)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SnapshotStore.uri(null)).isInstanceOf(IllegalArgumentException.class);
  }
}
