package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.Payslip;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PayslipRepository extends JpaRepository<Payslip, Long> {

    /**
     * 按 (员工,月份) 查找工资单行，供"先查后插/复用"的 get-or-create 逻辑用。
     * Payslip 目前没有任何流程会把 isDeleted 置 true（确认/取消确认走的是 confirmed
     * 这个独立字段），所以这里直接过滤 isDeleted=false 即可，不需要"复活软删行"那一套。
     */
    Optional<Payslip> findByEmployeeIdAndYearMonthAndIsDeletedFalse(Long employeeId, String yearMonth);

    /** 管理层计算"公司利润"时，要扣掉当月所有其他已确认员工的阶梯Bonus+奖金 */
    List<Payslip> findByYearMonthAndConfirmedTrueAndIsDeletedFalseAndEmployeeIdNot(String yearMonth, Long employeeId);
}
