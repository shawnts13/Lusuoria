package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.ExecutorWageConfirmation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExecutorWageConfirmationRepository extends JpaRepository<ExecutorWageConfirmation, Long> {

    Optional<ExecutorWageConfirmation> findByManagerIdAndYearMonthAndIsDeletedFalse(Long managerId, String yearMonth);

    /**
     * 整月所有项目负责人的确认记录一次查完（不管 confirmed 是不是 true），供批量计算执行人员/
     * 项目负责人工资单时一次性拿到全部人的状态，避免按人循环查（N+1）。
     */
    List<ExecutorWageConfirmation> findByYearMonthAndIsDeletedFalse(String yearMonth);
}
