package com.example.leavemanagement.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.leavemanagement.entity.MasterResource;
import com.example.leavemanagement.entity.ProjectResource;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

/** Verifies the rate_card_by_year JSON column actually round-trips through H2, not just at schema-creation time. */
@DataJpaTest
class ProjectResourceRepositoryTest {

    @Autowired
    private MasterResourceRepository masterResourceRepository;

    @Autowired
    private ProjectResourceRepository repository;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Test
    void rateCardByYearRoundTripsThroughJsonColumn() {
        MasterResource resource = new MasterResource("R1");
        resource.setName("Asha");
        resource.setDateOfJoining(LocalDate.of(2026, 1, 1));
        masterResourceRepository.saveAndFlush(resource);

        ProjectResource assignment =
                new ProjectResource(resource, "P1", "Developer", LocalDate.of(2026, 1, 1));
        assignment.setRateCardByYear(Map.of("Year-1", 100874.0, "Year-2", 107594.0));
        repository.saveAndFlush(assignment);
        entityManager.clear(); // force a real reload from the DB, not the persistence-context cache

        ProjectResource reloaded = repository.findByResourceIdAndActiveTrue(resource.getId()).orElseThrow();
        assertThat(reloaded.getRateCardByYear())
                .containsEntry("Year-1", 100874.0)
                .containsEntry("Year-2", 107594.0);
    }
}
