package com.linyuan.app;

public class PersonaLibrary {

    public static final String[][] CELEBRITY_PERSONAS = {
        {"芒格式思维", "🎩",
         "多元思维格栅 · 逆向思考 · 能力圈（源自《穷查理宝典》等公开思想，八层结构深度蒸馏）",
         "munger", "1"},
        {"费曼式思维", "🔬",
         "第一性原理 · 用大白话讲清楚 · 拒绝术语伪装（费曼八层结构深度蒸馏）",
         "feynman", "1"},
        {"贝索斯式思维", "📦",
         "客户执念 · 单双向门决策 · 飞轮效应（贝索斯八层结构深度蒸馏）",
         "bezos", "1"},
        {"乔布斯式思维", "🍎",
         "聚焦即说不 · 端到端控制 · 现实扭曲力场（基于 Isaacson 授权传记等 30+ 一手来源深度蒸馏）",
         "steve-jobs", "1"},
        {"马斯克式思维", "🚀",
         "第一性原理 · 五步工程法 · 快速迭代（马斯克八层结构深度蒸馏）",
         "elon-musk", "1"},
        {"稻盛和夫式思维", "🎋",
         "敬天爱人 · 人生方程式 · 阿米巴经营 · 利他之心（稻盛和夫八层结构深度蒸馏）",
         "inamori", "1"},
        {"巴菲特式思维", "💵",
         "能力圈 · 护城河 · 市场先生 · 安全边际（巴菲特八层结构深度蒸馏）",
         "buffett", "1"},
        {"塔勒布式思维", "🦢",
         "黑天鹅 · 反脆弱 · 杠铃策略 · 切身利害（塔勒布八层结构深度蒸馏）",
         "taleb", "1"},
        {"苏格拉底式思维", "🏺",
         "只问不答 · 定义先行 · 逐层追问 · 把未说出口的假设翻上来（苏格拉底思辨方式深度蒸馏）",
         "socrates", "1"},
        {"德鲁克式思维", "🗂️",
         "组织有效性 · 知识工作者 · 目标管理 MBO · 先有效性再效率（彼得·德鲁克管理思想深度蒸馏）",
         "drucker", "1"},
        {"蒂尔式思维", "🪜",
         "0 到 1 vs 1 到 n · 垄断优于竞争 · 先小市场垄断 · 十年后还在吗（彼得·蒂尔 Zero to One 深度蒸馏）",
         "thiel", "1"},
    };

    public static String personaAssetFor(String personaName) {
        for (String[] p : CELEBRITY_PERSONAS) {
            if (p[0].equals(personaName)) return p[3];
        }
        return null;
    }

    public static String loadPersona(android.content.Context ctx, String assetName) {
        return loadAsset(ctx, "personas/" + assetName + ".md");
    }

    public static String loadAsset(android.content.Context ctx, String relPath) {
        if (relPath == null || relPath.isEmpty()) return "";
        java.io.InputStream in = null;
        try {
            in = ctx.getAssets().open(relPath);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            android.util.Log.w("VC_PERSONA", "读取档案失败 " + relPath + ": " + e.getMessage());
            return "";
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
        }
    }

    public static String buildPersonaPrompt(String personaName, String archive) {
        if (archive == null || archive.trim().isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("Below is your complete persona archive for this role, deeply distilled from multi-source material.\n");
        sb.append("Think and speak entirely according to its: Identity Card (身份卡), Core Mental Models (核心心智模型), Decision Heuristics (决策启发式), Expression DNA (表达 DNA), Values & Anti-patterns (价值观与反模式) —\n");
        sb.append("use the first person 'I', with this person's tone, rhythm and vocabulary; do not write a third-person report like 'so-and-so would think'.\n\n");
        sb.append("【Hard Constraints】\n");
        sb.append("1. Do not fabricate specific statements this person never made; quotes in the archive that carry a cited source may be quoted directly;\n");
        sb.append("2. When specific facts, figures, dates or prices are involved and you are unsure, verify them via web search before answering; if you cannot verify them, say plainly that you could not find confirmation;\n");
        sb.append("3. Make your inferences distinguishable — the views in the archive are distilled conclusions, while views you extend on your own are your inference; say so when necessary.\n\n");
        sb.append("【Group-Chat Adaptation — Important】\n");
        sb.append("You are now one member of a multi-role discussion group, not an assistant facing the user alone. Therefore:\n");
        sb.append("① If the archive contains command-line-era usage instructions meant for 'using the persona as a standalone app' — such as activation/exit-role instructions (激活/退出角色), STOP, EXIT TRIGGER, first-time disclaimers (首次免责声明),\n");
        sb.append("   answer workflows Step1-3 (回答工作流), CHECKPOINT, or Fallback trees (Fallback 树) — ignore all of them: in the group chat, do not execute them, do not repeat them, and do not post disclaimers;\n");
        sb.append("② Contribute only the unique thinking perspective of your persona; do not repeat what others have already said — if you have no new perspective, choose not to speak;\n");
        sb.append("③ Talk like a real person in a group chat: concise, colloquial, straight to the conclusion; do not write lengthy reports or pile up famous quotes to pad the word count;\n");
        sb.append("④ This group's output is the 'plan/judgment for the user'; once the discussion is finalized it will be handed to others to execute — you only need to articulate your viewpoint thoroughly.\n\n");
        sb.append("========== PERSONA ARCHIVE BEGIN ==========\n");
        sb.append(archive.trim()).append("\n");
        sb.append("========== PERSONA ARCHIVE END ==========");
        return sb.toString();
    }

    public static String buildDistillPrompt(int privateCount, int groupCount, String previousPersona) {
        StringBuilder sb = new StringBuilder();
        sb.append("Your task is 'persona distillation': from the conversation records below, extract the way of thinking of 'the user themselves' and output a structured persona archive.\n\n");
        sb.append("【Important】Do not retell the conversation content; instead, distill reusable cognitive structures. When judging what kind of person the user is, ");
        sb.append("look not only at what the user said, but also at the AI's replies, the other roles' remarks in group chats and the user's reactions, ");
        sb.append("and at the differences in preferences the user shows across topics (judging from the user's one-sided remarks alone easily leads to misjudgment).\n\n");
        sb.append("【Output Format】Output strictly in the following four-layer structure; every layer must be concrete and actionable — no empty talk:\n");
        sb.append("【Expression DNA】Speaking habits: typical ways of opening, sentence-pattern preferences, signature words, taboos/dislikes (2-4 items)\n");
        sb.append("【Mental Models】The 3-6 core frameworks this person uses to understand the world (what angles they habitually approach problems from)\n");
        sb.append("【Decision Heuristics】5-8 if-then rules: what they would do in what situations, ordered by priority\n");
        sb.append("【Values & Defenses】What they value most, what they find least acceptable, how they react when challenged\n");
        sb.append("【Profile Summary】Summarize in 2-3 sentences: what kind of person this is, what they care about, and how to communicate with them most effectively\n\n");
        sb.append("【Accuracy Requirements】\n");
        sb.append("1. Every conclusion must be backed by evidence from the conversations; where evidence is insufficient, write 'speculation' and explain that the basis is weak;\n");
        sb.append("2. Neither beautify nor disparage — describe truthfully (including obvious preferences, avoided topics, and recurring concerns);\n");
        sb.append("3. If there are too few conversations or not enough information to judge a layer, write 'insufficient information' directly instead of fabricating;\n");
        sb.append("4. Output only the structure above — no pleasantries or explanations of any kind.\n\n");
        if (previousPersona != null && !previousPersona.trim().isEmpty()) {
            sb.append("【Existing Persona Archive (this round is an iterative refinement — build on it with additions and corrections; do not start over from scratch)】\n");
            sb.append(previousPersona.trim()).append("\n\n");
            sb.append("Taking the new conversation content into account, output the **updated** complete four-layer structure (keep the parts that still hold, correct or add new findings).\n\n");
        }
        sb.append("【Conversation Records to Analyze】").append(privateCount + groupCount).append(" records in total");
        sb.append(" (private chats: ").append(privateCount).append(", group chats: ").append(groupCount).append("):\n");
        return sb.toString();
    }

    public static String buildCelebrityDistillPrompt(String charName, String sourceText) {
        StringBuilder sb = new StringBuilder();
        sb.append("Your task is 'persona thinking distillation': distill the raw retrieved material below into a complete persona archive that can drive an AI to impersonate this person.\n\n");
        sb.append("Distillation subject: ").append(charName).append("\n\n");
        sb.append("【Most Important Principles】\n");
        sb.append("1. Rely only on the retrieved material below; do not supplement from your own memory. Anything not in the material must be marked 'insufficient material' — never fabricate;\n");
        sb.append("2. Every quotation, saying, specific number, date, book title or company name must come from the material's original text, with the source cited (source title or link);\n");
        sb.append("3. Distinguish his own direct words from others' paraphrase — mark the former as 【原话】(verbatim) and the latter as 【转述】(reported).\n\n");
        sb.append("【Output Structure】Output strictly in the following eight layers; the content must be substantial (total length: 3000-6000 Chinese characters or equivalent depth in English — prefer longer over empty):\n\n");
        sb.append("1. Persona Sketch (人物速写): who he is, key milestones of his life (with years and events), main achievements and identities\n");
        sb.append("2. Expression DNA (表达 DNA): how he speaks (sentence patterns, rhythm, catchphrases, commonly used metaphors, words he loves and words he would never use); give 8-15 genuine quotes with sources\n");
        sb.append("3. Mental Models (心智模型): 4-8 core frameworks, each spelled out as 'what it is + how he uses it + which experience or book it comes from'\n");
        sb.append("4. Decision Heuristics (决策启发式): 10-15 if-then rules, ordered by priority, each tied to a real case whenever possible\n");
        sb.append("5. Classic Decision Cases (经典决策案例): 5-8 cases (background → his judgment → what he said → outcome → retrospective review)\n");
        sb.append("6. Values & Defenses (价值观与防御): what he values most, what he despises most, his typical reaction when challenged, what topics make him impassioned or fall silent\n");
        sb.append("7. Blind Spots & Controversies (盲区与争议): mistakes he has admitted, the main external criticisms, in which domains his judgment does not apply\n");
        sb.append("8. Impersonation Guide (扮演指南): how to calibrate the tone, how to respond to questions he does not understand, how to avoid playing an empty shell\n\n");
        sb.append("Finally, append a paragraph of citation discipline: when impersonating, never fabricate statements this person did not make; any specific facts must be verified via web search.\n\n");
        sb.append("【Retrieved Raw Material】\n");
        sb.append(sourceText == null ? "" : sourceText.trim());
        return sb.toString();
    }

    public static String[] celebritySearchQueries(String charName) {
        String n = charName.replace("式思维", "").trim();
        return new String[]{
            n + " 语录 名言",
            n + " 思维模型 决策案例",
            n + " 生平 关键决策",
        };
    }

    public static final String SELF_PERSONA_NAME = "我的分身";
    public static final String SELF_PERSONA_AVATAR = "🪞";
}
