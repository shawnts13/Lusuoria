package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.CommissionBonusTier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CommissionBonusTierRepository extends JpaRepository<CommissionBonusTier, Long> {

    List<CommissionBonusTier> findByEmployeeIdAndIsDeletedFalseOrderByMinAmountAsc(Long employeeId);

    void deleteByEmployeeId(Long employeeId);
}
