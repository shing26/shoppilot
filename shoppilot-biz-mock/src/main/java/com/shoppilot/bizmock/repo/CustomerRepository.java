package com.shoppilot.bizmock.repo;

import com.shoppilot.bizmock.domain.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<Customer, String> {
}
