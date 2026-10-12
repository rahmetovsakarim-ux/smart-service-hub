package kz.smarthub.service;

import kz.smarthub.domain.ServiceCategory;
import kz.smarthub.domain.Role;
import kz.smarthub.domain.ServiceItem;
import kz.smarthub.exception.ApiException;
import kz.smarthub.repository.ServiceCategoryRepository;
import kz.smarthub.repository.ServiceItemRepository;
import kz.smarthub.repository.UserRepository;
import kz.smarthub.web.dto.Dto.CategoryDto;
import kz.smarthub.web.dto.Dto.CategoryRequest;
import kz.smarthub.web.dto.Dto.ServiceDto;
import kz.smarthub.web.dto.Dto.ServiceRequest;
import kz.smarthub.web.dto.Mapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Қызметтер каталогы: көрсету + іздеу/сүзгі (FR-03) және Әкімші басқаруы (FR-12, NFR-09). */
@Service
public class CatalogService {

    private final ServiceCategoryRepository categories;
    private final ServiceItemRepository services;
    private final UserRepository users;

    public CatalogService(ServiceCategoryRepository categories, ServiceItemRepository services, UserRepository users) {
        this.categories = categories;
        this.services = services;
        this.users = users;
    }

    // ---------- Клиентке көрсету / іздеу ----------
    @Transactional(readOnly = true)
    public List<CategoryDto> listCategories() {
        return categories.findAllByOrderByIdAsc().stream().map(Mapper::category).toList();
    }

    @Transactional(readOnly = true)
    public List<ServiceDto> search(Long categoryId, String q) {
        String pattern = "%" + (q == null ? "" : q.trim().toLowerCase(Locale.ROOT)) + "%";
        List<ServiceItem> found = categoryId == null
                ? services.search(pattern)
                : services.searchInCategory(categoryId, pattern);
        if (found.isEmpty()) {
            return List.of();
        }
        Map<Long, CategoryStats> stats = categoryStats();
        CategoryStats none = new CategoryStats(0, 0, 0);
        return found.stream()
                .map(s -> {
                    CategoryStats c = stats.getOrDefault(s.getCategory().getId(), none);
                    return Mapper.service(s, c.ratingAvg(), c.ratingCount(), c.available());
                })
                .toList();
    }

    /** Rating and availability of the specialists of one category, shown on the service cards. */
    record CategoryStats(double ratingAvg, int ratingCount, int available) {
    }

    /** One aggregate query for all categories (not one per service). */
    private Map<Long, CategoryStats> categoryStats() {
        Map<Long, CategoryStats> result = new HashMap<>();
        for (Object[] row : users.specialistStatsByCategory(Role.WORKER)) {
            long reviews = row[3] == null ? 0 : ((Number) row[3]).longValue();
            double weighted = row[2] == null ? 0 : ((Number) row[2]).doubleValue();
            double avg = reviews == 0 ? 0 : Math.round(weighted / reviews * 100.0) / 100.0;
            int available = row[1] == null ? 0 : ((Number) row[1]).intValue();
            result.put(((Number) row[0]).longValue(), new CategoryStats(avg, (int) reviews, available));
        }
        return result;
    }

    // ---------- Әкімші: санаттар ----------
    @Transactional
    public CategoryDto createCategory(CategoryRequest r) {
        return Mapper.category(categories.save(new ServiceCategory(r.nameKk().trim(), r.nameRu().trim())));
    }

    @Transactional
    public CategoryDto updateCategory(Long id, CategoryRequest r) {
        ServiceCategory c = categories.findById(id).orElseThrow(() -> notFound("Санат", "Категория"));
        c.setNameKk(r.nameKk().trim());
        c.setNameRu(r.nameRu().trim());
        return Mapper.category(categories.save(c));
    }

    @Transactional
    public void deleteCategory(Long id) {
        ServiceCategory c = categories.findById(id).orElseThrow(() -> notFound("Санат", "Категория"));
        if (services.existsByCategoryId(id) || users.existsBySpecializationId(id)) {
            throw ApiException.conflict("Санатта қызметтер немесе мамандар бар, өшіруге болмайды||В категории есть услуги или специалисты, удаление невозможно");
        }
        categories.delete(c);
    }

    // ---------- Әкімші: қызметтер ----------
    @Transactional(readOnly = true)
    public List<ServiceDto> listAllServices() {
        return services.findAllByOrderByIdAsc().stream().map(Mapper::service).toList();
    }

    @Transactional
    public ServiceDto createService(ServiceRequest r) {
        ServiceItem s = new ServiceItem();
        apply(s, r);
        return Mapper.service(services.save(s));
    }

    @Transactional
    public ServiceDto updateService(Long id, ServiceRequest r) {
        ServiceItem s = services.findById(id).orElseThrow(() -> notFound("Қызмет", "Услуга"));
        apply(s, r);
        return Mapper.service(services.save(s));
    }

    /** "Өшіру" = каталогтан жасыру (active=false), сонда ескі тапсырыстар тарихы бұзылмайды. */
    @Transactional
    public void deleteService(Long id) {
        ServiceItem s = services.findById(id).orElseThrow(() -> notFound("Қызмет", "Услуга"));
        s.setActive(false);
        services.save(s);
    }

    private void apply(ServiceItem s, ServiceRequest r) {
        ServiceCategory c = categories.findById(r.categoryId()).orElseThrow(() -> notFound("Санат", "Категория"));
        s.setCategory(c);
        s.setNameKk(r.nameKk().trim());
        s.setNameRu(r.nameRu().trim());
        s.setDescriptionKk(r.descriptionKk());
        s.setDescriptionRu(r.descriptionRu());
        s.setPrice(r.price());
        s.setDurationMinutes(r.durationMinutes());
        s.setActive(r.active() == null || r.active());
    }

    private ApiException notFound(String kk, String ru) {
        return ApiException.notFound(kk + " табылмады||" + ru + " не найдена");
    }
}
