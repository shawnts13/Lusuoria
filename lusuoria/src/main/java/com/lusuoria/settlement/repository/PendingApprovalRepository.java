package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.PendingApproval;
import com.lusuoria.settlement.enums.PendingApprovalCategory;
import com.lusuoria.settlement.enums.PendingApprovalModule;
import com.lusuoria.settlement.enums.PendingApprovalStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PendingApprovalRepository extends JpaRepository<PendingApproval, Long> {

    /** 某条业务记录当前是否已有一条"待审核"的删除申请（防止重复发起） */
    boolean existsByTargetModuleAndTargetIdAndStatus(
            PendingApprovalModule targetModule, Long targetId, PendingApprovalStatus status);

    Optional<PendingApproval> findByTargetModuleAndTargetIdAndStatus(
            PendingApprovalModule targetModule, Long targetId, PendingApprovalStatus status);

    @Query("SELECT p FROM PendingApproval p " +
           "WHERE p.status = 'PENDING' " +
           "AND (:category IS NULL OR p.category = :category) " +
           "ORDER BY p.createdAt DESC")
    Page<PendingApproval> findPending(@Param("category") PendingApprovalCategory category, Pageable pageable);

    /** 批量查询：某个模块下，哪些记录当前有"待审核"的删除申请（用于列表页标记"审核中"，避免逐行查库） */
    @Query("SELECT p.targetId FROM PendingApproval p " +
           "WHERE p.targetModule = :module AND p.status = 'PENDING'")
    List<Long> findPendingTargetIds(@Param("module") PendingApprovalModule module);
}
