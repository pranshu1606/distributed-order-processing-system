package com.pranshu.inventory_service.service;

import com.pranshu.inventory_service.entity.Inventory;
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
                        new RuntimeException("Product not found"));
    }

    public Inventory addInventory(Inventory inventory) {
        return inventoryRepository.save(inventory);
    }

    public Inventory reduceInventory(Long productId, Integer quantity){
        Inventory inventory = inventoryRepository
                .findByProductId(productId)
                .orElseThrow(()->
                        new RuntimeException("Not enough inventory"));

        if(inventory.getQuantity()<0){
            throw new RuntimeException("not enough inventory");
        }
        inventory.setQuantity(inventory.getQuantity()-quantity);
        return inventoryRepository.save(inventory);
    }

}