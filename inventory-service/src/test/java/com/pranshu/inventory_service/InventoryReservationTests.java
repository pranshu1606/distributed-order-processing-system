package com.pranshu.inventory_service;

import com.pranshu.inventory_service.entity.Inventory;
import com.pranshu.inventory_service.exception.InsufficientInventoryException;
import com.pranshu.inventory_service.exception.ReservationReleasedException;
import com.pranshu.inventory_service.repository.InventoryRepository;
import com.pranshu.inventory_service.service.InventoryService;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

// Run only against disposable databases (see compose.test.yaml).
// These rules keep stock correct when Order times out, retries, or compensates.
@SpringBootTest(properties = "spring.jpa.show-sql=false")
class InventoryReservationTests {
    @Autowired InventoryService service;
    @Autowired InventoryRepository repository;
    @Autowired CacheManager cacheManager;

    @Test
    void retryingWithTheSameReservationReducesStockOnce() {
        withProduct(5, productId -> {
            UUID reservation = UUID.randomUUID();
            assertEquals(3, service.reduceInventory(productId, 2, reservation).getQuantity());
            assertEquals(3, service.reduceInventory(productId, 2, reservation).getQuantity());
            assertEquals(3, stock(productId));
        });
    }

    @Test
    void releaseReturnsStockExactlyOnceAndRefreshesCache() {
        withProduct(5, productId -> {
            UUID reservation = UUID.randomUUID();
            service.reduceInventory(productId, 2, reservation);
            assertEquals(3, service.getInventory(productId).getQuantity()); // cached

            assertTrue(service.releaseReservation(reservation));
            assertFalse(service.releaseReservation(reservation));
            assertEquals(5, stock(productId));
            assertNull(cacheManager.getCache("inventory").get(productId));
            assertEquals(5, service.getInventory(productId).getQuantity());
        });
    }

    @Test
    void releaseBeforeReductionRefusesTheLateReduction() {
        withProduct(5, productId -> {
            UUID reservation = UUID.randomUUID();
            assertFalse(service.releaseReservation(reservation));
            assertThrows(ReservationReleasedException.class,
                    () -> service.reduceInventory(productId, 1, reservation));
            assertEquals(5, stock(productId));
        });
    }

    @Test
    void rejectedReductionLeavesNothingToRelease() {
        withProduct(1, productId -> {
            UUID reservation = UUID.randomUUID();
            assertThrows(InsufficientInventoryException.class,
                    () -> service.reduceInventory(productId, 3, reservation));
            assertFalse(service.releaseReservation(reservation));
            assertEquals(1, stock(productId));
        });
    }

    @RepeatedTest(5)
    void racingReduceAndReleaseAlwaysRestoreStock() {
        withProduct(5, productId -> {
            UUID reservation = UUID.randomUUID();
            var executor = Executors.newFixedThreadPool(2);
            var start = new CountDownLatch(1);
            try {
                var reduce = executor.submit(() -> {
                    start.await();
                    try {
                        service.reduceInventory(productId, 2, reservation);
                    } catch (ReservationReleasedException refused) {
                        // release won the race and left a tombstone
                    }
                    return null;
                });
                var release = executor.submit(() -> {
                    start.await();
                    return service.releaseReservation(reservation);
                });
                start.countDown();
                reduce.get(30, TimeUnit.SECONDS);
                release.get(30, TimeUnit.SECONDS);
                // One release must be enough whichever side won the race.
                assertEquals(5, stock(productId));
            } catch (Exception e) {
                throw new AssertionError(e);
            } finally {
                executor.shutdownNow();
            }
        });
    }

    private void withProduct(int quantity, Consumer<Long> test) {
        Long productId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        Inventory row = repository.save(new Inventory(null, productId, "Reservation test", quantity));
        try {
            test.accept(productId);
        } finally {
            repository.deleteById(row.getId());
            cacheManager.getCache("inventory").evict(productId);
        }
    }

    private int stock(Long productId) {
        return repository.findByProductId(productId).orElseThrow().getQuantity();
    }
}
