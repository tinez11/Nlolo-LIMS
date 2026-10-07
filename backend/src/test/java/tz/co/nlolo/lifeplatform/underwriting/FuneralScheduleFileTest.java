package tz.co.nlolo.lifeplatform.underwriting;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.product.api.FuneralRole;
import tz.co.nlolo.lifeplatform.underwriting.domain.FuneralScheduleFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** A group funeral schedule file (2026-10-07): read row by row, its families' shape checked, every error numbered. */
class FuneralScheduleFileTest {

    private static final String HEADER =
        "member_reference,role,full_name,date_of_birth,sex,id_number,student,beneficiary_name,beneficiary_relationship,beneficiary_phone\n";

    private static FuneralScheduleFile.Parsed parse(String rows) {
        return FuneralScheduleFile.parse((HEADER + rows).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aFamilyOfFourAndAMemberAloneAreRead() {
        var parsed = parse("""
            M001,MAIN_MEMBER,Juma Ali,1980-05-12,MALE,,,Asha Juma,SPOUSE,0712000000
            M001,SPOUSE,Asha Juma,01/02/1983,FEMALE,,,,,
            M001,child,Neema Juma,2012-07-20,FEMALE,,,,,
            M001,CHILD,Baraka Juma,2008-03-03,MALE,,yes,,,
            M002,MAIN_MEMBER,Rehema Said,1975-11-30,FEMALE,,,,,
            """);
        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.lives()).hasSize(5);
        assertThat(parsed.lives().get(1).dateOfBirth()).isEqualTo(LocalDate.of(1983, 2, 1));
        assertThat(parsed.lives().get(2).role()).isEqualTo(FuneralRole.CHILD);
        assertThat(parsed.lives().get(3).student()).isTrue();
        assertThat(parsed.lives().get(0).beneficiaryName()).isEqualTo("Asha Juma");
    }

    @Test
    void aFamilyWithoutAMainMemberAndTwoMainMembersUnderOneNumberAreRefused() {
        var parsed = parse("""
            M001,SPOUSE,Asha Juma,1983-02-01,FEMALE,,,,,
            M002,MAIN_MEMBER,Rehema Said,1975-11-30,FEMALE,,,,,
            M002,MAIN_MEMBER,Said Omari,1970-01-01,MALE,,,,,
            """);
        assertThat(parsed.errors()).containsExactly(
            "Row 2: member M001 has no MAIN_MEMBER row",
            "Row 4: member M002 already has a MAIN_MEMBER (row 3)");
    }

    @Test
    void everyBadCellIsNamedWithItsRow() {
        var parsed = parse("""
            M001,MAIN_MEMBER,Juma Ali,12-05-1980,MALE,,,,,
            M001,COUSIN,,1990-01-01,,,,,,
            """);
        assertThat(parsed.errors()).contains(
            "Row 2: date_of_birth must be YYYY-MM-DD or DD/MM/YYYY, not '12-05-1980'",
            "Row 3: role must be MAIN_MEMBER, SPOUSE, CHILD, PARENT or EXTENDED, not 'COUSIN'",
            "Row 3: full_name is required");
    }

    @Test
    void aFileWithoutTheHeaderIsRefusedWhole() {
        var parsed = FuneralScheduleFile.parse("ref,name\nM1,Juma\n".getBytes(StandardCharsets.UTF_8));
        assertThat(parsed.lives()).isEmpty();
        assertThat(parsed.errors()).singleElement().asString().contains("missing: member_reference, role, full_name, date_of_birth");
    }
}
