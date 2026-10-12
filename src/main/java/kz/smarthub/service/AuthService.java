package kz.smarthub.service;

import kz.smarthub.domain.Districts;
import kz.smarthub.domain.Role;
import kz.smarthub.domain.ServiceCategory;
import kz.smarthub.domain.User;
import kz.smarthub.exception.ApiException;
import kz.smarthub.repository.ServiceCategoryRepository;
import kz.smarthub.repository.UserRepository;
import kz.smarthub.web.dto.Dto.RegisterRequest;
import kz.smarthub.web.dto.Dto.UserDto;
import kz.smarthub.web.dto.Mapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Тіркеу (UC-01, FR-01). Клиент пен Маман өзін тіркей алады; Әкімшіні тек жүйе жасайды. */
@Service
public class AuthService {

    private final UserRepository users;
    private final ServiceCategoryRepository categories;
    private final PasswordEncoder encoder;

    public AuthService(UserRepository users, ServiceCategoryRepository categories, PasswordEncoder encoder) {
        this.users = users;
        this.categories = categories;
        this.encoder = encoder;
    }

    @Transactional
    public UserDto register(RegisterRequest r) {
        if (r.role() == Role.ADMIN) {
            throw ApiException.forbidden("Әкімші ретінде тіркелуге болмайды||Нельзя зарегистрироваться как администратор");
        }
        String phone = r.phone().trim();
        if (users.existsByPhone(phone)) {
            throw ApiException.conflict("Бұл телефон нөмірі тіркелген||Этот номер телефона уже зарегистрирован");
        }
        String email = (r.email() == null || r.email().isBlank()) ? null : r.email().trim().toLowerCase();
        if (email != null && users.existsByEmail(email)) {
            throw ApiException.conflict("Бұл e-mail тіркелген||Этот e-mail уже зарегистрирован");
        }

        User u = new User();
        u.setFullName(r.fullName().trim());
        u.setPhone(phone);
        u.setEmail(email);
        u.setPasswordHash(encoder.encode(r.password()));
        u.setRole(r.role());

        if (r.role() == Role.WORKER) {
            if (r.specializationId() == null) {
                throw ApiException.badRequest("Мамандану санатын таңдаңыз||Выберите категорию специализации");
            }
            ServiceCategory spec = categories.findById(r.specializationId())
                    .orElseThrow(() -> ApiException.badRequest("Санат табылмады||Категория не найдена"));
            if (!Districts.isValid(r.district())) {
                throw ApiException.badRequest("Ауданды таңдаңыз||Выберите район");
            }
            u.setSpecialization(spec);
            u.setDistrict(r.district());
            u.setExperienceYears(r.experienceYears() == null ? 0 : Math.max(0, r.experienceYears()));
            u.setBio(r.bio());
        }
        return Mapper.user(users.save(u));
    }
}
