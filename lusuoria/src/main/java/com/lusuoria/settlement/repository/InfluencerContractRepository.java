package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.InfluencerContract;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;

@Repository
public interface InfluencerContractRepository extends JpaRepository<InfluencerContract, Long> {

    List<InfluencerContract> findByInfluencerIdOrderByStartDateDesc(Long influencerId);

    /** "红人需求管理"批量交叉核对用：一次性取出这一批红人的全部合同，避免逐条查库 */
    List<InfluencerContract> findByInfluencerIdIn(List<Long> influencerIds);

    /**
     * 同一个红人在同一个 (品牌方,团队) 下，跟给定日期区间有重叠的已有合同（新增/编辑时用来拒绝
     * 有效期冲突的记录，避免同一个红人同一个品牌方团队下同时存在两条"都在有效期内"的合同，
     * 导致"红人需求管理"那边不知道该展示哪一条）。teamId 允许为空（该品牌方下没配团队的场景），
     * 按 null 精确匹配 null。excludeId 编辑时传当前记录自己的 id，排除自身；新增时传 null。
     */
    @Query("SELECT c FROM InfluencerContract c WHERE c.influencerId = :influencerId AND c.brandId = :brandId " +
           "AND ((:teamId IS NULL AND c.teamId IS NULL) OR c.teamId = :teamId) " +
           "AND c.startDate <= :endDate AND c.endDate >= :startDate " +
           "AND (:excludeId IS NULL OR c.id <> :excludeId)")
    List<InfluencerContract> findOverlapping(@Param("influencerId") Long influencerId,
                                              @Param("brandId") Long brandId,
                                              @Param("teamId") Long teamId,
                                              @Param("startDate") Date startDate,
                                              @Param("endDate") Date endDate,
                                              @Param("excludeId") Long excludeId);
}
