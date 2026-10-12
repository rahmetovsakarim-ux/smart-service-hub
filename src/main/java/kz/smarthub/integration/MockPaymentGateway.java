package kz.smarthub.integration;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Демо төлем шлюзі. Ережелер:
 *  - карта нөмірі 16 сан болмаса — қабылданбайды;
 *  - нөмір 4000-мен басталса — "қаражат жеткіліксіз" деп қабылданбайды (қайталау сценарийін тексеру үшін);
 *  - қалғаны — сәтті.
 */
@Component
public class MockPaymentGateway implements PaymentGateway {

    @Override
    public PaymentResult charge(String cardNumber, BigDecimal amount, String orderRef) {
        String digits = cardNumber == null ? "" : cardNumber.replaceAll("[\\s-]", "");
        if (!digits.matches("\\d{16}")) {
            return new PaymentResult(false, null, "Карта нөмірі қате (16 сан)||Неверный номер карты (16 цифр)");
        }
        if (digits.startsWith("4000")) {
            return new PaymentResult(false, null, "Қаражат жеткіліксіз, басқа картамен көріңіз||Недостаточно средств, попробуйте другую карту");
        }
        return new PaymentResult(true, "TX-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase(),
                "Төлем қабылданды||Платёж принят");
    }
}
