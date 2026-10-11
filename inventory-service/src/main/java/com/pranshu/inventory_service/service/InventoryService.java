package com.pranshu.inventory_service.service;

import com.pranshu.inventory_service.dto.InventoryRequest;
import com.pranshu.inventory_service.entity.Inventory;
import com.pranshu.inventory_service.exception.DuplicateProductException;
import com.pranshu.inventory_service.exception.InsufficientInventoryException;
import com.pranshu.inventory_service.exception.InvalidQuantityException;
import com.pranshu.inventory_service.exception.ProductNotFoundException;
import com.pranshu.inventory_service.repository.InventoryRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CacheEvict;

@Service
public class InventoryService {

    private final InventoryRepository inventoryRepository;

    public InventoryService(InventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
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
    public Inventory reduceInventory(Long productId, Integer quantity) {
        if (quantity == null || quantity <= 0) {
            throw new InvalidQuantityException("Quantity must be greater than 0");
        }

        Inventory inventory = inventoryRepository
                .findByProductIdForUpdate(productId)
                .orElseThrow(() ->
                        new ProductNotFoundException("Product not found"));
        if (inventory.getQuantity() < quantity) {
            throw new InsufficientInventoryException("Not enough inventory");
        }
        inventory.setQuantity(
                inventory.getQuantity() - quantity
        );

        return inventoryRepository.save(inventory);
    }
}
