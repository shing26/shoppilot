package com.shoppilot.bizmock.repo;

import com.shoppilot.bizmock.domain.Coupon;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

    List<Coupon> findByCustomerId(String customerId);
}
