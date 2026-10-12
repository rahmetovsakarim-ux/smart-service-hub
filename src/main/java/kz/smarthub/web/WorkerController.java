package kz.smarthub.web;

import jakarta.validation.Valid;
import kz.smarthub.domain.OrderStatus;
import kz.smarthub.security.CurrentUser;
import kz.smarthub.service.OrderService;
import kz.smarthub.service.ReviewService;
import kz.smarthub.service.UserService;
import kz.smarthub.web.dto.Dto.OrderDto;
import kz.smarthub.web.dto.Dto.ProfileRequest;
import kz.smarthub.web.dto.Dto.ReviewDto;
import kz.smarthub.web.dto.Dto.StatusRequest;
import kz.smarthub.web.dto.Dto.UserDto;
import kz.smarthub.web.dto.Mapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Маман әрекеттері: UC-09 қабылдау/бас тарту, UC-10 статус жаңарту, профиль. Тек ROLE_WORKER. */
@RestController
@RequestMapping("/api/worker")
public class WorkerController {

    private final CurrentUser currentUser;
    private final OrderService orderService;
    private final ReviewService reviewService;
    private final UserService userService;

    public WorkerController(CurrentUser currentUser, OrderService orderService,
                            ReviewService reviewService, UserService userService) {
        this.currentUser = currentUser;
        this.orderService = orderService;
        this.reviewService = reviewService;
        this.userService = userService;
    }

    @GetMapping("/orders")
    public List<OrderDto> myOrders(@RequestParam(required = false) OrderStatus status) {
        return orderService.listForWorker(currentUser.require(), status);
    }

    @PostMapping("/orders/{id}/accept")
    public OrderDto accept(@PathVariable Long id) {
        return orderService.changeStatus(currentUser.require(), id, OrderStatus.ACCEPTED);
    }

    @PostMapping("/orders/{id}/reject")
    public OrderDto reject(@PathVariable Long id) {
        return orderService.reject(currentUser.require(), id);
    }

    @PostMapping("/orders/{id}/status")
    public OrderDto status(@PathVariable Long id, @Valid @RequestBody StatusRequest req) {
        return orderService.changeStatus(currentUser.require(), id, req.status());
    }

    @GetMapping("/profile")
    public UserDto profile() {
        return Mapper.user(currentUser.require());
    }

    @PutMapping("/profile")
    public UserDto updateProfile(@Valid @RequestBody ProfileRequest req) {
        return userService.updateProfile(currentUser.require(), req);
    }

    @GetMapping("/reviews")
    public List<ReviewDto> reviews() {
        return reviewService.listForWorker(currentUser.require());
    }
}
