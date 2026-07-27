package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.ExecutorWageConfirmation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExecutorWageConfirmationRepository extends JpaRepository<ExecutorWageConfirmation, Long> {

    /** 2026-07 起按 (managerId, executorId, yearMonth) 三者唯一确定一条确认记录 */
    Optional<ExecutorWageConfirmation> findByManagerIdAndExecutorIdAndYearMonthAndIsDeletedFalse(
            Long managerId, Long executorId, String yearMonth);

    /**
     * 整月所有项目负责人×执行人员的确认记录一次查完（不管 confirmed 是不是 true），供批量计算
     * 执行人员/项目负责人工资单时一次性拿到全部人的状态，避免按人循环查（N+1）。
     */
    List<ExecutorWageConfirmation> findByYearMonthAndIsDeletedFalse(String yearMonth);
}
