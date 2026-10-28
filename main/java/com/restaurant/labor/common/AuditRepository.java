package com.restaurant.labor.common;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Collection;

public interface AuditRepository extends JpaRepository<AuditEvent, String> {

    List<AuditEvent> findByTenantIdAndActionInAndIdGreaterThanOrderByIdAsc(
            String tenantId, Collection<String> actions, String afterId, Limit limit);

    List<AuditEvent> findByTenantIdAndTargetIdOrderByIdAsc(String tenantId, String targetId);
}
