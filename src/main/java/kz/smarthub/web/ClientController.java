package kz.smarthub.web;

import jakarta.validation.Valid;
import kz.smarthub.domain.OrderStatus;
import kz.smarthub.security.CurrentUser;
import kz.smarthub.service.OrderService;
import kz.smarthub.service.PaymentService;
import kz.smarthub.service.ReviewService;
import kz.smarthub.web.dto.Dto.OrderDto;
import kz.smarthub.web.dto.Dto.OrderRequest;
import kz.smarthub.web.dto.Dto.PaymentDto;
import kz.smarthub.web.dto.Dto.PaymentRequest;
import kz.smarthub.web.dto.Dto.ReviewDto;
import kz.smarthub.web.dto.Dto.ReviewRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Клиент әрекеттері: UC-05 тапсырыс, UC-06 қадағалау, UC-07 төлем, UC-08 бағалау. Тек ROLE_CLIENT. */
@RestController
@RequestMapping("/api/client")
public class ClientController {

    private final CurrentUser currentUser;
    private final OrderService orderService;
    private final PaymentService paymentService;
    private final ReviewService reviewService;

    public ClientController(CurrentUser currentUser, OrderService orderService,
                            PaymentService paymentService, ReviewService reviewService) {
        this.currentUser = currentUser;
        this.orderService = orderService;
        this.paymentService = paymentService;
        this.reviewService = reviewService;
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderDto create(@Valid @RequestBody OrderRequest req) {
        return orderService.create(currentUser.require(), req);
    }

    @GetMapping("/orders")
    public List<OrderDto> myOrders(@RequestParam(required = false) OrderStatus status) {
        return orderService.listForClient(currentUser.require(), status);
    }

    @PostMapping("/orders/{id}/pay")
    public PaymentDto pay(@PathVariable Long id, @Valid @RequestBody PaymentRequest req) {
        return paymentService.pay(currentUser.require(), id, req.cardNumber());
    }

    @PostMapping("/orders/{id}/review")
    @ResponseStatus(HttpStatus.CREATED)
    public ReviewDto review(@PathVariable Long id, @Valid @RequestBody ReviewRequest req) {
        return reviewService.create(currentUser.require(), id, req.rating(), req.comment());
    }
}
