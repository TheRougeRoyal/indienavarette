package com.veridion.client;

/** Exponential retry delay bounded to one minute. */
final class RetryBackoff {
    private int nextSeconds = 1;
    int failedAttemptDelaySeconds() {
        int delay = nextSeconds;
        nextSeconds = Math.min(nextSeconds * 2, 60);
        return delay;
    }
    void succeeded() { nextSeconds = 1; }
}
