package com.lusuoria.settlement.util;

/**
 * URL 归一化工具。
 *
 * 用于"采买旧视频的原链接"查重：同一个视频，Instagram/TikTok 的链接可能有
 * http/https、带不带 www.、结尾斜杠、带各种分享/追踪参数（如 ?igsh=xxx、?_r=1）
 * 等不同写法，但其实是同一条视频，需要被识别为重复。
 *
 * 做法：转小写 -> 去首尾空格 -> 统一协议头(去掉 http(s)://) -> 去掉 www./m. 前缀
 * -> 去掉结尾斜杠 -> 去掉查询参数(?后面的部分)和锚点(#后面的部分)。
 *
 * 局限：像 TikTok 的短链接（vm.tiktok.com/xxxx）需要实际跳转才能拿到真正的视频ID，
 * 这里没有做网络请求去解析短链接指向的真实地址，所以两个都是短链接但其实是
 * 同一个视频的情况，这个方法识别不出来（需要人工留意）。
 */
public class UrlNormalizer {

    public static String normalize(String url) {
        if (url == null) return null;
        String s = url.trim();
        if (s.isEmpty()) return null;

        s = s.toLowerCase();

        // 去掉协议头
        s = s.replaceFirst("^https?://", "");

        // 去掉常见子域名前缀
        s = s.replaceFirst("^www\\.", "");
        s = s.replaceFirst("^m\\.", "");

        // 去掉查询参数和锚点
        int qIdx = s.indexOf('?');
        if (qIdx >= 0) s = s.substring(0, qIdx);
        int hIdx = s.indexOf('#');
        if (hIdx >= 0) s = s.substring(0, hIdx);

        // 去掉结尾斜杠
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);

        return s.isEmpty() ? null : s;
    }
}
