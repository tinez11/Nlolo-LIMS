package tz.co.nlolo.lifeplatform.product.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.product.api.AccountChargeApi;
import tz.co.nlolo.lifeplatform.product.api.AccountChargeNotFoundException;
import tz.co.nlolo.lifeplatform.product.api.AccountChargeView;
import tz.co.nlolo.lifeplatform.product.domain.AccountCharge;
import tz.co.nlolo.lifeplatform.product.domain.AccountChargeSetting;
import tz.co.nlolo.lifeplatform.product.infrastructure.AccountChargeRepository;
import tz.co.nlolo.lifeplatform.product.infrastructure.AccountChargeSettingRepository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** See {@link AccountChargeApi}. */
@Service
public class AccountCharges implements AccountChargeApi {

    private final AccountChargeRepository charges;
    private final AccountChargeSettingRepository settings;

    public AccountCharges(AccountChargeRepository charges, AccountChargeSettingRepository settings) {
        this.charges = charges;
        this.settings = settings;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountChargeView> list(boolean activeOnly) {
        return charges.findByTenantIdOrderByNameAsc(TenantContext.get()).stream()
            .filter(c -> !activeOnly || c.isActive())
            .map(AccountCharges::view)
            .toList();
    }

    @Override
    @Transactional
    public AccountChargeView create(String name, String description, String when, String amountType, BigDecimal amount,
                                    String currency, String createdBy) {
        UUID tenantId = TenantContext.get();
        if (name != null && charges.existsByTenantIdAndNameIgnoreCase(tenantId, name.trim())) {
            throw new IllegalArgumentException("There is already a charge called " + name.trim());
        }
        return view(charges.save(new AccountCharge(tenantId, name, description, when, amountType, amount, currency,
            createdBy)));
    }

    @Override
    @Transactional
    public AccountChargeView setActive(UUID chargeId, boolean active) {
        AccountCharge charge = charges.findByTenantIdAndChargeId(TenantContext.get(), chargeId)
            .orElseThrow(() -> new AccountChargeNotFoundException(chargeId));
        charge.setActive(active);
        return view(charges.save(charge));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountChargeView> requireChoosable(Collection<UUID> chargeIds) {
        List<UUID> ids = List.copyOf(new LinkedHashSet<>(chargeIds));
        Map<UUID, AccountCharge> found = charges.findByTenantIdAndChargeIdIn(TenantContext.get(), ids).stream()
            .collect(Collectors.toMap(AccountCharge::getChargeId, Function.identity()));
        for (UUID id : ids) {
            AccountCharge charge = found.get(id);
            if (charge == null) {
                throw new AccountChargeNotFoundException(id);
            }
            if (!charge.isActive()) {
                throw new IllegalArgumentException("The charge " + charge.getName() + " is no longer offered");
            }
        }
        return ids.stream().map(found::get).map(AccountCharges::view).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountChargeView> resolve(Collection<UUID> chargeIds) {
        if (chargeIds.isEmpty()) {
            return List.of();
        }
        return charges.findByTenantIdAndChargeIdIn(TenantContext.get(), chargeIds).stream()
            .map(AccountCharges::view).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean mayGoBelowMinimum() {
        return settings.findById(TenantContext.get()).map(AccountChargeSetting::isMayGoBelowMinimum).orElse(false);
    }

    @Override
    @Transactional
    public void setMayGoBelowMinimum(boolean allowed, String updatedBy) {
        UUID tenantId = TenantContext.get();
        AccountChargeSetting setting = settings.findById(tenantId).orElseGet(() -> new AccountChargeSetting(tenantId));
        setting.set(allowed, updatedBy);
        settings.save(setting);
    }

    private static AccountChargeView view(AccountCharge c) {
        return new AccountChargeView(c.getChargeId(), c.getName(), c.getDescription(), c.getWhen(), c.getAmountType(),
            c.getAmount(), c.getCurrency(), c.isActive(), c.getCreatedBy(), c.getCreatedAt());
    }
}
