package com.shoppilot.bizmock.repo;

import com.shoppilot.bizmock.domain.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, String> {

    /**
     * 本租户的第一个买家，按主键升序取一条。
     *
     * <p>注册时用它给买家账号挑一个 {@code subjectRef}。刻意是「取一条」而不是 {@code findAll().get(0)}：
     * 后者把整张买家表拉进内存，只为了拿第一行。
     */
    Optional<Customer> findTopByOrderByIdAsc();
}
