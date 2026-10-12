package kz.smarthub.web.dto;

import kz.smarthub.domain.ServiceCategory;
import kz.smarthub.domain.ServiceItem;
import kz.smarthub.domain.User;
import kz.smarthub.web.dto.Dto.CategoryDto;
import kz.smarthub.web.dto.Dto.ServiceDto;
import kz.smarthub.web.dto.Dto.UserDto;

/** Entity → DTO түрлендіру (пароль хэші ешқашан сыртқа шықпайды). */
public final class Mapper {

    private Mapper() {
    }

    public static UserDto user(User u) {
        ServiceCategory sp = u.getSpecialization();
        return new UserDto(u.getId(), u.getFullName(), u.getPhone(), u.getEmail(), u.getRole(), u.isActive(),
                sp == null ? null : sp.getId(), sp == null ? null : sp.getNameKk(), sp == null ? null : sp.getNameRu(),
                u.getExperienceYears(), u.getDistrict(), u.getBio(), u.isAvailable(),
                u.getRatingAvg(), u.getRatingCount(), u.getCreatedAt());
    }

    public static CategoryDto category(ServiceCategory c) {
        return new CategoryDto(c.getId(), c.getNameKk(), c.getNameRu());
    }

    public static ServiceDto service(ServiceItem s) {
        return new ServiceDto(s.getId(), s.getCategory().getId(), s.getCategory().getNameKk(), s.getCategory().getNameRu(),
                s.getNameKk(), s.getNameRu(), s.getDescriptionKk(), s.getDescriptionRu(), s.getPrice(), s.getDurationMinutes(), s.isActive());
    }

    /** Catalog variant: adds the category-level rating and the number of specialists who can take an order now. */
    public static ServiceDto service(ServiceItem s, double ratingAvg, int ratingCount, int availableSpecialists) {
        return new ServiceDto(s.getId(), s.getCategory().getId(), s.getCategory().getNameKk(), s.getCategory().getNameRu(),
                s.getNameKk(), s.getNameRu(), s.getDescriptionKk(), s.getDescriptionRu(), s.getPrice(), s.getDurationMinutes(), s.isActive(),
                ratingAvg, ratingCount, availableSpecialists);
    }
}
