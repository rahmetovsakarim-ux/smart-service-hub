package kz.smarthub.web;

import kz.smarthub.domain.Districts;
import kz.smarthub.service.CatalogService;
import kz.smarthub.web.dto.Dto.CategoryDto;
import kz.smarthub.web.dto.Dto.ServiceDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** UC-03 Қызметтерді қарау және іздеу (ашық, кіру қажет емес). */
@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

    private final CatalogService catalog;

    public CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/categories")
    public List<CategoryDto> categories() {
        return catalog.listCategories();
    }

    @GetMapping("/services")
    public List<ServiceDto> services(@RequestParam(required = false) Long categoryId,
                                     @RequestParam(required = false) String q) {
        return catalog.search(categoryId, q);
    }

    @GetMapping("/districts")
    public List<String> districts() {
        return Districts.CODES;
    }
}
