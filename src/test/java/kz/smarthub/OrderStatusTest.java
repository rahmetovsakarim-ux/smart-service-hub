package kz.smarthub;

import kz.smarthub.domain.OrderStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FR-08: статус тек New → Accepted → In Progress → Completed тәртібімен өзгереді. */
class OrderStatusTest {

    @Test
    void chainGoesForwardOneStepAtATime() {
        assertTrue(OrderStatus.NEW.canMoveTo(OrderStatus.ACCEPTED));
        assertTrue(OrderStatus.ACCEPTED.canMoveTo(OrderStatus.IN_PROGRESS));
        assertTrue(OrderStatus.IN_PROGRESS.canMoveTo(OrderStatus.COMPLETED));
    }

    @Test
    void skippingOrGoingBackIsForbidden() {
        assertFalse(OrderStatus.NEW.canMoveTo(OrderStatus.COMPLETED));
        assertFalse(OrderStatus.ACCEPTED.canMoveTo(OrderStatus.COMPLETED));
        assertFalse(OrderStatus.COMPLETED.canMoveTo(OrderStatus.IN_PROGRESS));
        assertFalse(OrderStatus.NEW.canMoveTo(OrderStatus.NEW));
        assertFalse(OrderStatus.NEW.canMoveTo(null));
    }

    @Test
    void completedIsFinal() {
        assertNull(OrderStatus.COMPLETED.next());
        assertEquals(OrderStatus.ACCEPTED, OrderStatus.NEW.next());
    }
}
