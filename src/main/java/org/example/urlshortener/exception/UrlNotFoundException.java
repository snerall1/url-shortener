package org.example.urlshortener.exception;

public class UrlNotFoundException extends RuntimeException {

    public UrlNotFoundException(String shortCode) {
        super("No active URL mapping found for short code '" + shortCode + "'");
    }
}
