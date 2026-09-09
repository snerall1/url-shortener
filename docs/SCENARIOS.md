# Worked Scenarios

The assignment asks for three worked scenarios — greenfield, brownfield, and ambiguous — each
showing decomposition, execution, and validation. This build actually proceeded through all three
in sequence on the same codebase: a greenfield core, brownfield-style feature layering on top of
that core, and one requirement (analytics) that arrived underspecified and had to be normalized
before it could be decomposed at all. Each is described below as it actually happened.

---

## Scenario 1 — Greenfield: the core shorten/redirect service

**Starting point**: an empty skeleton (`Main.java` printing nothing useful, an unconfigured
`pom.xml` targeting a Java version that isn't installed). No existing conventions to respect, no
legacy behavior to preserve.

### Decomposition

Before writing code, the vague instruction "build a URL shortener" was normalized into a
dependency-ordered task list:

1. **Environment fix** (blocking everything else): retarget the build to Java 21 (the installed
   JDK), pick a persistence/cache/queue approach that needs zero external services (no Docker
   available) — H2 file DB, Caffeine cache. This had to happen before any code, since nothing
   else compiles or runs without it.
2. **Data model**: `UrlMapping` entity — what does a short URL *need* to record beyond the two
   obvious fields (code, target)? Decided at this step: creation time, optional expiry, active
   flag (for soft delete), synchronous click counter. Depends on nothing.
3. **Short code generation**: depends on (2) existing, specifically on the entity having a
   persisted identity to encode. Decided against random-generate-and-retry (see
   `docs/ARCHITECTURE.md` §4.1) up front, before writing the service layer, because the choice
   changes the creation flow's shape (two-phase insert vs. single insert).
4. **Service + controller for create/redirect**: depends on (2) and (3). The minimal viable
   surface: `POST /api/v1/urls`, `GET /{code}`.
5. **Error handling**: depends on (4) existing to know what can fail (not found, invalid input).
   Built as a `@RestControllerAdvice` from the start rather than ad hoc `try/catch` in each
   controller, since more failure modes were already known to be coming (rate limiting, expiry,
   alias conflicts) even though they weren't implemented yet.
6. **Tests**: unit tests for pure logic (encoder, validator) as those pieces were written;
   integration tests for the create→redirect→metadata flow once (4) stabilized.

### Execution

Implemented in the dependency order above. Two defects were caught and fixed *during* this phase,
before any test ran against them — see `docs/AI_ASSISTED_ENGINEERING.md` for the full
generated/edited/rejected trace:

- The click-counting approach was generated as a naive entity mutation
  (`mapping.recordClick(); repository.save(mapping)`) and rejected in favor of an atomic
  `UPDATE` query once the caching layer (Scenario 2) made it clear the mutated object could be a
  shared cache entry.
- The cache-and-expiry-check split (`docs/ARCHITECTURE.md` §4.2) was likewise caught before
  merging — an early draft cached the whole `resolveForRedirect` method including the expiry
  check, which would have silently kept expired links "alive" for up to 5 minutes.

### Validation

- Unit tests: `Base62EncoderTest` (round-trip encode/decode, boundary values),
  `ShortCodeGeneratorTest` (mask application, non-negative output).
- Integration test (`UrlApiIntegrationTest`): create → 201 with correct payload shape; get
  metadata → 200; list → paged; delete → 204 then 404 on subsequent redirect.
- Manual end-to-end via `mvn spring-boot:run` + curl (see `README.md`) confirmed the full loop
  with zero external services running.

---

## Scenario 2 — Brownfield: layering custom alias + rate limiting onto the existing core

**Starting point**: treat the Scenario 1 output — a working generated-code create/redirect
service with no alias support and no abuse protection — as an existing codebase that must not
regress. This is the brownfield posture: read what's there, identify exactly which modules a new
requirement touches, and avoid changing anything outside that footprint.

### Codebase reasoning (impacted-module analysis)

Before writing anything, the existing `createShortUrl` flow was read to determine where a custom
alias would plug in:

- `UrlService.createShortUrl` was the single entry point for creation — good, meant the change
  had one call site, not several duplicated ones.
- The existing generated-code path used a two-phase insert (placeholder → flush → derive code
  from ID). An alias doesn't need that: the caller already supplies the final code, so it can be
  validated and inserted directly. **Decision**: branch inside `createShortUrl` on whether
  `customAlias` is present, rather than forcing the alias path through the generated-code
  machinery (which would mean synthesizing a fake "identity-derived" step for no reason).
- `UrlMappingRepository` needed exactly one new method: `existsByShortCode`, to check availability
  before insert — cheaper than attempting an insert and catching a unique-constraint violation.
- `UrlValidator` (already existed for scheme/SSRF checks) was the natural home for a new
  `isReservedPath` check, rather than a new class, since it's the same category of concern
  (reject this input before it reaches persistence).
- **Nothing in the redirect path changes.** A short code is a short code once stored; the
  redirect/cache/click-counting code added in Scenario 1 needed zero modification. This was
  confirmed by re-reading `RedirectController` and `CachedUrlLookupService` and finding no
  assumption anywhere that a code must be system-generated.

The same analysis was repeated for rate limiting, layered in after alias support:

- New concern, not a modification of existing logic — implemented as a `HandlerInterceptor`
  (`RateLimitInterceptor`) registered in `WebConfig` against `/api/v1/urls`, rather than adding
  rate-limit checks inline in `UrlController`. This keeps the cross-cutting concern out of
  business logic entirely, and — critically — makes "which endpoints are protected" a
  configuration fact (`WebConfig`'s path pattern) instead of something buried in a method body.
- Read `WebConfig`'s existing interceptor registration (there wasn't one yet) and `UrlController`
  to confirm no endpoint-specific behavior would conflict.
- **Explicit exemption decision**: interceptor path pattern registration alone
  (`addPathPatterns("/api/v1/urls")`) does not distinguish HTTP methods, and `GET
  /api/v1/urls` (list) would have been rate-limited alongside `POST` if the interceptor didn't
  check the method explicitly. Caught by reasoning about the *existing* routing table, not by a
  test failure — added the explicit `if (!"POST".equalsIgnoreCase(...)) return true;` guard before
  writing the interceptor test, not after.

### Execution

- `UrlValidator.isReservedPath` + `UrlMappingRepository.existsByShortCode` +
  `UrlService.createWithCustomAlias` (new private method, existing `createShortUrl` branches into
  it) — additive, no existing method signature changed.
- `TokenBucket` (pure, synchronized, no Spring dependency — deliberately testable in isolation),
  `RateLimiterRegistry` (owns the `ConcurrentHashMap<clientKey, bucket>` and reads
  `app.rate-limit.*` config), `RateLimitInterceptor` (the only piece wired into `WebConfig`).

### Validation

- Regression check: re-ran the full Scenario 1 integration test (`UrlApiIntegrationTest`)
  unmodified — it still passed, confirming the brownfield addition didn't disturb the generated-
  code path.
- New tests scoped exactly to the addition: `rejectsCustomAliasAlreadyInUse`,
  `rejectsReservedCustomAlias` (unit, `UrlServiceTest`); `TokenBucketTest` (unit, pure bucket
  math); `RateLimitIntegrationTest` (integration — 429 after exhaustion with `Retry-After`
  header, and a dedicated assertion that the redirect endpoint is *not* rate-limited, directly
  testing the impacted-module boundary identified above).

---

## Scenario 3 — Ambiguous: normalizing "analytics"

**Starting point**: the assignment's requirement set calls for "AI-assisted execution" against
realistic, underspecified asks. Within this build, "expose analytics for a short URL" is exactly
that kind of ask — it names a feature, not a contract. Building against it directly (without
normalizing first) risks re-work: guessing at a shape, then discovering the real need was
different.

### Ambiguity, named explicitly

The raw requirement left every one of these open:
1. Analytics *for a single link*, or aggregate across all links?
2. A running total only, or a time series?
3. What dimensions matter — referrer? user agent? geography? device type?
4. Does this need to be exact/real-time, or is an eventually-consistent approximation acceptable?
5. How far back does history need to go?

### Decomposition (normalization decisions, made explicit rather than assumed)

Each open question was resolved to a concrete, bounded deliverable *before* writing the
`AnalyticsService`:

1. **Per-link**, scoped by `shortCode` — matches the granularity of everything else in the API
   (`/api/v1/urls/{code}/...`); an all-links rollup was out of scope (noted as a possible future
   endpoint, not built).
2. **Both**: a running total (`totalClicks`) *and* a bounded time series (`clicksByDay`) — a
   single number is close to useless for understanding traffic shape, but an unbounded time
   series is an unbounded response size. Resolved to a fixed 30-day lookback window
   (`AnalyticsService.LOOKBACK_DAYS`).
3. **Referrer and user agent**, top-10 each (`AnalyticsService.TOP_N`) — the two dimensions
   available "for free" from an HTTP request with zero extra client-side instrumentation.
   Geography/device-type would require either a GeoIP dependency or user-agent parsing library —
   explicitly deferred as scope creep for a prototype (noted in `docs/RISKS.md`).
4. **Eventually consistent for breakdowns, exact for the total.** This is the most consequential
   normalization decision and directly shaped the architecture
   (`docs/ARCHITECTURE.md` §4.6): the *count* that other endpoints and any billing/quota logic
   would rely on must never drift, so it's synchronous and atomic; the *breakdown* is allowed to
   lag by the async queue's processing time, in exchange for the redirect hot path never blocking
   on analytics writes.
5. **30 days**, chosen as a bounded, product-defensible default rather than "forever" (which
   turns the click-event table into an unbounded time-series store this prototype has no
   retention/rollup strategy for).

### Execution

`AnalyticsService.getAnalytics(shortCode)`: fetch the `UrlMapping` for `totalClicks`
(authoritative); fetch `ClickEvent` rows within the lookback window and aggregate in memory for
the day/referrer/user-agent breakdowns. `AnalyticsResponse` DTO shape mirrors exactly these five
resolved decisions — no field exists that wasn't traced back to one of them.

### Validation

- `RedirectIntegrationTest.redirectsAndRecordsAnalytics` exercises the full loop: redirect
  (synchronous count updates immediately, asserted with no wait) → analytics breakdown (asserted
  with `Awaitility.await()` against the async write, proving the eventual-consistency decision is
  both real and bounded in practice, not just in the docs).
- Manual curl verification (`README.md`) confirmed `totalClicks` was correct immediately after a
  redirect while the breakdown fields populated moments later — validating that the
  normalization decision (exact total, eventually-consistent breakdown) produces the intended
  user-visible behavior rather than just an internal implementation detail.
