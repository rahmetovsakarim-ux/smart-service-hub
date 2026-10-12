package kz.smarthub.service;

import kz.smarthub.domain.Role;
import kz.smarthub.domain.ServiceCategory;
import kz.smarthub.domain.ServiceItem;
import kz.smarthub.repository.ServiceCategoryRepository;
import kz.smarthub.repository.ServiceItemRepository;
import kz.smarthub.repository.UserRepository;
import kz.smarthub.web.dto.Dto.ServiceDto;
import kz.smarthub.web.dto.Dto.ServiceRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Catalog cards: rating and availability come from the specialists of the service's category. */
class CatalogServiceTest {

    private ServiceItemRepository services;
    private UserRepository users;
    private CatalogService catalog;

    @BeforeEach
    void setUp() {
        services = mock(ServiceItemRepository.class);
        users = mock(UserRepository.class);
        catalog = new CatalogService(mock(ServiceCategoryRepository.class), services, users);
    }

    private static ServiceItem item(long id, long categoryId, String name) {
        ServiceCategory category = new ServiceCategory("Санат " + categoryId, "Категория " + categoryId);
        category.setId(categoryId);
        ServiceItem s = new ServiceItem();
        s.setId(id);
        s.setCategory(category);
        s.setNameKk(name);
        s.setNameRu(name);
        s.setPrice(new BigDecimal("8000"));
        s.setActive(true);
        return s;
    }

    @Test
    void cardsCarryWeightedCategoryRatingAndAvailableSpecialists() {
        when(services.search("%%")).thenReturn(List.of(item(1, 10, "Fridge"), item(2, 20, "Cleaning")));
        // category 10: 2 specialists accepting orders; 4.5 x 2 reviews + 3.0 x 2 reviews = 15.0 over 4 reviews
        when(users.specialistStatsByCategory(Role.WORKER))
                .thenReturn(Collections.singletonList(new Object[]{10L, 2L, 15.0, 4L}));

        List<ServiceDto> cards = catalog.search(null, null);

        assertEquals(2, cards.size());
        ServiceDto fridge = cards.get(0);
        assertEquals(3.75, fridge.ratingAvg(), 0.0001);
        assertEquals(4, fridge.ratingCount());
        assertEquals(2, fridge.availableSpecialists());
    }

    @Test
    void categoryWithoutSpecialistsShowsNoRatingAndNoAvailability() {
        when(services.search("%%")).thenReturn(List.of(item(2, 20, "Cleaning")));
        when(users.specialistStatsByCategory(Role.WORKER))
                .thenReturn(Collections.singletonList(new Object[]{10L, 2L, 15.0, 4L}));

        ServiceDto card = catalog.search(null, null).get(0);

        assertEquals(0, card.ratingAvg(), 0.0001);
        assertEquals(0, card.ratingCount());
        assertEquals(0, card.availableSpecialists());
    }

    @Test
    void specialistsWithoutReviewsGiveZeroRatingNotADivisionByZero() {
        when(services.search("%%")).thenReturn(List.of(item(1, 10, "Fridge")));
        // 1 specialist, accepting orders, no reviews yet
        when(users.specialistStatsByCategory(Role.WORKER))
                .thenReturn(Collections.singletonList(new Object[]{10L, 1L, 0.0, 0L}));

        ServiceDto card = catalog.search(null, null).get(0);

        assertEquals(0, card.ratingAvg(), 0.0001);
        assertEquals(0, card.ratingCount());
        assertEquals(1, card.availableSpecialists());
    }

    @Test
    void statsAreNotQueriedWhenNothingMatchesTheSearch() {
        when(services.search("%nothing%")).thenReturn(List.of());

        assertTrue(catalog.search(null, "  Nothing ").isEmpty());

        verify(users, never()).specialistStatsByCategory(Role.WORKER);
    }

    private ServiceDto create(Integer durationMinutes) {
        ServiceCategoryRepository categories = mock(ServiceCategoryRepository.class);
        ServiceCategory category = new ServiceCategory("Жөндеу", "Ремонт");
        category.setId(1L);
        when(categories.findById(1L)).thenReturn(Optional.of(category));
        when(services.save(any(ServiceItem.class))).thenAnswer(inv -> inv.getArgument(0));
        CatalogService withCategories = new CatalogService(categories, services, users);
        return withCategories.createService(new ServiceRequest(1L, "Атауы", "Название", null, null,
                new BigDecimal("5000"), durationMinutes, null));
    }

    @Test
    void serviceDurationIsStoredAndReturned() {
        assertEquals(90, create(90).durationMinutes());
    }

    @Test
    void serviceDurationIsOptional() {
        assertNull(create(null).durationMinutes());
    }
}
