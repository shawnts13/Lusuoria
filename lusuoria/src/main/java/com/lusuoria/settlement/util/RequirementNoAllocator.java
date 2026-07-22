package com.lusuoria.settlement.util;

import com.lusuoria.settlement.repository.InfluencerRequirementRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 内部需求编号分配器，逻辑照抄 ProjectNoAllocator（"红人合作跟踪"内部项目编号的分配器），
 * 只是唯一性判断查 InfluencerRequirementRepository 而不是 CollaborationTrackingRepository。
 * 格式统一复用 ProjectNoGenerator。
 */
@Component
public class RequirementNoAllocator {

    @Autowired private ProjectNoGenerator generator;
    @Autowired private InfluencerRequirementRepository requirementRepo;

    /** 生成一个在"红人需求管理"表里不重复的内部需求编号 */
    public String allocate(String brandName, String teamName, String month, String accountName) {
        String prefixPattern = generator.buildPrefix(brandName, teamName, month, accountName) + "%";
        long count = requirementRepo.countByInternalRequirementNoPrefix(prefixPattern);

        String candidate;
        do {
            candidate = generator.generate(brandName, teamName, month, accountName, count);
            count++;
        } while (requirementRepo.existsByInternalRequirementNo(candidate));

        return candidate;
    }
}
