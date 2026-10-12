package kz.smarthub.repository;

import kz.smarthub.domain.Order;
import kz.smarthub.domain.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long>, JpaSpecificationExecutor<Order> {

    // The entity graphs below fetch everything an OrderDto needs in the same query (no per-row secondary selects).
    @EntityGraph(attributePaths = {"client", "worker", "service", "service.category"})
    List<Order> findByClientIdOrderByCreatedAtDesc(Long clientId);

    @EntityGraph(attributePaths = {"client", "worker", "service", "service.category"})
    List<Order> findByWorkerIdOrderByCreatedAtDesc(Long workerId);

    @Override
    @EntityGraph(attributePaths = {"client", "worker", "service", "service.category"})
    Page<Order> findAll(Specification<Order> spec, Pageable pageable);

    /** Row lock: serialises concurrent operations on one order (e.g. two simultaneous payments). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from ServiceOrder o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);

    long countByWorkerIdAndStatusIn(Long workerId, Collection<OrderStatus> statuses);

    /** Маман көрсетілген уақыт аралығында бос емес пе (екі тапсырыс қабаттаспауы үшін). */
    boolean existsByWorkerIdAndStatusInAndScheduledTimeBetween(
            Long workerId, Collection<OrderStatus> statuses, LocalDateTime from, LocalDateTime to);

    long countByStatusAndWorkerIsNull(OrderStatus status);

    // ---- Есеп беру модулі (FR-13) ----
    @Query("select o.status, count(o) from ServiceOrder o group by o.status")
    List<Object[]> countGroupedByStatus();

    @Query("select o.worker.id, count(o) from ServiceOrder o where o.worker is not null group by o.worker.id")
    List<Object[]> countPerWorker();

    @Query("select o.worker.id, count(o) from ServiceOrder o "
            + "where o.worker is not null and o.status = :status group by o.worker.id")
    List<Object[]> countPerWorkerByStatus(@Param("status") OrderStatus status);

    @Query("select o.createdAt from ServiceOrder o where o.createdAt >= :from")
    List<LocalDateTime> findCreatedAtSince(@Param("from") LocalDateTime from);
}
