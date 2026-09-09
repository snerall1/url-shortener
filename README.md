# URL Shortener

A production-quality URL shortener service, built as the vehicle for an AI-assisted software
engineering exercise. It runs as a single self-contained process and demonstrates a full feature set: short-link creation (generated
or custom alias), redirection, click analytics, TTL-based expiry, per-client rate limiting, and
operational endpoints (health, OpenAPI).

For the story behind *how* this was built — decomposition, AI-assisted execution with
traceability, validation, and risk analysis — see [`docs/`](docs/). This file covers what the
system does and how to run it.

## Quick start

Prerequisites: JDK 21, Maven (a wrapper is included — `./mvnw` / `mvnw.cmd` — invoke plain `mvn`
if you already have Maven 3.6+ installed).

```bash
mvn spring-boot:run
```

That's it. On first run this creates an embedded H2 database file under `./data/`, starts an
in-process cache and rate limiter, and listens on `http://localhost:8080`. There is nothing else
to install or configure. To use a different port: `mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=18080`.

Run the test suite:

```bash
mvn test
```

Explore the API interactively once running: `http://localhost:8080/swagger-ui/index.html`
(raw OpenAPI JSON at `/v3/api-docs`). Health check: `http://localhost:8080/actuator/health`.

## API

All endpoints are JSON over HTTP. Base path for management operations is `/api/v1/urls`;
redirection lives at the root path so short links stay short.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/urls` | Create a short URL (generated code or custom alias, optional TTL) |
| `GET` | `/api/v1/urls/{code}` | Fetch metadata + click count for a short code |
| `GET` | `/api/v1/urls?page=&size=` | Paged list of active short URLs |
| `DELETE` | `/api/v1/urls/{code}` | Soft-delete (deactivate) a short URL |
| `GET` | `/api/v1/urls/{code}/analytics` | Aggregated analytics for a short code |
| `GET` | `/{code}` | Redirect (302) to the original URL; records a click |

### Examples

Create a short URL with a generated code:

```bash
curl -s -X POST http://localhost:8080/api/v1/urls \
  -H 'Content-Type: application/json' \
  -d '{"longUrl":"https://example.com/very/long/path"}'
```

```json
{
  "shortCode": "rwramE",
  "shortUrl": "http://localhost:8080/rwramE",
  "longUrl": "https://example.com/very/long/path",
  "customAlias": false,
  "createdAt": "2026-09-09T17:40:38.285055800Z",
  "expiresAt": null,
  "lastAccessedAt": null,
  "clickCount": 0
}
```

Create with a custom alias and a 1-hour TTL:

```bash
curl -s -X POST http://localhost:8080/api/v1/urls \
  -H 'Content-Type: application/json' \
  -d '{"longUrl":"https://example.com/launch","customAlias":"launch-2026","ttlSeconds":3600}'
```

Follow the short link (302 redirect to the original URL):

```bash
curl -i http://localhost:8080/rwramE
```

Read analytics after some clicks:

```bash
curl -s http://localhost:8080/api/v1/urls/rwramE/analytics
```

```json
{
  "shortCode": "rwramE",
  "totalClicks": 3,
  "clicksByDay": [{"day": "2026-09-09", "count": 3}],
  "topReferrers": {"direct": 3},
  "topUserAgents": {"curl/8.7.1": 3}
}
```

Soft-delete a short URL:

```bash
curl -i -X DELETE http://localhost:8080/api/v1/urls/launch-2026
```

Error responses follow a consistent, RFC 7807-inspired shape across every failure mode
(validation, not-found, expired, conflict, rate-limited):

```json
{
  "timestamp": "2026-09-09T17:40:39.941847200Z",
  "status": 404,
  "error": "NOT_FOUND",
  "message": "No active URL mapping found for short code 'totally-unknown-code'",
  "path": "/totally-unknown-code",
  "details": null
}
```

| Status | `error` | When |
|---|---|---|
| 400 | `INVALID_URL` | Bad scheme, SSRF-guarded target, reserved alias, failed bean validation |
| 404 | `NOT_FOUND` | Unknown or already-deactivated short code |
| 409 | `ALIAS_CONFLICT` | Requested custom alias is already taken |
| 410 | `URL_EXPIRED` | Short code exists, is still active, but its TTL has passed |
| 429 | `RATE_LIMIT_EXCEEDED` | Per-client token bucket exhausted on `POST /api/v1/urls` (carries a `Retry-After` header) |

## Design summary

- **Stack**: Spring Boot 3.3 / Java 21 / Maven, H2 (file-backed) via Spring Data JPA, Caffeine for
  in-process caching, a hand-rolled in-process token-bucket rate limiter. No Redis, no message
  broker, no separate cache tier — everything needed to run this service ships in the one JVM
  process. See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for why, and what that trades away.
- **Short codes**: Base62-encoded database identity (with an XOR obfuscation mask), not random
  generate-and-retry. Collision-free by construction; the identity is never exposed unmasked.
- **Redirect hot path**: cached lookup (Caffeine, 5-minute TTL) decoupled from an always-fresh
  expiry check, and an atomic SQL increment for the click counter — so concurrent redirects can't
  lose click counts, and a cached hit still enforces TTL correctly. Click *detail* rows (referrer,
  user agent, hashed IP) are recorded asynchronously off the request thread.
- **Analytics**: click totals are authoritative and synchronous; breakdowns (by day, top
  referrer, top user agent) are best-effort and asynchronous. This is a deliberate trade-off —
  see [`docs/RISKS.md`](docs/RISKS.md).
- **Security**: scheme allowlist (`http`/`https` only) plus an SSRF guard rejecting private,
  loopback, and link-local targets; reserved-path protection so a custom alias can't shadow
  `/api`, `/actuator`, `/swagger-ui`, etc.; client IPs are SHA-256 hashed before being stored for
  analytics, never kept raw.
- **Lifecycle**: deletes are soft (an `active` flag), preserving click history; a scheduled job
  deactivates expired URLs and evicts idle rate-limit buckets so neither table nor in-memory map
  grows unbounded.

## Limitations (by design, for this exercise)

- **Single-node only.** The cache and rate limiter are in-process (`ConcurrentHashMap`-backed).
  Running more than one instance means each instance rate-limits and caches independently —
  fine for a prototype, wrong for a real multi-instance deployment (see `docs/RISKS.md` for the
  Redis-backed migration path).
- **No authentication/authorization.** Every endpoint is open. Out of scope for this exercise,
  but a real deployment needs at least API-key or OAuth2-based access control on the write path.
- **`ddl-auto: update`.** Convenient for a prototype; a real deployment needs versioned schema
  migrations (Flyway/Liquibase) and `ddl-auto: validate`.
- **Analytics breakdowns are best-effort.** Async click-detail recording can drop events under
  process crash between the synchronous counter update and the async write; the total click count
  itself is unaffected (it's written synchronously and atomically).

## Repository layout

```
src/main/java/org/example/urlshortener/
  controller/   REST endpoints + centralized exception → HTTP mapping
  service/      Business logic (creation, redirect resolution, analytics)
  repository/   Spring Data JPA repositories
  model/entity  JPA entities (UrlMapping, ClickEvent)
  model/dto     Request/response payloads
  ratelimit/    Token bucket + registry + interceptor
  scheduler/    Scheduled cleanup jobs
  config/       Cache, async, web, OpenAPI configuration
  exception/    Domain exceptions
  util/         Base62 encoding, URL/SSRF validation
docs/           Architecture, scenarios, AI-assisted engineering log, testing, risks
```
