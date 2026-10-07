package tz.co.nlolo.lifeplatform.underwriting.domain;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import tz.co.nlolo.lifeplatform.product.api.FuneralRole;
import tz.co.nlolo.lifeplatform.underwriting.api.GroupProposal;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A group funeral schedule as an association sends it (2026-10-07): one row per life, a family's rows sharing the
 * association's member reference, exactly one MAIN_MEMBER per reference. Pure -- the plan's role rules are applied by
 * the caller; this reads the file and checks its shape, every error with its row number (row 1 is the header).
 */
public final class FuneralScheduleFile {

    public static final List<String> HEADER = List.of("member_reference", "role", "full_name", "date_of_birth", "sex",
        "id_number", "student", "beneficiary_name", "beneficiary_relationship", "beneficiary_phone");

    private static final List<DateTimeFormatter> DATES = List.of(DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("dd/MM/uuuu"), DateTimeFormatter.ofPattern("d/M/uuuu"));

    /** The lives read and every row's problem; the lives are usable only when {@code errors} is empty. */
    public record Parsed(List<GroupProposal.LifeLine> lives, List<String> errors) {}

    private FuneralScheduleFile() {}

    public static Parsed parse(byte[] content) {
        List<GroupProposal.LifeLine> lives = new ArrayList<>();
        List<Integer> rows = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        String text = new String(content, StandardCharsets.UTF_8).replace("﻿", "");
        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(true)
            .setTrim(true).setIgnoreHeaderCase(true).build();
        try (CSVParser parser = CSVParser.parse(new StringReader(text), format)) {
            List<String> missing = HEADER.subList(0, 4).stream()
                .filter(h -> !parser.getHeaderMap().containsKey(h)).toList();
            if (!missing.isEmpty()) {
                return new Parsed(List.of(), List.of("The file's header must name " + String.join(", ", HEADER)
                    + "; missing: " + String.join(", ", missing)));
            }
            for (CSVRecord record : parser) {
                int row = (int) record.getRecordNumber() + 1;
                List<String> rowErrors = new ArrayList<>();
                String reference = cell(record, "member_reference");
                String roleText = cell(record, "role");
                String name = cell(record, "full_name");
                String dobText = cell(record, "date_of_birth");
                if (reference.isEmpty()) rowErrors.add("member_reference is required");
                FuneralRole role = role(roleText);
                if (role == null) {
                    rowErrors.add("role must be MAIN_MEMBER, SPOUSE, CHILD, PARENT or EXTENDED, not '" + roleText + "'");
                }
                if (name.isEmpty()) rowErrors.add("full_name is required");
                LocalDate dob = date(dobText);
                if (dob == null) rowErrors.add("date_of_birth must be YYYY-MM-DD or DD/MM/YYYY, not '" + dobText + "'");
                rowErrors.forEach(e -> errors.add("Row " + row + ": " + e));
                if (rowErrors.isEmpty()) {
                    lives.add(new GroupProposal.LifeLine(reference, role, name, dob, blankToNull(cell(record, "sex")),
                        blankToNull(cell(record, "id_number")), yes(cell(record, "student")),
                        blankToNull(cell(record, "beneficiary_name")), blankToNull(cell(record, "beneficiary_relationship")),
                        blankToNull(cell(record, "beneficiary_phone"))));
                    rows.add(row);
                }
            }
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            return new Parsed(List.of(), List.of("The file is not a readable CSV: " + e.getMessage()));
        }
        errors.addAll(familyShape(lives, rows));
        if (lives.isEmpty() && errors.isEmpty()) {
            errors.add("The file names no life");
        }
        return new Parsed(lives, errors);
    }

    /** One MAIN_MEMBER per reference: none leaves a family with nobody to belong to, two are two families. */
    public static List<String> familyShape(List<GroupProposal.LifeLine> lives, List<Integer> rows) {
        Map<String, List<Integer>> mains = new LinkedHashMap<>();
        Map<String, Integer> firstRow = new LinkedHashMap<>();
        for (int i = 0; i < lives.size(); i++) {
            GroupProposal.LifeLine life = lives.get(i);
            int row = rows != null && i < rows.size() ? rows.get(i) : i + 1;
            firstRow.putIfAbsent(life.memberReference(), row);
            if (life.role() == FuneralRole.MAIN_MEMBER) {
                mains.computeIfAbsent(life.memberReference(), r -> new ArrayList<>()).add(row);
            }
        }
        List<String> errors = new ArrayList<>();
        firstRow.forEach((reference, row) -> {
            List<Integer> m = mains.getOrDefault(reference, List.of());
            if (m.isEmpty()) {
                errors.add("Row " + row + ": member " + reference + " has no MAIN_MEMBER row");
            } else if (m.size() > 1) {
                errors.add("Row " + m.get(1) + ": member " + reference + " already has a MAIN_MEMBER (row " + m.get(0) + ")");
            }
        });
        return errors;
    }

    private static String cell(CSVRecord record, String column) {
        return record.isMapped(column) && record.isSet(column) ? record.get(column).trim() : "";
    }

    private static FuneralRole role(String text) {
        String t = text.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        if (t.equals("MAIN") || t.equals("MEMBER")) t = "MAIN_MEMBER";
        try {
            return t.isEmpty() ? null : FuneralRole.valueOf(t);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static LocalDate date(String text) {
        for (DateTimeFormatter f : DATES) {
            try {
                return LocalDate.parse(text.trim(), f);
            } catch (DateTimeParseException ignored) {
                // the next format
            }
        }
        return null;
    }

    private static boolean yes(String text) {
        String t = text.trim().toLowerCase(Locale.ROOT);
        return t.equals("yes") || t.equals("y") || t.equals("true") || t.equals("1");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
