package com.restaurant.labor.labor.timecard;

import com.restaurant.labor.common.AuditService;
import com.restaurant.labor.labor.punch.ClockState;
import com.restaurant.labor.labor.punch.ClockStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Finds forgotten clock-outs. Shifts still open after N hours are flagged for a manager to review.
 * They are deliberately NOT auto-closed at the scheduled end time: guessing could underpay someone.
 */
@Component
public class OpenShiftReviewJob {

    private static final Logger log = LoggerFactory.getLogger(OpenShiftReviewJob.class);

    private final ClockStateRepository clockStates;
    private final TimecardStateRepository timecards;
    private final AuditService audit;
    private final long reviewHours;

    public OpenShiftReviewJob(ClockStateRepository clockStates, TimecardStateRepository timecards, AuditService audit,
                              @Value("${labor.open-shift-review-hours:14}") long reviewHours) {
        this.clockStates = clockStates;
        this.timecards = timecards;
        this.audit = audit;
        this.reviewHours = reviewHours;
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT15M")
    @Transactional
    public void runOnSchedule() {
        run();
    }

    /** Returns how many timecards were flagged. Public so tests (or an admin endpoint) can trigger it. */
    @Transactional
    public int run() {
        Instant cutoff = Instant.now().minus(Duration.ofHours(reviewHours));
        int flagged = 0;
        for (ClockState cs : clockStates.findByStateNotAndOpenClockInAtBefore(ClockState.State.OFF, cutoff)) {
            String tcId = TimecardState.idFor(cs.getEmployeeId(), cs.getOpenBusinessDate());
            var tc = timecards.findByIdAndTenantId(tcId, cs.getTenantId()).orElse(null);
            if (tc != null && !tc.isNeedsReview()) {
                tc.flagForReview("open_shift_over_" + reviewHours + "h");
                audit.recordSystem(cs.getTenantId(), "timecard.flagged_for_review", "timecard", tcId,
                        "Shift open since " + cs.getOpenClockInAt());
                flagged++;
            }
        }
        if (flagged > 0) log.info("Flagged {} open shift(s) for manager review", flagged);
        return flagged;
    }
}
