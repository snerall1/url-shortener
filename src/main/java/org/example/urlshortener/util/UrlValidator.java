package org.example.urlshortener.util;

import org.example.urlshortener.exception.InvalidUrlException;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Set;

/**
 * Validates that a submitted long URL is well-formed and safe to store/redirect to.
 * <p>
 * Two concerns are handled here, deliberately kept separate from business logic:
 * <ol>
 *   <li>Syntax + scheme allowlist (http/https only -- rejects javascript:, file:, data: etc,
 *       which would otherwise turn the redirect endpoint into an XSS/local-file-read vector).</li>
 *   <li>A best-effort SSRF guard: refuses to shorten URLs that resolve to loopback, link-local,
 *       or private RFC1918 addresses, so this service cannot be used to make an internal
 *       network scanning/pivoting tool out of the public redirect endpoint.</li>
 * </ol>
 * This is a defense-in-depth control appropriate for a prototype, not a substitute for network
 * level egress controls in production (see docs/RISKS.md).
 */
public final class UrlValidator {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final Set<String> RESERVED_PATHS = Set.of(
            "api", "actuator", "swagger-ui", "v3", "h2-console", "favicon.ico", "error");

    private UrlValidator() {
    }

    public static void validate(String rawUrl) {
        URI uri;
        try {
            uri = new URI(rawUrl);
        } catch (URISyntaxException e) {
            throw new InvalidUrlException("longUrl is not a valid URI: " + e.getMessage());
        }

        if (uri.getScheme() == null || !ALLOWED_SCHEMES.contains(uri.getScheme().toLowerCase())) {
            throw new InvalidUrlException("longUrl must use http or https scheme");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new InvalidUrlException("longUrl must include a host");
        }

        String host = IDN.toASCII(uri.getHost());
        checkNotPrivateOrLoopback(host);
    }

    private static void checkNotPrivateOrLoopback(String host) {
        InetAddress address;
        try {
            address = InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            // DNS may legitimately fail for hosts that resolve later, in restricted test
            // networks, or in unit tests using example domains; we don't hard-fail resolution
            // errors, only proven-unsafe targets.
            return;
        }
        if (address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isAnyLocalAddress()) {
            throw new InvalidUrlException("longUrl resolves to a private/internal address and is not allowed");
        }
    }

    public static boolean isReservedPath(String code) {
        return RESERVED_PATHS.contains(code.toLowerCase());
    }
}
