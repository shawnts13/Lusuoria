package com.lusuoria.settlement.util;

import com.lusuoria.settlement.repository.CollaborationTrackingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 内部项目编号统一分配器。
 *
 * 编号的唯一生成源头是"红人合作跟踪"——新建跟踪记录时立即生成一次，此后永久不变。
 * "项目订单"只能通过跟踪记录联动生成（填了"客户方的项目订单"后系统自动新建），
 * 直接复用跟踪记录已有的编号，不会再单独生成，所以这里只需要保证在
 * "红人合作跟踪"这一张表内不重复即可。
 */
@Component
public class ProjectNoAllocator {

    @Autowired private ProjectNoGenerator generator;
    @Autowired private CollaborationTrackingRepository trackingRepo;

    /** 生成一个在"红人合作跟踪"表里不重复的内部项目编号 */
    public String allocate(String brandName, String month, String accountName) {
        String prefixPattern = generator.buildPrefix(brandName, month, accountName) + "%";
        long count = trackingRepo.countByInternalProjectNoPrefix(prefixPattern);

        String candidate;
        do {
            candidate = generator.generate(brandName, month, accountName, count);
            count++;
        } while (trackingRepo.existsByInternalProjectNo(candidate));

        return candidate;
    }
}

