package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.InfluencerPayment;
import com.lusuoria.settlement.enums.InfluencerPaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface InfluencerPaymentRepository extends JpaRepository<InfluencerPayment, Long> {

    Optional<InfluencerPayment> findByIdAndIsDeletedFalse(Long id);

    @EntityGraph(attributePaths = {"brand", "team"})
    @Query("SELECT ip FROM InfluencerPayment ip " +
           "WHERE ip.isDeleted = false " +
           "AND (:settlementMonth IS NULL OR ip.settlementMonth = :settlementMonth) " +
           "AND (:brandId IS NULL OR ip.brandId = :brandId) " +
           "AND (:teamId IS NULL OR ip.teamId = :teamId) " +
           "AND (:paymentStatus IS NULL OR ip.paymentStatus = :paymentStatus) " +
           "ORDER BY ip.settlementMonth DESC, ip.paymentNo ASC")
    Page<InfluencerPayment> findByFilters(
            @Param("settlementMonth") String settlementMonth,
            @Param("brandId") Long brandId,
            @Param("teamId") Long teamId,
            @Param("paymentStatus") InfluencerPaymentStatus paymentStatus,
            Pageable pageable);

    /**
     * 导出用：open-in-view 已关闭（application.yml），brand/team 是懒加载关联，
     * 离开这次查询的事务边界后再访问会抛 LazyInitializationException，
     * 所以导出场景都要带上 @EntityGraph 预先抓取。
     */
    @EntityGraph(attributePaths = {"brand", "team"})
    java.util.List<InfluencerPayment> findBySettlementMonthAndIsDeletedFalse(String month);

    @EntityGraph(attributePaths = {"brand", "team"})
    java.util.List<InfluencerPayment> findByIsDeletedFalse();

    /** 结款单号分配用：统计某"品牌-团队-结算月份-"前缀已用了多少个（作为序号起点） */
    @Query("SELECT COUNT(ip) FROM InfluencerPayment ip WHERE ip.paymentNo LIKE :prefixPattern")
    long countByPaymentNoPrefix(@Param("prefixPattern") String prefixPattern);
}
