package com.pranshu.order_service.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Calls Inventory. Every reduction carries a reservation ID so it can be undone. */
@Component
public class InventoryClient {
    private static final Logger log = LoggerFactory.getLogger(InventoryClient.class);
    // Waits before each retry; release is idempotent, so repeating it is safe.
    private static final long[] RELEASE_RETRY_SECONDS = {1, 2, 4, 8, 16};

    private final RestClient restClient;
    private final ScheduledExecutorService releases = Executors.newScheduledThreadPool(4, task -> {
        Thread thread = new Thread(task, "reservation-release");
        thread.setDaemon(true);
        return thread;
    });

    public InventoryClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public void reserve(Long productId, Integer quantity, UUID reservationId) {
        restClient.put()
                .uri(uriBuilder -> uriBuilder
                        .path("/inventory/{productId}/reduce")
                        .queryParam("quantity", quantity)
                        .queryParam("reservationId", reservationId)
                        .build(productId))
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Gives reserved stock back without blocking the caller. Runs in the
     * background so a stalled Inventory cannot tie up request threads.
     * Not durable: if this process stops first, the reservation stays RESERVED
     * and is visible to reconciliation (no saved order has its ID).
     */
    public void releaseLater(UUID reservationId) {
        scheduleRelease(reservationId, 0);
    }

    private void scheduleRelease(UUID reservationId, int attempt) {
        long delay = attempt == 0 ? 0 : RELEASE_RETRY_SECONDS[attempt - 1];
        releases.schedule(() -> {
            try {
                restClient.post()
                        .uri("/inventory/reservations/{reservationId}/release", reservationId)
                        .retrieve()
                        .toBodilessEntity();
            } catch (RestClientException e) {
                if (attempt < RELEASE_RETRY_SECONDS.length) {
                    scheduleRelease(reservationId, attempt + 1);
                } else {
                    log.error("Reservation {} not released after {} attempts; needs reconciliation",
                            reservationId, attempt + 1, e);
                }
            }
        }, delay, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        releases.shutdown();
    }
}
