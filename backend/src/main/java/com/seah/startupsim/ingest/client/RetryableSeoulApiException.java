package com.seah.startupsim.ingest.client;

public class RetryableSeoulApiException extends SeoulApiException {
    public RetryableSeoulApiException(String message) {
        super(message);
    }
}
