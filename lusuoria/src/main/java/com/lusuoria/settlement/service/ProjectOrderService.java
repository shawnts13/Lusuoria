package com.lusuoria.settlement.service;

import com.lusuoria.settlement.dto.request.ProjectOrderRequest;
import com.lusuoria.settlement.dto.response.MonthlySummaryResponse;
import com.lusuoria.settlement.dto.response.ProjectOrderResponse;
import com.lusuoria.settlement.enums.ClientStatus;
import com.lusuoria.settlement.enums.InternalSettlementStatus;
import com.lusuoria.settlement.enums.ProjectType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;

public interface ProjectOrderService {

    ProjectOrderResponse save(ProjectOrderRequest request);

    ProjectOrderResponse getById(Long id);

    Page<ProjectOrderResponse> list(Long brandId, String projectMonth, ProjectType projectType,
                                    ClientStatus clientStatus, InternalSettlementStatus internalStatus,
                                    Long influencerId, String keyword, Pageable pageable);

    void delete(Long id);

    MonthlySummaryResponse getMonthlySummary(String month);

    /** 老板审核通过：PENDING_APPROVAL -> CONFIRMED */
    ProjectOrderResponse approve(Long id);

    /** 老板驳回：PENDING_APPROVAL -> CALCULATED */
    ProjectOrderResponse reject(Long id);

    void exportExcel(String projectMonth, HttpServletResponse response) throws IOException;

    List<String> importExcel(MultipartFile file) throws IOException;
}
