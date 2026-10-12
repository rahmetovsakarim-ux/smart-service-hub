package kz.smarthub.integration;

import java.math.BigDecimal;

/** Сыртқы төлем шлюзімен интерфейс (Lab-4 Class диаграммасы). */
public interface PaymentGateway {

    PaymentResult charge(String cardNumber, BigDecimal amount, String orderRef);

    record PaymentResult(boolean success, String transactionRef, String message) {
    }
}
