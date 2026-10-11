package tz.co.nlolo.lifeplatform.reporting.domain;

import java.time.LocalDate;
import java.time.Period;
import java.util.List;

public final class AgeBands {
    private AgeBands() {}

    public static final List<String> ORDER = List.of("0-17", "18-24", "25-34", "35-44", "45-54", "55-64", "65+", "Unknown");

    public static String of(LocalDate dateOfBirth, LocalDate asOf) {
        if (dateOfBirth == null) return "Unknown";
        int age = Period.between(dateOfBirth, asOf).getYears();
        if (age < 18) return "0-17";
        if (age < 25) return "18-24";
        if (age < 35) return "25-34";
        if (age < 45) return "35-44";
        if (age < 55) return "45-54";
        if (age < 65) return "55-64";
        return "65+";
    }
}
