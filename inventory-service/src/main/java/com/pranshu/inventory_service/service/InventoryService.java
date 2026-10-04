package com.pranshu.inventory_service.service;

import com.pranshu.inventory_service.entity.Inventory;
import com.pranshu.inventory_service.exception.InsufficientInventoryException;
import com.pranshu.inventory_service.exception.ProductNotFoundException;
import com.pranshu.inventory_service.repository.InventoryRepository;
import org.springframework.stereotype.Service;

@Service
public class InventoryService {

    private final InventoryRepository inventoryRepository;

    public InventoryService(InventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }

    public Inventory getInventory(Long productId) {
        return inventoryRepository
                .findByProductId(productId)
                .orElseThrow(() ->
                        new ProductNotFoundException("Product not found"));
    }

    public Inventory addInventory(Inventory inventory) {
        return inventoryRepository.save(inventory);
    }

    public Inventory reduceInventory(Long productId, Integer quantity) {

        Inventory inventory = inventoryRepository
                .findByProductId(productId)
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