package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.config.InfluencerTeamCache;
import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.entity.InfluencerTeam;
import com.lusuoria.settlement.repository.InfluencerTeamRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/influencer-teams")
public class InfluencerTeamController {

    @Autowired private InfluencerTeamRepository teamRepo;
    @Autowired private InfluencerTeamCache teamCache;

    /** 获取所有团队（前端下拉用） */
    @GetMapping
    public ApiResponse<List<InfluencerTeam>> list() {
        return ApiResponse.success(teamCache.getAll());
    }

    /** 新增团队 */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<InfluencerTeam> add(@RequestBody String name) {
        name = name.trim().replaceAll("^\"|\"$", "");
        if (name.isEmpty()) throw new RuntimeException("团队名称不能为空");
        if (teamRepo.existsByNameAndIsDeletedFalse(name))
            throw new RuntimeException("团队已存在：" + name);
        InfluencerTeam team = new InfluencerTeam();
        team.setName(name);
        team.setIsDeleted(false);
        InfluencerTeam saved = teamRepo.save(team);
        teamCache.refresh();
        return ApiResponse.success(saved);
    }

    /** 删除团队（仅 ADMIN） */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        InfluencerTeam team = teamRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("团队不存在"));
        team.setIsDeleted(true);
        teamRepo.save(team);
        teamCache.refresh();
        return ApiResponse.success();
    }
}
