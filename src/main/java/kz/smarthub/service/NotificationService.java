package kz.smarthub.service;

import kz.smarthub.domain.Notification;
import kz.smarthub.domain.Order;
import kz.smarthub.domain.Role;
import kz.smarthub.domain.User;
import kz.smarthub.integration.SmsPushProvider;
import kz.smarthub.repository.NotificationRepository;
import kz.smarthub.repository.UserRepository;
import kz.smarthub.web.dto.Dto.NotificationDto;
import kz.smarthub.web.dto.Dto.NotificationsResponse;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Хабарламалар модулі (FR-09, FR-14, UC-14): әр хабарлама БД-ға жазылады (қолданбадағы қоңырау белгісі)
 * және сыртқы SMS/Push провайдері арқылы жіберіледі.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notifications;
    private final UserRepository users;
    private final SmsPushProvider smsPush;
    private final Executor externalExecutor;

    @Autowired
    public NotificationService(NotificationRepository notifications, UserRepository users, SmsPushProvider smsPush) {
        this(notifications, users, smsPush, newExternalExecutor());
    }

    /** Test seam: lets a test run the external delivery synchronously ({@code Runnable::run}). */
    NotificationService(NotificationRepository notifications, UserRepository users, SmsPushProvider smsPush,
                        Executor externalExecutor) {
        this.notifications = notifications;
        this.users = users;
        this.smsPush = smsPush;
        this.externalExecutor = externalExecutor;
    }

    private static ExecutorService newExternalExecutor() {
        AtomicInteger n = new AtomicInteger();
        ThreadFactory daemon = r -> {
            Thread t = new Thread(r, "sms-push-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        // bounded queue; if it ever fills up the caller thread delivers the message itself rather than losing it
        return new ThreadPoolExecutor(1, 4, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(500), daemon,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @PreDestroy
    void shutdownExecutor() {
        if (externalExecutor instanceof ExecutorService es) {
            es.shutdown();
        }
    }

    // ---------- Төмен деңгейлі жіберу ----------
    @Transactional
    public void notifyUser(User user, Long orderId, String kk, String ru) {
        Notification n = new Notification();
        n.setUser(user);
        n.setOrderId(orderId);
        n.setMessageKk(kk);
        n.setMessageRu(ru);
        notifications.save(n);
        dispatchExternal(user.getPhone(), kk + " / " + ru);
    }

    /**
     * The in-app notification row is part of the caller's transaction; the SMS/Push call is not. It is handed to a
     * background executor only after the transaction commits, so:
     * <ul>
     *   <li>a rolled-back order never sends a message;</li>
     *   <li>a slow or failing provider can neither roll back the business operation nor keep a database connection
     *       busy (Spring releases the connection only after {@code afterCommit} callbacks have returned, which is why
     *       the call must leave the request thread rather than just run in {@code afterCommit}).</li>
     * </ul>
     */
    private void dispatchExternal(String phone, String text) {
        Runnable send = () -> {
            try {
                smsPush.send(phone, text);
            } catch (RuntimeException e) {
                log.warn("SMS/Push delivery to {} failed; the in-app notification was still saved", phone, e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    externalExecutor.execute(send);
                }
            });
        } else {
            externalExecutor.execute(send);
        }
    }

    @Transactional
    public void notifyAdmins(Long orderId, String kk, String ru) {
        for (User admin : users.findByRoleAndActiveTrue(Role.ADMIN)) {
            notifyUser(admin, orderId, kk, ru);
        }
    }

    // ---------- Бизнес оқиғалар ----------
    public void orderCreated(Order o) {
        notifyUser(o.getClient(), o.getId(),
                "Тапсырыс №" + o.getId() + " тіркелді. Мәртебесі: Жаңа",
                "Заказ №" + o.getId() + " зарегистрирован. Статус: Новый");
    }

    public void workerAssigned(Order o) {
        User w = o.getWorker();
        notifyUser(w, o.getId(),
                "Сізге жаңа тапсырыс №" + o.getId() + " тағайындалды: " + o.getService().getNameKk() + ", " + o.getAddress(),
                "Вам назначен новый заказ №" + o.getId() + ": " + o.getService().getNameRu() + ", " + o.getAddress());
        notifyUser(o.getClient(), o.getId(),
                "Тапсырыс №" + o.getId() + " үшін маман тағайындалды: " + w.getFullName(),
                "По заказу №" + o.getId() + " назначен специалист: " + w.getFullName());
    }

    /** Бос маман табылмағанда (FR-14). */
    public void escalationNoWorker(Order o) {
        notifyAdmins(o.getId(),
                "Назар аударыңыз: №" + o.getId() + " тапсырысқа бос маман табылмады. Қолмен тағайындаңыз",
                "Внимание: для заказа №" + o.getId() + " не найден свободный специалист. Назначьте вручную");
        notifyUser(o.getClient(), o.getId(),
                "Тапсырыс №" + o.getId() + " қабылданды, маман іздестірілуде",
                "Заказ №" + o.getId() + " принят, идёт поиск специалиста");
    }

    /** Маман бас тартқанда (FR-14, UC-09 балама сценарий). */
    public void workerRejected(Order o, User worker) {
        notifyAdmins(o.getId(),
                "Маман " + worker.getFullName() + " №" + o.getId() + " тапсырыстан бас тартты. Басқа маман тағайындаңыз",
                "Специалист " + worker.getFullName() + " отказался от заказа №" + o.getId() + ". Назначьте другого");
    }

    public void statusChanged(Order o) {
        String kk = "Тапсырыс №" + o.getId() + " мәртебесі: " + o.getStatus().getLabelKk();
        String ru = "Статус заказа №" + o.getId() + ": " + o.getStatus().getLabelRu();
        notifyUser(o.getClient(), o.getId(), kk, ru);
        if (o.getWorker() != null) {
            notifyUser(o.getWorker(), o.getId(), kk, ru);
        }
    }

    public void paid(Order o) {
        String kk = "Тапсырыс №" + o.getId() + " үшін төлем қабылданды";
        String ru = "Оплата заказа №" + o.getId() + " принята";
        notifyUser(o.getClient(), o.getId(), kk, ru);
        if (o.getWorker() != null) {
            notifyUser(o.getWorker(), o.getId(), kk, ru);
        }
    }

    public void reviewed(Order o, int rating) {
        if (o.getWorker() != null) {
            notifyUser(o.getWorker(), o.getId(),
                    "Тапсырыс №" + o.getId() + " бойынша жаңа баға: " + rating + "/5",
                    "Новая оценка по заказу №" + o.getId() + ": " + rating + "/5");
        }
    }

    // ---------- Оқу ----------
    @Transactional(readOnly = true)
    public NotificationsResponse list(User user) {
        List<NotificationDto> items = notifications.findTop50ByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .map(n -> new NotificationDto(n.getId(), n.getOrderId(), n.getMessageKk(), n.getMessageRu(),
                        n.isSeen(), n.getCreatedAt()))
                .toList();
        return new NotificationsResponse(notifications.countByUserIdAndSeenFalse(user.getId()), items);
    }

    @Transactional
    public void markAllSeen(User user) {
        notifications.markAllSeen(user.getId());
    }
}
