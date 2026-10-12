package kz.smarthub.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import kz.smarthub.domain.OrderStatus;
import kz.smarthub.domain.PaymentStatus;
import kz.smarthub.domain.Role;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Барлық сұраныс/жауап DTO-лары (REST API, JSON — NFR-07). */
public final class Dto {

    private Dto() {
    }

    // ================= Сұраныстар =================
    public record RegisterRequest(
            @NotBlank @Size(max = 100) String fullName,
            @NotBlank @Pattern(regexp = "^\\+?[0-9]{10,15}$") String phone,
            @Email @Size(max = 120) String email,
            @NotBlank @Size(min = 6, max = 64) String password,
            @NotNull Role role,
            Long specializationId,
            Integer experienceYears,
            String district,
            @Size(max = 500) String bio) {
    }

    public record LoginRequest(@NotBlank String login, @NotBlank String password) {
    }

    public record CategoryRequest(@NotBlank @Size(max = 100) String nameKk, @NotBlank @Size(max = 100) String nameRu) {
    }

    public record ServiceRequest(
            @NotNull Long categoryId,
            @NotBlank @Size(max = 150) String nameKk,
            @NotBlank @Size(max = 150) String nameRu,
            @Size(max = 1000) String descriptionKk,
            @Size(max = 1000) String descriptionRu,
            @NotNull @DecimalMin("0.0") BigDecimal price,
            @Min(5) @Max(1440) Integer durationMinutes,
            Boolean active) {
    }

    public record OrderRequest(
            @NotNull Long serviceId,
            @NotBlank @Size(max = 255) String address,
            @NotBlank String district,
            @NotNull @Future LocalDateTime scheduledTime,
            @Size(max = 500) String note) {
    }

    public record StatusRequest(@NotNull OrderStatus status) {
    }

    public record AssignRequest(@NotNull Long workerId) {
    }

    public record PaymentRequest(@NotBlank String cardNumber) {
    }

    public record ReviewRequest(@Min(1) @Max(5) int rating, @Size(max = 500) String comment) {
    }

    public record ProfileRequest(Integer experienceYears, @Size(max = 500) String bio, String district, Boolean available) {
    }

    public record ActiveRequest(boolean active) {
    }

    // ================= Жауаптар =================
    public record UserDto(
            Long id, String fullName, String phone, String email, Role role, boolean active,
            Long specializationId, String specializationKk, String specializationRu,
            Integer experienceYears, String district, String bio, boolean available,
            double ratingAvg, int ratingCount, LocalDateTime createdAt) {
    }

    public record CategoryDto(Long id, String nameKk, String nameRu) {
    }

    /**
     * A catalog service. {@code ratingAvg}/{@code ratingCount} (average of the reviews received by the specialists of
     * the service's category) and {@code availableSpecialists} (active specialists currently accepting orders) are
     * filled for the public catalog and are 0 elsewhere (e.g. admin editing responses).
     */
    public record ServiceDto(
            Long id, Long categoryId, String categoryNameKk, String categoryNameRu,
            String nameKk, String nameRu, String descriptionKk, String descriptionRu,
            BigDecimal price, Integer durationMinutes, boolean active,
            double ratingAvg, int ratingCount, int availableSpecialists) {

        /** Without the catalog statistics (kept so existing callers do not change). */
        public ServiceDto(Long id, Long categoryId, String categoryNameKk, String categoryNameRu,
                          String nameKk, String nameRu, String descriptionKk, String descriptionRu,
                          BigDecimal price, Integer durationMinutes, boolean active) {
            this(id, categoryId, categoryNameKk, categoryNameRu, nameKk, nameRu, descriptionKk, descriptionRu,
                    price, durationMinutes, active, 0, 0, 0);
        }
    }

    public record HistoryDto(OrderStatus oldStatus, OrderStatus newStatus, String changedBy,
                             LocalDateTime changedAt, String note) {
    }

    public record OrderDto(
            Long id, Long serviceId, String serviceNameKk, String serviceNameRu, BigDecimal price,
            String address, String district, LocalDateTime scheduledTime, String note,
            OrderStatus status, LocalDateTime createdAt,
            Long clientId, String clientName, String clientPhone,
            Long workerId, String workerName, String workerPhone, double workerRating,
            boolean paid, boolean reviewed, Integer reviewRating,
            List<HistoryDto> history) {
    }

    public record PaymentDto(PaymentStatus status, String transactionRef, String message) {
    }

    public record ReviewDto(Long id, Long orderId, int rating, String text, String clientName, LocalDateTime createdAt) {
    }

    public record NotificationDto(Long id, Long orderId, String messageKk, String messageRu,
                                  boolean seen, LocalDateTime createdAt) {
    }

    public record NotificationsResponse(long unread, List<NotificationDto> items) {
    }

    public record WorkerStat(Long id, String fullName, long total, long completed, double ratingAvg, int ratingCount) {
    }

    public record DayStat(LocalDate date, long count) {
    }

    public record StatsDto(
            long totalOrders, long unassigned, long periodOrders, int days,
            Map<String, Long> byStatus, Map<String, Long> usersByRole,
            List<WorkerStat> workers, List<DayStat> daily, BigDecimal revenue) {
    }
}
