package kz.smarthub.repository;

import kz.smarthub.domain.Role;
import kz.smarthub.domain.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByPhone(String phone);

    Optional<User> findByEmail(String email);

    boolean existsByPhone(String phone);

    boolean existsByEmail(String email);

    boolean existsBySpecializationId(Long categoryId);

    long countByRole(Role role);

    List<User> findAllByOrderByCreatedAtDesc();

    List<User> findByRoleOrderByCreatedAtDesc(Role role);

    List<User> findByRoleOrderByFullNameAsc(Role role);

    List<User> findByRoleAndActiveTrue(Role role);

    List<User> findByRoleAndSpecializationIdAndActiveTrue(Role role, Long specializationId);

    List<User> findByRoleAndSpecializationIdAndActiveTrueAndAvailableTrue(Role role, Long specializationId);

    /**
     * One aggregated row per category that has active specialists, in a single query (catalog cards):
     * {@code [categoryId, specialists accepting orders, sum(ratingAvg * ratingCount), sum(ratingCount)]}.
     * The weighted sum lets the caller compute the true average over every review of the category.
     */
    @Query("select u.specialization.id, sum(case when u.available = true then 1 else 0 end), "
            + "sum(u.ratingAvg * u.ratingCount), sum(u.ratingCount) "
            + "from User u where u.role = :role and u.active = true and u.specialization is not null "
            + "group by u.specialization.id")
    List<Object[]> specialistStatsByCategory(@Param("role") Role role);

    /**
     * Same candidates as above but row-locked (SELECT ... FOR UPDATE) and always in id order, so concurrent
     * assignments for one category queue up instead of both seeing the same specialist as free, and never deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<User> findByRoleAndSpecializationIdAndActiveTrueAndAvailableTrueOrderByIdAsc(Role role, Long specializationId);
}
