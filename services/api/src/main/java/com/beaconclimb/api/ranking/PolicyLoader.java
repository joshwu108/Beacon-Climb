package com.beaconclimb.api.ranking;

import com.beaconclimb.api.platform.json.Jcs;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Reads the bundled policy resource and computes {@code code_digest}: SHA-256 over the bytes of
 * every {@code .class} file of this package on the classpath, concatenated in ascending file-name
 * order. The digest is computed at load, never stored in the policy file, so it identifies the
 * compiled implementation that ranks (ADR-0003 faithful replay compares it).
 */
@Component
public class PolicyLoader {

  public static final String DEFAULT_RESOURCE = "policies/heuristic-v1.json";
  static final String CLASS_PATTERN = "classpath*:com/beaconclimb/api/ranking/*.class";

  private final String resource;
  private final String codeDigest;

  public PolicyLoader() {
    this(DEFAULT_RESOURCE);
  }

  public PolicyLoader(String resource) {
    this.resource = resource;
    this.codeDigest = computeCodeDigest();
  }

  /** The policy with its code digest attached. */
  public Policy load() {
    return Policy.fromJson(rawJson()).withCodeDigest(codeDigest);
  }

  /** The resource text as shipped; {@code serialized_config} is its JCS form. */
  public String rawJson() {
    try (InputStream in = PolicyLoader.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("classpath resource " + resource + " missing");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + resource, e);
    }
  }

  public String codeDigest() {
    return codeDigest;
  }

  static String computeCodeDigest() {
    try {
      Resource[] classes = new PathMatchingResourcePatternResolver().getResources(CLASS_PATTERN);
      Arrays.sort(classes, Comparator.comparing(Resource::getFilename));
      MessageDigest digest = Jcs.sha256();
      for (Resource clazz : classes) {
        try (InputStream in = clazz.getInputStream()) {
          digest.update(in.readAllBytes());
        }
      }
      if (classes.length == 0) {
        throw new IllegalStateException("no ranking classes found for " + CLASS_PATTERN);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read ranking classes", e);
    }
  }
}
