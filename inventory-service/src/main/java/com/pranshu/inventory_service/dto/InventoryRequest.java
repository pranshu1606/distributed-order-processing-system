package com.pranshu.inventory_service.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class InventoryRequest {
    @NotNull
    private Long productId;

    @NotBlank
    private String productName;

    @NotNull
    @Min(0)
    private Integer quantity;
}
