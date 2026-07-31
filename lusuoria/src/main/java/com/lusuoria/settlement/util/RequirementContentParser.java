package com.lusuoria.settlement.util;

import com.lusuoria.settlement.dto.response.RequirementContentParseResponse;
import com.lusuoria.settlement.entity.Influencer;
import com.lusuoria.settlement.enums.VideoType;
import com.lusuoria.settlement.repository.InfluencerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "提取需求内容"：从客户提需求时常见的自由格式聊天记录/邮件文本里，尽力解析出结构化信息。
 * 纯正则/关键词启发式，不调用任何 AI 接口——目前只覆盖已知的几种文本格式（见类内三组测试
 * 用例思路），后续遇到新格式大概率还要再补规则，所有规则集中在这一个类，方便单独调整。
 *
 * 项目视频类型（videoType）：只在文本里出现"视频类型：xxx"或"项目视频类型：xxx"（前面可以带
 * "-"、label 和值之间的空格也可有可无），且 xxx 精确匹配 VideoType 枚举的中文 label 时才识别；
 * 识别不出来（没写、或者写了但不是这4个已知值）时留空让用户手动选，不做模糊匹配/猜测。
 */
@Component
public class RequirementContentParser {

    private static final Logger log = LoggerFactory.getLogger(RequirementContentParser.class);

    @Autowired private InfluencerRepository influencerRepo;

    // ---- 账号提取：按可靠程度依次尝试 ----
    private static final Pattern LABEL_ACCOUNT = Pattern.compile(
            "红人(?:社媒)?完整名字[：:]\\s*([A-Za-z0-9_.]+)");
    private static final Pattern INSTAGRAM_LINK = Pattern.compile(
            "instagram\\.com/([A-Za-z0-9_.]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOOSE_DAREN = Pattern.compile(
            "达人([A-Za-z0-9_.]+)");

    // ---- 单价+数量识别：要求"USD/条"紧跟在数字后面，避免误把"其他权益"里 0USD 的附加说明当条目 ----
    // "合作价格"（不带"客户"/"红人"前缀，出现在不太规范的需求文本里）也当客户合作单价处理；
    // 用负向后顾排除"红人合作价格"这种写法，避免跟红人成本混淆
    private static final Pattern CLIENT_UNIT_PRICE = Pattern.compile(
            "(?:客户价格|客户合作价格|(?<!红人)合作价格)[^：:\\n]*[：:]\\s*([\\d.]+)\\s*USD\\s*/\\s*条(?:[^\\n]*?(?:共计|合计|总计)\\s*([\\d.]+)\\s*USD)?");
    private static final Pattern COST_UNIT_PRICE = Pattern.compile(
            "(?:红人成本|红人合作成本)[^：:\\n]*[：:]\\s*([\\d.]+)\\s*USD\\s*/\\s*条(?:[^\\n]*?(?:共计|合计|总计)\\s*([\\d.]+)\\s*USD)?");
    private static final Pattern QTY_ORDER_COUNT = Pattern.compile("下单条数[：:]\\s*(\\d+)\\s*条");
    private static final Pattern QTY_COOPERATION = Pattern.compile("合作\\s*(\\d+)\\s*条");

    // ---- 项目视频类型识别：值必须精确匹配 VideoType 的中文 label，枚举顺序拼进正则的候选组里，
    // 新增/改动 VideoType 的 label 时这里自动跟着变，不用同步改两处 ----
    private static final Pattern LABEL_VIDEO_TYPE = Pattern.compile(
            "(?:项目)?视频类型[：:]\\s*(" + Arrays.stream(VideoType.values())
                    .map(VideoType::getLabel)
                    .collect(java.util.stream.Collectors.joining("|")) + ")");

    public static class ParseException extends RuntimeException {
        public ParseException(String msg) { super(msg); }
    }

    public RequirementContentParseResponse parse(String content) {
        if (content == null || content.trim().isEmpty()) {
            throw new ParseException("完整需求内容为空，无法提取");
        }

        String accountName = extractAccountName(content);
        if (accountName == null) {
            throw new ParseException("未能从文本中识别出红人账号，请手动选择红人后再重试");
        }

        Influencer influencer = influencerRepo.findByAccountNameIgnoreCaseAndIsDeletedFalse(accountName)
                .orElse(null);
        if (influencer == null) {
            throw new ParseException("系统未匹配到红人\"" + accountName
                    + "\"的数据，请先在\"红人管理\"模块新增此红人的相关数据后，再新增需求内容.");
        }

        Integer videoCount = extractQuantity(content);
        BigDecimal clientTotal = null;
        BigDecimal costTotal = null;
        BigDecimal clientUnitPrice = null;
        BigDecimal costUnitPrice = null;

        Matcher clientMatcher = CLIENT_UNIT_PRICE.matcher(content);
        if (clientMatcher.find()) {
            clientUnitPrice = parseAmount(clientMatcher.group(1));
            if (clientMatcher.group(2) != null) clientTotal = parseAmount(clientMatcher.group(2));
        }
        Matcher costMatcher = COST_UNIT_PRICE.matcher(content);
        if (costMatcher.find()) {
            costUnitPrice = parseAmount(costMatcher.group(1));
            if (costMatcher.group(2) != null) costTotal = parseAmount(costMatcher.group(2));
        }

        if (videoCount == null || (clientUnitPrice == null && costUnitPrice == null)) {
            throw new ParseException("未能从需求内容中识别出有效的需求条目（单价/数量），"
                    + "请仔细检查完整需求内容后，重新填写并识别，或手动新增条目");
        }

        if (clientUnitPrice != null && clientUnitPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ParseException("识别到的客户合作单价不是有效的正数金额，请仔细检查完整需求内容后，重新填写并识别");
        }
        if (costUnitPrice != null && costUnitPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ParseException("识别到的红人视频制作与发布单价成本不是有效的正数金额，请仔细检查完整需求内容后，重新填写并识别");
        }

        if (clientTotal != null && clientUnitPrice != null) {
            BigDecimal expected = clientUnitPrice.multiply(BigDecimal.valueOf(videoCount));
            if (expected.compareTo(clientTotal) != 0) {
                throw new ParseException("客户合作总价格（" + clientTotal + " USD）与 单价×数量（"
                        + expected + " USD）对不上，请仔细检查完整需求内容后，重新填写并识别");
            }
        }
        if (costTotal != null && costUnitPrice != null) {
            BigDecimal expected = costUnitPrice.multiply(BigDecimal.valueOf(videoCount));
            if (expected.compareTo(costTotal) != 0) {
                throw new ParseException("红人视频制作与发布总成本（" + costTotal + " USD）与 单价×数量（"
                        + expected + " USD）对不上，请仔细检查完整需求内容后，重新填写并识别");
            }
        }

        String platformJoined = PlatformTextParser.extractPlatforms(content);
        List<String> platforms = platformJoined == null
                ? new ArrayList<>() : Arrays.asList(platformJoined.split("\n"));

        RequirementContentParseResponse.ParsedItem item = new RequirementContentParseResponse.ParsedItem();
        item.setVideoType(extractVideoType(content));
        item.setPlatform(platforms);
        item.setVideoCount(videoCount);
        item.setClientUnitPrice(clientUnitPrice);
        item.setInfluencerUnitCostPrice(costUnitPrice);

        RequirementContentParseResponse resp = new RequirementContentParseResponse();
        resp.setInfluencerId(influencer.getId());
        resp.setAccountName(influencer.getAccountName());
        resp.setItems(new ArrayList<>(java.util.Collections.singletonList(item)));
        return resp;
    }

    private String extractAccountName(String content) {
        Matcher m = LABEL_ACCOUNT.matcher(content);
        if (m.find()) return m.group(1);
        m = INSTAGRAM_LINK.matcher(content);
        if (m.find()) return m.group(1);
        m = LOOSE_DAREN.matcher(content);
        if (m.find()) return m.group(1);
        return null;
    }

    private VideoType extractVideoType(String content) {
        Matcher m = LABEL_VIDEO_TYPE.matcher(content);
        if (m.find()) return VideoType.fromLabel(m.group(1));
        return null;
    }

    private Integer extractQuantity(String content) {
        Matcher m = QTY_ORDER_COUNT.matcher(content);
        if (m.find()) return Integer.parseInt(m.group(1));
        m = QTY_COOPERATION.matcher(content);
        if (m.find()) return Integer.parseInt(m.group(1));
        return null;
    }

    /** 金额解析：正则已经只捕获数字和小数点，理论上不会解析失败，这里只是防御性兜底 */
    private BigDecimal parseAmount(String raw) {
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            // 正则理论上只会捕获数字和小数点，走到这里说明规则本身可能有遗漏case，
            // 之前完全静默返回 null，日志里查不到是什么输入触发的
            log.warn("金额解析失败（理论上不应发生），原始片段：{}", raw);
            return null;
        }
    }
}
