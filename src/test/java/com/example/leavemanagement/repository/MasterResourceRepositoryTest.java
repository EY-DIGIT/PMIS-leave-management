package com.example.leavemanagement.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.leavemanagement.entity.MasterResource;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

/** Verifies the rate_card_by_year JSON column actually round-trips through H2, not just at schema-creation time. */
@DataJpaTest
class MasterResourceRepositoryTest {

    @Autowired
    private MasterResourceRepository repository;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Test
    void rateCardByYearRoundTripsThroughJsonColumn() {
        MasterResource resource = new MasterResource("R1");
        resource.setName("Asha");
        resource.setActive(true);
        resource.setDateOfJoining(LocalDate.of(2026, 1, 1));
        resource.setRateCardByYear(Map.of("Year-1", 100874.0, "Year-2", 107594.0));

        repository.saveAndFlush(resource);
        entityManager.clear(); // force a real reload from the DB, not the persistence-context cache

        MasterResource reloaded = repository.findByResIdAndActiveTrue("R1").orElseThrow();
        assertThat(reloaded.getRateCardByYear())
                .containsEntry("Year-1", 100874.0)
                .containsEntry("Year-2", 107594.0);
    }
}
