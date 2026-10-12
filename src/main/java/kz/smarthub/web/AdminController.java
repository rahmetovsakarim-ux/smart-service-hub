package kz.smarthub.web;

import jakarta.validation.Valid;
import kz.smarthub.domain.OrderStatus;
import kz.smarthub.domain.Role;
import kz.smarthub.security.CurrentUser;
import kz.smarthub.service.CatalogService;
import kz.smarthub.service.OrderService;
import kz.smarthub.service.StatisticsService;
import kz.smarthub.service.UserService;
import kz.smarthub.web.dto.Dto.ActiveRequest;
import kz.smarthub.web.dto.Dto.AssignRequest;
import kz.smarthub.web.dto.Dto.CategoryDto;
import kz.smarthub.web.dto.Dto.CategoryRequest;
import kz.smarthub.web.dto.Dto.OrderDto;
import kz.smarthub.web.dto.Dto.ServiceDto;
import kz.smarthub.web.dto.Dto.ServiceRequest;
import kz.smarthub.web.dto.Dto.StatsDto;
import kz.smarthub.web.dto.Dto.UserDto;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Әкімшілік басқару модулі (FR-12): каталог, пайдаланушылар, тапсырыстар, статистика. Тек ROLE_ADMIN. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final CurrentUser currentUser;
    private final OrderService orderService;
    private final CatalogService catalog;
    private final UserService userService;
    private final StatisticsService statistics;

    public AdminController(CurrentUser currentUser, OrderService orderService, CatalogService catalog,
                           UserService userService, StatisticsService statistics) {
        this.currentUser = currentUser;
        this.orderService = orderService;
        this.catalog = catalog;
        this.userService = userService;
        this.statistics = statistics;
    }

    // ---------- Тапсырыстар ----------
    @GetMapping("/orders")
    public List<OrderDto> orders(@RequestParam(required = false) OrderStatus status,
                                 @RequestParam(required = false) Long clientId,
                                 @RequestParam(required = false) Long workerId) {
        return orderService.listForAdmin(status, clientId, workerId);
    }

    @GetMapping("/orders/{id}/candidates")
    public List<UserDto> candidates(@PathVariable Long id) {
        return orderService.candidates(id);
    }

    @PostMapping("/orders/{id}/assign")
    public OrderDto assign(@PathVariable Long id, @Valid @RequestBody AssignRequest req) {
        return orderService.assign(currentUser.require(), id, req.workerId());
    }

    // ---------- Санаттар ----------
    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryDto createCategory(@Valid @RequestBody CategoryRequest req) {
        return catalog.createCategory(req);
    }

    @PutMapping("/categories/{id}")
    public CategoryDto updateCategory(@PathVariable Long id, @Valid @RequestBody CategoryRequest req) {
        return catalog.updateCategory(id, req);
    }

    @DeleteMapping("/categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCategory(@PathVariable Long id) {
        catalog.deleteCategory(id);
    }

    // ---------- Қызметтер ----------
    @GetMapping("/services")
    public List<ServiceDto> allServices() {
        return catalog.listAllServices();
    }

    @PostMapping("/services")
    @ResponseStatus(HttpStatus.CREATED)
    public ServiceDto createService(@Valid @RequestBody ServiceRequest req) {
        return catalog.createService(req);
    }

    @PutMapping("/services/{id}")
    public ServiceDto updateService(@PathVariable Long id, @Valid @RequestBody ServiceRequest req) {
        return catalog.updateService(id, req);
    }

    @DeleteMapping("/services/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteService(@PathVariable Long id) {
        catalog.deleteService(id);
    }

    // ---------- Пайдаланушылар ----------
    @GetMapping("/users")
    public List<UserDto> users(@RequestParam(required = false) Role role) {
        return userService.list(role);
    }

    @PostMapping("/users/{id}/active")
    public UserDto setActive(@PathVariable Long id, @RequestBody ActiveRequest req) {
        return userService.setActive(id, req.active());
    }

    // ---------- Статистика ----------
    @GetMapping("/stats")
    public StatsDto stats(@RequestParam(defaultValue = "14") int days) {
        return statistics.build(days);
    }
}
