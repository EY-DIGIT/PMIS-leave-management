package com.example.leavemanagement.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.leavemanagement.entity.MasterResource;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

@DataJpaTest
class MasterResourceRepositoryTest {

    @Autowired
    private MasterResourceRepository repository;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Test
    void resIdIsTheUniqueBusinessKey() {
        MasterResource resource = new MasterResource("R1");
        resource.setName("Asha");
        resource.setDateOfJoining(LocalDate.of(2026, 1, 1));

        repository.saveAndFlush(resource);
        entityManager.clear(); // force a real reload from the DB, not the persistence-context cache

        MasterResource reloaded = repository.findByResId("R1").orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("Asha");
        assertThat(reloaded.getDateOfJoining()).isEqualTo(LocalDate.of(2026, 1, 1));
    }
}
