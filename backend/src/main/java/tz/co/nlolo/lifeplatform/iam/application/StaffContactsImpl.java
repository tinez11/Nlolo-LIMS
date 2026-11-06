package tz.co.nlolo.lifeplatform.iam.application;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.iam.api.StaffContacts;
import tz.co.nlolo.lifeplatform.TenantContext;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The staff record's phone, held to the current tenant (2026-11-06). */
@Service
class StaffContactsImpl implements StaffContacts {

    private final NamedParameterJdbcTemplate jdbc;

    StaffContactsImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> phoneOf(UUID userId) {
        List<String> phones = jdbc.queryForList(
            "SELECT phone FROM iam.staff_user WHERE tenant_id = :tenant AND user_id = :user",
            new MapSqlParameterSource("tenant", TenantContext.get()).addValue("user", userId), String.class);
        return phones.stream().filter(p -> p != null && !p.isBlank()).map(String::trim).findFirst();
    }
}
