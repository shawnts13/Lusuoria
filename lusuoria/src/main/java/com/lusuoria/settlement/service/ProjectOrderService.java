package com.lusuoria.settlement.service;

import com.lusuoria.settlement.dto.request.ProjectOrderRequest;
import com.lusuoria.settlement.dto.response.MonthlySummaryResponse;
import com.lusuoria.settlement.dto.response.ProjectOrderResponse;
import com.lusuoria.settlement.enums.ClientStatus;
import com.lusuoria.settlement.enums.InternalSettlementStatus;
import com.lusuoria.settlement.enums.ProjectType;
import com.lusuoria.settlement.enums.VideoType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

public interface ProjectOrderService {

    ProjectOrderResponse save(ProjectOrderRequest request);

    ProjectOrderResponse getById(Long id);

    Page<ProjectOrderResponse> list(Long brandId, String projectMonth, ProjectType projectType,
                                    ClientStatus clientStatus, InternalSettlementStatus internalStatus,
                                    VideoType videoType,
                                    Long influencerId, String accountName, Long projectManagerId,
                                    String keyword, Pageable pageable);

    void delete(Long id);

    MonthlySummaryResponse getMonthlySummary(String month);

    /** 老板审核通过：PENDING_APPROVAL -> CONFIRMED */
    ProjectOrderResponse approve(Long id);

    /** 老板驳回：PENDING_APPROVAL -> CALCULATED */
    ProjectOrderResponse reject(Long id);

    /**
     * 状态流转：只修改甲方状态 + 内部状态，不接收、也不会改动其他任何字段。
     * 配合前端专门的"状态流转"弹窗使用。
     */
    ProjectOrderResponse updateStatus(Long id, ClientStatus clientStatus, InternalSettlementStatus internalStatus);

    void exportExcel(String projectMonth, HttpServletResponse response) throws IOException;
}
