package com.lusuoria.settlement.excel;

import com.lusuoria.settlement.config.BrandCache;
import com.lusuoria.settlement.config.InfluencerTeamCache;
import com.lusuoria.settlement.entity.Brand;
import com.lusuoria.settlement.entity.Influencer;
import com.lusuoria.settlement.entity.TeamContract;
import com.lusuoria.settlement.entity.InfluencerRequirement;
import com.lusuoria.settlement.entity.InfluencerRequirementItem;
import com.lusuoria.settlement.entity.InfluencerTeam;
import com.lusuoria.settlement.repository.TeamContractRepository;
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
    @Autowired private TeamContractRepository teamContractRepo;
    @Autowired private BrandCache brandCache;
    @Autowired private InfluencerTeamCache teamCache;

    /** "红人需求管理"列表页的"导出Excel"：批量预取条目/合同关联数据避免逐条查库，写成 xlsx 下载 */
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
        // 2026-08 新增：需求完成进度/需求完成时间，紧跟在"总成本"后面，顺序照抄前端
        // RequirementListPage.vue 的列顺序（成本 -> 进度 -> 完成时间）。进度用"已完成/总数"
        // 的分数文本（不复刻前端那条进度条，Excel 没法渲染进度条，文本信息是等价的）；
        // 完成时间未完成时导出"-"，跟前端展示口径一致
        cols.add("需求完成进度");
        cols.add("需求完成时间");
        // 2026-08 新增：结款状态，紧跟在"需求完成时间"后面，前端列顺序也是这样
        cols.add("结款状态");
        cols.add("创建时间");
        cols.add("Invoice链接");
        cols.add("合同链接");
        cols.add("需求条目明细");
        cols.add("完整需求内容");

        // 这两列可能是"该品牌方是一年签一次合同，请在'品牌方/红人团队管理'处查看"这种较长的
        // 提示文案，单独放宽列宽 + 允许换行，避免跟其他短文本列一样挤成一坨
        Set<Integer> wrapWideCols = new HashSet<>(Arrays.asList(cols.indexOf("Invoice链接"), cols.indexOf("合同链接")));

        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < cols.size(); i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(cols.get(i));
            cell.setCellStyle(hdr);
            sheet.setColumnWidth(i, (wrapWideCols.contains(i) ? 30 : 18) * 256);
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
        // "合同链接"列，品牌方/团队是"一年签一次合同"时要按 (品牌方,团队,需求月份) 去匹配
        // "品牌方/红人团队管理"里维护的团队级合同（见 TeamContract 类注释），一次性批量查出
        // 这批需求涉及的全部合同，避免逐条查库
        Set<Long> teamIds = new HashSet<>();
        Set<Long> brandIdsWithoutTeam = new HashSet<>();
        for (InfluencerRequirement r : list) {
            if (r.getTeamId() != null) teamIds.add(r.getTeamId());
            else if (r.getBrandId() != null) brandIdsWithoutTeam.add(r.getBrandId());
        }
        Map<String, List<TeamContract>> contractsByBrandTeam = new HashMap<>();
        if (!teamIds.isEmpty()) {
            for (TeamContract c : teamContractRepo.findByTeamIdIn(new ArrayList<>(teamIds))) {
                contractsByBrandTeam.computeIfAbsent(brandTeamKey(c.getBrandId(), c.getTeamId()), k -> new ArrayList<>()).add(c);
            }
        }
        if (!brandIdsWithoutTeam.isEmpty()) {
            for (TeamContract c : teamContractRepo.findByBrandIdInAndTeamIdIsNull(new ArrayList<>(brandIdsWithoutTeam))) {
                contractsByBrandTeam.computeIfAbsent(brandTeamKey(c.getBrandId(), c.getTeamId()), k -> new ArrayList<>()).add(c);
            }
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
            setCellStr(row, c++, (r.getCompletedCount() != null ? r.getCompletedCount() : 0)
                    + "/" + (r.getTotalItemCount() != null ? r.getTotalItemCount() : 0), nor);
            setCellStr(row, c++, r.getCompletedAt() != null ? dtf.format(r.getCompletedAt()) : "-", nor);
            setCellStr(row, c++, r.getSettlementStatus() != null ? r.getSettlementStatus().getLabel() : "-", nor);
            setCellStr(row, c++, r.getCreatedAt() != null ? dtf.format(r.getCreatedAt()) : "", nor);
            setCellStr(row, c++, invoiceCell(r, brand), wrap);
            setCellStr(row, c++, contractCell(r, brand, team, contractsByBrandTeam), wrap);
            setCellStr(row, c++, itemsSummary(itemsByReqId.getOrDefault(r.getId(), Collections.emptyList())), wrap);
            setCellStr(row, c++, r.getFullRequirementContent(), wrap);
        }

        sheet.createFreezePane(0, 1);
        wb.write(response.getOutputStream());
        wb.close();
    }

    /** "需求条目明细"列文案：每个条目一行（换行分隔，配合 wrap 样式），列出视频类型/平台/数量/客户单价/红人单价 */
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

    /** 金额格式化为两位小数字符串（拼接进 itemsSummary 的条目文案里用），null 兜底成 "0.00" */
    private String fmtMoney(BigDecimal v) {
        return v == null ? "0.00" : v.setScale(2, java.math.RoundingMode.HALF_UP).toString();
    }

    /** "Invoice链接"列文案，逻辑跟前端 RequirementListPage.vue 的 invoiceLink 列展示保持一致 */
    private String invoiceCell(InfluencerRequirement r, Brand brand) {
        if (brand != null && !brand.requiresInvoiceUpload()) return "不涉及";
        return r.getInvoiceLink() != null ? r.getInvoiceLink() : "";
    }

    /**
     * "合同链接"列文案，逻辑跟前端 RequirementListPage.vue 的 contractCellState() 保持一致：
     * 品牌方/团队是"每次需求签一次合同"时看需求自己的 contractLink；是"一年签一次合同"时
     * 按 (品牌方,团队,需求月份) 去匹配"品牌方/红人团队管理"里维护的团队级合同，匹配上展示
     * 那条合同的链接，没匹配上展示引导文案（Excel 场景下是只读查看，所以文案用"查看"而不是
     * 前端那句"上传"）。
     */
    private String contractCell(InfluencerRequirement r, Brand brand, InfluencerTeam team,
                                 Map<String, List<TeamContract>> contractsByBrandTeam) {
        if (InfluencerTeam.isPerRequirementContract(brand, team)) {
            return r.getContractLink() != null ? r.getContractLink() : "";
        }
        List<TeamContract> contracts = contractsByBrandTeam.getOrDefault(
                brandTeamKey(r.getBrandId(), r.getTeamId()), Collections.emptyList());
        for (TeamContract c : contracts) {
            if (monthOverlapsContractRange(r.getRequirementMonth(), c.getStartDate(), c.getEndDate())) {
                return c.getContractLink();
            }
        }
        return "该品牌方是一年签一次合同，请在\"品牌方/红人团队管理\"处查看";
    }

    /** brandId+teamId 拼成的 map key，跟 InfluencerRequirementService 里的同名方法是同一套约定 */
    private String brandTeamKey(Long brandId, Long teamId) {
        return brandId + "|" + (teamId != null ? teamId : -1L);
    }

    /**
     * 需求月份（yyyyMM）是否落在合同有效期 [startDate, endDate] 内：只要这个月里有任意一天
     * 落在区间内就算覆盖，跟前端 monthOverlapsContractRange() 的判定逻辑一致。
     */
    private boolean monthOverlapsContractRange(String yyyyMM, Date startDate, Date endDate) {
        if (yyyyMM == null || yyyyMM.length() < 6 || startDate == null || endDate == null) return false;
        try {
            int year = Integer.parseInt(yyyyMM.substring(0, 4));
            int month = Integer.parseInt(yyyyMM.substring(4, 6));
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.clear();
            cal.set(year, month - 1, 1, 0, 0, 0);
            Date monthStart = cal.getTime();
            cal.add(java.util.Calendar.MONTH, 1);
            cal.add(java.util.Calendar.MILLISECOND, -1);
            Date monthEnd = cal.getTime();
            return !monthStart.after(endDate) && !startDate.after(monthEnd);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 单元格写字符串值（null 兜底成空字符串）并套用样式 */
    private void setCellStr(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value != null ? value : "");
        cell.setCellStyle(style);
    }

    /** 单元格写金额（BigDecimal 转 double 写入，null 则留空只套样式） */
    private void setCellMoney(Row row, int col, BigDecimal value, CellStyle style) {
        Cell cell = row.createCell(col);
        if (value != null) cell.setCellValue(value.doubleValue());
        cell.setCellStyle(style);
    }

    /** 单元格写普通数字（如需求条目总数），null 则留空只套样式 */
    private void setCellNum(Row row, int col, Double value, CellStyle style) {
        Cell cell = row.createCell(col);
        if (value != null) cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    /** 表头样式：蓝底白字加粗、居中、四周细边框 */
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

    /** 金额列样式：千分位+两位小数（#,##0.00） */
    private XSSFCellStyle createMoneyStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        style.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
        return style;
    }

    /** 普通数据行样式：无特殊修饰，只是为了跟其他样式对象区分开 */
    private XSSFCellStyle createNormalStyle(XSSFWorkbook wb) {
        return wb.createCellStyle();
    }

    /** 长文本列样式（Invoice链接/合同链接/需求条目明细/完整需求内容）：自动换行、顶部对齐 */
    private XSSFCellStyle createWrapStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        style.setWrapText(true);
        style.setVerticalAlignment(VerticalAlignment.TOP);
        return style;
    }
}
