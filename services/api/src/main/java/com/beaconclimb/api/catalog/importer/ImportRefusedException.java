package com.beaconclimb.api.catalog.importer;

/**
 * The whole file was refused before any row was written. {@link Reason#exitCode} is the process
 * exit status of the {@code import} profile; 2 (rows rejected, accepted rows committed) is not a
 * refusal and is reported through {@link ImportResult#exitCode()} instead.
 */
public final class ImportRefusedException extends RuntimeException {

  /** Why a file is refused, with the documented exit code. */
  public enum Reason {
    /** synthetic=false without LOCAL_RESEARCH_ONLY / REVIEWED_PERMITTED plus acknowledge-audit. */
    LICENSE_GATE(3),
    /** manifest.content_sha256 differs from the digest of the canonicalised record lines. */
    CONTENT_SHA256_MISMATCH(4),
    /** Not a catalog v1 file: no manifest first, manifest fails the schema, bad JSON Lines. */
    FILE_INVALID(5);

    private final int exitCode;

    Reason(int exitCode) {
      this.exitCode = exitCode;
    }

    public int exitCode() {
      return exitCode;
    }
  }

  private final Reason reason;

  public ImportRefusedException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }

  public int exitCode() {
    return reason.exitCode();
  }
}
