package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.Influencer;
import com.lusuoria.settlement.enums.ProjectType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InfluencerRepository extends JpaRepository<Influencer, Long> {

    List<Influencer> findByIsDeletedFalseOrderByTeamNameAscAccountNameAsc();

    Optional<Influencer> findByIdAndIsDeletedFalse(Long id);

    List<Influencer> findByInfluencerTypeAndIsDeletedFalse(ProjectType type);

    List<Influencer> findByTeamNameAndIsDeletedFalse(String teamName);
}