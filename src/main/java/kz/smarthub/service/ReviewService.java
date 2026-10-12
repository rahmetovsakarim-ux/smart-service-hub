package kz.smarthub.service;

import kz.smarthub.domain.Order;
import kz.smarthub.domain.OrderStatus;
import kz.smarthub.domain.Review;
import kz.smarthub.domain.User;
import kz.smarthub.exception.ApiException;
import kz.smarthub.repository.ReviewRepository;
import kz.smarthub.repository.UserRepository;
import kz.smarthub.web.dto.Dto.ReviewDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Қызметті бағалау (UC-08, FR-10): баға сақталады және маманның орташа рейтингі қайта есептеледі. */
@Service
public class ReviewService {

    private final OrderService orderService;
    private final ReviewRepository reviews;
    private final UserRepository users;
    private final NotificationService notifications;

    public ReviewService(OrderService orderService, ReviewRepository reviews, UserRepository users,
                         NotificationService notifications) {
        this.orderService = orderService;
        this.reviews = reviews;
        this.users = users;
        this.notifications = notifications;
    }

    @Transactional
    public ReviewDto create(User client, Long orderId, int rating, String comment) {
        Order o = orderService.load(orderId);
        if (!o.getClient().getId().equals(client.getId())) {
            throw ApiException.forbidden("Бұл сіздің тапсырысыңыз емес||Это не ваш заказ");
        }
        if (o.getStatus() != OrderStatus.COMPLETED || o.getWorker() == null) {
            throw ApiException.badRequest("Баға тек аяқталған тапсырысқа қойылады||Оценку можно поставить только завершённому заказу");
        }
        if (reviews.existsByOrderId(orderId)) {
            throw ApiException.conflict("Бұл тапсырысқа баға бұрын қойылған||Оценка по этому заказу уже поставлена");
        }

        Review r = new Review();
        r.setOrder(o);
        r.setClient(client);
        r.setWorker(o.getWorker());
        r.setRating(rating);
        r.setReviewText(comment == null || comment.isBlank() ? null : comment.trim());
        reviews.save(r);

        recalculateRating(o.getWorker());
        notifications.reviewed(o, rating);
        return toDto(r);
    }

    /** Маман рейтингін қайта есептеу (FR-10). */
    private void recalculateRating(User worker) {
        Double avg = reviews.averageRating(worker.getId());
        long count = reviews.countByWorkerId(worker.getId());
        worker.setRatingAvg(avg == null ? 0 : Math.round(avg * 100.0) / 100.0);
        worker.setRatingCount((int) count);
        users.save(worker);
    }

    @Transactional(readOnly = true)
    public List<ReviewDto> listForWorker(User worker) {
        return reviews.findByWorkerIdOrderByCreatedAtDesc(worker.getId()).stream().map(this::toDto).toList();
    }

    private ReviewDto toDto(Review r) {
        return new ReviewDto(r.getId(), r.getOrder().getId(), r.getRating(), r.getReviewText(),
                r.getClient().getFullName(), r.getCreatedAt());
    }
}
