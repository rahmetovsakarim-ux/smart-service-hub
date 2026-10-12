package kz.smarthub.service;

import kz.smarthub.domain.Order;
import kz.smarthub.domain.OrderRejection;
import kz.smarthub.domain.OrderStatus;
import kz.smarthub.domain.Role;
import kz.smarthub.domain.ServiceCategory;
import kz.smarthub.domain.ServiceItem;
import kz.smarthub.domain.User;
import kz.smarthub.exception.ApiException;
import kz.smarthub.repository.OrderRejectionRepository;
import kz.smarthub.repository.OrderRepository;
import kz.smarthub.repository.OrderStatusHistoryRepository;
import kz.smarthub.repository.PaymentRepository;
import kz.smarthub.repository.ReviewRepository;
import kz.smarthub.repository.ServiceItemRepository;
import kz.smarthub.repository.UserRepository;
import kz.smarthub.web.dto.Dto.OrderDto;
import kz.smarthub.web.dto.Dto.OrderRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Order lifecycle, assignment, rejection and access control. */
class OrderServiceTest {

    private OrderRepository orders;
    private ServiceItemRepository services;
    private OrderRejectionRepository rejections;
    private WorkerSearchService workerSearch;
    private NotificationService notifications;
    private OrderService service;

    private User client;
    private User worker;
    private User otherWorker;
    private User admin;
    private ServiceItem repair;

    @BeforeEach
    void setUp() {
        orders = mock(OrderRepository.class);
        services = mock(ServiceItemRepository.class);
        rejections = mock(OrderRejectionRepository.class);
        workerSearch = mock(WorkerSearchService.class);
        notifications = mock(NotificationService.class);
        service = new OrderService(orders, services, mock(UserRepository.class),
                mock(OrderStatusHistoryRepository.class), mock(ReviewRepository.class), mock(PaymentRepository.class),
                rejections, workerSearch, notifications);

        client = user(1L, Role.CLIENT);
        worker = user(10L, Role.WORKER);
        otherWorker = user(11L, Role.WORKER);
        admin = user(99L, Role.ADMIN);

        ServiceCategory category = new ServiceCategory("Жөндеу", "Ремонт");
        category.setId(1L);
        repair = new ServiceItem();
        repair.setId(3L);
        repair.setCategory(category);
        repair.setNameKk("Тоңазытқыш");
        repair.setNameRu("Холодильник");
        repair.setPrice(new BigDecimal("8000"));
        repair.setActive(true);
        when(services.findById(3L)).thenReturn(Optional.of(repair));

        // persisting a new order gives it an id, like the database would
        when(orders.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            if (o.getId() == null) {
                o.setId(500L);
            }
            return o;
        });
    }

    private static User user(long id, Role role) {
        User u = new User();
        u.setId(id);
        u.setRole(role);
        u.setFullName("User " + id);
        u.setPhone("+7700000" + id);
        u.setActive(true);
        return u;
    }

    private Order order(long id, User assignedTo, OrderStatus status) {
        Order o = new Order();
        o.setId(id);
        o.setClient(client);
        o.setWorker(assignedTo);
        o.setService(repair);
        o.setAddress("Abay 1");
        o.setDistrict("CENTER");
        o.setScheduledTime(LocalDateTime.now().plusDays(2));
        o.setPrice(repair.getPrice());
        o.setStatus(status);
        when(orders.findByIdForUpdate(id)).thenReturn(Optional.of(o));
        when(orders.findById(id)).thenReturn(Optional.of(o));
        return o;
    }

    private static void assertStatus(HttpStatus expected, Runnable action) {
        ApiException e = assertThrows(ApiException.class, action::run);
        assertEquals(expected, e.getStatus());
    }

    private OrderRequest request(String district) {
        return new OrderRequest(3L, "Abay 1", district, LocalDateTime.now().plusDays(1), "note");
    }

    // ---------- creating ----------

    @Test
    void newOrderIsAssignedAutomaticallyWhenASpecialistIsFree() {
        when(workerSearch.findAvailableWorker(any(Order.class))).thenReturn(Optional.of(worker));

        OrderDto dto = service.create(client, request("CENTER"));

        assertEquals(OrderStatus.NEW, dto.status());
        assertEquals(10L, dto.workerId());
        assertEquals(0, new BigDecimal("8000").compareTo(dto.price()));
        verify(notifications).orderCreated(any(Order.class));
        verify(notifications).workerAssigned(any(Order.class));
        verify(notifications, never()).escalationNoWorker(any());
    }

    @Test
    void whenNobodyIsFreeTheOrderStaysOpenAndAdminsAreEscalated() {
        when(workerSearch.findAvailableWorker(any(Order.class))).thenReturn(Optional.empty());

        OrderDto dto = service.create(client, request("CENTER"));

        assertNull(dto.workerId());
        verify(notifications).escalationNoWorker(any(Order.class));
        verify(notifications, never()).workerAssigned(any());
    }

    @Test
    void anUnknownDistrictIsRejected() {
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.create(client, request("MARS")));
        verify(orders, never()).save(any());
    }

    @Test
    void anInactiveServiceCannotBeOrdered() {
        repair.setActive(false);
        assertStatus(HttpStatus.NOT_FOUND, () -> service.create(client, request("CENTER")));
    }

    @Test
    void theOrderKeepsTheCatalogPriceFromTheMomentOfOrdering() {
        when(workerSearch.findAvailableWorker(any(Order.class))).thenReturn(Optional.empty());
        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);

        service.create(client, request("CENTER"));
        verify(orders).save(saved.capture());
        repair.setPrice(new BigDecimal("99999")); // the catalog price changes afterwards

        assertEquals(0, new BigDecimal("8000").compareTo(saved.getValue().getPrice()));
    }

    // ---------- status chain ----------

    @Test
    void theAssignedSpecialistWalksTheStatusChainStepByStep() {
        Order o = order(7L, worker, OrderStatus.NEW);

        assertEquals(OrderStatus.ACCEPTED, service.changeStatus(worker, 7L, OrderStatus.ACCEPTED).status());
        assertEquals(OrderStatus.IN_PROGRESS, service.changeStatus(worker, 7L, OrderStatus.IN_PROGRESS).status());
        assertEquals(OrderStatus.COMPLETED, service.changeStatus(worker, 7L, OrderStatus.COMPLETED).status());
        assertEquals(OrderStatus.COMPLETED, o.getStatus());
    }

    @Test
    void statusesCannotBeSkippedOrReversed() {
        order(7L, worker, OrderStatus.NEW);
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.changeStatus(worker, 7L, OrderStatus.COMPLETED));

        order(8L, worker, OrderStatus.COMPLETED);
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.changeStatus(worker, 8L, OrderStatus.IN_PROGRESS));
    }

    @Test
    void aSpecialistCannotTouchSomeoneElsesOrder() {
        Order o = order(7L, worker, OrderStatus.NEW);

        assertStatus(HttpStatus.FORBIDDEN, () -> service.changeStatus(otherWorker, 7L, OrderStatus.ACCEPTED));
        assertEquals(OrderStatus.NEW, o.getStatus());
    }

    // ---------- rejection ----------

    @Test
    void aRejectedOrderIsReassignedToAnotherSpecialist() {
        Order o = order(7L, worker, OrderStatus.NEW);
        when(rejections.findWorkerIdsByOrderId(7L)).thenReturn(List.of(10L));
        when(workerSearch.findAvailableWorker(eq(o), anyCollection())).thenReturn(Optional.of(otherWorker));

        OrderDto dto = service.reject(worker, 7L);

        assertSame(otherWorker, o.getWorker());
        assertEquals(11L, dto.workerId());
        verify(rejections).save(any(OrderRejection.class));
        verify(workerSearch).findAvailableWorker(o, List.of(10L)); // the one who declined is excluded
        verify(notifications).workerAssigned(o);
        verify(notifications, never()).workerRejected(any(), any());
    }

    @Test
    void whenNoReplacementExistsTheOrderIsUnassignedAndAdminsAreTold() {
        Order o = order(7L, worker, OrderStatus.NEW);
        when(rejections.findWorkerIdsByOrderId(7L)).thenReturn(List.of(10L));
        when(workerSearch.findAvailableWorker(eq(o), anyCollection())).thenReturn(Optional.empty());

        OrderDto dto = service.reject(worker, 7L);

        assertNull(o.getWorker());
        assertNull(dto.workerId());
        assertEquals(OrderStatus.NEW, dto.status());
        verify(notifications).workerRejected(o, worker);
    }

    @Test
    void anAcceptedOrderCannotBeRejected() {
        order(7L, worker, OrderStatus.ACCEPTED);
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.reject(worker, 7L));
        verify(rejections, never()).save(any());
    }

    // ---------- manual assignment ----------

    @Test
    void anAdminCanAssignOnlyNewOrders() {
        order(7L, null, OrderStatus.ACCEPTED);
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.assign(admin, 7L, 10L));
    }

    // ---------- reading ----------

    @Test
    void anOrderIsVisibleToItsClientItsSpecialistAndAdminsOnly() {
        order(7L, worker, OrderStatus.NEW);
        User stranger = user(2L, Role.CLIENT);

        assertNotNull(service.get(client, 7L));
        assertNotNull(service.get(worker, 7L));
        assertNotNull(service.get(admin, 7L));
        assertStatus(HttpStatus.FORBIDDEN, () -> service.get(stranger, 7L));
        assertStatus(HttpStatus.FORBIDDEN, () -> service.get(otherWorker, 7L));
    }
}
