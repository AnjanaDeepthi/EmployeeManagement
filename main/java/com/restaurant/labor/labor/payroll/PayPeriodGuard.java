package com.restaurant.labor.labor.payroll;

import com.restaurant.labor.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** Locked pay periods are frozen: nothing that changes hours or tips may land inside one. */
@Component
public class PayPeriodGuard {

    private final PayPeriodRepository periods;

    public PayPeriodGuard(PayPeriodRepository periods) {
        this.periods = periods;
    }

    public void assertOpen(String tenantId, String locationId, LocalDate businessDate) {
        periods.findCovering(tenantId, locationId, businessDate).filter(PayPeriod::isClosed).ifPresent(p -> {
            throw new ApiException(HttpStatus.LOCKED, "pay_period_locked",
                    "This date is in a locked pay period. Record a prior-period adjustment in the current open period instead.")
                    .with("pay_period_id", p.getId())
                    .with("prior_period_adjustment_endpoint", "POST /v1/pay_periods/{open_period_id}/prior_period_adjustments");
        });
    }
}
