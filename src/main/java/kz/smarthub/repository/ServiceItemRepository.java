package kz.smarthub.repository;

import kz.smarthub.domain.ServiceItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ServiceItemRepository extends JpaRepository<ServiceItem, Long> {

    boolean existsByCategoryId(Long categoryId);

    List<ServiceItem> findAllByOrderByIdAsc();

    /** Іздеу модулі (FR-03): белсенді қызметтер, атауы/сипаттамасы бойынша. pattern = "%сөз%" (кіші әріппен). */
    @Query("select s from ServiceItem s where s.active = true and ("
            + "lower(s.nameKk) like :pattern or lower(s.nameRu) like :pattern "
            + "or lower(s.descriptionKk) like :pattern or lower(s.descriptionRu) like :pattern) "
            + "order by s.id")
    List<ServiceItem> search(@Param("pattern") String pattern);

    @Query("select s from ServiceItem s where s.active = true and s.category.id = :categoryId and ("
            + "lower(s.nameKk) like :pattern or lower(s.nameRu) like :pattern "
            + "or lower(s.descriptionKk) like :pattern or lower(s.descriptionRu) like :pattern) "
            + "order by s.id")
    List<ServiceItem> searchInCategory(@Param("categoryId") Long categoryId, @Param("pattern") String pattern);
}
