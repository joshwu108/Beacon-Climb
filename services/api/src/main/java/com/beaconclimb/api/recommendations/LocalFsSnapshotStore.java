package com.beaconclimb.api.recommendations;

import com.beaconclimb.api.platform.json.Jcs;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * {@link SnapshotStore} on the local filesystem rooted at {@code beacon.decisions.dir}: {@code
 * <dir>/<first two hex>/<digest>.json}. {@code put} re-canonicalises the bytes and refuses anything
 * that is not already canonical, writes to a temporary file in the target directory and renames it
 * atomically, so a reader never sees a partial file; an identical file already present is left as
 * it is. The quota is the number of {@code .json} files under the root, counted once lazily and
 * then tracked in this process (another writer on the same directory is only seen at restart),
 * which is the documented approximation for the local-disk MVP (TM-25).
 */
public final class LocalFsSnapshotStore implements SnapshotStore {

  private static final String SUFFIX = ".json";
  private static final String TEMP_SUFFIX = ".tmp";

  private final Path root;
  private final long maxFiles;
  private long fileCount = -1;

  public LocalFsSnapshotStore(Path root, long maxFiles) {
    if (maxFiles < 1) {
      throw new IllegalArgumentException("max-files must be positive");
    }
    this.root = root.toAbsolutePath().normalize();
    this.maxFiles = maxFiles;
  }

  public Path root() {
    return root;
  }

  @Override
  public synchronized String put(byte[] canonicalBytes) {
    requireCanonical(canonicalBytes);
    String digest = Jcs.sha256Hex(canonicalBytes);
    Path target = pathOf(digest);
    try {
      if (Files.exists(target)) {
        if (!Arrays.equals(Files.readAllBytes(target), canonicalBytes)) {
          throw new IllegalStateException("digest collision at " + target);
        }
        return digest;
      }
      if (fileCount() + 1 > maxFiles) {
        throw new SnapshotQuotaExceededException(maxFiles);
      }
      Files.createDirectories(target.getParent());
      Path temp = Files.createTempFile(target.getParent(), digest, TEMP_SUFFIX);
      try {
        Files.write(temp, canonicalBytes);
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        fileCount++;
      } catch (FileAlreadyExistsException raced) {
        Files.deleteIfExists(temp);
      } catch (IOException | RuntimeException e) {
        Files.deleteIfExists(temp);
        throw e;
      }
      return digest;
    } catch (IOException e) {
      throw new UncheckedIOException("cannot write snapshot " + digest, e);
    }
  }

  @Override
  public synchronized Optional<byte[]> get(String digest) {
    Path path = pathOf(digest);
    try {
      return Files.exists(path) ? Optional.of(Files.readAllBytes(path)) : Optional.empty();
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read snapshot " + digest, e);
    }
  }

  @Override
  public synchronized boolean exists(String digest) {
    return Files.exists(pathOf(digest));
  }

  @Override
  public synchronized void delete(String digest) {
    try {
      if (Files.deleteIfExists(pathOf(digest)) && fileCount >= 0) {
        fileCount--;
      }
    } catch (IOException e) {
      throw new UncheckedIOException("cannot delete snapshot " + digest, e);
    }
  }

  /** Files currently counted against the quota. */
  public synchronized long fileCount() {
    if (fileCount < 0) {
      fileCount = countFiles();
    }
    return fileCount;
  }

  Path pathOf(String digest) {
    SnapshotStore.requireDigest(digest);
    return root.resolve(digest.substring(0, 2)).resolve(digest + SUFFIX);
  }

  private long countFiles() {
    if (!Files.isDirectory(root)) {
      return 0;
    }
    try (Stream<Path> files = Files.walk(root)) {
      return files
          .filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().endsWith(SUFFIX))
          .count();
    } catch (IOException e) {
      throw new UncheckedIOException("cannot count snapshots under " + root, e);
    }
  }

  private static void requireCanonical(byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      throw new IllegalArgumentException("snapshot bytes are empty");
    }
    String text = new String(bytes, StandardCharsets.UTF_8);
    byte[] canonical;
    try {
      canonical = Jcs.canonicalBytes(text);
    } catch (Jcs.JcsException e) {
      throw new IllegalArgumentException("snapshot bytes are not JSON", e);
    }
    if (!Arrays.equals(canonical, bytes)) {
      throw new IllegalArgumentException("snapshot bytes are not RFC 8785 canonical");
    }
  }
}
