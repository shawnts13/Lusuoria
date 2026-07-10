package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.InfluencerBrandTeam;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InfluencerBrandTeamRepository extends JpaRepository<InfluencerBrandTeam, Long> {

    List<InfluencerBrandTeam> findByInfluencerId(Long influencerId);

    /** 批量查询多个红人的关联关系（红人列表展示用，避免逐条 N+1 查询） */
    @Query("SELECT ibt FROM InfluencerBrandTeam ibt WHERE ibt.influencerId IN :influencerIds AND ibt.isDeleted = false")
    List<InfluencerBrandTeam> findByInfluencerIdIn(@Param("influencerIds") List<Long> influencerIds);

    /** 某红人在某品牌方下的所有关联（可能 0/1/多条，用于判断团队怎么选） */
    @Query("SELECT ibt FROM InfluencerBrandTeam ibt " +
           "WHERE ibt.influencerId = :influencerId AND ibt.brandId = :brandId AND ibt.isDeleted = false")
    List<InfluencerBrandTeam> findByInfluencerIdAndBrandId(
            @Param("influencerId") Long influencerId, @Param("brandId") Long brandId);

    boolean existsByInfluencerIdAndBrandId(Long influencerId, Long brandId);

    /**
     * 某品牌方下（不限红人）出现过的所有团队 id，去重。
     * 结果可能包含 null——代表这个品牌方下有红人没配团队，"不选团队"本身也是一个合法选项
     * （红人结款新建结款记录时，品牌方-团队级联选择要用）。
     */
    @Query("SELECT DISTINCT ibt.teamId FROM InfluencerBrandTeam ibt " +
           "WHERE ibt.brandId = :brandId AND ibt.isDeleted = false")
    List<Long> findDistinctTeamIdsByBrandId(@Param("brandId") Long brandId);
}
