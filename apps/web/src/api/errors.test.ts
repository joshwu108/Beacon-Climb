import { describe, expect, it } from 'vitest';
import conflict from '../../../../contracts/examples/patchAttempt/response-409.json';
import validation from '../../../../contracts/examples/createAttempt/response-422.json';
import { ApiError, isErrorEnvelope, toApiError } from './errors';

describe('ApiError mapping', () => {
  it('maps a 422 ErrorEnvelope fixture to a typed ApiError with field errors', () => {
    const error = toApiError(
      validation.status,
      validation.body,
      new Headers(validation.headers),
    );
    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(422);
    expect(error.code).toBe('VALIDATION_FAILED');
    expect(error.message).toBe('Request failed validation');
    expect(error.requestId).toBe('fixture-request-2');
    expect(error.fieldErrors).toEqual(validation.body.field_errors);
    expect(error.fieldErrorsByField().get('attempts_count')?.message).toBe(
      'must be >= 1',
    );
    expect(error.currentRevision).toBeNull();
  });

  it('carries current_revision for REVISION_CONFLICT', () => {
    const error = toApiError(conflict.status, conflict.body);
    expect(error.code).toBe('REVISION_CONFLICT');
    expect(error.currentRevision).toBe(2);
  });

  it('falls back to UNKNOWN with the response X-Request-Id when the body is not an envelope', () => {
    const headers = new Headers({
      'X-Request-Id': 'edge-1',
      'Retry-After': '30',
    });
    const error = toApiError(502, '<html>bad gateway</html>', headers);
    expect(error.code).toBe('UNKNOWN');
    expect(error.status).toBe(502);
    expect(error.requestId).toBe('edge-1');
    expect(error.retryAfterSeconds).toBe(30);
    expect(error.message).toBe('Request failed with status 502');
  });

  it('isErrorEnvelope requires the four mandatory members', () => {
    expect(isErrorEnvelope(validation.body)).toBe(true);
    expect(isErrorEnvelope({ code: 'X', message: 'm' })).toBe(false);
    expect(isErrorEnvelope(null)).toBe(false);
  });
});
