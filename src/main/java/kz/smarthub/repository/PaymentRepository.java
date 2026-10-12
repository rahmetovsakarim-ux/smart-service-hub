package kz.smarthub.repository;

import kz.smarthub.domain.Payment;
import kz.smarthub.domain.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    boolean existsByOrderIdAndStatus(Long orderId, PaymentStatus status);

    /** The single PENDING/PAID payment of an order (guarded by a UNIQUE column), if any. */
    Optional<Payment> findByActiveOrderId(Long orderId);

    /** Batch lookup for order lists: ids (out of the given ones) that have a payment in the given status. */
    @Query("select distinct p.order.id from Payment p where p.status = :status and p.order.id in :orderIds")
    List<Long> findOrderIdsByStatus(@Param("orderIds") Collection<Long> orderIds, @Param("status") PaymentStatus status);

    @Query("select sum(p.amount) from Payment p where p.status = :status")
    BigDecimal sumByStatus(@Param("status") PaymentStatus status);
}
