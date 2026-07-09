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

    /**
     * 某条业务记录当前是否已有某个类别的一条"待审核"事项（防止重复发起）。
     * 必须按 category 区分：同一条记录现在可能同时存在"删除审核"和"进度倒退审核"
     * 两种互不相关的待审核事项，不加 category 会把两种事项混在一起误判。
     */
    boolean existsByTargetModuleAndTargetIdAndCategoryAndStatus(
            PendingApprovalModule targetModule, Long targetId,
            PendingApprovalCategory category, PendingApprovalStatus status);

    Optional<PendingApproval> findByTargetModuleAndTargetIdAndCategoryAndStatus(
            PendingApprovalModule targetModule, Long targetId,
            PendingApprovalCategory category, PendingApprovalStatus status);

    @Query("SELECT p FROM PendingApproval p " +
           "WHERE p.status = 'PENDING' " +
           "AND (:category IS NULL OR p.category = :category) " +
           "ORDER BY p.createdAt DESC")
    Page<PendingApproval> findPending(@Param("category") PendingApprovalCategory category, Pageable pageable);

    /**
     * 批量查询：某个模块下、某个类别，哪些记录当前有"待审核"事项
     * （用于列表页批量标记"审核中"，避免逐行查库）。
     */
    @Query("SELECT p.targetId FROM PendingApproval p " +
           "WHERE p.targetModule = :module AND p.category = :category AND p.status = 'PENDING'")
    List<Long> findPendingTargetIds(@Param("module") PendingApprovalModule module,
                                     @Param("category") PendingApprovalCategory category);
}
