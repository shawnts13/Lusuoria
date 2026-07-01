package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.CollaborationTracking;
import com.lusuoria.settlement.enums.CollaborationProgress;
import com.lusuoria.settlement.enums.VideoType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;
import java.util.Optional;

@Repository
public interface CollaborationTrackingRepository extends JpaRepository<CollaborationTracking, Long> {

    Optional<CollaborationTracking> findByIdAndIsDeletedFalse(Long id);

    List<CollaborationTracking> findByIsDeletedFalseOrderByAccountNameAsc();

    /**
     * 去重判断：同一红人 + 同一发布链接 + 同一发布日期 视为重复。
     * 仅当 publishLink 与 publishDate 均非空时才有意义（调用方负责判断）。
     * 排除自身 id（编辑时不和自己比）。
     */
    @Query("SELECT c FROM CollaborationTracking c " +
           "WHERE c.isDeleted = false " +
           "AND c.accountName = :accountName " +
           "AND c.publishLink = :publishLink " +
           "AND c.publishDate = :publishDate " +
           "AND (:excludeId IS NULL OR c.id <> :excludeId)")
    List<CollaborationTracking> findDuplicates(
            @Param("accountName") String accountName,
            @Param("publishLink") String publishLink,
            @Param("publishDate") Date publishDate,
            @Param("excludeId") Long excludeId);

    /**
     * "项目视频月份"筛选：用 to_char 把 publishDate 转成 'YYYYMM' 字符串再比较，
     * 不用 Date 类型参数做区间比较。
     * 原因：Date 类型参数在这条动态筛选查询里（配合 Supabase 连接池）会触发
     * "could not determine data type of parameter"（SQLState 42P18），
     * 无论传不传值都会报错。改成字符串比较后，videoMonth 参数跟这条查询里
     * 其他正常工作的字符串筛选字段（teamName/accountName等）是完全一样的类型，
     * 不会再有参数类型歧义问题（跟 ProjectOrder.projectMonth 直接存字符串是同一个思路）。
     */
    @Query("SELECT c FROM CollaborationTracking c " +
           "WHERE c.isDeleted = false " +
           "AND (:brandId IS NULL OR c.brandId = :brandId) " +
           "AND (:teamName IS NULL OR c.teamName LIKE %:teamName%) " +
           "AND (:countryMarket IS NULL OR c.countryMarket = :countryMarket) " +
           "AND (:accountName IS NULL OR c.accountName LIKE %:accountName%) " +
           "AND (:platform IS NULL OR c.platform LIKE %:platform%) " +
           "AND (:progress IS NULL OR c.progress = :progress) " +
           "AND (:videoType IS NULL OR c.videoType = :videoType) " +
           "AND (:videoMonth IS NULL OR FUNCTION('to_char', c.publishDate, 'YYYYMM') = :videoMonth) " +
           "AND (:clientOrderId IS NULL OR c.clientOrderId LIKE %:clientOrderId%) " +
           "AND (:clientPaymentBatch IS NULL OR c.clientPaymentBatch LIKE %:clientPaymentBatch%) " +
           "AND (:projectManagerId IS NULL OR c.projectManagerId = :projectManagerId)")
    Page<CollaborationTracking> findByFilters(
            @Param("brandId") Long brandId,
            @Param("teamName") String teamName,
            @Param("countryMarket") String countryMarket,
            @Param("accountName") String accountName,
            @Param("platform") String platform,
            @Param("progress") CollaborationProgress progress,
            @Param("videoType") VideoType videoType,
            @Param("videoMonth") String videoMonth,
            @Param("clientOrderId") String clientOrderId,
            @Param("clientPaymentBatch") String clientPaymentBatch,
            @Param("projectManagerId") Long projectManagerId,
            Pageable pageable);

    /**
     * 数据看板用：取出全部未删除记录，月份归属（发布月份，若无则归创建月份）
     * 的精确判断在 Service 层用 Java 完成，避免不同数据库方言下日期函数写法
     * 不一致、以及"跨月范围+按月归属"组合逻辑在 SQL 里难以正确表达的问题。
     */
    List<CollaborationTracking> findByIsDeletedFalse();
}
