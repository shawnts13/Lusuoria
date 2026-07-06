package com.lusuoria.settlement.config;

import com.lusuoria.settlement.dto.response.InfluencerSimpleResponse;
import com.lusuoria.settlement.entity.InfluencerBrandTeam;
import com.lusuoria.settlement.entity.InfluencerBrandTeamView;
import com.lusuoria.settlement.repository.InfluencerBrandTeamRepository;
import com.lusuoria.settlement.repository.InfluencerRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.*;

/**
 * 红人精简信息内存缓存（id / 账号名 / 国家市场 / 关联的"品牌方-团队"对）
 *
 * 供项目订单、红人合作跟踪、打款等模块的红人选择下拉框使用（/api/influencers/simple）。
 * 启动时加载，每4小时自动刷新；红人新增/编辑/删除/Excel导入后，
 * InfluencerController 会主动调用 refresh()，保证所有用户马上都能看到最新数据，
 * 不依赖任何前端本地缓存过期时间。
 */
@Component
public class InfluencerCache {

    @Autowired private InfluencerRepository influencerRepo;
    @Autowired private InfluencerBrandTeamRepository influencerBrandTeamRepo;
    @Autowired private BrandCache brandCache;
    @Autowired private InfluencerTeamCache teamCache;

    private volatile List<InfluencerSimpleResponse> simpleList = new ArrayList<InfluencerSimpleResponse>();

    @PostConstruct
    public void init() {
        refresh();
    }

    /** 每4小时自动刷新一次，兜底防止意外情况下缓存未被主动刷新 */
    @Scheduled(fixedDelay = 4 * 60 * 60 * 1000)
    public synchronized void refresh() {
        List<Object[]> rows = influencerRepo.findSimpleProjections();
        List<InfluencerSimpleResponse> list = new ArrayList<InfluencerSimpleResponse>();
        List<Long> ids = new ArrayList<Long>();
        for (Object[] row : rows) {
            InfluencerSimpleResponse r = new InfluencerSimpleResponse();
            r.setId((Long) row[0]);
            r.setAccountName((String) row[1]);
            r.setCountryMarket((String) row[2]);
            list.add(r);
            ids.add(r.getId());
        }
        if (!ids.isEmpty()) {
            List<InfluencerBrandTeam> rels = influencerBrandTeamRepo.findByInfluencerIdIn(ids);
            Map<Long, List<InfluencerBrandTeamView>> byInfluencer = new HashMap<Long, List<InfluencerBrandTeamView>>();
            for (InfluencerBrandTeam rel : rels) {
                com.lusuoria.settlement.entity.Brand brand = brandCache.findById(rel.getBrandId());
                com.lusuoria.settlement.entity.InfluencerTeam team = teamCache.findById(rel.getTeamId());
                InfluencerBrandTeamView view = new InfluencerBrandTeamView(
                        rel.getBrandId(), brand != null ? brand.getName() : null,
                        rel.getTeamId(), team != null ? team.getName() : null);
                byInfluencer.computeIfAbsent(rel.getInfluencerId(), k -> new ArrayList<InfluencerBrandTeamView>()).add(view);
            }
            for (InfluencerSimpleResponse r : list) {
                r.setBrandTeamPairs(byInfluencer.getOrDefault(r.getId(), Collections.<InfluencerBrandTeamView>emptyList()));
            }
        }
        simpleList = list;
    }

    public List<InfluencerSimpleResponse> getAll() {
        return simpleList;
    }
}
