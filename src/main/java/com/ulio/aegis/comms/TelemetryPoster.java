package com.ulio.aegis.comms;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class TelemetryPoster implements AutoCloseable {
    private static final int LOG_RATE_LIMIT_MS = 5000;
    private static final int INITIAL_BACKOFF_MS = 250;
    private static final int MAX_BACKOFF_MS = 2000;

    private final URI telemetryEndpoint;
    private final String aegisToken;
    private final int timeoutMs;
    private final LinkedBlockingDeque<String> queue;
    private final HttpClient httpClient;
    private final Thread senderThread;
    private final AtomicBoolean running = new AtomicBoolean(true);

    private volatile long lastWarnLogMs;

    public TelemetryPoster(String backendUrl, String aegisToken, int timeoutMs, int queueMax) {
        String normalizedUrl = normalizeBackendUrl(backendUrl);
        this.telemetryEndpoint = URI.create(normalizedUrl + "/api/telemetry");
        this.aegisToken = aegisToken == null ? "" : aegisToken;
        this.timeoutMs = Math.max(1, timeoutMs);
        this.queue = new LinkedBlockingDeque<>(Math.max(1, queueMax));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(this.timeoutMs))
                .build();

        this.senderThread = new Thread(this::runSenderLoop, "aegis-telemetry-poster");
        this.senderThread.setDaemon(true);
        this.senderThread.start();
    }

    public void enqueue(String payload) {
        if (payload == null || !running.get()) {
            return;
        }

        if (queue.offerLast(payload)) {
            return;
        }

        queue.pollFirst();
        if (!queue.offerLast(payload)) {
            warnRateLimited("Telemetry POST queue full; dropping event");
        }
    }

    @Override
    public void close() {
        running.set(false);
        senderThread.interrupt();
        try {
            senderThread.join(750);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void runSenderLoop() {
        int backoffMs = INITIAL_BACKOFF_MS;

        while (running.get() || !queue.isEmpty()) {
            try {
                String payload = queue.poll(250, TimeUnit.MILLISECONDS);
                if (payload == null) {
                    continue;
                }

                boolean success = post(payload);
                if (success) {
                    backoffMs = INITIAL_BACKOFF_MS;
                } else {
                    sleepQuietly(backoffMs);
                    backoffMs = Math.min(MAX_BACKOFF_MS, backoffMs * 2);
                }
            } catch (InterruptedException e) {
                if (!running.get()) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } catch (Throwable e) {
                warnRateLimited("Telemetry POST sender failed: " + safeMessage(e));
                sleepQuietly(backoffMs);
                backoffMs = Math.min(MAX_BACKOFF_MS, backoffMs * 2);
            }
        }
    }

    private boolean post(String payload) {
        try {
            HttpRequest request = HttpRequest.newBuilder(telemetryEndpoint)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .header("X-Aegis-Token", aegisToken)
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200) {
                warnRateLimited("Telemetry POST failed: HTTP " + response.statusCode());
                return false;
            }
            return true;
        } catch (Throwable e) {
            warnRateLimited("Telemetry POST failed: " + safeMessage(e));
            return false;
        }
    }

    private String normalizeBackendUrl(String backendUrl) {
        if (backendUrl == null) {
            throw new IllegalArgumentException("backendUrl is null");
        }

        String trimmed = backendUrl.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("backendUrl is empty");
        }

        if (trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }

        return trimmed;
    }

    private void warnRateLimited(String message) {
        long now = System.currentTimeMillis();
        if (now - lastWarnLogMs >= LOG_RATE_LIMIT_MS) {
            lastWarnLogMs = now;
            System.err.println(message);
        }
    }

    private void sleepQuietly(int ms) {
        try {
            Thread.sleep(Math.max(1, ms));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }

        String compact = message.replace('\n', ' ').replace('\r', ' ');
        return compact.length() > 240 ? compact.substring(0, 240) : compact;
    }
}
