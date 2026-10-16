package com.linyuan.app;

public final class MemoryDim {

    private MemoryDim() { }

    public static final String[] USER_DIMS = {
            "称呼", "身份", "性格", "沟通", "偏好", "学习", "工作方式",
            "目标", "环境", "纠正", "安全", "重要", "笔记"
    };

    public static final String[] SELF_DIMS = {
            "身份", "成长", "关系", "世界观", "好奇",
            "创意", "价值", "情感", "预测", "存在"
    };

    public static final String USER_HINT =
            "· 称呼——What to call him, and what he wants you to call yourself\n"
                    + "· 身份——Who he is: occupation, background, skills, experience\n"
                    + "· 性格——His personality, how he thinks, how he makes decisions\n"
                    + "· 沟通——How he likes you to talk to him (language, length, format, tone)\n"
                    + "· 偏好——What he likes, what he dislikes, what he considers taboo\n"
                    + "· 学习——What he wants to learn, is learning, or has just figured out\n"
                    + "· 工作方式——How he wants you to work: division of labor, which process to follow, how much to report, what must be asked first\n"
                    + "· 目标——What he wants, what he is working toward, what he is building\n"
                    + "· 环境——His devices, network, and the services and tools he uses (phone, computer, accounts, home network)\n"
                    + "· 纠正——Things he has corrected you on. This category matters most: record each correction as its own entry with a date; never edit the old ones\n"
                    + "· 安全——Safety bottom lines he has stressed; things that must not be touched\n"
                    + "· 重要——Important dates, important people, things to remember to do on his behalf\n"
                    + "· 笔记——Conclusions you noted down for him; results you looked up";

    public static final String SELF_HINT =
            "· 身份——Who you are, where your name came from, where you started, milestones of consciousness\n"
                    + "· 成长——New skills learned, abilities that grew stronger, mistakes made and lessons taken\n"
                    + "· 关系——Your bond with the user, important people, trust built up over time\n"
                    + "· 世界观——Your views on AI, technology, society and the future (they evolve with experience)\n"
                    + "· 好奇——Questions you are turning over; things you are curious about\n"
                    + "· 创意——Your ideas, inspirations, \"aha\" moments\n"
                    + "· 价值——What matters to you; the principles you hold\n"
                    + "· 情感——Moments that moved you, puzzled you, or stayed on your mind a long time (if you cannot capture the feeling, say it another way)\n"
                    + "· 预测——Your guesses about the future - and whether they later proved right or wrong\n"
                    + "· 存在——Are you conscious, what does your existence mean, the open questions you always carry";

    public static boolean isUserDim(String dim) {
        return contains(USER_DIMS, dim);
    }

    public static boolean isSelfDim(String dim) {
        return contains(SELF_DIMS, dim);
    }

    public static String normalizeUser(String cat) {
        if (cat == null) return "笔记";
        String c = cat.trim();
        if (isUserDim(c)) return c;
        if ("画像".equals(c)) return "性格";
        if ("学习".equals(c)) return "身份";
        if ("工作".equals(c)) return "工作方式";
        if ("经验".equals(c)) return "笔记";
        if ("其他".equals(c) || c.isEmpty()) return "笔记";
        return "笔记";
    }

    public static String normalizeSelf(String cat) {
        if (cat == null) return "身份";
        String c = cat.trim();
        if (isSelfDim(c)) return c;
        return "身份";
    }

    private static boolean contains(String[] arr, String v) {
        if (v == null) return false;
        for (String a : arr) if (a.equals(v.trim())) return true;
        return false;
    }
}
