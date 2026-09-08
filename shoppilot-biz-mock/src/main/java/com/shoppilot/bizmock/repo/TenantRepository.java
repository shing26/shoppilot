package com.shoppilot.bizmock.repo;

import com.shoppilot.bizmock.domain.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRepository extends JpaRepository<Tenant, String> {
}
