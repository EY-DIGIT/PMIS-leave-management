package com.example.leavemanagement.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;
import java.time.LocalDate;

/**
 * Parses a date that may arrive either as a plain ISO date ({@code 2027-05-11}) or as a full ISO
 * datetime with offset ({@code 2027-05-11T00:00:00+05:30}) — the projects service returns the latter
 * for activity {@code startDate}/{@code endDate}. Only the calendar date is kept.
 */
public class LenientLocalDateDeserializer extends JsonDeserializer<LocalDate> {

    @Override
    public LocalDate deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String text = p.getValueAsString();
        if (text == null || text.isBlank()) {
            return null;
        }
        text = text.trim();
        // Both "2027-05-11" and "2027-05-11T00:00:00+05:30" start with the yyyy-MM-dd calendar date.
        return LocalDate.parse(text.substring(0, 10));
    }
}
