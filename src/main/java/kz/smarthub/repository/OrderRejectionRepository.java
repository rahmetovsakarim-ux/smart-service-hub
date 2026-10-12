package kz.smarthub.repository;

import kz.smarthub.domain.OrderRejection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OrderRejectionRepository extends JpaRepository<OrderRejection, Long> {

    @Query("select r.worker.id from OrderRejection r where r.order.id = :orderId")
    List<Long> findWorkerIdsByOrderId(@Param("orderId") Long orderId);
}
