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

    /**
     * 管理层计算"公司利润"时，要扣掉当月所有其他已到最终版员工的阶梯Bonus+奖金——必须是
     * finalConfirmed（不是 confirmed），因为要读 detailJson 快照，项目负责人/执行人员这两个
     * 角色 confirmed=true 但还没到最终版时快照还没写入。
     */
    List<Payslip> findByYearMonthAndFinalConfirmedTrueAndIsDeletedFalseAndEmployeeIdNot(String yearMonth, Long employeeId);

    /**
     * 整月所有工资单行（含未确认的草稿）。列表页/管理层确认前置校验用这一个查询批量拿到
     * 全部员工当月的确认状态，避免按员工一个个查（N+1）。
     */
    List<Payslip> findByYearMonthAndIsDeletedFalse(String yearMonth);
}
