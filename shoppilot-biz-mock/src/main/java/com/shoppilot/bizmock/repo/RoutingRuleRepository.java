package com.shoppilot.bizmock.repo;

import com.shoppilot.bizmock.domain.RoutingRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RoutingRuleRepository extends JpaRepository<RoutingRule, Long> {

    /** 只读启用中的规则；表只有几行，挑最具体那条在内存里做（理由见 {@code RoutingRule} 的 javadoc）。 */
    List<RoutingRule> findByEnabledTrueOrderByIdAsc();
}