package com.example.leavemanagement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One employment stint of a workforce resource, upserted from the resource master Excel upload.
 *
 * <p>A resource ({@code resId}) can have several rows over time — one per designation/period, so
 * a designation change or resignation-then-rejoin is recorded as history rather than overwriting
 * the prior stint. At most one row per {@code resId} should have {@code active = true} at a time;
 * that row is "the current resource" as far as the rest of the app (attendance validation, get,
 * update) is concerned. {@code dateOfJoining}/{@code lastDate} are this stint's effective date
 * range.
 */
@Entity
@Table(name = "master_resource", indexes = @Index(name = "idx_master_resource_res_id", columnList = "res_id"))
@Getter
@Setter
public class MasterResource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(name = "res_id", nullable = false, length = 50)
    @Setter(AccessLevel.NONE)
    private String resId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "email_id", length = 200)
    private String emailId;

    @Column(name = "role", length = 200)
    private String designationType;

    @Column(name = "location", length = 100)
    private String location;

    /** Year-1..Year-7 rate card, e.g. {"Year-1": 100874.0, "Year-2": 107594.0, ...}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rate_card_by_year")
    private Map<String, Double> rateCardByYear = new LinkedHashMap<>();

    @Column(name = "category", length = 20)
    private String category;

    @Column(name = "category_details", length = 50)
    private String categoryDetails;

    @Column(name = "date_of_joining")
    private LocalDate dateOfJoining;

    @Column(name = "last_date")
    private LocalDate lastDate;

    @Column(name = "project_id", length = 50)
    private String projectId;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    protected MasterResource() {
        // for JPA
    }

    public MasterResource(String resId) {
        this.resId = resId;
    }
}
