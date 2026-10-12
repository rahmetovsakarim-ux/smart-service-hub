package kz.smarthub.service;

import kz.smarthub.domain.Districts;
import kz.smarthub.domain.Role;
import kz.smarthub.domain.User;
import kz.smarthub.exception.ApiException;
import kz.smarthub.repository.UserRepository;
import kz.smarthub.web.dto.Dto.ProfileRequest;
import kz.smarthub.web.dto.Dto.UserDto;
import kz.smarthub.web.dto.Mapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Пайдаланушыларды басқару (Әкімші, FR-12) және маман профилі. */
@Service
public class UserService {

    private final UserRepository users;

    public UserService(UserRepository users) {
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<UserDto> list(Role role) {
        List<User> list = role == null ? users.findAllByOrderByCreatedAtDesc() : users.findByRoleOrderByCreatedAtDesc(role);
        return list.stream().map(Mapper::user).toList();
    }

    /** Аккаунтты бұғаттау / бұғаттан шығару. Әкімшіні бұғаттауға болмайды. */
    @Transactional
    public UserDto setActive(Long id, boolean active) {
        User u = users.findById(id).orElseThrow(() -> ApiException.notFound("Пайдаланушы табылмады||Пользователь не найден"));
        if (u.getRole() == Role.ADMIN) {
            throw ApiException.badRequest("Әкімшіні бұғаттауға болмайды||Администратора нельзя заблокировать");
        }
        u.setActive(active);
        return Mapper.user(users.save(u));
    }

    @Transactional
    public UserDto updateProfile(User worker, ProfileRequest r) {
        if (r.experienceYears() != null) {
            worker.setExperienceYears(Math.max(0, r.experienceYears()));
        }
        if (r.bio() != null) {
            worker.setBio(r.bio());
        }
        if (r.district() != null) {
            if (!Districts.isValid(r.district())) {
                throw ApiException.badRequest("Аудан қате||Неверный район");
            }
            worker.setDistrict(r.district());
        }
        if (r.available() != null) {
            worker.setAvailable(r.available());
        }
        return Mapper.user(users.save(worker));
    }
}
