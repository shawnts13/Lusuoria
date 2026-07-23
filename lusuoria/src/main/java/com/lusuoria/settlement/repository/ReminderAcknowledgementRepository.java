package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.ReminderAcknowledgement;
import com.lusuoria.settlement.enums.ReminderCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReminderAcknowledgementRepository extends JpaRepository<ReminderAcknowledgement, Long> {

    Optional<ReminderAcknowledgement> findByCategoryAndTargetIdAndEmployeeId(
            ReminderCategory category, Long targetId, Long employeeId);

    /** 批量查某个员工在某个类别下、对一批业务记录的标记情况，避免详情列表逐行查库 */
    List<ReminderAcknowledgement> findByCategoryAndEmployeeIdAndTargetIdIn(
            ReminderCategory category, Long employeeId, List<Long> targetIds);
}
