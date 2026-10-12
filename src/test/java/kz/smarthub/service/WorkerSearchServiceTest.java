package kz.smarthub.service;

import kz.smarthub.domain.Order;
import kz.smarthub.domain.OrderStatus;
import kz.smarthub.domain.Role;
import kz.smarthub.domain.ServiceCategory;
import kz.smarthub.domain.ServiceItem;
import kz.smarthub.domain.User;
import kz.smarthub.repository.OrderRepository;
import kz.smarthub.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Automatic specialist selection (UC-15): availability, district, rating, load, exclusions. */
class WorkerSearchServiceTest {

    private static final Long CATEGORY_ID = 1L;
    private static final LocalDateTime WHEN = LocalDateTime.of(2030, 5, 20, 10, 0);

    private UserRepository users;
    private OrderRepository orders;
    private WorkerSearchService search;
    private Order order;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        orders = mock(OrderRepository.class);
        search = new WorkerSearchService(users, orders);

        ServiceCategory category = new ServiceCategory("Жөндеу", "Ремонт");
        category.setId(CATEGORY_ID);
        ServiceItem item = new ServiceItem();
        item.setCategory(category);
        order = new Order();
        order.setId(50L);
        order.setService(item);
        order.setDistrict("CENTER");
        order.setScheduledTime(WHEN);
    }

    private User worker(long id, String district, double rating) {
        User w = new User();
        w.setId(id);
        w.setRole(Role.WORKER);
        w.setDistrict(district);
        w.setRatingAvg(rating);
        return w;
    }

    private void candidates(User... workers) {
        when(users.findByRoleAndSpecializationIdAndActiveTrueAndAvailableTrueOrderByIdAsc(Role.WORKER, CATEGORY_ID))
                .thenReturn(List.of(workers));
    }

    private void busy(long workerId) {
        when(orders.existsByWorkerIdAndStatusInAndScheduledTimeBetween(
                eq(workerId), anyCollection(), eq(WHEN.minusHours(2)), eq(WHEN.plusHours(2)))).thenReturn(true);
    }

    private void load(long workerId, long activeOrders) {
        when(orders.countByWorkerIdAndStatusIn(eq(workerId), anyCollection())).thenReturn(activeOrders);
    }

    @Test
    void noCandidatesMeansNoWorker() {
        candidates();
        assertTrue(search.findAvailableWorker(order).isEmpty());
    }

    @Test
    void prefersTheSameDistrictOverAHigherRating() {
        candidates(worker(1, "NORTH", 5.0), worker(2, "CENTER", 3.0));
        assertEquals(2L, search.findAvailableWorker(order).orElseThrow().getId());
    }

    @Test
    void withinADistrictTheBetterRatedSpecialistWins() {
        candidates(worker(1, "CENTER", 4.0), worker(2, "CENTER", 4.8));
        assertEquals(2L, search.findAvailableWorker(order).orElseThrow().getId());
    }

    @Test
    void withEqualDistrictAndRatingTheLessLoadedSpecialistWins() {
        candidates(worker(1, "CENTER", 4.5), worker(2, "CENTER", 4.5));
        load(1, 3);
        load(2, 1);
        assertEquals(2L, search.findAvailableWorker(order).orElseThrow().getId());
    }

    @Test
    void fullyTiedCandidatesResolveDeterministicallyById() {
        candidates(worker(7, "CENTER", 4.0), worker(3, "CENTER", 4.0));
        assertEquals(3L, search.findAvailableWorker(order).orElseThrow().getId());
    }

    @Test
    void aSpecialistWithAnOverlappingOrderIsSkipped() {
        candidates(worker(1, "CENTER", 5.0), worker(2, "SOUTH", 3.0));
        busy(1);
        assertEquals(2L, search.findAvailableWorker(order).orElseThrow().getId());
    }

    @Test
    void excludedSpecialistsAreNeverPickedAgain() {
        candidates(worker(1, "CENTER", 5.0), worker(2, "SOUTH", 3.0));
        Optional<User> found = search.findAvailableWorker(order, List.of(1L));
        assertEquals(2L, found.orElseThrow().getId());
    }

    @Test
    void whenEveryoneIsExcludedOrBusyThereIsNoWorker() {
        candidates(worker(1, "CENTER", 5.0), worker(2, "SOUTH", 3.0));
        busy(2);
        assertTrue(search.findAvailableWorker(order, List.of(1L)).isEmpty());
    }

    @Test
    void loadIsQueriedOncePerCandidateNotOncePerComparison() {
        candidates(worker(1, "CENTER", 4.0), worker(2, "CENTER", 4.0), worker(3, "CENTER", 4.0), worker(4, "CENTER", 4.0));
        search.findAvailableWorker(order);
        for (long id = 1; id <= 4; id++) {
            verify(orders, times(1)).countByWorkerIdAndStatusIn(eq(id), anyCollection());
        }
    }

    @Test
    void onlyActiveStatusesCountAsBusy() {
        assertEquals(List.of(OrderStatus.NEW, OrderStatus.ACCEPTED, OrderStatus.IN_PROGRESS), WorkerSearchService.ACTIVE);
    }
}
