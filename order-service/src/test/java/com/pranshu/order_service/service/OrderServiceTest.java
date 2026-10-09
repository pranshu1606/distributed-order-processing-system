package com.pranshu.order_service.service;

import com.pranshu.order_service.entity.Order;
import com.pranshu.order_service.exception.OrderNotFoundException;
import com.pranshu.order_service.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.function.Function;
import org.springframework.web.util.UriBuilder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pranshu.order_service.dto.OrderRequest;
import com.pranshu.order_service.exception.InsufficientInventoryException;

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.doReturn;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private RestClient restClient;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository, restClient);
    }

    @Test
    void getOrder_whenOrderExists_shouldReturnOrder() {

        Order expectedOrder = new Order(
                1L,
                101L,
                2,
                "CREATED"
        );

        when(orderRepository.findById(1L))
                .thenReturn(Optional.of(expectedOrder));

        Order actualOrder = orderService.getOrder(1L);

        assertSame(expectedOrder, actualOrder);
        verify(orderRepository).findById(1L);
        verifyNoInteractions(restClient);
    }
    @Test
    void getOrder_whenOrderDoesNotExist_shouldThrowException() {

        when(orderRepository.findById(9999L))
                .thenReturn(Optional.empty());

        OrderNotFoundException exception = assertThrows(
                OrderNotFoundException.class,
                () -> orderService.getOrder(9999L)
        );

        assertEquals("Order Not Found", exception.getMessage());
        verify(orderRepository).findById(9999L);
        verifyNoInteractions(restClient);
    }

    @Test
    void getAllOrders_shouldReturnOrders() {

        Order firstOrder = new Order(1L, 101L, 2, "CREATED");
        Order secondOrder = new Order(2L, 102L, 1, "CREATED");

        List<Order> expectedOrders = List.of(firstOrder, secondOrder);

        when(orderRepository.findAll())
                .thenReturn(expectedOrders);

        List<Order> actualOrders = orderService.getAllOrders();

        assertEquals(expectedOrders, actualOrders);
        verify(orderRepository).findAll();
        verifyNoInteractions(restClient);
    }

    @Test
    void createOrder_whenStockIsInsufficient_shouldRejectOrder() {

        OrderRequest request = new OrderRequest();
        request.setProductId(101L);
        request.setQuantity(2);

        OrderService serviceSpy = spy(orderService);

        doReturn(1)
                .when(serviceSpy)
                .getInventoryQuantity(101L);

        InsufficientInventoryException exception = assertThrows(
                InsufficientInventoryException.class,
                () -> serviceSpy.createOrder(request)
        );

        assertEquals("Not enough inventory", exception.getMessage());

        verifyNoInteractions(orderRepository);
        verifyNoInteractions(restClient);
    }

    @Test
    void createOrder_whenStockIsAvailable_shouldSaveOrder() {

        OrderRequest request = new OrderRequest();
        request.setProductId(101L);
        request.setQuantity(2);

        OrderService serviceSpy = spy(orderService);

        doReturn(10)
                .when(serviceSpy)
                .getInventoryQuantity(101L);

        RestClient.RequestBodyUriSpec uriSpec =
                mock(RestClient.RequestBodyUriSpec.class);

        RestClient.RequestBodySpec bodySpec =
                mock(RestClient.RequestBodySpec.class);

        RestClient.ResponseSpec responseSpec =
                mock(RestClient.ResponseSpec.class);

        when(restClient.put()).thenReturn(uriSpec);

        when(uriSpec.uri(
                org.mockito.ArgumentMatchers
                        .<Function<UriBuilder, URI>>any()
        )).thenReturn(bodySpec);

        when(bodySpec.retrieve()).thenReturn(responseSpec);

        when(orderRepository.save(any(Order.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Order createdOrder = serviceSpy.createOrder(request);

        assertEquals(Long.valueOf(101L), createdOrder.getProductId());
        assertEquals(Integer.valueOf(2), createdOrder.getQuantity());
        assertEquals("CREATED", createdOrder.getStatus());

        verify(restClient).put();
        verify(responseSpec).toBodilessEntity();
        verify(orderRepository).save(createdOrder);
    }
}