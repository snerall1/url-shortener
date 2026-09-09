# Risks, Failure Scenarios, Guardrails, and Final Engineering Summary

## 1. Risks and failure scenarios

Each entry: the risk, the concrete scenario in which it bites, and the guardrail this build puts
in place (or explicitly does not, with rationale).

### 1.1 Single-node cache and rate limiter don't survive scaling out

**Scenario**: the service is deployed as two instances behind a load balancer for availability.
Each instance has its own Caffeine cache and its own `RateLimiterRegistry`. A client's requests
alternate between instances; their effective rate limit is now `2 × capacity`, not `capacity` —
and a code deleted on instance A can still serve a cached, stale `302` from instance B for up to
5 minutes.

**Guardrail in this build**: documented, not hidden — `README.md` and `docs/ARCHITECTURE.md` §4.7
both call this out explicitly as a single-node-only design. The cache and rate-limit code are
each isolated behind a narrow interface (`CachedUrlLookupService`, `RateLimiterRegistry`)
specifically so a real deployment can swap in a Redis-backed implementation of each without
touching `UrlService` or `RateLimitInterceptor` at all.

**Not fixed here because**: the execution environment for this exercise has no Docker and no
external services available; introducing a hard Redis dependency would make the prototype
unrunnable for whoever grades it, which is a worse outcome than an explicitly-documented
single-node limitation.

### 1.2 Analytics total and breakdown can disagree

**Scenario**: the process crashes (OOM, forced kill) between the synchronous click-count
`UPDATE` committing and the async `ClickEvent` insert completing. On restart,
`totalClicks` reflects the click that happened; `clicksByDay`/`topReferrers` do not.

**Guardrail**: this is a deliberate, bounded trade-off (`docs/ARCHITECTURE.md` §4.6), not an
oversight — the number every other part of the system treats as authoritative (`clickCount`) is
never at risk from this failure mode, only the descriptive breakdown is. Documented explicitly
here rather than left for someone to discover by noticing a mismatch.

**Residual risk**: if a caller assumes `sum(clicksByDay[*].count) == totalClicks` as an
invariant, they will occasionally be wrong. `docs/TESTING.md` and this document both name that
possibility so it isn't a silent surprise.

### 1.3 SSRF / open redirect

**Scenario**: a URL shortener is asked to redirect based entirely on user-supplied input — the
canonical SSRF/open-redirect risk shape. Without a guard, an attacker could submit
`http://169.254.169.254/latest/meta-data/` (cloud metadata endpoint) or
`http://10.0.0.5:6379/` (an internal service) as the "long URL," and any system that
follows/fetches short links server-side (not just a browser) becomes a proxy into the internal
network.

**Guardrail**: `UrlValidator` enforces an `http`/`https`-only scheme allowlist (rejecting
`javascript:`, `data:`, `file:`, `ftp:`, which have their own well-known abuse patterns) and
resolves the host to reject loopback, RFC 1918 private ranges, and link-local addresses at
*creation* time — the check runs before a malicious URL is ever stored, not just before it's
served.

**Residual risk, explicitly not solved**: DNS rebinding (a hostname that resolves to a public IP
at validation time and a private IP at redirect time) is not defended against, since this
service performs a client-side browser redirect (`302` with `Location:`) rather than a
server-side fetch — the SSRF surface for a pure redirect is smaller than for a URL-fetching
service, but a future feature like "preview the target page's title" would reopen it and would
need re-validation at fetch time, not just at creation time.

### 1.4 No authentication or authorization

**Scenario**: anyone can call `POST /api/v1/urls` (subject only to the rate limiter) and anyone
can call `DELETE /api/v1/urls/{code}` for any code, including one they didn't create.

**Guardrail**: none implemented — explicitly out of scope for this exercise, called out in
`README.md` and here rather than silently assumed. Rate limiting mitigates *volume* abuse
(spam-creating links) but does nothing for *authorization* (deleting/reading someone else's
link).

**What a real deployment needs**: at minimum, an API key or bearer-token scheme on write
endpoints (`POST`, `DELETE`), and an owner field on `UrlMapping` to scope `DELETE`/analytics
access to the creator. This is a contained addition (a new column, a new
`@PreAuthorize`/interceptor layer) given the current structure, not a rearchitecture.

### 1.5 Reserved-path collisions and routing ambiguity

**Scenario**: a custom alias request for `swagger-ui` or `actuator` would, if allowed, create a
short URL entry that can never actually be reached (the routing layer already owns that path),
silently wasting the alias and confusing whoever registered it.

**Guardrail**: `UrlValidator.isReservedPath` rejects these at creation time with a clear
`400 INVALID_URL` error naming the conflict, rather than allowing a dead alias to be created.

**Residual risk**: the reserved-path list (`api`, `actuator`, `swagger-ui`, `v3`, `h2-console`,
`favicon.ico`, `error`) is a static, manually-maintained set. Adding a new top-level route to the
application in the future requires remembering to add it here too — a real hardening would
derive this list from the actual registered `RequestMappingHandlerMapping` at startup instead of
hand-maintaining it.

### 1.6 Unbounded growth of in-memory or persisted state

**Scenario A**: every distinct client IP that ever calls `POST /api/v1/urls` gets an entry in
`RateLimiterRegistry`'s map, which — without cleanup — grows forever and is eventually a memory
leak / DoS vector in its own right.

**Scenario B**: TTL'd short URLs that expire are never revisited, so a reader has to
independently know to check `expiresAt` on every read, and the `active` flag never reflects
reality for expired-but-unvisited rows.

**Guardrail**: `CleanupScheduler` runs two independent scheduled jobs (`docs/ARCHITECTURE.md`
§4.8) — evicting idle rate-limit buckets and deactivating expired URLs — specifically to bound
both of these.

**Residual risk**: `click_event` itself has no retention policy — it grows forever, unbounded, by
design (this build makes no claim about long-term analytics retention). A real deployment needs a
rollup-and-purge strategy (e.g., collapse to daily aggregates after 90 days) before this table's
growth becomes an operational concern.

### 1.7 Prototype-grade schema management

**Scenario**: `ddl-auto: update` silently alters the schema to match the entity definitions on
every startup — convenient in development, dangerous in production (a bad entity change could
alter a live schema unexpectedly, with no migration history, no rollback path, and no review
step).

**Guardrail**: none beyond documentation — flagged explicitly in `application.yml`'s inline
comment, `README.md`, and here. A production deployment should move to Flyway or Liquibase with
`ddl-auto: validate` (schema drift becomes a startup failure, not a silent auto-migration).

### 1.8 Rate limiting is IP-based and spoofable

**Scenario**: `RateLimitInterceptor` trusts the first value in `X-Forwarded-For` if present,
falling back to `remoteAddr`. Behind a load balancer that doesn't strip/set this header
correctly, a client could set an arbitrary `X-Forwarded-For` value themselves and get a fresh
rate-limit bucket on every request.

**Guardrail**: acceptable for a prototype behind a trusted, correctly-configured proxy (the
common case); explicitly not hardened further here.

**What a real deployment needs**: only trust `X-Forwarded-For` when the request actually comes
from a known, trusted proxy/load balancer (verify the immediate peer address before trusting the
header), which is standard practice but was out of scope for a single-process prototype with no
reverse proxy in front of it in this environment.

## 2. Final engineering summary

*(Core Requirement 8 of the assignment: plan and rationale, artifacts produced, risks/trade-offs/
validation, assumptions, and limitations, in one place.)*

### 2.1 Plan and rationale

Built greenfield from an empty skeleton, retargeted to the actually-installed toolchain (Java 21,
no Docker), and scoped to run as a single process with zero external services (H2 file DB,
Caffeine cache, in-process token-bucket rate limiter) specifically so it can be cloned and run
with one command in any environment matching this one. Feature scope (full: creation with
generated/custom codes, redirect, analytics, TTL/expiry, rate limiting, structured errors,
OpenAPI, actuator health) and documentation scope (full doc set in-repo) were both explicit,
human-approved decisions, not AI-selected defaults — see
`docs/AI_ASSISTED_ENGINEERING.md` §6. Work was decomposed in dependency order (environment →
data model → short-code strategy → core CRUD → cross-cutting concerns → tests → docs), and two
concrete brownfield-style additions (custom alias, rate limiting) were layered onto the
greenfield core with explicit impacted-module analysis rather than broad rewrites —
`docs/SCENARIOS.md` walks through all three postures (greenfield/brownfield/ambiguous) in detail.

### 2.2 Artifacts produced

- **Application**: ~30 main source files across `controller/service/repository/model/ratelimit/
  scheduler/config/exception/util` packages implementing the full feature set above.
- **Tests**: 51 passing tests across 5 unit test classes and 3 integration test classes,
  covering every HTTP status code the API returns.
- **Configuration**: `application.yml` (runtime) and `application-test.yml` (test profile,
  isolated H2 instance per class, relaxed rate limits so unrelated tests aren't cross-affected).
- **Documentation**: this file, `docs/ARCHITECTURE.md`, `docs/SCENARIOS.md`,
  `docs/AI_ASSISTED_ENGINEERING.md`, `docs/TESTING.md`, and `README.md`.
- **Verified running artifact**: the application was started via `mvn spring-boot:run` and
  driven end-to-end with `curl` against every endpoint and error path documented in
  `README.md`, confirming the built artifact — not just its test suite — behaves as designed.

### 2.3 Risks, trade-offs, and how they were validated

Summarized in §1 above; each risk maps to either a passing automated test (`docs/TESTING.md`), a
manually-reproduced curl transcript (`README.md`), or an explicit, named limitation where no
guardrail was built (because doing so was out of scope for a zero-external-services prototype,
per §2.1). The two most consequential trade-offs — synchronous-total/async-breakdown analytics
(§1.2) and single-node cache/rate-limiting (§1.1) — were both caught and resolved *before* being
frozen into the design, and both are structured so the narrow interface they sit behind
(`AnalyticsService`, `CachedUrlLookupService`, `RateLimiterRegistry`) can absorb a future
production-hardening change without touching calling code.

### 2.4 Assumptions

- The grading/execution environment has JDK 21 and Maven available, and no Docker — this shaped
  every infrastructure decision in the build.
- A single-process deployment is an acceptable target for this exercise; a multi-instance
  production deployment is explicitly out of scope but designed to be reachable later (§1.1).
- Analytics dimensions (referrer, user agent, daily totals) are a reasonable normalization of an
  underspecified "analytics" requirement — see `docs/SCENARIOS.md` Scenario 3 for the full
  reasoning trail.
- No authentication is required for this exercise's evaluation; a real deployment would need it
  before going anywhere near production traffic (§1.4).

### 2.5 Limitations

Restated from `README.md` for completeness: single-node cache/rate-limiter, no auth,
`ddl-auto: update` instead of managed migrations, best-effort (not exactly-consistent) analytics
breakdowns, no concurrency/load testing, no automated CI smoke test of the running artifact (the
end-to-end verification was manual). None of these are silent — every one is named in at least
two places in this documentation set (usually `README.md` plus the relevant deep-dive doc) so a
reviewer doesn't have to infer them from the code.
