package com.pranshu.inventory_service.controller;

import java.util.Map;
import java.util.UUID;

import com.pranshu.inventory_service.dto.InventoryRequest;
import com.pranshu.inventory_service.entity.Inventory;
import com.pranshu.inventory_service.service.InventoryService;
import jakarta.validation.Valid;
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
    public Inventory addInventory(@Valid @RequestBody InventoryRequest request) {
        return inventoryService.addInventory(request);
    }

    @PutMapping("/{productId}/reduce")
    public Inventory reduceInventory(@PathVariable Long productId, @RequestParam Integer quantity,
                                     @RequestParam(required = false) UUID reservationId){
        return inventoryService.reduceInventory(productId, quantity, reservationId);
    }

    @PostMapping("/reservations/{reservationId}/release")
    public Map<String, Boolean> releaseReservation(@PathVariable UUID reservationId) {
        return Map.of("released", inventoryService.releaseReservation(reservationId));
    }
}
