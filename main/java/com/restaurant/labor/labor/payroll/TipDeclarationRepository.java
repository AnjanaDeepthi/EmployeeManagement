package com.restaurant.labor.labor.payroll;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface TipDeclarationRepository extends JpaRepository<TipDeclaration, String> {

    List<TipDeclaration> findByTenantIdAndLocationIdAndBusinessDateBetween(String tenantId, String locationId,
                                                                          LocalDate from, LocalDate to);
}
