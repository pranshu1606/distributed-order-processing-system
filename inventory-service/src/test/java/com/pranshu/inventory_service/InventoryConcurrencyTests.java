package com.pranshu.inventory_service;

import com.pranshu.inventory_service.entity.Inventory;
import com.pranshu.inventory_service.exception.InsufficientInventoryException;
import com.pranshu.inventory_service.exception.InvalidQuantityException;
import com.pranshu.inventory_service.exception.ProductNotFoundException;
import com.pranshu.inventory_service.repository.InventoryRepository;
import com.pranshu.inventory_service.service.InventoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

// Run only against disposable databases (see compose.test.yaml).
// No test-level transaction: each worker must commit its own service transaction.
@SpringBootTest(properties = "spring.jpa.show-sql=false")
class InventoryConcurrencyTests {
    @Autowired InventoryService service;
    @Autowired InventoryRepository repository;
    @Autowired CacheManager cacheManager;
    @Autowired PlatformTransactionManager transactionManager;

    @RepeatedTest(3)
    void concurrentReductionsCannotSellMoreThanAvailableStock() throws Exception {
        Long productId = newProductId();
        Inventory row = repository.save(new Inventory(null, productId, "Concurrency test", 5));
        var executor = Executors.newFixedThreadPool(10);
        var ready = new CountDownLatch(10);
        var start = new CountDownLatch(1);
        var results = new ArrayList<Future<Boolean>>();
        try {
            // Populate Redis first: stock decisions must still use the locked database row.
            assertEquals(5, service.getInventory(productId).getQuantity());
            assertEquals(5, cacheManager.getCache("inventory").get(productId, Inventory.class).getQuantity());
            for (int i = 0; i < 10; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Workers did not start in time");
                    }
                    try {
                        service.reduceInventory(productId, 1, null);
                        return true;
                    } catch (InsufficientInventoryException expected) {
                        return false;
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            int successes = 0;
            for (var result : results) {
                if (result.get(30, TimeUnit.SECONDS)) successes++;
            }
            assertEquals(5, successes);
            assertEquals(0, repository.findByProductId(productId).orElseThrow().getQuantity());
            assertEquals(0, service.getInventory(productId).getQuantity());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
            repository.deleteById(row.getId());
            cacheManager.getCache("inventory").evict(productId);
        }
    }

    @Test
    void successfulReductionEvictsCachedStock() {
        Long productId = newProductId();
        Inventory row = repository.save(new Inventory(null, productId, "Cache test", 5));
        try {
            assertEquals(5, service.getInventory(productId).getQuantity());
            service.reduceInventory(productId, 2, null);
            assertNull(cacheManager.getCache("inventory").get(productId));
            assertEquals(3, service.getInventory(productId).getQuantity());
        } finally {
            repository.deleteById(row.getId());
            cacheManager.getCache("inventory").evict(productId);
        }
    }

    @Test
    void rollbackRestoresStockAndKeepsTheCachedValue() {
        Long productId = newProductId();
        Inventory row = repository.save(new Inventory(null, productId, "Rollback test", 5));
        try {
            assertEquals(5, service.getInventory(productId).getQuantity());
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                assertEquals(3, service.reduceInventory(productId, 2, null).getQuantity());
                status.setRollbackOnly();
            });
            // JDBC must share the service transaction; Redis eviction waits for commit.
            assertEquals(5, repository.findByProductId(productId).orElseThrow().getQuantity());
            assertEquals(5, cacheManager.getCache("inventory").get(productId, Inventory.class).getQuantity());
        } finally {
            repository.deleteById(row.getId());
            cacheManager.getCache("inventory").evict(productId);
        }
    }

    @Test
    void rejectedReductionsLeaveStockUnchanged() {
        Long productId = newProductId();
        Inventory row = repository.save(new Inventory(null, productId, "Validation test", 2));
        try {
            assertThrows(InvalidQuantityException.class, () -> service.reduceInventory(productId, null, null));
            assertThrows(InvalidQuantityException.class, () -> service.reduceInventory(productId, 0, null));
            assertThrows(InvalidQuantityException.class, () -> service.reduceInventory(productId, -1, null));
            assertThrows(InsufficientInventoryException.class, () -> service.reduceInventory(productId, 3, null));
            assertEquals(2, repository.findByProductId(productId).orElseThrow().getQuantity());
        } finally {
            repository.deleteById(row.getId());
        }
        assertThrows(ProductNotFoundException.class, () -> service.reduceInventory(productId, 1, null));
    }

    private Long newProductId() {
        return UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    }
}
