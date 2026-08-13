package com.lusuoria.settlement.util;

import java.util.LinkedHashSet;

/**
 * 从自由文本里识别"合作平台"的启发式规则（IG/TT/YT/FB 等缩写、REEL 归到 Instagram 等）。
 * 2026-07 从 CollaborationTrackingExcelHandler 抽出来，供 Excel 导入智能提取列 和
 * "红人需求管理"模块的"提取需求内容"共用，规则改一处两边都生效。
 */
public class PlatformTextParser {

    private PlatformTextParser() {}

    /** 从一段自由文本里识别出涉及的合作平台，识别到的按遇到顺序换行拼接，一个都没识别到返回 null */
    public static String extractPlatforms(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String u = raw.toUpperCase();
        LinkedHashSet<String> set = new LinkedHashSet<String>();
        if (u.contains("INSTAGRAM") || u.contains("IG") || u.contains("REEL")) set.add("Instagram");
        if (u.contains("TIKTOK") || matchesToken(u, "TT")) set.add("TikTok");
        if (u.contains("YOUTUBE") || matchesToken(u, "YT")) set.add("YouTube");
        if (u.contains("FACEBOOK") || matchesToken(u, "FB")) set.add("Facebook");
        if (raw.contains("微博") || u.contains("WEIBO")) set.add("微博");
        if (raw.contains("小红书") || u.contains("XHS") || u.contains("RED")) set.add("小红书");
        if (raw.contains("抖音")) set.add("抖音");
        return set.isEmpty() ? null : String.join("\n", set);
    }

    /** 标准化已是平台值的内容（如前端提交的"Instagram\nTikTok"或逗号分隔）；不像标准值就走智能提取 */
    public static String normalizePlatforms(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String extracted = extractPlatforms(raw);
        return extracted != null ? extracted : raw.trim();
    }

    /** 判断缩写 token 是否作为独立单词出现（避免 "TT" 误匹配 "ATTACK"） */
    private static boolean matchesToken(String upperText, String token) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(^|[^A-Z0-9])" + token + "([^A-Z0-9]|$)")
                .matcher(upperText);
        return m.find();
    }

    /**
     * 根据"视频发布链接"本身的域名反推它属于哪个合作平台（2026-08 新增，供 Excel 导入校验
     * "视频发布链接"跟"合作平台"列是否对得上用）。链接域名比 extractPlatforms() 依赖的自由
     * 文本描述更可靠，识别不到时返回 null——调用方应该报错让人核对，而不是像 extractPlatforms()
     * 那样静默忽略识别不到的部分。
     */
    public static String platformFromUrl(String url) {
        if (url == null || url.trim().isEmpty()) return null;
        String u = url.trim().toLowerCase();
        if (u.contains("instagram.com") || u.contains("instagr.am")) return "Instagram";
        if (u.contains("tiktok.com")) return "TikTok";
        if (u.contains("youtube.com") || u.contains("youtu.be")) return "YouTube";
        if (u.contains("facebook.com") || u.contains("fb.watch")) return "Facebook";
        if (u.contains("weibo.com") || u.contains("weibo.cn")) return "微博";
        if (u.contains("xiaohongshu.com") || u.contains("xhslink.com")) return "小红书";
        if (u.contains("douyin.com") || u.contains("iesdouyin.com")) return "抖音";
        return null;
    }
}
