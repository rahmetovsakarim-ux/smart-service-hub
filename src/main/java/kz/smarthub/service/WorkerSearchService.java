package kz.smarthub.service;

import kz.smarthub.domain.Order;
import kz.smarthub.domain.OrderStatus;
import kz.smarthub.domain.Role;
import kz.smarthub.domain.User;
import kz.smarthub.repository.OrderRepository;
import kz.smarthub.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Маманды автоматты іздеу (UC-15, FR-06). Таңдау белгілері объективті (4.4 проблема):
 *  1) қызмет санатына сай мамандану, белсенді және "қолжетімді" белгісі қосулы;
 *  2) тапсырыс уақытына ±2 сағат аралығында бос;
 *  3) іріктеу реті: сол ауданда болу → жоғары рейтинг → аз жүктеме → id (тұрақты нәтиже үшін).
 *
 * <p>Concurrency: candidates are loaded with a row lock (see {@link UserRepository}), then the "is the specialist
 * free?" check runs. Two simultaneous orders for the same category therefore queue on the lock and the second one
 * sees the first one's assignment. Callers must run in a transaction using {@code READ_COMMITTED}, otherwise MySQL's
 * default REPEATABLE READ would serve the check from an older snapshot and miss the other transaction's commit.
 */
@Service
public class WorkerSearchService {

    public static final List<OrderStatus> ACTIVE =
            List.of(OrderStatus.NEW, OrderStatus.ACCEPTED, OrderStatus.IN_PROGRESS);

    private final UserRepository users;
    private final OrderRepository orders;

    public WorkerSearchService(UserRepository users, OrderRepository orders) {
        this.users = users;
        this.orders = orders;
    }

    public Optional<User> findAvailableWorker(Order order) {
        return findAvailableWorker(order, List.of());
    }

    /**
     * @param excludedWorkerIds specialists that must not be picked (e.g. those who already rejected this order)
     */
    @Transactional
    public Optional<User> findAvailableWorker(Order order, Collection<Long> excludedWorkerIds) {
        Long categoryId = order.getService().getCategory().getId();
        LocalDateTime from = order.getScheduledTime().minusHours(2);
        LocalDateTime to = order.getScheduledTime().plusHours(2);

        List<User> free = users
                .findByRoleAndSpecializationIdAndActiveTrueAndAvailableTrueOrderByIdAsc(Role.WORKER, categoryId)
                .stream()
                .filter(w -> !excludedWorkerIds.contains(w.getId()))
                .filter(w -> !orders.existsByWorkerIdAndStatusInAndScheduledTimeBetween(w.getId(), ACTIVE, from, to))
                .toList();
        if (free.isEmpty()) {
            return Optional.empty();
        }

        // Load is queried once per remaining candidate, not once per comparator call.
        Map<Long, Long> load = new HashMap<>();
        for (User w : free) {
            load.put(w.getId(), orders.countByWorkerIdAndStatusIn(w.getId(), ACTIVE));
        }
        Comparator<User> ranking = Comparator
                .comparingInt((User w) -> order.getDistrict().equals(w.getDistrict()) ? 0 : 1)
                .thenComparingDouble((User w) -> -w.getRatingAvg())
                .thenComparingLong((User w) -> load.get(w.getId()))
                .thenComparingLong(User::getId);
        return free.stream().min(ranking);
    }

    /** Әкімшіге қолмен тағайындау үшін ұсынылатын мамандар (сол санат, белсенді). */
    @Transactional(readOnly = true)
    public List<User> candidates(Order order) {
        Long categoryId = order.getService().getCategory().getId();
        return users.findByRoleAndSpecializationIdAndActiveTrue(Role.WORKER, categoryId);
    }
}
