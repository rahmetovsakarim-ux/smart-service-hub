package kz.smarthub.repository;

import kz.smarthub.domain.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findTop50ByUserIdOrderByCreatedAtDesc(Long userId);

    long countByUserIdAndSeenFalse(Long userId);

    @Modifying(clearAutomatically = true)
    @Query("update Notification n set n.seen = true where n.user.id = :userId and n.seen = false")
    int markAllSeen(@Param("userId") Long userId);
}
