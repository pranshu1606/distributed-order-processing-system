package com.pranshu.order_service.controller;

import com.pranshu.order_service.entity.Order;
import com.pranshu.order_service.exception.OrderNotFoundException;
import com.pranshu.order_service.service.OrderService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import com.pranshu.order_service.dto.OrderRequest;

import org.mockito.ArgumentCaptor;

import static org.mockito.Mockito.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;

@WebMvcTest(OrderController.class)
public class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @Test
    void getAllOrders_shouldReturnOrders() throws Exception {

        Order order = new Order(
                1L,
                101L,
                2,
                "CREATED"
        );

        when(orderService.getAllOrders())
                .thenReturn(List.of(order));

        mockMvc.perform(get("/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].productId").value(101))
                .andExpect(jsonPath("$[0].quantity").value(2))
                .andExpect(jsonPath("$[0].status").value("CREATED"));
    }

    @Test
    void createOrder_shouldReturnCreatedOrder() throws Exception {

        Order order = new Order(
                1L,
                101L,
                2,
                "CREATED"
        );

        when(orderService.createOrder(any(OrderRequest.class))).thenReturn(order);
        mockMvc.perform(
                        post("/orders")
                                .contentType("application/json")
                                .content("""
                                    {
                                        "productId": 101,
                                        "quantity": 2,
                                        "status": "CREATED"
                                    }
                                    """)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.productId").value(101))
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.status").value("CREATED"));
                ArgumentCaptor<OrderRequest> requestCaptor =
                        ArgumentCaptor.forClass(OrderRequest.class);
                verify(orderService).createOrder(requestCaptor.capture());
                OrderRequest capturedRequest = requestCaptor.getValue();
                assertEquals(Long.valueOf(101L), capturedRequest.getProductId());
                assertEquals(Integer.valueOf(2), capturedRequest.getQuantity());
    }

    @Test
    void createOrder_withMissingProductId_shouldReturnBadRequest()
            throws Exception {

        mockMvc.perform(
                        post("/orders")
                                .contentType("application/json")
                                .content("""
                                    {
                                        "quantity": 2
                                    }
                                    """)
                )
                .andExpect(status().isBadRequest());

        verifyNoInteractions(orderService);
    }

    @Test
    void createOrder_withNegativeQuantity_shouldReturnBadRequest()
            throws Exception {

        mockMvc.perform(
                        post("/orders")
                                .contentType("application/json")
                                .content("""
                                    {
                                        "productId": 101,
                                        "quantity": -5
                                    }
                                    """)
                )
                .andExpect(status().isBadRequest());

        verifyNoInteractions(orderService);
    }

    @Test
    void getOrder_shouldReturnOrder() throws Exception {

        Order order = new Order(
                1L,
                101L,
                2,
                "CREATED"
        );

        when(orderService.getOrder(1L))
                .thenReturn(order);

        mockMvc.perform(get("/orders/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.productId").value(101))
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.status").value("CREATED"));
    }
    @Test
    void getOrder_whenOrderDoesNotExist_shouldReturnNotFound()
            throws Exception {

        when(orderService.getOrder(9999L))
                .thenThrow(new OrderNotFoundException("Order not found"));

        mockMvc.perform(get("/orders/9999"))
                .andExpect(status().isNotFound());
    }
}