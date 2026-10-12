package kz.smarthub.web;

import kz.smarthub.security.CurrentUser;
import kz.smarthub.service.NotificationService;
import kz.smarthub.service.OrderService;
import kz.smarthub.web.dto.Dto.NotificationsResponse;
import kz.smarthub.web.dto.Dto.OrderDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Кез келген кірген пайдаланушыға: тапсырыс картасы (қатынас тексеріледі) және хабарламалар. */
@RestController
@RequestMapping("/api")
public class CommonController {

    private final CurrentUser currentUser;
    private final OrderService orderService;
    private final NotificationService notifications;

    public CommonController(CurrentUser currentUser, OrderService orderService, NotificationService notifications) {
        this.currentUser = currentUser;
        this.orderService = orderService;
        this.notifications = notifications;
    }

    @GetMapping("/orders/{id}")
    public OrderDto order(@PathVariable Long id) {
        return orderService.get(currentUser.require(), id);
    }

    @GetMapping("/notifications")
    public NotificationsResponse notifications() {
        return notifications.list(currentUser.require());
    }

    @PostMapping("/notifications/read-all")
    public void readAll() {
        notifications.markAllSeen(currentUser.require());
    }
}
