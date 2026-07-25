package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.InfluencerContract;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InfluencerContractRepository extends JpaRepository<InfluencerContract, Long> {

    List<InfluencerContract> findByInfluencerIdOrderByYearDesc(Long influencerId);

    /** "红人需求管理"批量交叉核对用：一次性取出这一批红人的全部合同，避免逐条查库 */
    List<InfluencerContract> findByInfluencerIdIn(List<Long> influencerIds);

    boolean existsByInfluencerIdAndYear(Long influencerId, Integer year);

    boolean existsByInfluencerIdAndYearAndIdNot(Long influencerId, Integer year, Long id);
}
