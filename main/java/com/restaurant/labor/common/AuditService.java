package com.restaurant.labor.common;

import org.springframework.stereotype.Service;

@Service
public class AuditService {

    private final AuditRepository repository;

    public AuditService(AuditRepository repository) {
        this.repository = repository;
    }

    public void record(String action, String targetType, String targetId, String details) {
        Caller caller = Caller.current();
        repository.save(new AuditEvent(caller.tenantId(), caller.employeeId(), action, targetType, targetId, details));
    }

    public void recordSystem(String tenantId, String action, String targetType, String targetId, String details) {
        repository.save(new AuditEvent(tenantId, "system", action, targetType, targetId, details));
    }
}
