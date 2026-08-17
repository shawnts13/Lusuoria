package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.CommissionBonusTier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CommissionBonusTierRepository extends JpaRepository<CommissionBonusTier, Long> {

    /** 全表未删除的阶梯（2026-08-17 新增，供 CommissionBonusTierCache 启动/刷新时一次性加载用） */
    List<CommissionBonusTier> findByIsDeletedFalse();

    List<CommissionBonusTier> findByEmployeeIdAndIsDeletedFalseOrderByMinAmountAsc(Long employeeId);

    /** "员工管理"列表页批量展示用：一次性取出这一批员工的全部 bonus 阶梯，避免逐条查库 */
    List<CommissionBonusTier> findByEmployeeIdInAndIsDeletedFalseOrderByMinAmountAsc(List<Long> employeeIds);

    /** 真删（非软删）：员工编辑保存时先整体清空旧阶梯再重新插入这一批新的，阶梯本身没有独立查看/追溯的价值，不需要软删语义 */
    void deleteByEmployeeId(Long employeeId);
}
