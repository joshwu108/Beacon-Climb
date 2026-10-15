package com.beaconclimb.api.identity;

import com.beaconclimb.api.identity.api.ConsentDocument;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * Serves the current consent document (C-07). The text is the classpath file {@code
 * consent/consent-v1.md}; its SHA-256 over the UTF-8 bytes is computed once at startup and is what
 * {@code createConsent} must echo back. A new text is a new file and a new version.
 */
@Service
public class ConsentDocumentService {

  public static final String CURRENT_VERSION = "v1";
  static final String RESOURCE = "consent/consent-v1.md";
  static final List<String> SCOPES_AVAILABLE = List.of("service", "research");

  private final ConsentDocument document;

  public ConsentDocumentService() {
    this.document = load();
  }

  public ConsentDocument current() {
    return document;
  }

  private static ConsentDocument load() {
    try {
      byte[] bytes = new ClassPathResource(RESOURCE).getContentAsByteArray();
      String text = new String(bytes, StandardCharsets.UTF_8);
      String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      return new ConsentDocument(CURRENT_VERSION, sha256, text, SCOPES_AVAILABLE);
    } catch (IOException e) {
      throw new UncheckedIOException("Consent document " + RESOURCE + " is missing", e);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
