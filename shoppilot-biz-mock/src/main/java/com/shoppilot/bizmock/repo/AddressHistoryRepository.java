package com.shoppilot.bizmock.repo;

import com.shoppilot.bizmock.domain.AddressHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AddressHistoryRepository extends JpaRepository<AddressHistory, Long> {
}
