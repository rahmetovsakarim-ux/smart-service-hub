package kz.smarthub.config;

import kz.smarthub.domain.Order;
import kz.smarthub.domain.OrderStatus;
import kz.smarthub.domain.OrderStatusHistory;
import kz.smarthub.domain.Payment;
import kz.smarthub.domain.PaymentStatus;
import kz.smarthub.domain.Review;
import kz.smarthub.domain.Role;
import kz.smarthub.domain.ServiceCategory;
import kz.smarthub.domain.ServiceItem;
import kz.smarthub.domain.User;
import kz.smarthub.repository.OrderRepository;
import kz.smarthub.repository.OrderStatusHistoryRepository;
import kz.smarthub.repository.PaymentRepository;
import kz.smarthub.repository.ReviewRepository;
import kz.smarthub.repository.ServiceCategoryRepository;
import kz.smarthub.repository.ServiceItemRepository;
import kz.smarthub.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Demo data. Runs only when {@code app.seed.enabled=true} (env {@code APP_SEED_ENABLED}; on by default only in the
 * {@code h2} and {@code desktop} profiles) and only into an empty database. Never enable it in production: it creates
 * accounts with publicly known passwords. For a real deployment use {@link AdminBootstrap} instead.
 *
 * Бастапқы деректер: база бос болғанда ғана жүктеледі.
 * Демо аккаунттар (құпиясөз BCrypt-пен сақталады):
 *   Әкімші: admin@hub.kz / Admin123!     Клиент: client@hub.kz / Client123!
 *   Мамандар: worker1@hub.kz ... worker4@hub.kz / Worker123!
 */
@Component
@org.springframework.core.annotation.Order(1)
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final boolean enabled;
    private final UserRepository users;
    private final ServiceCategoryRepository categories;
    private final ServiceItemRepository services;
    private final OrderRepository orders;
    private final OrderStatusHistoryRepository history;
    private final ReviewRepository reviews;
    private final PaymentRepository payments;
    private final PasswordEncoder encoder;

    public DataInitializer(@Value("${app.seed.enabled:false}") boolean enabled, UserRepository users,
                           ServiceCategoryRepository categories, ServiceItemRepository services,
                           OrderRepository orders, OrderStatusHistoryRepository history,
                           ReviewRepository reviews, PaymentRepository payments, PasswordEncoder encoder) {
        this.enabled = enabled;
        this.users = users;
        this.categories = categories;
        this.services = services;
        this.orders = orders;
        this.history = history;
        this.reviews = reviews;
        this.payments = payments;
        this.encoder = encoder;
    }

    @Override
    public void run(String... args) {
        if (!enabled || users.count() > 0) {
            return;
        }

        // ---- Санаттар ----
        ServiceCategory repair = categories.save(new ServiceCategory("Жөндеу", "Ремонт"));
        ServiceCategory cleaning = categories.save(new ServiceCategory("Тазалау", "Уборка"));
        ServiceCategory install = categories.save(new ServiceCategory("Орнату", "Установка"));

        // ---- Қызметтер ----
        ServiceItem fridge = service(repair, "Тоңазытқышты жөндеу", "Ремонт холодильника",
                "Диагностика және ақауды жою, қосалқы бөлшектер бөлек.", "Диагностика и устранение неисправности, запчасти оплачиваются отдельно.", 8000, 90);
        service(repair, "Кір жуғыш машинаны жөндеу", "Ремонт стиральной машины",
                "Су ағызбау, барабан, электроника ақаулары.", "Течь, барабан, неисправности электроники.", 7000, 60);
        service(repair, "Розетка мен сымдарды жөндеу", "Ремонт розеток и проводки",
                "Розетка, ажыратқыш, жарықтандыру желісін жөндеу.", "Ремонт розеток, выключателей и линий освещения.", 5000, 45);
        service(repair, "Сантехникалық жөндеу", "Сантехнический ремонт",
                "Кран, сифон, құбыр ағуын жою.", "Устранение протечек крана, сифона, труб.", 6000, 60);
        ServiceItem general = service(cleaning, "Пәтерді жалпы тазалау", "Генеральная уборка квартиры",
                "Барлық бөлме, ванна және асүй толық тазаланады.", "Полная уборка всех комнат, ванной и кухни.", 15000, 240);
        service(cleaning, "Кілем мен жиһазды химиялық тазалау", "Химчистка ковров и мебели",
                "Кілем, диван, матрас кәсіби тазалауы.", "Профессиональная чистка ковров, диванов и матрасов.", 10000, 120);
        service(cleaning, "Терезені жуу", "Мытьё окон",
                "Терезе мен балкон әйнектерін жуу.", "Мойка окон и балконного остекления.", 6000, 60);
        ServiceItem ac = service(install, "Кондиционер орнату", "Установка кондиционера",
                "Ішкі және сыртқы блокты орнату, трасса төсеу.", "Монтаж внутреннего и наружного блоков, прокладка трассы.", 20000, 180);
        service(install, "Теледидарды қабырғаға орнату", "Установка ТВ на стену",
                "Кронштейн бекіту және теледидарды іліп қосу.", "Крепление кронштейна и подключение ТВ.", 5000, 45);
        service(install, "Тұрмыстық техниканы қосу", "Подключение бытовой техники",
                "Кір жуғыш, ыдыс жуғыш машинаны қосу.", "Подключение стиральной и посудомоечной машин.", 4000, 45);

        // ---- Пайдаланушылар ----
        User admin = user("Әкімші Қызылорда", "+77001110001", "admin@hub.kz", "Admin123!", Role.ADMIN, null, null, null);
        User client = user("Айдос Серіков", "+77001110002", "client@hub.kz", "Client123!", Role.CLIENT, null, null, null);
        User w1 = user("Ерлан Төлеуов", "+77001110011", "worker1@hub.kz", "Worker123!", Role.WORKER, repair, 8, "CENTER");
        User w2 = user("Гүлнар Әбенова", "+77001110012", "worker2@hub.kz", "Worker123!", Role.WORKER, cleaning, 5, "NORTH");
        User w3 = user("Марат Қожахметов", "+77001110013", "worker3@hub.kz", "Worker123!", Role.WORKER, install, 6, "SOUTH");
        user("Серік Жұмабаев", "+77001110014", "worker4@hub.kz", "Worker123!", Role.WORKER, repair, 3, "WEST");

        // ---- Демо тапсырыстар (дашбордты толтыру үшін) ----
        // 1) Аяқталған, төленген және бағаланған
        Order done = order(client, w1, fridge, "Ә. Молдағұлова көшесі, 12", "CENTER",
                LocalDateTime.now().minusDays(3), OrderStatus.COMPLETED);
        statusTrail(done, w1, client, OrderStatus.COMPLETED);
        Payment p = new Payment();
        p.setOrder(done);
        p.setAmount(done.getPrice());
        p.setStatus(PaymentStatus.PAID);
        p.setTransactionRef("TX-DEMO000001");
        p.setActiveOrderId(done.getId());
        p.setCardLast4("4242");
        payments.save(p);
        Review r = new Review();
        r.setOrder(done);
        r.setClient(client);
        r.setWorker(w1);
        r.setRating(5);
        r.setReviewText("Жылдам әрі сапалы жөндеді / Быстро и качественно");
        reviews.save(r);
        w1.setRatingAvg(5.0);
        w1.setRatingCount(1);
        users.save(w1);

        // 2) Орындалуда
        Order inProgress = order(client, w2, general, "Төле би көшесі, 45, 8-пәтер", "NORTH",
                LocalDateTime.now().minusHours(1), OrderStatus.IN_PROGRESS);
        statusTrail(inProgress, w2, client, OrderStatus.IN_PROGRESS);

        // 3) Жаңа, маман тағайындалмаған (escalation сценарийін көрсету үшін)
        Order fresh = order(client, null, ac, "Бейбарыс көшесі, 7", "EAST",
                LocalDateTime.now().plusDays(2), OrderStatus.NEW);
        logEntry(fresh, null, OrderStatus.NEW, client, "Тапсырыс құрылды / Заказ создан");

        log.info("Демо-деректер жүктелді. Әкімші: admin@hub.kz / Admin123!  Клиент: client@hub.kz / Client123!");
    }

    private ServiceItem service(ServiceCategory c, String nameKk, String nameRu, String descKk, String descRu, int price,
                              int durationMinutes) {
        ServiceItem s = new ServiceItem();
        s.setCategory(c);
        s.setNameKk(nameKk);
        s.setNameRu(nameRu);
        s.setDescriptionKk(descKk);
        s.setDescriptionRu(descRu);
        s.setPrice(BigDecimal.valueOf(price));
        s.setDurationMinutes(durationMinutes);
        return services.save(s);
    }

    private User user(String name, String phone, String email, String password, Role role,
                      ServiceCategory spec, Integer exp, String district) {
        User u = new User();
        u.setFullName(name);
        u.setPhone(phone);
        u.setEmail(email);
        u.setPasswordHash(encoder.encode(password));
        u.setRole(role);
        if (role == Role.WORKER) {
            u.setSpecialization(spec);
            u.setExperienceYears(exp);
            u.setDistrict(district);
            u.setBio("Қызылорда қаласында жұмыс істейді / Работает в городе Кызылорда");
        }
        return users.save(u);
    }

    private Order order(User client, User worker, ServiceItem s, String address, String district,
                        LocalDateTime when, OrderStatus status) {
        Order o = new Order();
        o.setClient(client);
        o.setWorker(worker);
        o.setService(s);
        o.setAddress(address);
        o.setDistrict(district);
        o.setScheduledTime(when);
        o.setPrice(s.getPrice());
        o.setStatus(status);
        o.setCreatedAt(when.minusDays(1));
        o.setUpdatedAt(LocalDateTime.now());
        return orders.save(o);
    }

    /** NEW → ... → target аралығындағы аудит жазбаларын жасайды. */
    private void statusTrail(Order o, User worker, User client, OrderStatus target) {
        logEntry(o, null, OrderStatus.NEW, client, "Тапсырыс құрылды / Заказ создан");
        OrderStatus current = OrderStatus.NEW;
        while (current != target) {
            OrderStatus next = current.next();
            logEntry(o, current, next, worker, null);
            current = next;
        }
    }

    private void logEntry(Order o, OrderStatus oldStatus, OrderStatus newStatus, User by, String note) {
        OrderStatusHistory h = new OrderStatusHistory();
        h.setOrder(o);
        h.setOldStatus(oldStatus);
        h.setNewStatus(newStatus);
        h.setChangedBy(by);
        h.setNote(note);
        history.save(h);
    }
}
