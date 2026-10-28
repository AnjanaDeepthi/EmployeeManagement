package com.restaurant.labor.employee;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.HashSet;
import java.util.Set;

/** A named bundle of permissions plus limits. System roles are read-only; tenants can add custom roles. */
@Entity
@Table(name = "roles")
public class Role {

    public static final String OWNER_KEY = "owner";

    @Id
    private String id;
    private String tenantId;
    /** Stable key for system roles (owner, general_manager, ...); null for custom roles. */
    private String systemKey;
    private String name;
    private boolean system;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "role_permissions", joinColumns = @JoinColumn(name = "role_id"))
    @Column(name = "permission")
    private Set<String> permissions = new HashSet<>();

    /** Maximum refund this role may issue, in cents. Null means unlimited. */
    private Long refundLimitCents;

    /** Null until first saved, so Spring Data treats the entity as new and persists it. */
    @Version
    private Long version;

    protected Role() {}

    public Role(String id, String tenantId, String systemKey, String name, boolean system,
                Set<String> permissions, Long refundLimitCents) {
        this.id = id;
        this.tenantId = tenantId;
        this.systemKey = systemKey;
        this.name = name;
        this.system = system;
        this.permissions = new HashSet<>(permissions);
        this.refundLimitCents = refundLimitCents;
    }

    public boolean isOwnerRole() { return OWNER_KEY.equals(systemKey); }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getSystemKey() { return systemKey; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isSystem() { return system; }
    public Set<String> getPermissions() { return permissions; }
    public void setPermissions(Set<String> permissions) { this.permissions = new HashSet<>(permissions); }
    public Long getRefundLimitCents() { return refundLimitCents; }
    public void setRefundLimitCents(Long refundLimitCents) { this.refundLimitCents = refundLimitCents; }
    public long getVersion() { return version == null ? 0 : version; }
}
