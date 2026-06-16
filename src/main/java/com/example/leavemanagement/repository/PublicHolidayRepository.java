package com.example.leavemanagement.repository;

import com.example.leavemanagement.entity.PublicHoliday;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PublicHolidayRepository extends JpaRepository<PublicHoliday, Long> {

    List<PublicHoliday> findByYearOrderByHolidayDateAsc(int year);

    /** All holidays on a date (a date may carry more than one). */
    List<PublicHoliday> findByHolidayDateOrderByNameAsc(LocalDate holidayDate);

    /** A specific named holiday on a date — used to upsert without duplicating. */
    Optional<PublicHoliday> findByHolidayDateAndName(LocalDate holidayDate, String name);

    List<PublicHoliday> findByHolidayDateBetweenOrderByHolidayDateAsc(LocalDate start, LocalDate end);
}
