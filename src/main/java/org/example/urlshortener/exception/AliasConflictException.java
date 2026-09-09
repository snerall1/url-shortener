package org.example.urlshortener.exception;

public class AliasConflictException extends RuntimeException {

    public AliasConflictException(String alias) {
        super("Custom alias '" + alias + "' is already in use");
    }
}
