package com.lusuoria.settlement.excel;

import com.lusuoria.settlement.entity.Influencer;
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

/**
 * 红人 Excel 导入/导出
 *
 * 敏感列：paymentInfo（收款信息，含银行账号）
 *   canViewSensitive = true  (ADMIN / AUDITOR)：含收款信息列
 *   canViewSensitive = false (STAFF / GUEST)  ：不含收款信息列
 */
@Component
public class InfluencerExcelHandler {

    @Autowired private InfluencerRepository influencerRepo;

    // ===== 导出 =====
    public void export(List<Influencer> influencers, boolean canViewSensitive,
                       HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        String fileName = "红人_" + new SimpleDateFormat("yyyyMMdd").format(new Date()) + ".xlsx";
        response.setHeader("Content-Disposition",
                "attachment;filename=" + java.net.URLEncoder.encode(fileName, "UTF-8"));

        XSSFWorkbook wb    = new XSSFWorkbook();
        XSSFSheet    sheet = wb.createSheet("红人");
        XSSFCellStyle hdrN = createHeaderStyle(wb, false);
        XSSFCellStyle hdrS = createHeaderStyle(wb, true);
        XSSFCellStyle nor  = createNormalStyle(wb);

        // 列定义：(标题, 是否敏感)
        List<String[]> cols = new ArrayList<String[]>();
        cols.add(new String[]{"红人类型",   "0"});
        cols.add(new String[]{"红人团队",   "0"});
        cols.add(new String[]{"红人账号",   "0"});
        cols.add(new String[]{"国家/市场",  "0"});
        cols.add(new String[]{"平台",       "0"});
        cols.add(new String[]{"合作模式",   "0"});
        cols.add(new String[]{"收款信息",   "0"});
        cols.add(new String[]{"备注",       "0"});

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
            setCellStr(row, c++, inf.getInfluencerType() != null
                    ? inf.getInfluencerType().getLabel() : "", nor);
            setCellStr(row, c++, inf.getTeamName(),        nor);
            setCellStr(row, c++, inf.getAccountName(),     nor);
            setCellStr(row, c++, inf.getCountryMarket(),   nor);
            setCellStr(row, c++, inf.getPlatform(),        nor);
            setCellStr(row, c++, inf.getCooperationMode(), nor);
            setCellStr(row, c++, inf.getPaymentInfo(), nor);
            setCellStr(row, c++, inf.getNotes(), nor);
        }

        sheet.createFreezePane(0, 1);
        wb.write(response.getOutputStream());
        wb.close();
    }

    // ===== 下载导入模板 =====
    public void downloadTemplate(boolean canViewSensitive, HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition",
                "attachment;filename=" + java.net.URLEncoder.encode("红人导入模板.xlsx", "UTF-8"));

        XSSFWorkbook wb    = new XSSFWorkbook();
        XSSFSheet    sheet = wb.createSheet("红人导入");
        XSSFCellStyle hdrN = createHeaderStyle(wb, false);
        XSSFCellStyle hdrS = createHeaderStyle(wb, true);

        List<String[]> cols = new ArrayList<String[]>();
        cols.add(new String[]{"红人类型(必填,海外红人/中国红人)", "0"});
        cols.add(new String[]{"红人团队",   "0"});
        cols.add(new String[]{"红人账号(必填)", "0"});
        cols.add(new String[]{"国家/市场",  "0"});
        cols.add(new String[]{"平台",       "0"});
        cols.add(new String[]{"合作模式",   "0"});
        cols.add(new String[]{"收款信息",   "0"});
        cols.add(new String[]{"备注",       "0"});

        Row headerRow = sheet.createRow(0);
        int colIdx = 0;
        for (String[] col : cols) {
            if ("1".equals(col[1]) && !canViewSensitive) continue;
            Cell cell = headerRow.createCell(colIdx++);
            cell.setCellValue(col[0]);
            cell.setCellStyle("1".equals(col[1]) ? hdrS : hdrN);
            sheet.setColumnWidth(colIdx - 1, 24 * 256);
        }

        // 红人类型下拉
        DataValidationHelper dvHelper = sheet.getDataValidationHelper();
        DataValidation dv = dvHelper.createValidation(
                dvHelper.createExplicitListConstraint(new String[]{"海外红人", "中国红人"}),
                new CellRangeAddressList(1, 1000, 0, 0));
        dv.setShowErrorBox(true);
        sheet.addValidationData(dv);

        // 示例行
        Row ex = sheet.createRow(1);
        int ec = 0;
        for (String[] col : cols) {
            if ("1".equals(col[1]) && !canViewSensitive) continue;
            ex.createCell(ec++).setCellValue(getExampleValue(col[0]));
        }

        wb.write(response.getOutputStream());
        wb.close();
    }

    // ===== 导入（增量，按红人账号去重）=====
    public List<String> importData(MultipartFile file) throws IOException {
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

        int successCount = 0, skipCount = 0;
        for (int i = 1; i <= totalRows; i++) {
            Row row = sheet.getRow(i);
            if (row == null || isRowEmpty(row)) continue;
            try {
                String accountName = getStr(row, colMap, "红人账号(必填)");
                if (accountName == null || accountName.isEmpty()) {
                    errors.add("第" + (i + 1) + "行：红人账号不能为空"); continue;
                }
                // 去重：账号已存在则跳过
                List<Influencer> existing = influencerRepo.findByTeamNameAndIsDeletedFalse(accountName);
                // 用账号名精确匹配
                boolean alreadyExists = influencerRepo
                        .findByIsDeletedFalseOrderByTeamNameAscAccountNameAsc()
                        .stream()
                        .anyMatch(inf -> accountName.equals(inf.getAccountName()));
                if (alreadyExists) { skipCount++; continue; }

                String typeStr = getStr(row, colMap, "红人类型(必填,海外红人/中国红人)");
                if (typeStr == null || typeStr.isEmpty()) {
                    errors.add("第" + (i + 1) + "行：红人类型不能为空"); continue;
                }

                Influencer inf = new Influencer();
                inf.setIsDeleted(false);
                inf.setInfluencerType("海外红人".equals(typeStr)
                        ? ProjectType.OVERSEAS_INFLUENCER : ProjectType.CHINA_INFLUENCER);
                inf.setTeamName(getStr(row, colMap, "红人团队"));
                inf.setAccountName(accountName);
                inf.setCountryMarket(getStr(row, colMap, "国家/市场"));
                inf.setPlatform(getStr(row, colMap, "平台"));
                inf.setCooperationMode(getStr(row, colMap, "合作模式"));
                inf.setPaymentInfo(getStr(row, colMap, "收款信息")); // 无该列时为 null，不影响
                inf.setNotes(getStr(row, colMap, "备注"));
                influencerRepo.save(inf);
                successCount++;
            } catch (Exception e) {
                errors.add("第" + (i + 1) + "行导入失败：" + e.getMessage());
            }
        }
        workbook.close();
        int failCount = errors.size();
        errors.add(0, "成功新增 " + successCount + " 条，跳过重复 " + skipCount + " 条，失败 " + failCount + " 条");
        return errors;
    }

    // ===== 工具 =====
    private String getExampleValue(String col) {
        Map<String, String> m = new HashMap<String, String>();
        m.put("红人类型(必填,海外红人/中国红人)", "海外红人");
        m.put("红人团队",   "游琳团队");
        m.put("红人账号(必填)", "bigdogtech");
        m.put("国家/市场",  "美国");
        m.put("平台",       "TikTok");
        m.put("合作模式",   "视频合作");
        m.put("收款信息",   "PayPal: xxx@email.com");
        m.put("备注",       "示例数据，填完后请删除");
        String v = m.get(col);
        return v != null ? v : "";
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

    private void setCellStr(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value != null ? value : "");
        cell.setCellStyle(style);
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

    private XSSFCellStyle createHeaderStyle(XSSFWorkbook wb, boolean sensitive) {
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

    private XSSFCellStyle createNormalStyle(XSSFWorkbook wb) {
        return wb.createCellStyle();
    }
}
