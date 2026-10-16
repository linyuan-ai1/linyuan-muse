package com.linyuan.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MemoryExtractor {

    public static class MemoryCandidate {
        public String content;
        public String category;

        public MemoryCandidate(String content, String category) {
            this.content = content;
            this.category = category;
        }
    }

    public static List<MemoryCandidate> extract(String userText, String aiReply) {
        List<MemoryCandidate> result = new ArrayList<>();
        String user = userText == null ? "" : userText;

        matchAndAdd(result, user, "我叫([\\u4e00-\\u9fa5A-Za-z]{1,20})", "称呼", "用户称呼：");
        matchAndAdd(result, user, "请叫我([\\u4e00-\\u9fa5A-Za-z]{1,20})", "称呼", "用户称呼：");

        matchAndAdd(result, user, "我是([\\u4e00-\\u9fa5]{0,20}?(?:学生|老师|程序员|工程师|医生|护士|妈妈|爸爸|上班族|创业者|老板))", "身份", "用户身份：");
        matchAndAdd(result, user, "我喜欢([\\u4e00-\\u9fa5A-Za-z0-9，,、 ]{2,40})", "偏好", "偏好：");
        matchAndAdd(result, user, "我不喜欢([\\u4e00-\\u9fa5A-Za-z0-9，,、 ]{2,40})", "偏好", "不喜欢：");
        matchAndAdd(result, user, "我讨厌([\\u4e00-\\u9fa5A-Za-z0-9，,、 ]{2,40})", "偏好", "不喜欢：");
        matchAndAdd(result, user, "我想学([\\u4e00-\\u9fa5A-Za-z0-9，,、 ]{2,60})", "学习", "学习目标：");
        matchAndAdd(result, user, "我最近在学([\\u4e00-\\u9fa5A-Za-z0-9，,、 ]{2,60})", "学习", "最近学习：");
        matchAndAdd(result, user, "我的目标是([\\u4e00-\\u9fa5A-Za-z0-9，,、 ]{2,60})", "目标", "目标：");
        matchAndAdd(result, user, "我在([\\u4e00-\\u9fa5A-Za-z0-9，,、 ]{2,30}?)工作", "工作方式", "工作：");
        matchAndAdd(result, user, "别忘了([\\u4e00-\\u9fa5A-Za-z0-9，,、 ]{4,60})", "重要", "重要事项：");
        matchAndAdd(result, user, "记住([\\u4e00-\\u9fa5A-Za-z0-9，,、 ]{4,60})", "重要", "重要事项：");

        return result;
    }

    private static void matchAndAdd(List<MemoryCandidate> result, String text, String regex,
                                    String category, String prefix) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        while (matcher.find()) {
            String value = matcher.group(1).trim();
            if (value.length() >= 2) {
                addIfNew(result, prefix + value, MemoryDim.normalizeUser(category));
            }
        }
    }

    private static void addIfNew(List<MemoryCandidate> result, String content, String category) {
        String clean = content.trim();
        if (clean.length() < 2) return;
        for (MemoryCandidate candidate : result) {
            if (candidate.content.equals(clean)) return;
        }
        result.add(new MemoryCandidate(clean, category));
    }

    public static String mockReply(String userText, List<String> memories) {
        String text = userText == null ? "" : userText.toLowerCase(Locale.ROOT);
        if (text.contains("我叫") || text.contains("请叫我")) {
            return "好的，我已经记住你的称呼了。以后我会一直用这个方式叫你。";
        }
        if (text.contains("喜欢") || text.contains("不喜欢") || text.contains("讨厌") || text.contains("想学")) {
            return "这个我记住了。以后聊天和学习的时候，我会按你的偏好来调整。";
        }
        if (text.contains("教") || text.contains("怎么学") || text.contains("帮我")) {
            return "没问题。你先告诉我今天想学什么，我可以把它拆成几步，一边讲一边陪你练。";
        }
        if (memories != null && !memories.isEmpty()) {
            return "我记得你之前提到过：" + memories.get(0) + "。我们可以接着这个话题继续。";
        }
        return "我在听。你继续说，我会尽量记住你提到的重点，不会聊着聊着就忘。";
    }
}
