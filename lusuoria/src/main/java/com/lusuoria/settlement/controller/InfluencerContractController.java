package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.dto.request.InfluencerContractRequest;
import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.entity.Influencer;
import com.lusuoria.settlement.entity.InfluencerContract;
import com.lusuoria.settlement.repository.InfluencerContractRepository;
import com.lusuoria.settlement.repository.InfluencerRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 红人已签署合同（2026-07 新增，"红人管理"编辑弹窗里的"已签署合同"区块）。
 * 一个红人可以有多条（一年一条），"新增"/"编辑"各自独立操作，不走整份表单的 save()。
 */
@RestController
@RequestMapping("/api/influencer-contracts")
public class InfluencerContractController {

    @Autowired private InfluencerContractRepository contractRepo;
    @Autowired private InfluencerRepository influencerRepo;

    /** 某个红人的合同列表（年份倒序），供"红人管理"编辑弹窗展示 */
    @GetMapping("/by-influencer/{influencerId}")
    public ApiResponse<List<InfluencerContract>> byInfluencer(@PathVariable Long influencerId) {
        return ApiResponse.success(contractRepo.findByInfluencerIdOrderByYearDesc(influencerId));
    }

    /**
     * 批量按红人 id 取合同，返回 influencerId -> {year -> contractLink}。
     * 供"红人需求管理"列表页按每条需求自己的需求年份，交叉核对该红人是否已经在"红人管理"
     * 上传过对应年份的合同（品牌方"一年签一次合同"场景），避免逐条查库。
     */
    @GetMapping("/by-influencer-ids")
    public ApiResponse<Map<Long, Map<Integer, String>>> byInfluencerIds(@RequestParam List<Long> ids) {
        Map<Long, Map<Integer, String>> result = new HashMap<>();
        for (InfluencerContract c : contractRepo.findByInfluencerIdIn(ids)) {
            result.computeIfAbsent(c.getInfluencerId(), k -> new HashMap<>()).put(c.getYear(), c.getContractLink());
        }
        return ApiResponse.success(result);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<InfluencerContract> create(@Valid @RequestBody InfluencerContractRequest req) {
        Influencer influencer = influencerRepo.findByIdAndIsDeletedFalse(req.getInfluencerId())
                .orElseThrow(() -> new RuntimeException("红人不存在：" + req.getInfluencerId()));
        if (contractRepo.existsByInfluencerIdAndYear(req.getInfluencerId(), req.getYear())) {
            throw new RuntimeException("该红人已有" + req.getYear() + "年的合同记录，请编辑已有记录");
        }

        InfluencerContract contract = new InfluencerContract();
        contract.setInfluencer(influencer);
        contract.setYear(req.getYear());
        contract.setContractLink(req.getContractLink());
        return ApiResponse.success(contractRepo.save(contract));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<InfluencerContract> update(@PathVariable Long id, @Valid @RequestBody InfluencerContractRequest req) {
        InfluencerContract contract = contractRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("合同记录不存在：" + id));
        if (contractRepo.existsByInfluencerIdAndYearAndIdNot(req.getInfluencerId(), req.getYear(), id)) {
            throw new RuntimeException("该红人已有" + req.getYear() + "年的合同记录，请编辑已有记录");
        }

        contract.setYear(req.getYear());
        contract.setContractLink(req.getContractLink());
        return ApiResponse.success(contractRepo.save(contract));
    }
}
