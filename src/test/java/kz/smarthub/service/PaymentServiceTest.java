package kz.smarthub.service;

import kz.smarthub.domain.Order;
import kz.smarthub.domain.OrderStatus;
import kz.smarthub.domain.Payment;
import kz.smarthub.domain.PaymentStatus;
import kz.smarthub.domain.User;
import kz.smarthub.exception.ApiException;
import kz.smarthub.integration.PaymentGateway;
import kz.smarthub.integration.PaymentGateway.PaymentResult;
import kz.smarthub.repository.OrderRepository;
import kz.smarthub.repository.PaymentRepository;
import kz.smarthub.web.dto.Dto.PaymentDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Payment rules: only the owner, only completed orders, never two live/successful payments for one order, and a
 * failing gateway must free the order for a retry instead of blocking it.
 */
class PaymentServiceTest {

    private static final Long ORDER_ID = 5L;

    private OrderRepository orders;
    private PaymentRepository payments;
    private PaymentGateway gateway;
    private NotificationService notifications;
    private PaymentService service;

    private final Map<Long, Payment> store = new HashMap<>();
    private final AtomicLong ids = new AtomicLong(100);
    private User client;
    private Order order;

    @BeforeEach
    void setUp() {
        orders = mock(OrderRepository.class);
        payments = mock(PaymentRepository.class);
        gateway = mock(PaymentGateway.class);
        notifications = mock(NotificationService.class);
        // A mocked transaction manager is enough: TransactionTemplate just calls getTransaction/commit on it.
        service = new PaymentService(orders, payments, gateway, notifications, mock(PlatformTransactionManager.class));

        client = new User();
        client.setId(1L);
        order = new Order();
        order.setId(ORDER_ID);
        order.setClient(client);
        order.setStatus(OrderStatus.COMPLETED);
        order.setPrice(new BigDecimal("8000"));
        when(orders.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));

        when(payments.saveAndFlush(any(Payment.class))).thenAnswer(inv -> persist(inv.getArgument(0)));
        when(payments.save(any(Payment.class))).thenAnswer(inv -> persist(inv.getArgument(0)));
        when(payments.findById(anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return Optional.ofNullable(store.get(id));
        });
    }

    private Payment persist(Payment p) {
        if (p.getId() == null) {
            p.setId(ids.incrementAndGet());
        }
        store.put(p.getId(), p);
        return p;
    }

    private static void assertStatus(HttpStatus expected, Runnable action) {
        ApiException e = assertThrows(ApiException.class, action::run);
        assertEquals(expected, e.getStatus());
    }

    private Payment livePayment(PaymentStatus status, LocalDateTime createdAt) {
        Payment p = new Payment();
        p.setId(99L);
        p.setOrder(order);
        p.setAmount(order.getPrice());
        p.setStatus(status);
        p.setActiveOrderId(ORDER_ID);
        p.setCreatedAt(createdAt);
        store.put(99L, p);
        when(payments.findByActiveOrderId(ORDER_ID)).thenReturn(Optional.of(p));
        return p;
    }

    @Test
    void successfulPaymentStoresOnePaidRecordAndNotifies() {
        when(gateway.charge(anyString(), any(BigDecimal.class), eq("ORD-5")))
                .thenReturn(new PaymentResult(true, "TX-1", "ok"));

        PaymentDto dto = service.pay(client, ORDER_ID, "4242 4242 4242 4242");

        assertEquals(PaymentStatus.PAID, dto.status());
        assertEquals("TX-1", dto.transactionRef());
        assertEquals(1, store.size());
        Payment p = store.values().iterator().next();
        assertEquals(PaymentStatus.PAID, p.getStatus());
        assertEquals(ORDER_ID, p.getActiveOrderId());
        assertEquals("4242", p.getCardLast4());
        assertEquals(0, new BigDecimal("8000").compareTo(p.getAmount()));
        verify(notifications).paid(order);
    }

    @Test
    void failedChargeFreesTheOrderForARetry() {
        when(gateway.charge(anyString(), any(BigDecimal.class), anyString()))
                .thenReturn(new PaymentResult(false, null, "declined"));

        PaymentDto dto = service.pay(client, ORDER_ID, "4000000000000002");

        assertEquals(PaymentStatus.FAILED, dto.status());
        Payment p = store.values().iterator().next();
        assertEquals(PaymentStatus.FAILED, p.getStatus());
        assertNull(p.getActiveOrderId(), "a failed attempt must release the unique slot");
        verify(notifications, never()).paid(any());
    }

    @Test
    void gatewayCrashIsReportedAsFailureAndAlsoFreesTheOrder() {
        when(gateway.charge(anyString(), any(BigDecimal.class), anyString())).thenThrow(new IllegalStateException("timeout"));

        PaymentDto dto = service.pay(client, ORDER_ID, "4242424242424242");

        assertEquals(PaymentStatus.FAILED, dto.status());
        assertNull(store.values().iterator().next().getActiveOrderId());
        verify(notifications, never()).paid(any());
    }

    @Test
    void alreadyPaidOrderIsRejectedWithoutCallingTheGateway() {
        livePayment(PaymentStatus.PAID, LocalDateTime.now().minusDays(1));

        assertStatus(HttpStatus.CONFLICT, () -> service.pay(client, ORDER_ID, "4242424242424242"));
        verify(gateway, never()).charge(any(), any(), any());
    }

    @Test
    void legacyPaidRecordWithoutGuardColumnIsStillRespected() {
        when(payments.existsByOrderIdAndStatus(ORDER_ID, PaymentStatus.PAID)).thenReturn(true);

        assertStatus(HttpStatus.CONFLICT, () -> service.pay(client, ORDER_ID, "4242424242424242"));
        verify(gateway, never()).charge(any(), any(), any());
    }

    @Test
    void secondAttemptWhileOneIsInFlightIsRejected() {
        livePayment(PaymentStatus.PENDING, LocalDateTime.now());

        assertStatus(HttpStatus.CONFLICT, () -> service.pay(client, ORDER_ID, "4242424242424242"));
        verify(gateway, never()).charge(any(), any(), any());
    }

    @Test
    void abandonedPendingPaymentIsSupersededAfterTheTimeout() {
        Payment stale = livePayment(PaymentStatus.PENDING,
                LocalDateTime.now().minus(PaymentService.PENDING_TTL).minusMinutes(1));
        when(gateway.charge(anyString(), any(BigDecimal.class), anyString()))
                .thenReturn(new PaymentResult(true, "TX-2", "ok"));

        PaymentDto dto = service.pay(client, ORDER_ID, "4242424242424242");

        assertEquals(PaymentStatus.PAID, dto.status());
        assertEquals(PaymentStatus.FAILED, stale.getStatus());
        assertNull(stale.getActiveOrderId());
        assertTrue(store.values().stream().anyMatch(p -> p.getStatus() == PaymentStatus.PAID));
    }

    @Test
    void onlyTheOwnerCanPay() {
        User stranger = new User();
        stranger.setId(2L);

        assertStatus(HttpStatus.FORBIDDEN, () -> service.pay(stranger, ORDER_ID, "4242424242424242"));
        verify(gateway, never()).charge(any(), any(), any());
    }

    @Test
    void onlyCompletedOrdersCanBePaid() {
        order.setStatus(OrderStatus.IN_PROGRESS);

        assertStatus(HttpStatus.BAD_REQUEST, () -> service.pay(client, ORDER_ID, "4242424242424242"));
        verify(gateway, never()).charge(any(), any(), any());
    }

    @Test
    void unknownOrderIsNotFound() {
        when(orders.findByIdForUpdate(404L)).thenReturn(Optional.empty());

        assertStatus(HttpStatus.NOT_FOUND, () -> service.pay(client, 404L, "4242424242424242"));
    }
}
