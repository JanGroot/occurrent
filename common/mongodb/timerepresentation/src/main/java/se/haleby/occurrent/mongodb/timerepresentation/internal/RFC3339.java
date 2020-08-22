package se.haleby.occurrent.mongodb.timerepresentation.internal;

import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;

import static java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME;

/**
 * Utilities for RFC3339 date/time conversions.
 */
public class RFC3339 {

    public static final DateTimeFormatter RFC_3339_DATE_TIME_FORMATTER = new DateTimeFormatterBuilder()
            .append(ISO_LOCAL_DATE_TIME)
            .optionalStart()
            .appendOffset("+HH:MM", "Z")
            .optionalEnd()
            .toFormatter();
}
