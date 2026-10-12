package kz.smarthub.domain;

public enum PaymentStatus {
    /** Reserved before the gateway call; blocks a second concurrent attempt. */
    PENDING, PAID, FAILED
}
