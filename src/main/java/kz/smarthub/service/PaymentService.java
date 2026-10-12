package kz.smarthub.service;

import kz.smarthub.domain.Order;
import kz.smarthub.domain.OrderStatus;
import kz.smarthub.domain.Payment;
import kz.smarthub.domain.PaymentStatus;
import kz.smarthub.domain.User;
import kz.smarthub.exception.ApiException;
import kz.smarthub.integration.PaymentGateway;
import kz.smarthub.repository.OrderRepository;
import kz.smarthub.repository.PaymentRepository;
import kz.smarthub.web.dto.Dto.PaymentDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Төлем жасау (UC-07, FR-11): сыртқы төлем шлюзі арқылы, нәтиже тіркеледі; сәтсіз болса қайталауға болады.
 *
 * <p>Three short steps instead of one long transaction, so a slow gateway never holds a database connection:
 * <ol>
 *   <li><b>reserve</b> (own transaction): lock the order row, validate, insert a PENDING payment. The
 *       {@code active_order_id} UNIQUE column makes a second concurrent payment for the same order impossible at
 *       the database level;</li>
 *   <li><b>charge</b> (no transaction): call the gateway;</li>
 *   <li><b>settle</b> (own transaction): mark the payment PAID or FAILED and notify.</li>
 * </ol>
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    /** A PENDING payment older than this is considered abandoned (e.g. crash mid-charge) and may be superseded. */
    static final Duration PENDING_TTL = Duration.ofMinutes(5);

    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final PaymentGateway gateway;
    private final NotificationService notifications;
    private final TransactionTemplate tx;

    public PaymentService(OrderRepository orders, PaymentRepository payments, PaymentGateway gateway,
                          NotificationService notifications, PlatformTransactionManager txManager) {
        this.orders = orders;
        this.payments = payments;
        this.gateway = gateway;
        this.notifications = notifications;
        this.tx = new TransactionTemplate(txManager);
    }

    public PaymentDto pay(User client, Long orderId, String cardNumber) {
        Reservation reservation = tx.execute(status -> reserve(client, orderId, cardNumber));

        PaymentGateway.PaymentResult result;
        try {
            result = gateway.charge(cardNumber, reservation.amount(), "ORD-" + orderId);
        } catch (RuntimeException e) {
            log.error("Payment gateway failed for order {}", orderId, e);
            result = new PaymentGateway.PaymentResult(false, null,
                    "Төлем жүйесі уақытша қолжетімсіз, кейінірек көріңіз||Платёжная система временно недоступна, попробуйте позже");
        }

        PaymentGateway.PaymentResult outcome = result;
        return tx.execute(status -> settle(reservation.paymentId(), outcome));
    }

    private Reservation reserve(User client, Long orderId, String cardNumber) {
        Order o = orders.findByIdForUpdate(orderId)
                .orElseThrow(() -> ApiException.notFound("Тапсырыс табылмады||Заказ не найден"));
        if (!o.getClient().getId().equals(client.getId())) {
            throw ApiException.forbidden("Бұл сіздің тапсырысыңыз емес||Это не ваш заказ");
        }
        if (o.getStatus() != OrderStatus.COMPLETED) {
            throw ApiException.badRequest("Төлем тек аяқталған тапсырысқа жасалады||Оплата доступна только для завершённого заказа");
        }

        Optional<Payment> live = payments.findByActiveOrderId(orderId);
        if (live.isPresent()) {
            Payment p = live.get();
            if (p.getStatus() == PaymentStatus.PAID) {
                throw ApiException.conflict("Тапсырыс төленіп қойған||Заказ уже оплачен");
            }
            if (p.getCreatedAt().isAfter(LocalDateTime.now().minus(PENDING_TTL))) {
                throw ApiException.conflict("Төлем өңделуде, күте тұрыңыз||Платёж обрабатывается, подождите");
            }
            // abandoned PENDING: release it so the order can be paid again
            p.setStatus(PaymentStatus.FAILED);
            p.setActiveOrderId(null);
            payments.saveAndFlush(p);
        }
        if (payments.existsByOrderIdAndStatus(orderId, PaymentStatus.PAID)) {
            // PAID rows created before active_order_id existed are not covered by the UNIQUE column
            throw ApiException.conflict("Тапсырыс төленіп қойған||Заказ уже оплачен");
        }

        String digits = cardNumber == null ? "" : cardNumber.replaceAll("[^0-9]", "");
        Payment p = new Payment();
        p.setOrder(o);
        p.setAmount(o.getPrice());
        p.setStatus(PaymentStatus.PENDING);
        p.setActiveOrderId(orderId);
        p.setCardLast4(digits.length() >= 4 ? digits.substring(digits.length() - 4) : null);
        payments.saveAndFlush(p);
        return new Reservation(p.getId(), o.getPrice());
    }

    private PaymentDto settle(Long paymentId, PaymentGateway.PaymentResult result) {
        Payment p = payments.findById(paymentId)
                .orElseThrow(() -> ApiException.notFound("Төлем табылмады||Платёж не найден"));
        if (p.getStatus() != PaymentStatus.PENDING) {
            // the reservation was superseded while the gateway call was running (see PENDING_TTL)
            log.warn("Payment {} was no longer PENDING when the gateway answered (success={})", paymentId, result.success());
            throw ApiException.conflict("Төлем күйі өзгерген, тапсырыс күйін тексеріңіз||Статус платежа изменился, проверьте статус заказа");
        }
        if (result.success()) {
            p.setStatus(PaymentStatus.PAID);
            p.setTransactionRef(result.transactionRef());
            payments.save(p);
            notifications.paid(p.getOrder());
        } else {
            p.setStatus(PaymentStatus.FAILED);
            p.setActiveOrderId(null); // frees the UNIQUE slot so the client can retry
            payments.save(p);
        }
        return new PaymentDto(p.getStatus(), p.getTransactionRef(), result.message());
    }

    private record Reservation(Long paymentId, BigDecimal amount) {
    }
}
