package kz.smarthub.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * A specialist declined an order. Remembered so automatic re-assignment never offers the same order to the same
 * specialist again, which keeps a rejected order from bouncing back or sitting unassigned forever.
 */
@Entity
@Table(name = "order_rejections", indexes = @Index(name = "idx_rejections_order", columnList = "order_id"))
public class OrderRejection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "order_id")
    private Order order;

    @ManyToOne(optional = false)
    @JoinColumn(name = "worker_id")
    private User worker;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public OrderRejection() {
    }

    public OrderRejection(Order order, User worker) {
        this.order = order;
        this.worker = worker;
    }

    public Long getId() {
        return id;
    }

    public Order getOrder() {
        return order;
    }

    public User getWorker() {
        return worker;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
