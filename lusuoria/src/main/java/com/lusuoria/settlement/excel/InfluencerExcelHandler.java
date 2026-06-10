package com.lusuoria.settlement.excel;

import com.lusuoria.settlement.config.InfluencerOptions;
import com.lusuoria.settlement.entity.Influencer;
import com.lusuoria.settlement.enums.InfluencerContactStatus;
import com.lusuoria.settlement.enums.ProjectType;
import com.lusuoria.settlement.repository.InfluencerRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

@Component
public class InfluencerExcelHandler {

    @Autowired private InfluencerRepository influencerRepo;

    // ===================================================================
    // 导出
    // ===================================================================
    public void export(List<Influencer> influencers, boolean canViewSensitive,
                       HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        String fileName = "红人_" + new SimpleDateFormat("yyyyMMdd").format(new Date()) + ".xlsx";
        response.setHeader("Content-Disposition",
                "attachment;filename=" + java.net.URLEncoder.encode(fileName, "UTF-8"));

        XSSFWorkbook wb      = new XSSFWorkbook();
        XSSFSheet    sheet   = wb.createSheet("红人");
        XSSFCellStyle hdrN   = headerStyle(wb, false);
        XSSFCellStyle hdrS   = headerStyle(wb, true);
        XSSFCellStyle normal = wrapStyle(wb);
        XSSFCellStyle red    = redStyle(wb);

        List<String[]> cols = new ArrayList<String[]>();
        cols.add(new String[]{"红人类型",           "0"});
        cols.add(new String[]{"红人团队",            "0"});
        cols.add(new String[]{"红人ID",              "0"});
        cols.add(new String[]{"国家/市场",           "0"});
        cols.add(new String[]{"平台",                "0"});
        cols.add(new String[]{"领域",                "0"});
        cols.add(new String[]{"粉丝量",              "0"});
        cols.add(new String[]{"主页链接",            "0"});
        cols.add(new String[]{"合作案例链接",        "0"});
        cols.add(new String[]{"红人邮箱",            "0"});
        cols.add(new String[]{"建联情况",            "0"});
        cols.add(new String[]{"付款周期",            "0"});
        cols.add(new String[]{"跟进人",              "0"});
        cols.add(new String[]{"红人成本（美金）",    "1"});
        cols.add(new String[]{"客户合作价格（美金）","1"});
        cols.add(new String[]{"备注",                "0"});

        Row headerRow = sheet.createRow(0);
        int colIdx = 0;
        for (String[] col : cols) {
            if ("1".equals(col[1]) && !canViewSensitive) continue;
            Cell cell = headerRow.createCell(colIdx++);
            cell.setCellValue(col[0]);
            cell.setCellStyle("1".equals(col[1]) ? hdrS : hdrN);
        }
        for (int i = 0; i < colIdx; i++) sheet.setColumnWidth(i, 20 * 256);

        for (int i = 0; i < influencers.size(); i++) {
            Influencer inf = influencers.get(i);
            Row row = sheet.createRow(i + 1);
            int c = 0;
            setCellStr(row, c++, inf.getInfluencerType() != null ? inf.getInfluencerType().getLabel() : "", normal);
            setCellStr(row, c++, inf.getTeamNames(),      normal);
            setCellStr(row, c++, inf.getAccountName(),    normal);
            setCellStr(row, c++, inf.getCountryMarket(),  normal);
            setCellStr(row, c++, inf.getPlatform(),       normal);
            setCellStr(row, c++, inf.getDomain(),         normal);
            setCellStr(row, c++, inf.getFollowerCount() != null
                    ? String.valueOf(inf.getFollowerCount()) : "", normal);
            setCellStr(row, c++, inf.getLinks(),          normal);
            setCellStr(row, c++, inf.getCasesLinks(),     normal);
            setCellStr(row, c++, inf.getEmail(),          normal);
            setCellStr(row, c++, inf.getContactStatus() != null
                    ? inf.getContactStatus().getLabel() : "", normal);
            setCellStr(row, c++, inf.getPaymentCycle(),   normal);
            setCellStr(row, c++, inf.getFollowerPerson(), normal);
            if (canViewSensitive) {
                setCellStrColored(row, c++, inf.getInfluencerCost(), normal, red);
                setCellStrColored(row, c++, inf.getClientPrice(),    normal, red);
            }
            setCellStr(row, c++, inf.getNotes(), normal);
        }

        sheet.createFreezePane(0, 1);
        wb.write(response.getOutputStream());
        wb.close();
    }

    // ===================================================================
    // 下载导入模板
    // ===================================================================
    public void downloadTemplate(boolean canViewSensitive, HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition",
                "attachment;filename=" + java.net.URLEncoder.encode("红人导入模板.xlsx", "UTF-8"));

        XSSFWorkbook wb    = new XSSFWorkbook();
        XSSFSheet    sheet = wb.createSheet("红人导入");
        // 隐藏 sheet 存放国家下拉数据（列表太长不能用 EXPLICIT）
        XSSFSheet    hide  = wb.createSheet("_lists");
        wb.setSheetHidden(1, true);
        for (int i = 0; i < InfluencerOptions.COUNTRIES.length; i++) {
            hide.createRow(i).createCell(0).setCellValue(InfluencerOptions.COUNTRIES[i]);
        }

        XSSFCellStyle hdrN = headerStyle(wb, false);
        XSSFCellStyle hdrS = headerStyle(wb, true);

        List<String[]> cols = new ArrayList<String[]>();
        cols.add(new String[]{"红人类型(必填)",           "0"});
        cols.add(new String[]{"红人团队(多个用换行分隔)", "0"});
        cols.add(new String[]{"红人ID(必填)",              "0"});
        cols.add(new String[]{"国家/市场",                "0"});
        cols.add(new String[]{"平台",                     "0"});
        cols.add(new String[]{"领域",                     "0"});
        cols.add(new String[]{"粉丝量",                   "0"});
        cols.add(new String[]{"主页链接(多条用换行分隔)", "0"});
        cols.add(new String[]{"合作案例链接(多条用换行分隔)", "0"});
        cols.add(new String[]{"红人邮箱",                 "0"});
        cols.add(new String[]{"建联情况",                 "0"});
        cols.add(new String[]{"付款周期",                 "0"});
        cols.add(new String[]{"跟进人",                   "0"});
        cols.add(new String[]{"红人成本（美金）",         "1"});
        cols.add(new String[]{"客户合作价格（美金）",     "1"});
        cols.add(new String[]{"备注",                     "0"});

        Row headerRow = sheet.createRow(0);
        int colIdx = 0;
        Map<String, Integer> colIdxMap = new LinkedHashMap<String, Integer>();
        for (String[] col : cols) {
            if ("1".equals(col[1]) && !canViewSensitive) continue;
            Cell cell = headerRow.createCell(colIdx);
            cell.setCellValue(col[0]);
            cell.setCellStyle("1".equals(col[1]) ? hdrS : hdrN);
            colIdxMap.put(col[0], colIdx);
            sheet.setColumnWidth(colIdx, 24 * 256);
            colIdx++;
        }

        // 下拉验证（全部引用 InfluencerOptions，与接口保持同步）
        DataValidationHelper dv = sheet.getDataValidationHelper();
        addDropdown(sheet, dv, colIdxMap, "红人类型(必填)",           InfluencerOptions.INFLUENCER_TYPES);
        addDropdown(sheet, dv, colIdxMap, "领域",                     InfluencerOptions.DOMAINS);
        addDropdown(sheet, dv, colIdxMap, "付款周期",                 InfluencerOptions.PAYMENT_CYCLES);
        addDropdown(sheet, dv, colIdxMap, "建联情况",                 InfluencerOptions.CONTACT_STATUSES);
        addDropdown(sheet, dv, colIdxMap, "平台",                     InfluencerOptions.PLATFORMS);
        addFormulaDropdown(sheet, dv, colIdxMap, "国家/市场",
                "_lists!$A$1:$A$" + InfluencerOptions.COUNTRIES.length);

        // 示例行
        Row ex = sheet.createRow(1);
        Map<String, String> examples = new HashMap<String, String>();
        examples.put("红人类型(必填)",           "海外红人");
        examples.put("红人团队(多个用换行分隔)", "游琳团队");
        examples.put("红人ID(必填)",              "bigdogtech");
        examples.put("国家/市场",                "美国");
        examples.put("平台",                     "TikTok");
        examples.put("领域",                     "科技");
        examples.put("粉丝量",                   "500000");
        examples.put("主页链接(多条用换行分隔)", "https://tiktok.com/xxx");
        examples.put("合作案例链接(多条用换行分隔)", "https://youtube.com/xxx");
        examples.put("红人邮箱",                 "influencer@email.com");
        examples.put("建联情况",                 "有合作意愿");
        examples.put("付款周期",                 "30天");
        examples.put("跟进人",                   "Charlene");
        examples.put("红人成本（美金）",         "500");
        examples.put("客户合作价格（美金）",     "价格待定，预计1000-1500");
        examples.put("备注",                     "示例数据，填完后请删除");
        for (Map.Entry<String, Integer> entry : colIdxMap.entrySet()) {
            String val = examples.get(entry.getKey());
            if (val != null) ex.createCell(entry.getValue()).setCellValue(val);
        }

        wb.write(response.getOutputStream());
        wb.close();
    }

    // ===================================================================
    // 导入
    // ===================================================================
    public List<String> importData(MultipartFile file, boolean canViewSensitive) throws IOException {
        List<String> errors = new ArrayList<String>();
        Workbook workbook = WorkbookFactory.create(file.getInputStream());
        Sheet sheet = workbook.getSheetAt(0);

        int totalRows = sheet.getLastRowNum();
        if (totalRows < 1) { errors.add("Excel 文件为空"); workbook.close(); return errors; }

        Row headerRow = sheet.getRow(0);
        Map<String, Integer> colMap = new HashMap<String, Integer>();
        for (int c = 0; c < headerRow.getLastCellNum(); c++) {
            Cell cell = headerRow.getCell(c);
            if (cell != null) colMap.put(cell.getStringCellValue().trim(), c);
        }

        int successCount = 0, updateCount = 0;
        for (int i = 1; i <= totalRows; i++) {
            Row row = sheet.getRow(i);
            if (row == null || isRowEmpty(row)) continue;
            try {
                // 兼容导出文件（如"红人ID"）和导入模板（如"红人ID(必填)"）
                String accountName = getStr(row, colMap, "红人ID(必填)");
                if (accountName == null || accountName.isEmpty())
                    accountName = getStr(row, colMap, "红人ID");
                if (accountName == null || accountName.isEmpty()) {
                    errors.add("第" + (i + 1) + "行：红人ID不能为空"); continue;
                }

                String typeStr = getStr(row, colMap, "红人类型(必填)");
                if (typeStr == null || typeStr.isEmpty())
                    typeStr = getStr(row, colMap, "红人类型");
                if (typeStr == null || typeStr.isEmpty()) {
                    errors.add("第" + (i + 1) + "行：红人类型不能为空"); continue;
                }

                Influencer inf = influencerRepo.findByAccountNameAndIsDeletedFalse(accountName)
                        .orElse(null);
                boolean isNew = (inf == null);
                if (isNew) { inf = new Influencer(); inf.setIsDeleted(false); }

                inf.setInfluencerType(parseType(typeStr));
                inf.setAccountName(accountName);

                // 兼容导出列名（无括号）和模板列名（有括号说明）
                String teamNamesRaw = getStr(row, colMap, "红人团队(多个用换行分隔)");
                if (teamNamesRaw == null) teamNamesRaw = getStr(row, colMap, "红人团队");
                inf.setTeamNames(parseMulti(teamNamesRaw));

                inf.setCountryMarket(getStr(row, colMap, "国家/市场"));
                inf.setPlatform(getStr(row, colMap, "平台"));
                inf.setDomain(getStr(row, colMap, "领域"));

                String followerStr = getStr(row, colMap, "粉丝量");
                if (followerStr != null && !followerStr.isEmpty()) {
                    try { inf.setFollowerCount(Long.parseLong(followerStr.replaceAll(",", ""))); }
                    catch (NumberFormatException ignored) {}
                }

                String linksRaw = getStr(row, colMap, "主页链接(多条用换行分隔)");
                if (linksRaw == null) linksRaw = getStr(row, colMap, "主页链接");
                inf.setLinks(parseLinks(linksRaw));

                String casesRaw = getStr(row, colMap, "合作案例链接(多条用换行分隔)");
                if (casesRaw == null) casesRaw = getStr(row, colMap, "合作案例链接");
                inf.setCasesLinks(parseLinks(casesRaw));
                inf.setEmail(getStr(row, colMap, "红人邮箱"));
                inf.setContactStatus(parseContactStatus(getStr(row, colMap, "建联情况")));
                inf.setPaymentCycle(getStr(row, colMap, "付款周期"));
                inf.setFollowerPerson(getStr(row, colMap, "跟进人"));
                inf.setNotes(getStr(row, colMap, "备注"));

                if (canViewSensitive) {
                    inf.setInfluencerCost(getStr(row, colMap, "红人成本（美金）"));
                    inf.setClientPrice(getStr(row, colMap, "客户合作价格（美金）"));
                }

                influencerRepo.save(inf);
                if (isNew) successCount++; else updateCount++;

            } catch (Exception e) {
                errors.add("第" + (i + 1) + "行导入失败：" + e.getMessage());
            }
        }
        workbook.close();
        errors.add(0, "新增 " + successCount + " 条，更新 " + updateCount + " 条，失败 " + errors.size() + " 条");
        return errors;
    }

    // ===================================================================
    // 解析工具
    // ===================================================================
    private ProjectType parseType(String label) {
        if ("中国红人".equals(label))        return ProjectType.CHINA_INFLUENCER;
        if ("境外红人（在华）".equals(label)) return ProjectType.FOREIGN_IN_CHINA;
        return ProjectType.OVERSEAS_INFLUENCER;
    }

    private InfluencerContactStatus parseContactStatus(String label) {
        if (label == null || label.trim().isEmpty()) return null;
        switch (label.trim()) {
            case "未开发":     return InfluencerContactStatus.UNDEVELOPED;
            case "已回复开发信": return InfluencerContactStatus.REPLIED;
            case "有合作意愿":   return InfluencerContactStatus.INTERESTED;
            case "正在合作":     return InfluencerContactStatus.COOPERATING;
            case "已合作过":     return InfluencerContactStatus.COOPERATED;
            default:             return null;
        }
    }

    /** 解析多条链接：兼容逗号或换行分隔，过滤出含 http 的有效链接，导出用 \n 分隔 */
    private String parseLinks(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (String part : raw.split("[,\n\r]+")) {
            String p = part.trim();
            if (p.contains("http")) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(p);
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /** 解析多值普通字段（如团队名称）：兼容逗号或换行分隔，导出用 \n 分隔 */
    private String parseMulti(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (String part : raw.split("[,\n\r]+")) {
            String p = part.trim();
            if (!p.isEmpty()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(p);
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private boolean isRemark(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        try { Double.parseDouble(value.trim()); return false; }
        catch (NumberFormatException e) { return true; }
    }

    // ===================================================================
    // Cell 工具
    // ===================================================================
    private void setCellStr(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value != null ? value : "");
        cell.setCellStyle(style);
    }

    private void setCellStrColored(Row row, int col, String value,
                                   CellStyle normalStyle, CellStyle redStyle) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value != null ? value : "");
        cell.setCellStyle(isRemark(value) ? redStyle : normalStyle);
    }

    private String getStr(Row row, Map<String, Integer> map, String header) {
        Integer idx = map.get(header);
        if (idx == null) return null;
        Cell cell = row.getCell(idx);
        if (cell == null) return null;
        switch (cell.getCellType()) {
            case STRING:  return cell.getStringCellValue().trim();
            case NUMERIC: return String.valueOf((long) cell.getNumericCellValue());
            default:      return null;
        }
    }

    private boolean isRowEmpty(Row row) {
        for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
            Cell cell = row.getCell(c);
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                String v = cell.getCellType() == CellType.STRING
                        ? cell.getStringCellValue().trim() : "x";
                if (!v.isEmpty()) return false;
            }
        }
        return true;
    }

    private void addDropdown(XSSFSheet sheet, DataValidationHelper dv,
                             Map<String, Integer> colIdxMap, String colName, String[] options) {
        Integer idx = colIdxMap.get(colName);
        if (idx == null) return;
        DataValidation val = dv.createValidation(
                dv.createExplicitListConstraint(options),
                new CellRangeAddressList(1, 1000, idx, idx));
        val.setShowErrorBox(false);
        sheet.addValidationData(val);
    }

    private void addFormulaDropdown(XSSFSheet sheet, DataValidationHelper dv,
                                    Map<String, Integer> colIdxMap, String colName, String formula) {
        Integer idx = colIdxMap.get(colName);
        if (idx == null) return;
        DataValidation val = dv.createValidation(
                dv.createFormulaListConstraint(formula),
                new CellRangeAddressList(1, 1000, idx, idx));
        val.setShowErrorBox(false);
        sheet.addValidationData(val);
    }

    // ===================================================================
    // 样式
    // ===================================================================
    private XSSFCellStyle headerStyle(XSSFWorkbook wb, boolean sensitive) {
        XSSFCellStyle style = wb.createCellStyle();
        XSSFFont font = wb.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        byte[] color = sensitive
                ? new byte[]{(byte)211,(byte)84,(byte)0}
                : new byte[]{(byte)41,(byte)128,(byte)185};
        style.setFillForegroundColor(new XSSFColor(color, null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private XSSFCellStyle wrapStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        style.setWrapText(true);
        style.setVerticalAlignment(VerticalAlignment.TOP);
        return style;
    }

    private XSSFCellStyle redStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        style.setWrapText(true);
        style.setVerticalAlignment(VerticalAlignment.TOP);
        XSSFFont font = wb.createFont();
        font.setColor(new XSSFColor(new byte[]{(byte)192,(byte)0,(byte)0}, null));
        style.setFont(font);
        return style;
    }
}
