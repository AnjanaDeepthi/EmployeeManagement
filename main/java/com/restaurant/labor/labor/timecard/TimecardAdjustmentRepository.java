package com.restaurant.labor.labor.timecard;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TimecardAdjustmentRepository extends JpaRepository<TimecardAdjustment, String> {

    List<TimecardAdjustment> findByTenantIdAndTimecardIdOrderByCreatedAtAsc(String tenantId, String timecardId);
}
