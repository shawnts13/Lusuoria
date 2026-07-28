package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.ReminderThresholdConfig;
import com.lusuoria.settlement.enums.ReminderCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReminderThresholdConfigRepository extends JpaRepository<ReminderThresholdConfig, Long> {

    List<ReminderThresholdConfig> findAllByOrderByCategoryAscSortOrderAsc();

    Optional<ReminderThresholdConfig> findByCategoryAndParamKey(ReminderCategory category, String paramKey);
}
