# Testing Approach

## 1. Strategy

Two layers, deliberately kept separate:

- **Unit tests** (`src/test/java/.../unit`): pure logic, collaborators mocked (Mockito), no
  Spring context, no database. Fast (the entire unit suite runs in well under a second) and
  precise — a failure here points at exactly one class's logic.
- **Integration tests** (`src/test/java/.../integration`): full `@SpringBootTest` +
  `@AutoConfigureMockMvc`, a real (in-memory) H2 database per test class via
  `@DynamicPropertySource`, real HTTP request/response handling through the actual controller →
  service → repository → DB stack. These validate wiring, HTTP status/JSON contracts, and
  cross-cutting concerns (rate limiting, async click recording) that no unit test can see.

No layer mocks the database in the integration tests — an in-memory H2 instance is used
precisely so schema and query behavior are exercised for real (see
`docs/AI_ASSISTED_ENGINEERING.md` #5, the day-bucketing query bug that a mocked repository would
never have caught).

## 2. What's covered

| Area | Test(s) | What it proves |
|---|---|---|
| Base62 encode/decode | `Base62EncoderTest` | Round-trips correctly, including `0` and large values |
| Short code masking | `ShortCodeGeneratorTest` | Deterministic, non-negative, distinct from raw ID |
| Token bucket math | `TokenBucketTest` | Consumes down to zero, refills over time, never exceeds capacity |
| URL/SSRF validation | `UrlValidatorTest` | Scheme allowlist, private/loopback rejection, reserved-path detection |
| Creation logic | `UrlServiceTest` | Generated-code path, custom-alias path, alias conflict (409), reserved alias (400), invalid URL (400), redirect resolution (404/410/200 cases), soft delete |
| Full create/read/list/delete HTTP contract | `UrlApiIntegrationTest` | Status codes and payload shape end-to-end against a real DB |
| Redirect + analytics lifecycle | `RedirectIntegrationTest` | 302 with correct `Location`; synchronous click count is correct with no wait; async analytics breakdown becomes correct within a bounded wait; unknown code → 404; expired-but-still-active code → 410 |
| Rate limiting | `RateLimitIntegrationTest` | 429 with `Retry-After` header after bucket exhaustion; redirect endpoint is explicitly exempt |

51 tests total, all passing (`mvn test`), covering every documented HTTP status code the API can
return (200, 201, 204, 302, 400, 404, 409, 410, 429).

## 3. Handling asynchrony deterministically

The redirect path records click *detail* asynchronously (`@Async`) so the hot path isn't blocked
by analytics writes (`docs/ARCHITECTURE.md` §2.2). A naive test (`assert immediately after the
request`) would be flaky — sometimes the async write lands before the assertion, sometimes not.
Two different strategies are used depending on which number is being asserted:

- **Synchronous count** (`clickCount`, `totalClicks`): asserted immediately, no wait, because
  the design guarantees these are updated in the same transaction as the redirect itself.
- **Async breakdown** (`clicksByDay`, `topReferrers`, `topUserAgents`): asserted via
  `Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(...)`, which polls until the
  assertion holds or the timeout elapses. This is intentionally different from a fixed
  `Thread.sleep(N)` — it fails fast when the write completes quickly and only waits the full
  timeout when something is actually wrong, avoiding both flakiness and unnecessarily slow tests.

## 4. Test isolation

Each integration test class gets its own H2 database (`jdbc:h2:mem:<random-uuid>`) via
`@DynamicPropertySource`, so tests in different classes cannot see each other's data.

Within a single class, however, the Spring test context — and therefore any singleton bean, such
as `RateLimiterRegistry` — is shared and reused across test methods for performance. This was hit
directly during development: two methods in `RateLimitIntegrationTest` both resolved to the same
rate-limit bucket key (`request.getRemoteAddr()`, constant under MockMvc), so one test's bucket
exhaustion leaked into the other regardless of execution order. The fix — giving each test method
a distinct synthetic `X-Forwarded-For` client key — is the general pattern used anywhere a test
needs to exercise stateful, keyed, in-process infrastructure without colliding with its sibling
tests.

## 5. Coverage limitations (honest gaps)

- **No explicit concurrency/load test.** The atomic-increment fix (`docs/ARCHITECTURE.md` §4.3)
  is validated by reasoning about the SQL (`UPDATE ... SET x = x + 1` is atomic at the database
  level by construction) and by unit-testing the repository method's intent, but there is no test
  that fires concurrent redirects at the same code and asserts the final count. A proper
  concurrency test (N parallel threads, assert `clickCount == N`) would strengthen this but adds
  meaningful runtime and flakiness risk to the default `mvn test` run; noted as the single most
  valuable test *not* included in this build.
- **No test exercises the `@Scheduled` cleanup jobs directly.** `CleanupScheduler` is simple
  enough (delegates straight to existing, already-tested repository methods) that it was assessed
  as low-risk, but its scheduling behavior itself (fires on the configured interval, doesn't
  double-fire) is unverified by an automated test.
- **No load/performance testing.** Nothing in this suite establishes throughput or latency
  characteristics under load; the caching and rate-limiting design decisions are justified
  qualitatively (`docs/ARCHITECTURE.md`) rather than benchmarked.
- **No security/fuzz testing beyond the explicit SSRF/scheme unit tests.** `UrlValidatorTest`
  covers the guard rules that exist; it does not attempt to discover novel bypasses (DNS
  rebinding, IPv6 address obfuscation tricks, etc.) — a real hardening pass would want a
  dedicated adversarial test set here (see `docs/RISKS.md`).
- **Manual, not automated, end-to-end verification.** The `mvn spring-boot:run` + curl walkthrough
  in `README.md` was performed by hand once, not wired into CI as an automated smoke test.

## 6. Trade-offs made in the test suite itself

- **MockMvc over a real HTTP client + running server** for integration tests: faster (no socket
  binding, no port conflicts between parallel test runs), and Spring's own testing guidance
  recommends it for exactly this kind of full-stack-but-in-process test. The cost is that it
  doesn't exercise the actual Tomcat connector — accepted, since the manual curl verification
  step covers that gap once, deliberately, outside the automated suite.
- **In-memory H2 for tests, file-based H2 for the running app**: keeps tests hermetic and fast
  (no leftover state between runs) while the running application gets durability across restarts.
  The trade-off is that a bug specific to file-mode H2 (locking, `AUTO_SERVER` behavior) would not
  be caught by the test suite — assessed as low risk since the JPA/SQL layer is otherwise
  identical between the two modes.
