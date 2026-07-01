package com.lusuoria.settlement.config;

import com.lusuoria.settlement.dto.response.InfluencerSimpleResponse;
import com.lusuoria.settlement.entity.InfluencerBrand;
import com.lusuoria.settlement.repository.InfluencerBrandRepository;
import com.lusuoria.settlement.repository.InfluencerRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.*;

/**
 * 红人精简信息内存缓存（id / 账号名 / 团队 / 国家市场 / 关联品牌）
 *
 * 供项目订单、红人合作跟踪、打款等模块的红人选择下拉框使用（/api/influencers/simple）。
 * 启动时加载，每4小时自动刷新；红人新增/编辑/删除/Excel导入后，
 * InfluencerController 会主动调用 refresh()，保证所有用户马上都能看到最新数据，
 * 不依赖任何前端本地缓存过期时间。
 */
@Component
public class InfluencerCache {

    @Autowired private InfluencerRepository influencerRepo;
    @Autowired private InfluencerBrandRepository influencerBrandRepo;

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
            r.setTeamName((String) row[2]);
            r.setCountryMarket((String) row[3]);
            list.add(r);
            ids.add(r.getId());
        }
        if (!ids.isEmpty()) {
            List<InfluencerBrand> rels = influencerBrandRepo.findByInfluencerIdIn(ids);
            Map<Long, List<Long>> byInfluencer = new HashMap<Long, List<Long>>();
            for (InfluencerBrand rel : rels) {
                byInfluencer.computeIfAbsent(rel.getInfluencerId(), k -> new ArrayList<Long>()).add(rel.getBrandId());
            }
            for (InfluencerSimpleResponse r : list) {
                r.setBrandIds(byInfluencer.getOrDefault(r.getId(), Collections.<Long>emptyList()));
            }
        }
        simpleList = list;
    }

    public List<InfluencerSimpleResponse> getAll() {
        return simpleList;
    }
}
