# AI-Assisted Engineering Log

This document is the traceability record the assignment asks for: what the AI (Claude Code)
generated, what a human reviewed/edited/rejected and why, what quality gates every change had to
clear, how AI usage was kept secure, and where explicit human sign-off happened. It is written
after the fact from the actual session, not reconstructed from memory.

## 1. Engagement model

- **Engineer (human)**: owns requirements interpretation, approves the plan before any code is
  written, approves every tool action the harness surfaces for approval (file writes, shell
  commands), and is the final authority on whether output is acceptable.
- **AI (Claude Code, this assistant)**: proposes ambiguity resolutions, decomposes the work,
  writes code and tests, runs builds/tests, self-reviews its own output against the test suite
  and manual verification, and documents its own decisions and defects for the human to audit.
- **Boundary**: the AI never merges its own claim of correctness as the final word — every
  claim in this log is backed by either a passing test the human can re-run, or a
  manually-reproduced curl transcript, both included in the repo/`README.md`.

## 2. Prompting discipline

1. **Ambiguity was surfaced before code, not resolved silently.** The assignment left three
   material decisions open (stack/persistence approach, feature scope, documentation format).
   Rather than picking defaults, the AI asked three explicit multiple-choice questions with a
   recommendation and rationale attached to each option, and only proceeded once the engineer
   picked: **Spring Boot 3 + H2**, **full feature set**, **full doc set in repo**.
2. **Plan-before-code was a hard gate, not a suggestion.** The AI entered plan mode, read the
   actual environment (installed JDK version, Maven version, Docker availability) before
   proposing a stack, wrote a concrete plan file (package layout, API surface, key design
   decisions with trade-offs, file list, verification steps), and did not write a single line of
   application code until the engineer approved that plan.
3. **Environment facts were checked, not assumed.** The original `pom.xml` targeted Java 26; the
   AI ran `java -version`-equivalent checks, found JDK 21 installed, and retargeted the build
   accordingly *in the plan*, before implementation — rather than writing code against an
   assumed toolchain and discovering the mismatch at build time.
4. **Decomposition was dependency-ordered.** See `docs/SCENARIOS.md` for the explicit ordering
   (environment fix → data model → short-code strategy → service/controller → error handling →
   tests) and the reasoning for why each step blocked the next.

## 3. Quality gates

Every unit of work passed through the same sequence before being considered done:

1. **Compiles** (`mvn compile`) — checked incrementally as files were added.
2. **Self-review against known failure modes** — before running any test, the AI re-read
   concurrency-sensitive and caching-sensitive code specifically looking for races and stale-read
   bugs (this caught two defects pre-test; see §4).
3. **Unit tests pass** (`mvn test`, unit package) — pure-logic components (`Base62Encoder`,
   `ShortCodeGenerator`, `TokenBucket`, `UrlValidator`, `UrlService` with mocked collaborators).
4. **Integration tests pass** (`mvn test`, integration package) — full Spring context, MockMvc,
   real (in-memory) H2, exercising HTTP status codes and payload shapes end-to-end.
5. **Manual end-to-end verification** — the application was actually started
   (`mvn spring-boot:run`) and driven with `curl` against every documented endpoint and error
   path (create/redirect/analytics/delete, 400/404/409/410/429, SSRF guard, reserved alias,
   Swagger UI, actuator health) — not just asserted by tests, but observed running. Transcript
   summarized in `README.md`.
6. **Documentation reviewed against the code it describes** — architecture/scenario/risk docs
   were written by re-reading the actual final source, not from a remembered plan, so that
   documented behavior (e.g., "GET is exempt from rate limiting") matches an assertion that
   exists in a passing test.

Nothing in this repository is marked "done" on the basis of the AI's own narrative description
alone; each claim in this log points at a test name or a reproducible command.

## 4. Traceability log — generated / edited / rejected

| # | Artifact | Status | Rationale |
|---|---|---|---|
| 1 | `pom.xml` (Java 26 target, empty deps) | **Rejected**, fully rewritten | Java 26 is not installed in the execution environment (JDK 21 is); an assumed toolchain would fail at first `mvn compile`. Rewrote targeting Java 21, Spring Boot 3.3.4, and the dependency set the approved plan called for. |
| 2 | `UrlService.createWithGeneratedCode` — two-phase insert | **Generated**, accepted as-is | Chosen specifically to avoid a collision-retry loop (see `docs/ARCHITECTURE.md` §4.1); validated by `UrlServiceTest.createsShortUrlWithGeneratedCode`. |
| 3 | Click counting: `mapping.recordClick(); repository.save(mapping)` | **Generated, then self-rejected before any test ran** | On review, recognized that the resolved `UrlMapping` could be a Caffeine cache hit — a shared object instance across concurrent redirect threads — making a non-atomic read-increment-write a lost-update race. Replaced with an atomic `@Modifying @Query` `UPDATE ... SET click_count = click_count + 1` (`UrlMappingRepository.incrementClickCount`), keyed only by `shortCode`, independent of any in-memory object. No test ever exercised the rejected version. |
| 4 | `resolveForRedirect` caching the whole method (lookup + expiry check together) | **Generated, then self-rejected before any test ran** | `@Cacheable` skips re-executing the method body on a cache hit; caching a method that also decides "is this expired" means a mapping cached while valid would keep returning `302` past its real `expiresAt` for up to the cache TTL. Split into `CachedUrlLookupService.findActiveByShortCode` (cached, no expiry logic — only the immutable-at-cache-time row) and `UrlService.resolveForRedirect` (uncached, re-checks `isExpired(Instant.now())` on every call). Directly validated by `UrlServiceTest.resolveForRedirectThrowsWhenExpired` and `RedirectIntegrationTest.returns410ForExpiredShortCode`. |
| 5 | `ClickEventRepository` day-bucketing via H2-specific `formatdatetime(...)` JPQL function | **Generated, then edited** | Dialect-coupled and fragile — would silently break on any database other than H2. Refactored to fetch raw `ClickEvent` rows in the lookback window and aggregate by day in Java (`AnalyticsService`, `DateTimeFormatter` + `Collectors.groupingBy`), portable across any JDBC target. |
| 6 | `AnalyticsResponse.totalClicks` sourced from `COUNT(*)` over `click_event` | **Generated, then edited** | That table is written asynchronously and best-effort; sourcing the headline total from it means the total itself inherits the async table's eventual-consistency and potential-loss characteristics. Re-sourced `totalClicks` from `UrlMapping.clickCount` (synchronous, atomic — see #3), leaving only the *breakdowns* (day/referrer/user-agent) on the async table. Documented as a deliberate split, not hidden, in `docs/ARCHITECTURE.md` §4.6 and `docs/RISKS.md`. |
| 7 | `shortCode` column `length = 32` | **Generated, then edited** | The two-phase-insert placeholder (`"~pending~" + UUID`) is ~45 characters and exceeded the original column bound, which would have thrown a data-truncation error at the exact moment a `saveAndFlush` ran. Caught by re-deriving the placeholder's actual length against the schema rather than assuming 32 was enough; widened to `length = 64` on both `UrlMapping.shortCode` and `ClickEvent.shortCode`. |
| 8 | `RateLimitInterceptor.preHandle` applied to all methods on `/api/v1/urls` | **Generated, then edited** | Spring's `addPathPatterns` matches by path only, not HTTP method — so `GET /api/v1/urls` (list) would have shared the same token bucket as `POST /api/v1/urls` (create), rate-limiting a read endpoint that was never meant to be protected. Added an explicit `if (!"POST".equalsIgnoreCase(request.getMethod())) return true;` guard. Validated by `RateLimitIntegrationTest.redirectEndpointIsNotRateLimited` (and, implicitly, by every list-endpoint call in `UrlApiIntegrationTest` not consuming rate-limit tokens). |
| 9 | `UrlServiceTest` mocking `ClickEventService` (a collaborator `UrlService` never actually called) | **Rejected, corrected** | Once `UrlService`'s constructor was changed (fix #4) to depend on `CachedUrlLookupService` instead, the existing test file no longer compiled (`incompatible types: ClickEventService cannot be converted to CachedUrlLookupService`). This was a genuine build break caught by `mvn test`, not a silent skip — fixed by updating the mock field, constructor call, and the three redirect-resolution test bodies to stub `cachedUrlLookupService.findActiveByShortCode(...)` directly. |
| 10 | `RateLimitIntegrationTest` — both test methods sharing one client key | **Generated, then edited after a real test failure** | `resolveClientKey` falls back to `request.getRemoteAddr()`, which MockMvc holds constant across requests within a test class. Because Spring reuses the application context (and therefore the singleton `RateLimiterRegistry`) across both test methods, one test's exhausted bucket carried into the next, regardless of run order — observed directly as `redirectEndpointIsNotRateLimited` failing with `expected 201 but was 429` on its very first request. Fixed by giving each test method a distinct synthetic `X-Forwarded-For` client key, isolating their buckets. |
| 11 | Documentation set (`README.md`, `docs/*.md`) | **Generated** from the final, tested source — not from the plan | Written last, after every test above passed and manual end-to-end verification (§3.5) completed, specifically so documented behavior is a description of what was verified rather than what was intended. |

## 5. Secure AI usage

- **No secrets, credentials, or external network calls were ever needed or used.** The prototype
  is entirely local (H2 file DB on local disk); nothing in scope required an API key, cloud
  resource, or third-party credential.
- **Dependency versions are pinned** (Spring Boot parent `3.3.4`, `springdoc-openapi` `2.6.0`) —
  no AI-suggested dependency was added at a floating/latest version.
- **Every shell command and file write executed by the AI was subject to the harness's
  permission model** — destructive or irreversible actions (there were none needed for this
  task) would have required explicit human approval; all actions taken were local,
  reversible file edits and local build/test/run commands.
- **Generated code was checked for the exact class of vulnerability the assignment calls out
  handling AI-assisted code for**: the `UrlValidator` SSRF guard and scheme allowlist exist
  specifically because a URL-shortener's core function (fetch/redirect based on user-supplied
  input) is a textbook SSRF and open-redirect surface, and that risk was treated as a
  first-class design requirement rather than an afterthought (`docs/RISKS.md` §"SSRF / open
  redirect").
- **No user-controlled input reaches a shell, a raw SQL string, or a template-rendering
  sink.** All persistence goes through parameterized Spring Data JPA queries; there is no
  string-concatenated SQL anywhere in the codebase.
- **IP addresses are hashed (SHA-256), never stored raw**, on the reasoning that analytics
  data is exactly the kind of dataset that shouldn't casually accumulate PII by default.

## 6. Human sign-off record

- **Plan approval**: the engineer reviewed the full implementation plan (stack, architecture,
  API surface, file list, verification steps) via the harness's plan-mode approval flow before
  any code was written.
- **Scope decisions approval**: the engineer explicitly selected all three scope-defining
  answers (Spring Boot 3 + H2; full feature set; full doc set in repo) rather than the AI
  defaulting to them unprompted.
- **Outstanding sign-off for this repository**: the engineer is expected to independently
  re-run `mvn test` and the manual curl walkthrough in `README.md` before treating this as
  final — this log documents what the AI did and verified, not a substitute for that
  independent check. Every fix in §4 is reproducible from the current source; none depends on
  taking the AI's word for the outcome.
