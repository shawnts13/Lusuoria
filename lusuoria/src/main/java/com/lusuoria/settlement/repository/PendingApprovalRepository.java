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
     * "项目管理员"视角的待审核队列（2026-08-21 新增）：只看品牌方在自己负责范围内的记录。
     * brandIds 由调用方保证非空（PendingApprovalService.listPending() 里没有品牌方时直接
     * 返回空 Page，不会走到这条查询），所以这里不需要像 CollaborationTrackingRepository
     * 那样为"list 可能为空/null"额外加 CollaborationFilterUtil 那套 xxxActive 占位符规避
     * Hibernate 经典解析器 "unexpected AST node" 的 bug——那个 bug 的触发条件是"同一个参数
     * 既被 IS NULL 判断、又被 IN 使用"，这里 brandIds 只参与 IN，从不参与 IS NULL 判断，
     * category 才参与 IS NULL 判断且只用 = 比较（不是 IN），两者是不同参数，不属于同一类风险。
     */
    @Query("SELECT p FROM PendingApproval p " +
           "WHERE p.status = 'PENDING' " +
           "AND (:category IS NULL OR p.category = :category) " +
           "AND p.targetBrandId IN :brandIds " +
           "ORDER BY p.createdAt DESC")
    Page<PendingApproval> findPendingForBrands(@Param("category") PendingApprovalCategory category,
                                                 @Param("brandIds") List<Long> brandIds, Pageable pageable);

    /**
     * 批量查询：某个模块下、某个类别，哪些记录当前有"待审核"事项
     * （用于列表页批量标记"审核中"，避免逐行查库）。
     */
    @Query("SELECT p.targetId FROM PendingApproval p " +
           "WHERE p.targetModule = :module AND p.category = :category AND p.status = 'PENDING'")
    List<Long> findPendingTargetIds(@Param("module") PendingApprovalModule module,
                                     @Param("category") PendingApprovalCategory category);

    /**
     * "处理结果通知"用（2026-07 新增）：某个员工作为项目负责人或执行人员，已经被处理
     * （同意/拒绝）的事项，按处理时间倒序。是否已被这个员工"确认删除"过，在 Service 层
     * 按 dismissedByEmployeeIds 过滤，不在这里做（TEXT 字段模糊匹配不适合放 JPQL）。
     */
    @Query("SELECT p FROM PendingApproval p " +
           "WHERE p.status IN (com.lusuoria.settlement.enums.PendingApprovalStatus.APPROVED, " +
           "  com.lusuoria.settlement.enums.PendingApprovalStatus.REJECTED) " +
           "AND (p.targetProjectManagerId = :employeeId OR p.targetExecutorId = :employeeId) " +
           "ORDER BY p.resolvedAt DESC")
    List<PendingApproval> findResolvedForEmployee(@Param("employeeId") Long employeeId);

    /**
     * "待我审核"用（2026-07 新增，EXECUTOR_COST_MODIFY 专属）：某个项目负责人名下，
     * 当前还"待审核"、且审核人正是这个项目负责人本人（不是 ADMIN）的事项。
     */
    @Query("SELECT p FROM PendingApproval p " +
           "WHERE p.status = com.lusuoria.settlement.enums.PendingApprovalStatus.PENDING " +
           "AND p.category = :category AND p.targetProjectManagerId = :employeeId " +
           "ORDER BY p.createdAt DESC")
    List<PendingApproval> findMyApprovalQueue(@Param("employeeId") Long employeeId,
                                               @Param("category") PendingApprovalCategory category);

    /**
     * ProgressReminderService 的"删除审核待处理"/"进度倒退审核待处理"跑批用（2026-08-16 新增）：
     * 这两类不分档，只要存在未处理事项就生成一条提醒，所以只需要个数，不需要整批实体。
     */
    long countByCategoryAndStatus(PendingApprovalCategory category, PendingApprovalStatus status);

    /**
     * ProgressReminderService 的"内部执行成本修改审核待处理"跑批用（2026-08-16 新增）：需要按
     * targetProjectManagerId 分组分别生成一条提醒，所以要整批实体，不能只要个数。
     */
    List<PendingApproval> findByCategoryAndStatus(PendingApprovalCategory category, PendingApprovalStatus status);
}
