package org.example.urlshortener.exception;

public class UrlExpiredException extends RuntimeException {

    public UrlExpiredException(String shortCode) {
        super("Short code '" + shortCode + "' has expired");
    }
}
