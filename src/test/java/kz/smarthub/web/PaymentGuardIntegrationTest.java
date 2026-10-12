package kz.smarthub.web;

import kz.smarthub.domain.Order;
import kz.smarthub.domain.Payment;
import kz.smarthub.domain.PaymentStatus;
import kz.smarthub.repository.OrderRepository;
import kz.smarthub.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The duplicate-payment guard must hold in the database itself, not just in application code: even if two requests
 * got past every check, the UNIQUE index on payments.active_order_id rejects the second live payment.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:guardtest;MODE=MySQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("h2")
class PaymentGuardIntegrationTest {

    @Autowired
    private PaymentRepository payments;
    @Autowired
    private OrderRepository orders;

    private Payment payment(Order order, PaymentStatus status, Long activeOrderId) {
        Payment p = new Payment();
        p.setOrder(order);
        p.setAmount(new BigDecimal("1000"));
        p.setStatus(status);
        p.setActiveOrderId(activeOrderId);
        return p;
    }

    @Test
    void theDatabaseRefusesASecondLivePaymentForTheSameOrder() {
        Order order = orders.findAll().stream()
                .filter(o -> payments.findByActiveOrderId(o.getId()).isEmpty())
                .findFirst().orElseThrow();
        List<Payment> created = new ArrayList<>();
        try {
            created.add(payments.saveAndFlush(payment(order, PaymentStatus.PAID, order.getId())));

            assertThatThrownBy(() -> payments.saveAndFlush(payment(order, PaymentStatus.PENDING, order.getId())))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            payments.deleteAll(created);
        }
    }

    @Test
    void anyNumberOfFailedAttemptsIsAllowed() {
        Order order = orders.findAll().stream()
                .filter(o -> payments.findByActiveOrderId(o.getId()).isEmpty())
                .findFirst().orElseThrow();
        List<Payment> created = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                created.add(payments.saveAndFlush(payment(order, PaymentStatus.FAILED, null)));
            }
            created.add(payments.saveAndFlush(payment(order, PaymentStatus.PAID, order.getId())));

            assertThat(created).hasSize(4);
        } finally {
            payments.deleteAll(created);
        }
    }
}
