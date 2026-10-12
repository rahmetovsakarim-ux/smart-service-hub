package kz.smarthub.repository;

import kz.smarthub.domain.Review;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    Optional<Review> findByOrderId(Long orderId);

    boolean existsByOrderId(Long orderId);

    /** Batch lookup for order lists. */
    List<Review> findByOrderIdIn(Collection<Long> orderIds);

    long countByWorkerId(Long workerId);

    List<Review> findByWorkerIdOrderByCreatedAtDesc(Long workerId);

    @Query("select avg(r.rating) from Review r where r.worker.id = :workerId")
    Double averageRating(@Param("workerId") Long workerId);
}
