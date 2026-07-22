package com.lusuoria.settlement.excel;

import com.lusuoria.settlement.config.BrandCache;
import com.lusuoria.settlement.config.InfluencerTeamCache;
import com.lusuoria.settlement.entity.Brand;
import com.lusuoria.settlement.entity.Influencer;
import com.lusuoria.settlement.entity.InfluencerRequirement;
import com.lusuoria.settlement.entity.InfluencerRequirementItem;
import com.lusuoria.settlement.entity.InfluencerTeam;
import com.lusuoria.settlement.repository.InfluencerRepository;
import com.lusuoria.settlement.repository.InfluencerRequirementItemRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 红人需求管理 - 只导出，不导入（照抄 InfluencerPaymentExcelHandler 的先例）。
 *
 * open-in-view=false，导出发生在事务外，不能访问 r.getBrand()/r.getTeam() 这类 LAZY
 * @ManyToOne（会抛 LazyInitializationException）——跟 CollaborationTrackingExcelHandler
 * 一样，改用直读的 id 列 + BrandCache/InfluencerTeamCache 取名称，不触碰懒加载关联。
 */
@Component
public class InfluencerRequirementExcelHandler {

    @Autowired private InfluencerRepository influencerRepo;
    @Autowired private InfluencerRequirementItemRepository itemRepo;
    @Autowired private BrandCache brandCache;
    @Autowired private InfluencerTeamCache teamCache;

    public void export(List<InfluencerRequirement> list, HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        String fileName = "红人需求_" + new SimpleDateFormat("yyyyMMdd").format(new Date()) + ".xlsx";
        response.setHeader("Content-Disposition",
                "attachment;filename=" + java.net.URLEncoder.encode(fileName, "UTF-8"));

        XSSFWorkbook wb    = new XSSFWorkbook();
        XSSFSheet    sheet = wb.createSheet("红人需求");
        XSSFCellStyle hdr   = createHeaderStyle(wb);
        XSSFCellStyle money = createMoneyStyle(wb);
        XSSFCellStyle nor   = createNormalStyle(wb);
        XSSFCellStyle wrap  = createWrapStyle(wb);

        List<String> cols = new ArrayList<String>();
        cols.add("内部需求编号");
        cols.add("需求月份");
        cols.add("品牌方");
        cols.add("红人团队");
        cols.add("服务国家/市场");
        cols.add("红人社媒完整名字");
        cols.add("需求条目总数");
        cols.add("客户合作总价格（$）");
        cols.add("红人视频制作与发布总成本（$）");
        cols.add("创建时间");
        cols.add("需求条目明细");
        cols.add("完整需求内容");

        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < cols.size(); i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(cols.get(i));
            cell.setCellStyle(hdr);
            sheet.setColumnWidth(i, 18 * 256);
        }

        // 一次性批量查出所有需求的条目，避免逐条查库
        Set<Long> ids = list.stream().map(InfluencerRequirement::getId).collect(Collectors.toSet());
        Map<Long, List<InfluencerRequirementItem>> itemsByReqId = new HashMap<>();
        if (!ids.isEmpty()) {
            for (InfluencerRequirementItem item : itemRepo.findByRequirementIdInOrderByIdAsc(new ArrayList<>(ids))) {
                itemsByReqId.computeIfAbsent(item.getRequirementId(), k -> new ArrayList<>()).add(item);
            }
        }
        Set<Long> infIds = list.stream().map(InfluencerRequirement::getInfluencerId).collect(Collectors.toSet());
        Map<Long, String> accountNameById = new HashMap<>();
        if (!infIds.isEmpty()) {
            for (Influencer inf : influencerRepo.findAllById(infIds)) accountNameById.put(inf.getId(), inf.getAccountName());
        }

        SimpleDateFormat dtf = new SimpleDateFormat("yyyy-MM-dd HH:mm");

        for (int i = 0; i < list.size(); i++) {
            InfluencerRequirement r = list.get(i);
            Row row = sheet.createRow(i + 1);
            int c = 0;

            setCellStr(row, c++, r.getInternalRequirementNo(), nor);
            setCellStr(row, c++, r.getRequirementMonth(), nor);
            Brand brand = r.getBrandId() != null ? brandCache.findById(r.getBrandId()) : null;
            setCellStr(row, c++, brand != null ? brand.getName() : "", nor);
            InfluencerTeam team = r.getTeamId() != null ? teamCache.findById(r.getTeamId()) : null;
            setCellStr(row, c++, team != null ? team.getName() : "", nor);
            setCellStr(row, c++, r.getCountryMarket(), nor);
            setCellStr(row, c++, accountNameById.getOrDefault(r.getInfluencerId(), ""), nor);
            setCellNum(row, c++, r.getTotalItemCount() != null ? (double) r.getTotalItemCount() : null, nor);
            setCellMoney(row, c++, r.getTotalClientPrice(), money);
            setCellMoney(row, c++, r.getTotalInfluencerCost(), money);
            setCellStr(row, c++, r.getCreatedAt() != null ? dtf.format(r.getCreatedAt()) : "", nor);
            setCellStr(row, c++, itemsSummary(itemsByReqId.getOrDefault(r.getId(), Collections.emptyList())), wrap);
            setCellStr(row, c++, r.getFullRequirementContent(), wrap);
        }

        sheet.createFreezePane(0, 1);
        wb.write(response.getOutputStream());
        wb.close();
    }

    private String itemsSummary(List<InfluencerRequirementItem> items) {
        if (items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (InfluencerRequirementItem item : items) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(item.getVideoType() != null ? item.getVideoType().getLabel() : "?")
              .append("-").append(item.getPlatform() != null ? item.getPlatform().replace("\n", "、") : "?")
              .append("：").append(item.getVideoCount()).append("条")
              .append("，客户单价¥").append(fmtMoney(item.getClientUnitPrice()))
              .append("，红人单价¥").append(fmtMoney(item.getInfluencerUnitCostPrice()));
        }
        return sb.toString();
    }

    private String fmtMoney(BigDecimal v) {
        return v == null ? "0.00" : v.setScale(2, java.math.RoundingMode.HALF_UP).toString();
    }

    private void setCellStr(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value != null ? value : "");
        cell.setCellStyle(style);
    }

    private void setCellMoney(Row row, int col, BigDecimal value, CellStyle style) {
        Cell cell = row.createCell(col);
        if (value != null) cell.setCellValue(value.doubleValue());
        cell.setCellStyle(style);
    }

    private void setCellNum(Row row, int col, Double value, CellStyle style) {
        Cell cell = row.createCell(col);
        if (value != null) cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private XSSFCellStyle createHeaderStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        XSSFFont font = wb.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(new XSSFColor(new byte[]{(byte)41,(byte)128,(byte)185}, null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private XSSFCellStyle createMoneyStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        style.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
        return style;
    }

    private XSSFCellStyle createNormalStyle(XSSFWorkbook wb) {
        return wb.createCellStyle();
    }

    private XSSFCellStyle createWrapStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        style.setWrapText(true);
        style.setVerticalAlignment(VerticalAlignment.TOP);
        return style;
    }
}
