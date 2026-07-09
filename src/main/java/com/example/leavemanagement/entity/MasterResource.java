package com.example.leavemanagement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

/** A workforce resource (employee) master record, upserted from the resource master Excel upload. */
@Entity
@Table(name = "master_resource")
@Getter
@Setter
public class MasterResource {

    @Id
    @Column(name = "res_id", length = 50)
    @Setter(AccessLevel.NONE)
    private String resId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "email_id", length = 200)
    private String emailId;

    @Column(name = "rate_card")
    private Double rateCard;

    @Column(name = "date_of_joining")
    private LocalDate dateOfJoining;

    @Column(name = "last_date")
    private LocalDate lastDate;

    @Column(name = "designation_type", length = 100)
    private String designationType;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    protected MasterResource() {
        // for JPA
    }

    public MasterResource(String resId) {
        this.resId = resId;
    }
}
