package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.InfluencerPaymentTeam;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InfluencerPaymentTeamRepository extends JpaRepository<InfluencerPaymentTeam, Long> {

    List<InfluencerPaymentTeam> findByInfluencerPaymentIdAndIsDeletedFalse(Long influencerPaymentId);

    /** 批量查询多条结款记录涉及的团队（列表展示/导出用，避免逐条 N+1 查询） */
    @Query("SELECT ipt FROM InfluencerPaymentTeam ipt " +
           "WHERE ipt.influencerPaymentId IN :paymentIds AND ipt.isDeleted = false")
    List<InfluencerPaymentTeam> findByInfluencerPaymentIdIn(@Param("paymentIds") List<Long> paymentIds);

    /** 列表页按团队筛选：涉及了这个团队的结款记录 id */
    @Query("SELECT DISTINCT ipt.influencerPaymentId FROM InfluencerPaymentTeam ipt " +
           "WHERE ipt.teamId = :teamId AND ipt.isDeleted = false")
    List<Long> findPaymentIdsByTeamId(@Param("teamId") Long teamId);
}
