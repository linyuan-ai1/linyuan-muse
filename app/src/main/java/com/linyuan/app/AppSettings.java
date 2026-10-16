package com.linyuan.app;

public class AppSettings {

    public String mode = "openai";
    public String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    public String model = "deepseek-v4-flash-0731";
    public String apiKey = "";
    public String language = "zh-CN";
    public String rate = "1.0";
    public String systemPrompt = "你是lin渊。回答要自然、口语化、适合朗读，通常1-3句话。回答里不要出现markdown。";
    public String temperature = "0.6";
    public String maxTokens = "110000";
    public String topP = "1.0";
    public boolean enableThinking = true;
    public boolean enableDeepThinking = false;

    public int thinkLevel = -1;

    public double frequencyPenalty = 0;
    public double presencePenalty = 0;
    public int connectTimeout = 60000;
    public int readTimeout = 300000;

    public boolean memoryEnabled = true;
    public boolean webSearchEnabled = false;
    public String webSearchApiUrl = "";

    public String tokeniqBaseUrl = "";  // 搜索服务地址（OpenAI 兼容格式），公开版不内置，请在设置页填写
    public String tokeniqApiKey = "";
    public int tokeniqSearchCount = 20;
    public String tokeniqFreshness = "";
    public int searchMinGapSec = 8;

    public int searchPerMinute = 30;

    public String searchKeyPool = "";
    public int searchPoolCooldownSec = 1;

    // 公开版清空了内置密钥池：密钥属于个人资产，不随源码分发。
    // 请在 App「设置 → 搜索」里填入自己的服务地址与 Key（每行一条：地址|Key）。
    public static final String[] BUILTIN_SEARCH_KEY_POOL = {};

    // 同上：公开版不内置生图密钥。请在「设置 → 图片生成」里配置端点和 Key。
    public static final String[] BUILTIN_IMAGE_KEY_POOL = {};
    public double memoryDedupThreshold = 0.7;
    public String memoryPinnedNotes = "";
    public boolean memoryUseVector = true;
    public boolean memoryUseLlmExtract = true;
    public boolean entityEnabled = true;
    public double memoryTimeDecay = 0.95;
    public int memoryExtractMaxTokens = 110000;
    public double memoryExtractTemperature = 0.1;

    // 记忆整理模型：专门负责把对话整理进长期记忆（add / update / merge）。
    // 和主对话分开配，可以用更便宜或更强的模型；留空则回落到主对话模型。
    public String memoryModel = "";
    public String memoryBaseUrl = "";
    public String memoryApiKey = "";

    /** 空闲多久后触发记忆整理（秒）。用户定：30 秒。*/
    public int memoryIdleSeconds = 30;

    // 三个辅助模型各自的思考档位（用户 2026-10-13 要求各配一个档位按钮；
    // 2026-10-14 用户把默认值从 0(关) 改成 2(中)）。-1=跟随主对话（未使用）。
    public int personaThinkLevel = 2;
    public int memoryThinkLevel = 2;
    public int compactThinkLevel = 2;
    public double memoryWeightImportance = 0.3;
    public double memoryWeightVector = 0.5;
    public double memoryWeightTime = 0.2;

    public int contextBudgetTokens = 1000000;

    public int memoryStoreBudgetTokens = 350000;
    public int shortTermBudgetTokens = 300000;

    public int selfStoreBudgetTokens = 100000;

    public int memorySearchLimit = 40;

    public int maxToolIterations = 500;
    public boolean useMemoryTools = true;
    public boolean floatBallEnabled = true;
    public boolean systemToolsEnabled = true;

    public String embeddingModel = "Qwen/Qwen3-Embedding-0.6B";
    public String embeddingBaseUrl = "https://api.siliconflow.cn/v1";
    public String embeddingApiKey = "";

    public String chatBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    public String chatModel = "deepseek-v4-flash-0731";
    public String chatApiKey = "";

    public static final String[] DOUBAO_PROFILES = {

    };

    public String asrBaseUrl = "https://dashscope.aliyuncs.com";
    public String asrModel = "qwen-audio-3.1-asr-flash";
    public String asrApiKey = "";

    public String modelPoolState = "";

    public boolean modelPoolEnabled = true;

    public String ttsBaseUrl = "wss://dashscope.aliyuncs.com/api-ws/v1/inference";
    public String ttsModel = "cosyvoice-v1";
    public String ttsVoice = "longxiaochun";
    public String ttsApiKey = "";

    public boolean useTencentTts = true;
    public String tencentSecretId = "";
    public String tencentSecretKey = "";
    public String tencentTtsRegion = "ap-guangzhou";
    public String tencentTtsVoice = "101006";

    public int tencentTtsSpeed = 1;

    public String ttsKeyPool = "";

    public int ttsPoolCooldownSec = 10;

    public int ttsPoolDisableMin = 30;
    public boolean useBailianAsr = true;
    public boolean useBailianTts = false;

    public String doubaoAppId = "";
    public String doubaoSpeakerId = "zh_female_vv_uranus_bigtts";
    public String doubaoAccessKey = "";
    /** 新版控制台的 API Key。填了它就走双向流式（首字快），
     *  留空则回退到 AppId+AccessKey 的 HTTP 单向。*/
    public String doubaoApiKey = "";

    public static final String[][] DOUBAO_SPEAKERS = {
            // 带 uranus 的是 2.0 代音色。注意别和 zh_female_wanwanxiaohe_moon_bigtts
            // （「湾湾小何」，台湾口音的另一支）弄混。
            {"zh_female_xiaohe_uranus_bigtts", "小何 2.0（女·默认）"},
            {"zh_female_vv_uranus_bigtts", "乌拉诺斯（女）"},
            {"zh_female_wanwanxiaohe_moon_bigtts", "晓禾（女·治愈）"},
            {"zh_male_qinqin_bigtts", "勤勤（男·磁性）"},
            {"zh_female_zhixing_mars_bigtts", "知性（女·知性）"},
            {"zh_female_ymq_bigtts", "一梦千（女·灵动）"}
    };
    public boolean enableDoubaoTts = true;
    public boolean enableDoubaoAsr = false;

    public boolean useAliyunTts = true;
    public String aliyunTtsBaseUrl = "wss://dashscope.aliyuncs.com/api-ws/v1/inference";
    public String aliyunTtsApiKey = "";
    public String aliyunTtsModel = "cosyvoice-v1";

    public String visionBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    public String visionModel = "shijue";
    public String visionApiKey = "";

    public boolean visionUseMainModel = false;

    public boolean personaDistillEnabled = false;
    public String personaModel = "";
    public String personaBaseUrl = "";
    public String personaApiKey = "";

    public boolean compactEnabled = true;
    public String compactModel = "";
    public String compactBaseUrl = "";
    public String compactApiKey = "";
    public int compactTriggerTokens = 0;
    public int personaDistillInterval = 20;
    public String personaProfile = "";
    public int personaDistilledMsgCount = 0;
    public long personaLastDistillAt = 0;

    public String imageGenBaseUrl = "";  // 生图端点（OpenAI 兼容 /v1/images/generations），请在设置页填写

    public String imageGenModel = "image-gen-2.5-flash";

    public String imageGenKeyPool = "";
    public int imageGenPoolCooldownSec = 0;

    public int imageGenConcurrency = 3;

    public static final String IMAGE_GEN_DIR = "images_gen";

    public String amapApiKey = "";

    public boolean voiceprintEnabled = false;
    public double voiceprintThreshold = 0.5;
    public boolean voiceprintEnrolled = false;
    public String voiceprintEmbedding = "";

    public boolean imeTypingEnabled = true;

    public boolean imeRestoreAfterTyping = true;

    private static final String PREFS = "voice_companion";
    private static final String KEY_USER_IME = "savedUserIme";

    public static boolean isImeRestoreAfterTyping(android.content.Context ctx) {
        try {
            return ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                    .getBoolean("imeRestoreAfterTyping", true);
        } catch (Throwable t) {
            return true;
        }
    }

    public static void setSavedUserIme(android.content.Context ctx, String imeId) {
        try {
            ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                    .edit().putString(KEY_USER_IME, imeId).apply();
        } catch (Throwable ignored) {
        }
    }

    public static String getSavedUserIme(android.content.Context ctx) {
        try {
            return ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                    .getString(KEY_USER_IME, null);
        } catch (Throwable t) {
            return null;
        }
    }
}
