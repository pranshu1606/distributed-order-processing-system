package com.pranshu.order_service.service;

import com.pranshu.order_service.dto.OrderRequest;
import com.pranshu.order_service.entity.Order;
import com.pranshu.order_service.exception.IdempotencyKeyReusedException;
import com.pranshu.order_service.exception.OrderNotFoundException;
import com.pranshu.order_service.exception.ServiceBusyException;
import com.pranshu.order_service.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private InventoryClient inventoryClient;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository, inventoryClient);
    }

    @Test
    void getOrder_whenOrderExists_shouldReturnOrder() {

        Order expectedOrder = new Order(
                1L,
                101L,
                2,
                "CREATED",
                null,
                null
        );

        when(orderRepository.findById(1L))
                .thenReturn(Optional.of(expectedOrder));

        Order actualOrder = orderService.getOrder(1L);

        assertSame(expectedOrder, actualOrder);
        verify(orderRepository).findById(1L);
        verifyNoInteractions(inventoryClient);
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
        verifyNoInteractions(inventoryClient);
    }

    @Test
    void getAllOrders_shouldReturnOrders() {

        Order firstOrder = new Order(1L, 101L, 2, "CREATED", null, null);
        Order secondOrder = new Order(2L, 102L, 1, "CREATED", null, null);

        List<Order> expectedOrders = List.of(firstOrder, secondOrder);

        when(orderRepository.findAll())
                .thenReturn(expectedOrders);

        List<Order> actualOrders = orderService.getAllOrders();

        assertEquals(expectedOrders, actualOrders);
        verify(orderRepository).findAll();
        verifyNoInteractions(inventoryClient);
    }

    @Test
    void createOrder_whenStockIsInsufficient_shouldRejectOrder() {
        doThrow(HttpClientErrorException.create(HttpStatus.CONFLICT, "Not enough inventory", null, new byte[0], null))
                .when(inventoryClient).reserve(eq(101L), eq(2), any(UUID.class));

        assertThrows(HttpClientErrorException.Conflict.class, () -> orderService.createOrder(request(101L, 2)));
        verifyNoInteractions(orderRepository);
        // Inventory rolled back, so there is nothing to release.
        verify(inventoryClient, never()).releaseLater(any());
    }

    @Test
    void createOrder_whenStockIsAvailable_shouldSaveOrderWithItsReservation() {
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Order createdOrder = orderService.createOrder(request(101L, 2));

        ArgumentCaptor<UUID> reservation = ArgumentCaptor.forClass(UUID.class);
        verify(inventoryClient).reserve(eq(101L), eq(2), reservation.capture());
        assertEquals(Long.valueOf(101L), createdOrder.getProductId());
        assertEquals(Integer.valueOf(2), createdOrder.getQuantity());
        assertEquals("CREATED", createdOrder.getStatus());
        assertEquals(reservation.getValue(), createdOrder.getReservationId());
        verify(inventoryClient, never()).releaseLater(any());
    }

    @Test
    void createOrder_whenInventoryTimesOut_shouldReleaseAndReportBusy() {
        doThrow(new ResourceAccessException("Read timed out"))
                .when(inventoryClient).reserve(eq(101L), eq(1), any(UUID.class));

        assertThrows(ServiceBusyException.class, () -> orderService.createOrder(request(101L, 1)));

        // The reduction may still commit, so the same reservation must be released.
        ArgumentCaptor<UUID> reserved = ArgumentCaptor.forClass(UUID.class);
        verify(inventoryClient).reserve(eq(101L), eq(1), reserved.capture());
        verify(inventoryClient).releaseLater(reserved.getValue());
        verifyNoInteractions(orderRepository);
    }

    @Test
    void createOrder_whenSavingFails_shouldReleaseReservedStock() {
        when(orderRepository.save(any(Order.class))).thenThrow(new DataIntegrityViolationException("save failed"));

        assertThrows(DataIntegrityViolationException.class, () -> orderService.createOrder(request(101L, 1)));

        ArgumentCaptor<UUID> reserved = ArgumentCaptor.forClass(UUID.class);
        verify(inventoryClient).reserve(eq(101L), eq(1), reserved.capture());
        verify(inventoryClient).releaseLater(reserved.getValue());
    }

    @Test
    void createOrder_whenKeyWasAlreadyUsed_shouldReturnThatOrderWithoutReservingStock() {
        Order placed = new Order(7L, 101L, 1, "CREATED", UUID.randomUUID(), "checkout-42");
        when(orderRepository.findByIdempotencyKey("checkout-42")).thenReturn(Optional.of(placed));

        assertSame(placed, orderService.createOrder(request(101L, 1), "checkout-42"));
        verifyNoInteractions(inventoryClient);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void createOrder_whenConcurrentRetrySavedFirst_shouldReleaseOwnStockAndReturnTheirOrder() {
        Order winner = new Order(7L, 101L, 1, "CREATED", UUID.randomUUID(), "checkout-42");
        when(orderRepository.findByIdempotencyKey("checkout-42"))
                .thenReturn(Optional.empty())       // checked before reserving
                .thenReturn(Optional.of(winner));   // after our save lost the unique-key race
        when(orderRepository.save(any(Order.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate idempotency_key"));

        assertSame(winner, orderService.createOrder(request(101L, 1), "checkout-42"));

        ArgumentCaptor<UUID> ours = ArgumentCaptor.forClass(UUID.class);
        verify(inventoryClient).reserve(eq(101L), eq(1), ours.capture());
        verify(inventoryClient).releaseLater(ours.getValue());
    }

    @Test
    void createOrder_whenKeyIsReusedForDifferentOrder_shouldReject() {
        Order placed = new Order(7L, 101L, 1, "CREATED", UUID.randomUUID(), "checkout-42");
        when(orderRepository.findByIdempotencyKey("checkout-42")).thenReturn(Optional.of(placed));

        assertThrows(IdempotencyKeyReusedException.class,
                () -> orderService.createOrder(request(101L, 5), "checkout-42"));
        verifyNoInteractions(inventoryClient);
    }

    private OrderRequest request(Long productId, Integer quantity) {
        OrderRequest request = new OrderRequest();
        request.setProductId(productId);
        request.setQuantity(quantity);
        return request;
    }
}
