package com.example.leavemanagement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

/**
 * A single public holiday that is marked onto the calendar for a given year.
 * A date may carry more than one holiday (e.g. a national day and a festival
 * on the same date), so uniqueness is on the {@code (holiday_date, name)} pair —
 * the same name can't be added twice to the same day.
 */
@Entity
@Table(
        name = "public_holiday",
        uniqueConstraints =
                @UniqueConstraint(name = "uk_public_holiday_date_name", columnNames = {"holiday_date", "name"}))
@Getter
@Setter
public class PublicHoliday {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(name = "holiday_date", nullable = false)
    @Setter(AccessLevel.NONE)
    private LocalDate holidayDate;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /** The calendar year the holiday belongs to (derived from holidayDate). */
    @Column(name = "holiday_year", nullable = false)
    @Setter(AccessLevel.NONE)
    private int year;

    protected PublicHoliday() {
        // for JPA
    }

    public PublicHoliday(LocalDate holidayDate, String name) {
        this.holidayDate = holidayDate;
        this.name = name;
        this.year = holidayDate.getYear();
    }

    public void setHolidayDate(LocalDate holidayDate) {
        this.holidayDate = holidayDate;
        this.year = holidayDate.getYear();
    }
}
