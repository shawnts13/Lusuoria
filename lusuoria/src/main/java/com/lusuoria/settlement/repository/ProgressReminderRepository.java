package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.ProgressReminder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProgressReminderRepository extends JpaRepository<ProgressReminder, Long> {

    /**
     * 表里任何时刻都只有"最新一次跑批"的结果，按受众角色查即可，不需要按 batchDate 过滤。
     * 不在这里排序：urgency/category 是 EnumType.STRING，数据库按字母序排跟业务期望的
     * "已超期 -> 1-3天 -> 3-7天"顺序对不上，排序统一交给 Service 层按枚举 ordinal 处理。
     */
    List<ProgressReminder> findByAudienceEmployeeRole(String audienceEmployeeRole);
}
