package com.pranshu.inventory_service.controller;

import com.pranshu.inventory_service.entity.Inventory;
import com.pranshu.inventory_service.service.InventoryService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/inventory")
public class InventoryController {
    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService){
        this.inventoryService = inventoryService;
    }

    @GetMapping("/{productId}")
    public Inventory getInventory(@PathVariable Long productId){
        return inventoryService.getInventory(productId);
    }

    @PostMapping
    public Inventory addInventory(@RequestBody Inventory inventory) {
        return inventoryService.addInventory(inventory);
    }

    @PutMapping("/{productId}/reduce")
    public Inventory reduceInventory(@PathVariable Long productId, @RequestParam Integer quantity){
        return inventoryService.reduceInventory(productId,quantity);
    }
}
