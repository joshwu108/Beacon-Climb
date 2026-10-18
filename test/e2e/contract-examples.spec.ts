import { readFileSync } from "node:fs";
import path from "node:path";
import { expect, test, type APIRequestContext } from "@playwright/test";
import {
  ANGLE_DEG,
  BASE_URL,
  BOARD_ID,
  PASSWORD,
  apiClient,
  uniqueUsername,
  type ConsentDocument,
  type Decision,
  type ErrorEnvelope,
  type RecommendationResponse,
} from "./fixtures";

/**
 * BC-0005c: live conformance of the contract example fixtures. The request fixtures under
 * contracts/examples are replayed verbatim (method, path, body) against the seeded compose
 * stack through nginx on :3000, so a fixture that drifts from the committed synthetic demo
 * board (ml/datasets/synthetic/fixtures/demo-board-v1.jsonl) fails here, not only in a
 * client's mocks. Only fields bound to the fresh user (username) or to the live consent
 * document (version, text digest) are adapted; everything else is the fixture's bytes.
 */

const EXAMPLES_DIR = path.resolve(__dirname, "../../contracts/examples");

interface RequestFixture {
  readonly method: string;
  readonly path: string;
  readonly path_params?: Record<string, string>;
  readonly headers?: Record<string, string>;
  readonly body?: unknown;
  readonly expected_status?: number;
}

interface ResponseFixture {
  readonly status: number;
  readonly body?: unknown;
}

const loadFixture = <T>(operationId: string, file: string): T =>
  JSON.parse(
    readFileSync(path.join(EXAMPLES_DIR, operationId, file), "utf8"),
  ) as T;

const requestFixture = (operationId: string, file = "request.json") =>
  loadFixture<RequestFixture>(operationId, file);

const responseFixture = (operationId: string, file: string) =>
  loadFixture<ResponseFixture>(operationId, file);

interface Replay<T> {
  readonly status: number;
  readonly body: T;
  readonly requestId: string | undefined;
}

/** Send a request fixture as-is; `pathParams` fill `{name}` segments, `body` overrides it. */
const replay = async <T>(
  request: APIRequestContext,
  fixture: RequestFixture,
  options: {
    readonly token?: string;
    readonly pathParams?: Record<string, string>;
    readonly body?: unknown;
  } = {},
): Promise<Replay<T>> => {
  const params = { ...fixture.path_params, ...options.pathParams };
  const resolvedPath = fixture.path.replace(/\{(\w+)\}/g, (_, name: string) => {
    const value = params[name];
    if (value === undefined) throw new Error(`no path param ${name}`);
    return encodeURIComponent(value);
  });
  const body = "body" in options ? options.body : fixture.body;
  const response = await request.fetch(`${BASE_URL}${resolvedPath}`, {
    method: fixture.method,
    headers: {
      Accept: "application/json",
      ...(options.token === undefined
        ? {}
        : { Authorization: `Bearer ${options.token}` }),
      ...fixture.headers,
    },
    ...(body === undefined ? {} : { data: body }),
  });
  const text = await response.text();
  return {
    status: response.status(),
    body: (text === "" ? null : JSON.parse(text)) as T,
    requestId: response.headers()["x-request-id"],
  };
};

const errorCodeOf = (fixture: ResponseFixture): string => {
  const body = fixture.body as { code?: unknown } | undefined;
  if (typeof body?.code !== "string") throw new Error("fixture has no code");
  return body.code;
};

test("contract example fixtures replay against the seeded demo board", async ({
  request,
}, testInfo) => {
  const username = uniqueUsername(testInfo.project.name);
  const consentDoc = await apiClient(request).get<ConsentDocument>(
    requestFixture("getConsentDocument").path,
  );
  expect(consentDoc.status).toBe(200);

  let token = "";
  await test.step("registerUser fixture (fresh username) opens a session", async () => {
    const fixture = requestFixture("registerUser");
    const result = await replay<{ token: string; expires_at: string }>(
      request,
      fixture,
      { body: { ...(fixture.body as object), username, password: PASSWORD } },
    );
    expect(result.status).toBe(201);
    expect(result.requestId).toBeTruthy();
    expect(typeof result.body.token).toBe("string");
    expect(Date.parse(result.body.expires_at)).toBeGreaterThan(Date.now());
    token = result.body.token;
  });

  await test.step("createConsent fixture (live version and digest) is accepted", async () => {
    const fixture = requestFixture("createConsent");
    const result = await replay<null>(request, fixture, {
      token,
      body: {
        ...(fixture.body as object),
        consent_version: consentDoc.body.version,
        text_sha256: consentDoc.body.text_sha256,
      },
    });
    expect(result.status).toBe(204);
  });

  await test.step("putPreferences fixture is 2xx on the seeded board", async () => {
    const fixture = requestFixture("putPreferences");
    const result = await replay<{ board_id: string; angle_deg: number }>(
      request,
      fixture,
      { token },
    );
    expect(result.status, JSON.stringify(result.body)).toBe(200);
    expect(result.body).toMatchObject({
      board_id: BOARD_ID,
      angle_deg: ANGLE_DEG,
    });
    expect(result.body).toMatchObject(fixture.body as Record<string, unknown>);
  });

  let decisionId = "";
  await test.step("createRecommendations fixture is 2xx with a decision_id", async () => {
    const fixture = requestFixture("createRecommendations");
    const result = await replay<RecommendationResponse>(request, fixture, {
      token,
    });
    expect(result.status, JSON.stringify(result.body)).toBe(200);
    expect(result.body.decision_id).toMatch(/^[0-9a-f-]{36}$/);
    expect(result.body.recommendations.length).toBeGreaterThan(0);
    for (const item of result.body.recommendations) {
      expect(item.route_variant_id).toMatch(new RegExp(`:${ANGLE_DEG}$`));
    }
    decisionId = result.body.decision_id;
  });

  await test.step("getDecision fixture path resolves the live decision", async () => {
    const fixture = requestFixture("getDecision");
    const result = await replay<Decision>(request, fixture, {
      token,
      pathParams: { decision_id: decisionId },
    });
    expect(result.status, JSON.stringify(result.body)).toBe(200);
    expect(result.body.decision_id).toBe(decisionId);
    const recommended = requestFixture("createRecommendations").body as {
      session_id: string;
    };
    expect(result.body.request.session_id).toBe(recommended.session_id);
  });

  for (const operationId of ["putPreferences", "createRecommendations"]) {
    await test.step(`${operationId} request-422 fixture is rejected as documented`, async () => {
      const fixture = requestFixture(operationId, "request-422.json");
      const expected = responseFixture(operationId, "response-422.json");
      expect(fixture.expected_status).toBe(expected.status);
      const result = await replay<ErrorEnvelope>(request, fixture, { token });
      expect(result.status, JSON.stringify(result.body)).toBe(
        fixture.expected_status,
      );
      expect(result.body.code).toBe(errorCodeOf(expected));
      expect(result.body.request_id).toBe(result.requestId);
    });
  }
});
