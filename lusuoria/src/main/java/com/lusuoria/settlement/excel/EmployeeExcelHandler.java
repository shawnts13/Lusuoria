package com.lusuoria.settlement.excel;

import com.lusuoria.settlement.entity.CommissionBonusTier;
import com.lusuoria.settlement.entity.Employee;
import com.lusuoria.settlement.entity.ExecutorPayRateTier;
import com.lusuoria.settlement.enums.VideoType;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 员工管理 Excel 导出（只导出，不导入，照抄 InfluencerPaymentExcelHandler 的先例）。
 *
 * 2026-07-30 新增两列（跟"员工管理"列表页"薪资信息"列展示的口径完全一致，格式化逻辑
 * 照抄 frontend/src/utils/executorRateFormat.js + EmployeeListPage.vue 的 bonusTierSummary）：
 *   - "Bonus阶梯"：项目负责人/管理层的提成 bonus 阶梯配置，之前导出完全没有这项信息。
 *   - "执行人员薪资标准"：执行人员按项目视频类型分档的单价配置（这里只展示"管理层"这份配置，
 *     跟列表页一样——同一个执行人员可能被多个不同项目负责人配置了不同费率，这里不是全量汇总，
 *     只是列表页展示的那份"管理层视角"的代表值，见 ExecutorPayRateController.resolveManagerId）。
 * 之前导出完全没有这两项信息，员工管理模块新增了执行人员工资梯度和项目负责人bonus阶梯之后
 * 一直没有同步补进导出里。
 */
@Component
public class EmployeeExcelHandler {

    public void export(List<Employee> employees,
                        Map<Long, List<CommissionBonusTier>> bonusTiersByEmployeeId,
                        Map<Long, List<ExecutorPayRateTier>> executorRatesByExecutorId,
                        HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        String fileName = "员工_" + new SimpleDateFormat("yyyyMMdd").format(new Date()) + ".xlsx";
        response.setHeader("Content-Disposition",
                "attachment;filename=" + java.net.URLEncoder.encode(fileName, "UTF-8"));

        XSSFWorkbook wb    = new XSSFWorkbook();
        XSSFSheet    sheet = wb.createSheet("员工");
        XSSFCellStyle hdr   = createHeaderStyle(wb);
        XSSFCellStyle money = createMoneyStyle(wb);
        XSSFCellStyle nor   = createNormalStyle(wb);
        XSSFCellStyle wrap  = createWrapStyle(wb);

        List<String> cols = new ArrayList<String>();
        cols.add("姓名");
        cols.add("角色");
        cols.add("邮箱");
        cols.add("联系电话");
        cols.add("入职时间");
        cols.add("离职时间");
        cols.add("默认提成比例");
        cols.add("Bonus阶梯");
        cols.add("执行人员薪资标准");
        cols.add("固定月薪（人民币）");
        cols.add("备注");

        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < cols.size(); i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(cols.get(i));
            cell.setCellStyle(hdr);
            sheet.setColumnWidth(i, 16 * 256);
        }
        // Bonus阶梯/执行人员薪资标准这两列内容比较长，单独放宽
        sheet.setColumnWidth(7, 36 * 256);
        sheet.setColumnWidth(8, 40 * 256);

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");

        for (int i = 0; i < employees.size(); i++) {
            Employee e = employees.get(i);
            Row row = sheet.createRow(i + 1);
            int c = 0;

            setCellStr(row, c++, e.getName(), nor);
            setCellStr(row, c++, e.getRole(), nor);
            setCellStr(row, c++, e.getEmail(), nor);
            setCellStr(row, c++, e.getContactPhone(), nor);
            setCellStr(row, c++, e.getHireDate()   != null ? sdf.format(e.getHireDate())   : "", nor);
            setCellStr(row, c++, e.getResignDate() != null ? sdf.format(e.getResignDate()) : "", nor);
            setCellStr(row, c++, e.getDefaultCommissionRate() != null
                    ? e.getDefaultCommissionRate().multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + "%" : "", nor);
            setCellStr(row, c++, formatBonusTiers(bonusTiersByEmployeeId.get(e.getId()), e.getBonusTierCurrency()), wrap);
            setCellStr(row, c++, formatExecutorRates(executorRatesByExecutorId.get(e.getId())), wrap);
            setCellMoney(row, c++, e.getFixedMonthlySalary(), money);
            setCellStr(row, c++, e.getNotes(), nor);
        }

        sheet.createFreezePane(0, 1);
        wb.write(response.getOutputStream());
        wb.close();
    }

    /** 跟 EmployeeListPage.vue 的 bonusTierSummary() 格式完全一致，如 "¥1,000.00-¥5,000.00→5%，¥5,000.00+→8%" */
    private String formatBonusTiers(List<CommissionBonusTier> tiers, String currency) {
        if (tiers == null || tiers.isEmpty()) return "";
        String symbol = "RMB".equals(currency) ? "¥" : "$";
        List<CommissionBonusTier> sorted = new ArrayList<CommissionBonusTier>(tiers);
        sorted.sort(Comparator.comparing(t -> t.getMinAmount() != null ? t.getMinAmount() : BigDecimal.ZERO));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sorted.size(); i++) {
            CommissionBonusTier t = sorted.get(i);
            if (i > 0) sb.append("，");
            String range = t.getMaxAmount() == null
                    ? symbol + fmtMoney(t.getMinAmount()) + "+"
                    : symbol + fmtMoney(t.getMinAmount()) + "-" + symbol + fmtMoney(t.getMaxAmount());
            String pct = t.getBonusRate().multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP) + "%";
            sb.append(range).append("→").append(pct);
        }
        return sb.toString();
    }

    /** 跟 EmployeeListPage.vue 每个视频类型一行的展示方式一致，这里用换行分隔（配合 wrap 样式），
     * 每行格式跟 utils/executorRateFormat.js 的 formatVideoTypeTiers() 完全一致 */
    private String formatExecutorRates(List<ExecutorPayRateTier> tiers) {
        if (tiers == null || tiers.isEmpty()) return "";
        Map<VideoType, List<ExecutorPayRateTier>> byType = tiers.stream()
                .collect(Collectors.groupingBy(ExecutorPayRateTier::getVideoType, LinkedHashMap::new, Collectors.toList()));
        StringBuilder sb = new StringBuilder();
        for (VideoType type : VideoType.values()) {
            List<ExecutorPayRateTier> typeTiers = byType.get(type);
            if (typeTiers == null || typeTiers.isEmpty()) continue;
            if (sb.length() > 0) sb.append("\n");
            sb.append(type.getLabel()).append("：").append(formatVideoTypeTiers(typeTiers));
        }
        return sb.toString();
    }

    private String formatVideoTypeTiers(List<ExecutorPayRateTier> tiers) {
        List<ExecutorPayRateTier> sorted = new ArrayList<ExecutorPayRateTier>(tiers);
        sorted.sort(Comparator.comparing(t -> t.getMinCount() != null ? t.getMinCount() : 0));
        boolean showRange = sorted.size() > 1;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sorted.size(); i++) {
            ExecutorPayRateTier t = sorted.get(i);
            if (i > 0) sb.append("；");
            String rangeLabel = "";
            if (showRange) {
                if (t.getMaxCount() == null) rangeLabel = t.getMinCount() + "条+：";
                else if (t.getMinCount().equals(t.getMaxCount())) rangeLabel = "第" + t.getMinCount() + "条：";
                else rangeLabel = t.getMinCount() + "-" + t.getMaxCount() + "条：";
            }
            String capLabel = t.getMonthlyCap() != null ? "，当月封顶¥" + fmtMoney(t.getMonthlyCap()) : "";
            sb.append(rangeLabel).append("¥").append(fmtMoney(t.getRate())).append("/条").append(capLabel);
        }
        return sb.toString();
    }

    private String fmtMoney(BigDecimal v) {
        return v == null ? "0.00" : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
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

    /** 执行人员薪资标准这一列按视频类型换行展示，需要开启自动换行才能看到多行内容 */
    private XSSFCellStyle createWrapStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        style.setWrapText(true);
        style.setVerticalAlignment(VerticalAlignment.TOP);
        return style;
    }
}
