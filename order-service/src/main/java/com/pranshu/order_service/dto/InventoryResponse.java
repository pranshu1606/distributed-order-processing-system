package com.pranshu.order_service.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class InventoryResponse {
    private Long id;
    private Long productId;
    private String productName;
    private Integer quantity;
}