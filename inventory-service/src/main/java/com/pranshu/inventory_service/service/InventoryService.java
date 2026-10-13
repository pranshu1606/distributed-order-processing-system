package com.pranshu.inventory_service.service;

import com.pranshu.inventory_service.dto.InventoryRequest;
import com.pranshu.inventory_service.entity.Inventory;
import com.pranshu.inventory_service.exception.DuplicateProductException;
import com.pranshu.inventory_service.exception.InsufficientInventoryException;
import com.pranshu.inventory_service.exception.InvalidQuantityException;
import com.pranshu.inventory_service.exception.ProductNotFoundException;
import com.pranshu.inventory_service.exception.ReservationReleasedException;
import com.pranshu.inventory_service.repository.InventoryRepository;
import com.pranshu.inventory_service.repository.InventoryStockRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.UUID;

@Service
public class InventoryService {

    private final InventoryRepository inventoryRepository;
    private final InventoryStockRepository stockRepository;
    private final CacheManager cacheManager;

    public InventoryService(InventoryRepository inventoryRepository, InventoryStockRepository stockRepository,
                            CacheManager cacheManager) {
        this.inventoryRepository = inventoryRepository;
        this.stockRepository = stockRepository;
        this.cacheManager = cacheManager;
    }

    @Cacheable(cacheNames = "inventory", key = "#p0")
    public Inventory getInventory(Long productId) {
        return inventoryRepository
                .findByProductId(productId)
                .orElseThrow(() ->
                        new ProductNotFoundException("Product not found"));
    }

    public Inventory addInventory(InventoryRequest request) {
        if (inventoryRepository.existsByProductId(request.getProductId())) {
            throw new DuplicateProductException("Product already exists");
        }

        Inventory inventory = new Inventory();
        inventory.setProductId(request.getProductId());
        inventory.setProductName(request.getProductName());
        inventory.setQuantity(request.getQuantity());

        return inventoryRepository.save(inventory);
    }

    @Transactional
    @CacheEvict(cacheNames = "inventory", key = "#p0")
    public Inventory reduceInventory(Long productId, Integer quantity, UUID reservationId) {
        if (quantity == null || quantity <= 0) {
            throw new InvalidQuantityException("Quantity must be greater than 0");
        }

        // The reservation row and the stock change commit or roll back together.
        if (reservationId != null
                && !stockRepository.insertReservation(reservationId, productId, quantity, "RESERVED")) {
            if ("RESERVED".equals(stockRepository.findReservationStatus(reservationId).orElse(null))) {
                // A retry of a reduction that already succeeded: do not reduce twice.
                return inventoryRepository.findByProductId(productId)
                        .orElseThrow(() -> new ProductNotFoundException("Product not found"));
            }
            throw new ReservationReleasedException("Reservation already released");
        }

        var reduced = stockRepository.reduceIfAvailable(productId, quantity);
        if (reduced.isPresent()) {
            return reduced.get();
        }
        if (!inventoryRepository.existsByProductId(productId)) {
            throw new ProductNotFoundException("Product not found");
        }
        throw new InsufficientInventoryException("Not enough inventory");
    }

    /**
     * Gives reserved stock back exactly once. Safe to call repeatedly, and safe to
     * call before the reduction arrives: it then leaves a tombstone that refuses it.
     * Returns true only when stock was actually returned by this call.
     */
    @Transactional
    public boolean releaseReservation(UUID reservationId) {
        if (stockRepository.insertReservation(reservationId, null, null, "RELEASED")) {
            return false;
        }
        var released = stockRepository.markReleased(reservationId);
        if (released.isEmpty()) {
            return false;
        }
        stockRepository.restock(released.get().productId(), released.get().quantity());
        Cache cache = cacheManager.getCache("inventory");
        if (cache != null) {
            cache.evict(released.get().productId()); // transaction-aware: runs after commit
        }
        return true;
    }
}
