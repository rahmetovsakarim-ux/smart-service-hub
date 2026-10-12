package kz.smarthub.service;

import kz.smarthub.domain.OrderStatus;
import kz.smarthub.domain.PaymentStatus;
import kz.smarthub.domain.Role;
import kz.smarthub.domain.User;
import kz.smarthub.repository.OrderRepository;
import kz.smarthub.repository.PaymentRepository;
import kz.smarthub.repository.UserRepository;
import kz.smarthub.web.dto.Dto.DayStat;
import kz.smarthub.web.dto.Dto.StatsDto;
import kz.smarthub.web.dto.Dto.WorkerStat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Есеп беру (Admin Dashboard) модулі (UC-13, FR-13): статус, маман және кезең бойынша статистика. */
@Service
public class StatisticsService {

    private final OrderRepository orders;
    private final UserRepository users;
    private final PaymentRepository payments;

    public StatisticsService(OrderRepository orders, UserRepository users, PaymentRepository payments) {
        this.orders = orders;
        this.users = users;
        this.payments = payments;
    }

    @Transactional(readOnly = true)
    public StatsDto build(int daysParam) {
        int days = Math.max(1, Math.min(daysParam, 90));

        // Статус бойынша
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (OrderStatus s : OrderStatus.values()) {
            byStatus.put(s.name(), 0L);
        }
        for (Object[] row : orders.countGroupedByStatus()) {
            byStatus.put(((OrderStatus) row[0]).name(), (Long) row[1]);
        }
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();

        // Рөл бойынша пайдаланушылар
        Map<String, Long> usersByRole = new LinkedHashMap<>();
        for (Role r : Role.values()) {
            usersByRole.put(r.name(), users.countByRole(r));
        }

        // Маман бойынша
        Map<Long, Long> totals = toMap(orders.countPerWorker());
        Map<Long, Long> completed = toMap(orders.countPerWorkerByStatus(OrderStatus.COMPLETED));
        List<WorkerStat> workers = new ArrayList<>();
        for (User w : users.findByRoleOrderByFullNameAsc(Role.WORKER)) {
            workers.add(new WorkerStat(w.getId(), w.getFullName(),
                    totals.getOrDefault(w.getId(), 0L), completed.getOrDefault(w.getId(), 0L),
                    w.getRatingAvg(), w.getRatingCount()));
        }

        // Кезең бойынша (күн сайын)
        LocalDate today = LocalDate.now();
        LocalDate firstDay = today.minusDays(days - 1L);
        LocalDateTime from = firstDay.atStartOfDay();
        Map<LocalDate, Long> perDay = new LinkedHashMap<>();
        for (int i = 0; i < days; i++) {
            perDay.put(firstDay.plusDays(i), 0L);
        }
        long periodTotal = 0;
        for (LocalDateTime created : orders.findCreatedAtSince(from)) {
            perDay.merge(created.toLocalDate(), 1L, Long::sum);
            periodTotal++;
        }
        List<DayStat> daily = new ArrayList<>();
        perDay.forEach((d, c) -> daily.add(new DayStat(d, c)));

        BigDecimal revenue = payments.sumByStatus(PaymentStatus.PAID);
        return new StatsDto(total, orders.countByStatusAndWorkerIsNull(OrderStatus.NEW), periodTotal, days,
                byStatus, usersByRole, workers, daily, revenue == null ? BigDecimal.ZERO : revenue);
    }

    private Map<Long, Long> toMap(List<Object[]> rows) {
        Map<Long, Long> m = new HashMap<>();
        for (Object[] r : rows) {
            m.put((Long) r[0], (Long) r[1]);
        }
        return m;
    }
}
