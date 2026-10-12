package kz.smarthub.service;

import kz.smarthub.domain.Districts;
import kz.smarthub.domain.Order;
import kz.smarthub.domain.OrderRejection;
import kz.smarthub.domain.OrderStatus;
import kz.smarthub.domain.OrderStatusHistory;
import kz.smarthub.domain.PaymentStatus;
import kz.smarthub.domain.Review;
import kz.smarthub.domain.Role;
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
import kz.smarthub.web.dto.Dto.HistoryDto;
import kz.smarthub.web.dto.Dto.OrderDto;
import kz.smarthub.web.dto.Dto.OrderRequest;
import kz.smarthub.web.dto.Dto.UserDto;
import kz.smarthub.web.dto.Mapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Негізгі бизнес-процесс модулі (Service қабаты): тапсырыс құру, маман тағайындау,
 * статус тізбегін бақылау, аудит журналы (FR-05..FR-08, FR-14, FR-15).
 */
@Service
public class OrderService {

    private final OrderRepository orders;
    private final ServiceItemRepository services;
    private final UserRepository users;
    private final OrderStatusHistoryRepository history;
    private final ReviewRepository reviews;
    private final PaymentRepository payments;
    private final OrderRejectionRepository rejections;
    private final WorkerSearchService workerSearch;
    private final NotificationService notifications;

    public OrderService(OrderRepository orders, ServiceItemRepository services, UserRepository users,
                        OrderStatusHistoryRepository history, ReviewRepository reviews, PaymentRepository payments,
                        OrderRejectionRepository rejections, WorkerSearchService workerSearch,
                        NotificationService notifications) {
        this.orders = orders;
        this.services = services;
        this.users = users;
        this.history = history;
        this.reviews = reviews;
        this.payments = payments;
        this.rejections = rejections;
        this.workerSearch = workerSearch;
        this.notifications = notifications;
    }

    // ================= Клиент: тапсырыс құру (UC-05) =================
    /**
     * READ_COMMITTED on purpose: the specialist search takes row locks and then re-reads the schedule; under MySQL's
     * default REPEATABLE READ that re-read could use a stale snapshot and miss a concurrent assignment.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderDto create(User client, OrderRequest req) {
        ServiceItem service = services.findById(req.serviceId())
                .filter(ServiceItem::isActive)
                .orElseThrow(() -> ApiException.notFound("Қызмет табылмады||Услуга не найдена"));
        if (!Districts.isValid(req.district())) {
            throw ApiException.badRequest("Ауданды таңдаңыз||Выберите район");
        }

        Order o = new Order();
        o.setClient(client);
        o.setService(service);
        o.setAddress(req.address().trim());
        o.setDistrict(req.district());
        o.setScheduledTime(req.scheduledTime());
        o.setNote(req.note());
        o.setPrice(service.getPrice());
        o.setStatus(OrderStatus.NEW);
        orders.save(o);

        log(o, null, OrderStatus.NEW, client, "Тапсырыс құрылды / Заказ создан");
        notifications.orderCreated(o);

        Optional<User> worker = workerSearch.findAvailableWorker(o);
        if (worker.isPresent()) {
            assignInternal(o, worker.get(), null, "Автоматты тағайындау / Авто-назначение");
        } else {
            notifications.escalationNoWorker(o); // 6а: Әкімшіге escalation
        }
        return toDto(o, false);
    }

    // ================= Әкімші: қолмен тағайындау (UC-11) =================
    @Transactional
    public OrderDto assign(User admin, Long orderId, Long workerId) {
        Order o = loadForUpdate(orderId);
        if (o.getStatus() != OrderStatus.NEW) {
            throw ApiException.badRequest("Маманды тек «Жаңа» тапсырысқа тағайындауға болады||Назначить специалиста можно только для заказа со статусом «Новый»");
        }
        User worker = users.findById(workerId)
                .filter(w -> w.getRole() == Role.WORKER && w.isActive())
                .orElseThrow(() -> ApiException.notFound("Маман табылмады||Специалист не найден"));
        assignInternal(o, worker, admin, "Әкімші тағайындады / Назначено администратором");
        return toDto(o, false);
    }

    private void assignInternal(Order o, User worker, User actor, String note) {
        o.setWorker(worker);
        o.setUpdatedAt(LocalDateTime.now());
        orders.save(o);
        log(o, o.getStatus(), o.getStatus(), actor, note + ": " + worker.getFullName());
        notifications.workerAssigned(o);
    }

    public List<UserDto> candidates(Long orderId) {
        return workerSearch.candidates(load(orderId)).stream().map(Mapper::user).toList();
    }

    // ================= Маман: қабылдау / бас тарту / статус (UC-09, UC-10) =================
    @Transactional
    public OrderDto changeStatus(User worker, Long orderId, OrderStatus target) {
        Order o = loadAssigned(orderId, worker);
        OrderStatus old = o.getStatus();
        if (!old.canMoveTo(target)) {
            throw ApiException.badRequest("Статусты " + old + " → " + target
                    + " ауыстыруға болмайды. Тек New → Accepted → In Progress → Completed||"
                    + "Нельзя изменить статус " + old + " → " + target
                    + ". Допустимо только New → Accepted → In Progress → Completed");
        }
        o.setStatus(target);
        o.setUpdatedAt(LocalDateTime.now());
        orders.save(o);
        log(o, old, target, worker, null);
        notifications.statusChanged(o);
        return toDto(o, false);
    }

    /**
     * The specialist declines a NEW order. The order must not be left dangling: the rejection is remembered and
     * the system immediately looks for another specialist (never one who already declined this order). Only when
     * nobody is free are the administrators escalated to assign manually.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderDto reject(User worker, Long orderId) {
        Order o = loadAssigned(orderId, worker);
        if (o.getStatus() != OrderStatus.NEW) {
            throw ApiException.badRequest("Қабылданған тапсырыстан бас тартуға болмайды||Нельзя отказаться от уже принятого заказа");
        }
        o.setWorker(null);
        o.setUpdatedAt(LocalDateTime.now());
        orders.save(o);
        rejections.save(new OrderRejection(o, worker));
        log(o, OrderStatus.NEW, OrderStatus.NEW, worker, "Маман бас тартты / Специалист отказался: " + worker.getFullName());

        Optional<User> replacement = workerSearch.findAvailableWorker(o, rejections.findWorkerIdsByOrderId(o.getId()));
        if (replacement.isPresent()) {
            assignInternal(o, replacement.get(), null, "Автоматты қайта тағайындау / Авто-переназначение");
        } else {
            notifications.workerRejected(o, worker);
        }
        return toDto(o, false);
    }

    // ================= Оқу / іздеу / сүзгі =================
    @Transactional(readOnly = true)
    public List<OrderDto> listForClient(User client, OrderStatus status) {
        return toDtos(orders.findByClientIdOrderByCreatedAtDesc(client.getId()).stream()
                .filter(o -> status == null || o.getStatus() == status).toList());
    }

    @Transactional(readOnly = true)
    public List<OrderDto> listForWorker(User worker, OrderStatus status) {
        return toDtos(orders.findByWorkerIdOrderByCreatedAtDesc(worker.getId()).stream()
                .filter(o -> status == null || o.getStatus() == status).toList());
    }

    /** Әкімші: статус / клиент / маман бойынша сүзу (Іздеу/сүзгі модулі). Ең көбі 200 жазба. */
    @Transactional(readOnly = true)
    public List<OrderDto> listForAdmin(OrderStatus status, Long clientId, Long workerId) {
        Specification<Order> spec = (root, q, cb) -> cb.conjunction();
        if (status != null) {
            Specification<Order> s = (root, q, cb) -> cb.equal(root.get("status"), status);
            spec = spec.and(s);
        }
        if (clientId != null) {
            Specification<Order> s = (root, q, cb) -> cb.equal(root.get("client").get("id"), clientId);
            spec = spec.and(s);
        }
        if (workerId != null) {
            Specification<Order> s = (root, q, cb) -> cb.equal(root.get("worker").get("id"), workerId);
            spec = spec.and(s);
        }
        return toDtos(orders.findAll(spec, PageRequest.of(0, 200, Sort.by(Sort.Direction.DESC, "createdAt")))
                .getContent());
    }

    @Transactional(readOnly = true)
    public OrderDto get(User viewer, Long id) {
        Order o = load(id);
        boolean allowed = viewer.getRole() == Role.ADMIN
                || o.getClient().getId().equals(viewer.getId())
                || (o.getWorker() != null && o.getWorker().getId().equals(viewer.getId()));
        if (!allowed) {
            throw ApiException.forbidden("Бұл тапсырыс сізге қолжетімсіз||Этот заказ вам недоступен");
        }
        return toDto(o, true);
    }

    // ================= Көмекші =================
    public Order load(Long id) {
        return orders.findById(id).orElseThrow(() -> ApiException.notFound("Тапсырыс табылмады||Заказ не найден"));
    }

    /** Row-locked read: concurrent status changes / assignments / rejections of one order run one after another. */
    private Order loadForUpdate(Long id) {
        return orders.findByIdForUpdate(id)
                .orElseThrow(() -> ApiException.notFound("Тапсырыс табылмады||Заказ не найден"));
    }

    private Order loadAssigned(Long orderId, User worker) {
        Order o = loadForUpdate(orderId);
        if (o.getWorker() == null || !o.getWorker().getId().equals(worker.getId())) {
            throw ApiException.forbidden("Бұл тапсырыс сізге тағайындалмаған||Этот заказ вам не назначен");
        }
        return o;
    }

    private void log(Order o, OrderStatus oldStatus, OrderStatus newStatus, User by, String note) {
        OrderStatusHistory h = new OrderStatusHistory();
        h.setOrder(o);
        h.setOldStatus(oldStatus);
        h.setNewStatus(newStatus);
        h.setChangedBy(by);
        h.setNote(note);
        history.save(h);
    }

    /** Single order (detail view, create/assign responses). */
    public OrderDto toDto(Order o, boolean withHistory) {
        List<HistoryDto> hist = withHistory
                ? history.findByOrderIdOrderByChangedAtAscIdAsc(o.getId()).stream()
                .map(h -> new HistoryDto(h.getOldStatus(), h.getNewStatus(),
                        h.getChangedBy() == null ? null : h.getChangedBy().getFullName(),
                        h.getChangedAt(), h.getNote()))
                .toList()
                : null;
        return build(o, payments.existsByOrderIdAndStatus(o.getId(), PaymentStatus.PAID),
                reviews.findByOrderId(o.getId()), hist);
    }

    /** Lists: paid flags and reviews come from two batch queries instead of two queries per order. */
    private List<OrderDto> toDtos(List<Order> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        List<Long> ids = list.stream().map(Order::getId).toList();
        Set<Long> paid = new HashSet<>(payments.findOrderIdsByStatus(ids, PaymentStatus.PAID));
        Map<Long, Review> reviewed = new HashMap<>();
        for (Review r : reviews.findByOrderIdIn(ids)) {
            reviewed.put(r.getOrder().getId(), r);
        }
        return list.stream()
                .map(o -> build(o, paid.contains(o.getId()), Optional.ofNullable(reviewed.get(o.getId())), null))
                .toList();
    }

    private OrderDto build(Order o, boolean paid, Optional<Review> review, List<HistoryDto> hist) {
        User c = o.getClient();
        User w = o.getWorker();
        return new OrderDto(
                o.getId(), o.getService().getId(), o.getService().getNameKk(), o.getService().getNameRu(), o.getPrice(),
                o.getAddress(), o.getDistrict(), o.getScheduledTime(), o.getNote(),
                o.getStatus(), o.getCreatedAt(),
                c.getId(), c.getFullName(), c.getPhone(),
                w == null ? null : w.getId(), w == null ? null : w.getFullName(),
                w == null ? null : w.getPhone(), w == null ? 0 : w.getRatingAvg(),
                paid, review.isPresent(), review.map(Review::getRating).orElse(null),
                hist);
    }
}
