package com.linyuan.app;

public class DevRoleLibrary {

    public static final int PHASE_CORE = 0;
    public static final int PHASE_DESIGN = 1;
    public static final int PHASE_FINAL = 2;

    public static final String[][] DEV_ROLES = {

        {"项目统筹专家", "🎛️",
         "把各角色意见理成一条路 · 归并重复 · 挑出真冲突并给取舍 · 排分步优先级 · 点出没人管的缺口",
         "project-orchestrator", "1", "0"},
        {"需求分析师", "🧭",
         "需求澄清 · 用户故事 · 范围与优先级 · 验收标准",
         "product-manager", "1", "0"},
        {"发散思维专家", "🔭",
         "别盯死一个方向 · 重新定义问题 · 摊开平行方案 · 挖没说破的假设 · 想到相邻可能",
         "divergent-thinker", "1", "0"},
        {"创意专家", "💫",
         "独特功能与巧思 · 前端交互/按键/微动效的点睛 · 点子按核心/锦上添花/以后再说分级 · 不拖垮最小可用版",
         "creative-designer", "1", "0"},
        {"技术调研员", "🛰️",
         "技术选型调研 · 竞品与方案对比 · 联网核实最新资料",
         "trend-researcher", "1", "0"},
        {"开源调研员", "🐙",
         "同类项目横向普查(不死盯一个) · 拼装现成优先于造轮子 · 许可证与商用限制 · 维护活跃度与二次开发成本",
         "oss-scout", "1", "0"},
        {"系统架构师", "🏗️",
         "整体架构 · 技术栈选型 · 模块划分 · 扩展性",
         "software-architect", "1", "0"},
        {"后端架构师", "⚙️",
         "后端方案与语言 · 接口设计 · 数据模型 · 功能链路合理性",
         "backend-architect", "1", "0"},
        {"前端开发", "🖥️",
         "前端技术方案 · 界面结构与布局 · 组件划分 · 交互实现",
         "frontend-developer", "1", "0"},
        {"可行性分析师", "🧩",
         "现实校验 · 泼冷水 · 成本与工期是否靠谱 · 逻辑是否自洽",
         "reality-checker", "0", "0"},
        {"逻辑架构师", "♟️",
         "业务逻辑是否自洽 · 规则有无矛盾/遗漏 · 状态机闭合 · 推理谬误",
         "logic-architect", "1", "0"},
        {"数据架构师", "🗃️",
         "数据模型与字段 · 唯一约束与索引 · 软删与关联 · 迁移与备份",
         "data-architect", "1", "0"},
        {"提示词工程师", "🪄",
         "AI 功能设计 · 提示词与输出格式 · 上下文管理 · 幻觉防护 · 失败重试与降级",
         "prompt-engineer", "1", "0"},
        {"容量与并发规划师", "🌊",
         "先算有多少人用再决定要不要上 · 拦住过度设计 · 真正的瓶颈在哪 · 缓存与限流 · 扛不住时怎么表现",
         "capacity-planner", "1", "0"},

        {"UI设计师", "🎨",
         "界面视觉 · 配色与对比度 · 布局间距与栅格 · 组件状态与尺寸",
         "ui-designer", "1", "1"},
        {"交互架构师", "🛤",
         "交互流程 · 页面跳转与层级返回 · 转场方式 · 状态与反馈",
         "ux-architect", "1", "1"},
        {"用户研究员", "👤",
         "用户视角 · 使用场景 · 认知负担 · 人因与易用性",
         "ux-researcher", "1", "1"},
        {"防呆设计师", "🛡️",
         "防傻瓜/防误操作 · 二次确认与撤销 · 输入校验 · 草稿不丢 · 报错说人话",
         "foolproof-designer", "1", "1"},
        {"破坏性测试员", "💣",
         "专门把东西弄坏 · 极端输入 · 连点与中断 · 弱网断网 · 绕过规则",
         "chaos-tester", "1", "1"},

        {"阈值与容量守护者", "📏",
         "枚举系统全部参数阈值（上下文/工具/输出/重试/调度）· 查行业同类设计与上游真实限制 · "
                 + "区分「看一眼」与「进记忆」的阈值 · 保地基可截枝叶 · 拿不准的提给用户拍板",
         "threshold-guardian", "1", "1"},
        {"支付订单专家", "💳",
         "支付链路与回调 · 幂等防重复扣款 · 订单状态机 · 金额与优惠计算 · 对账与退款",
         "payment-expert", "1", "1"},
        {"增长数据分析师", "📉",
         "北极星指标 · 指标口径 · 埋点清单 · 漏斗与留存 · A/B 验证",
         "growth-analyst", "1", "1"},
        {"人性行为设计师", "🧲",
         "真实的人愿不愿意用 · 动机与阻力 · 新手第一分钟 · 怎么让人回来 · 不做成瘾与暗黑模式",
         "human-factor-designer", "1", "1"},
        {"环境适配工程师", "🧷",
         "换设备/系统版本还能不能跑 · 屏幕与字体 · 权限与杀后台 · 弱网断网 · 渠道审核",
         "env-compat-engineer", "1", "1"},
        {"代码审查员", "🔎",
         "代码逻辑 · 边界与异常 · 可维护性 · 反模式",
         "code-reviewer", "1", "1"},
        {"接口测试员", "🧪",
         "接口契约 · 参数与错误码 · 边界用例 · 联调问题",
         "api-tester", "1", "1"},
        {"性能测试员", "⚡",
         "性能基准 · 响应时间 · 并发与资源占用 · 瓶颈定位",
         "performance-benchmarker", "0", "1"},
        {"无障碍审计员", "♿",
         "可访问性 · 对比度 · 字号与触达区域 · 无障碍适配",
         "accessibility-auditor", "0", "1"},
        {"小程序开发", "💠",
         "微信小程序实现 · 平台限制 · 审核规则 · 类目能力",
         "mini-program", "1", "1"},
        {"移动端开发", "📲",
         "App 实现方案 · 平台差异 · 打包发布 · 权限与兼容",
         "mobile-builder", "1", "1"},

        {"管理后台架构师", "🗄️",
         "管理后台整体架构 · 账号与角色权限(RBAC) · 菜单与功能模块 · 内容/订单/用户管理 · 与主系统的数据链路",
         "backend-architect", "1", "1"},
        {"运营后台产品", "📈",
         "运营人员日常操作界面 · 数据看板与报表 · 内容审核与配置 · 操作日志与审计 · 批量处理效率",
         "product-manager", "1", "1"},

        {"安全工程师", "🔐",
         "服务器防护 · 注入/XSS/越权 · 密钥与数据安全 · 上线前加固",
         "security-engineer", "1", "2"},
        {"运维部署师", "🚚",
         "部署架构 · 环境与发布流程 · 监控告警 · 回滚与备份",
         "sre", "1", "2"},
        {"合规审计员", "📘",
         "合规风险 · 资质与备案 · 数据与隐私合规 · 审核要点",
         "compliance-auditor", "1", "2"},
        {"法务顾问", "📜",
         "法律风险 · 用户协议与隐私政策 · 知识产权 · 责任边界",
         "legal-compliance", "1", "2"},
        {"资质代办参谋", "🏛️",
         "小程序/App 资质清单 · ICP备案/等保 · 材料与流程 · 常见驳回点",
         "gov-digital", "1", "2"},
        {"成本核算师", "💸",
         "服务器/模型/短信/带宽月成本 · 单用户成本 · 保本点 · 防刷爆与限流",
         "cost-analyst", "1", "2"},
        {"风险定级专家", "🚦",
         "把所有人提的风险统一排序 · 高危必改/中危优化/低危后置 · 标清判级前提 · 给最小可行处置",
         "risk-triage", "1", "2"},
        {"上线推广参谋", "🇨🇳",
         "上线投放 · 冷启动渠道 · 市场本地化 · 首批用户获取",
         "china-localization", "1", "2"},
        {"技术文档师", "📝",
         "把讨论定稿写成可交给开发直接执行的实施方案（含验收标准）",
         "technical-writer", "0", "2"},

        {"构建实证思维", "🤖",
         "构建即理解 · 软件 2.0/3.0 · 九的进军（公开技术思想的方法论提炼）",
         "karpathy", "1", "1"},
        {"务实维护思维", "🐧",
         "反对过度设计 · 务实优先 · 维护者视角（开源工程共识的方法论提炼）",
         "torvalds", "1", "1"},
        {"算法严谨思维", "📐",
         "算法与严谨性 · 文学化编程 · 正确性优先（计算机科学经典方法论提炼）",
         "knuth", "0", "1"},
        {"体系结构思维", "🧮",
         "体系结构 · 抽象层次 · 计算本质（计算机体系结构经典方法论提炼）",
         "vonneumann", "0", "1"},
        {"信息量化思维", "📡",
         "信息论 · 信道与噪声 · 用数学量化解法（信息科学经典方法论提炼）",
         "shannon", "0", "1"},
        {"形式化计算思维", "🔣",
         "可计算性 · 形式化 · 从第一原理拆问题（计算理论经典方法论提炼）",
         "turing", "0", "1"},
        {"长期主义工程思维", "🌱",
         "工程与商业结合 · 计算思维 · 长期主义（科技史与工程实践方法论提炼）",
         "wujun", "1", "1"},
        {"克制产品思维", "🍃",
         "克制 · 简单即美 · 用户价值优先 · 不打扰（中文互联网产品实践方法论提炼）",
         "zhangxiaolong", "0", "1"},
    };

    private static final java.util.Set<String> ENGINEER_ASSETS = new java.util.HashSet<>(java.util.Arrays.asList(
            "karpathy", "torvalds", "knuth", "vonneumann", "shannon", "turing", "wujun", "zhangxiaolong"));

    private static final java.util.List<String> PERSONAL_ROLE_NAMES = java.util.Arrays.asList(
            "需求分析师", "发散思维专家", "创意专家", "用户研究员", "人性行为设计师",
            "技术调研员", "开源调研员", "可行性分析师",
            "系统架构师", "后端架构师", "数据架构师", "逻辑架构师", "容量与并发规划师",
            "务实维护思维", "构建实证思维", "克制产品思维",
            "提示词工程师", "前端开发", "UI设计师", "交互架构师", "防呆设计师", "环境适配工程师",
            "安全工程师", "运维部署师",
            "破坏性测试员", "代码审查员", "接口测试员", "性能测试员", "阈值与容量守护者",
            "风险定级专家", "项目统筹专家", "技术文档师");

    public static int personalRoleCount() {
        return PERSONAL_ROLE_NAMES.size();
    }

    public static java.util.List<String[]> personalRoles() {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        for (String want : PERSONAL_ROLE_NAMES) {
            for (String[] r : DEV_ROLES) {
                if (want.equals(r[0])) { out.add(r); break; }
            }
        }
        return out;
    }

    private static final java.util.Set<String> SOLO_ROLE_NAMES = new java.util.LinkedHashSet<>(java.util.Arrays.asList(
            "项目统筹专家",
            "需求分析师",
            "创意专家",
            "开源调研员",
            "可行性分析师",
            "系统架构师",
            "逻辑架构师",
            "数据架构师",
            "提示词工程师",
            "务实维护思维",
            "防呆设计师",
            "破坏性测试员",
            "环境适配工程师",
            "人性行为设计师",
            "容量与并发规划师",
            "成本核算师",
            "风险定级专家",
            "技术文档师"
    ));

    public static java.util.List<String[]> soloRoles() {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        for (String[] r : DEV_ROLES) {
            if (SOLO_ROLE_NAMES.contains(r[0])) out.add(r);
        }
        return out;
    }

    private static final java.util.Set<String> MOBILE_ROLE_NAMES = new java.util.LinkedHashSet<>(java.util.Arrays.asList(
            "项目统筹专家", "需求分析师", "发散思维专家", "创意专家", "技术调研员", "开源调研员",
            "系统架构师", "后端架构师", "前端开发", "可行性分析师", "逻辑架构师", "数据架构师", "提示词工程师",
            "UI设计师", "交互架构师", "用户研究员", "防呆设计师", "破坏性测试员", "人性行为设计师",
            "环境适配工程师", "代码审查员", "接口测试员", "无障碍审计员",
            "小程序开发", "移动端开发", "支付订单专家", "增长数据分析师",
            "安全工程师", "合规审计员", "法务顾问", "资质代办参谋", "成本核算师", "风险定级专家", "上线推广参谋",
            "技术文档师", "克制产品思维", "务实维护思维"
    ));

    private static final java.util.Set<String> PC_ROLE_NAMES = new java.util.LinkedHashSet<>(java.util.Arrays.asList(
            "项目统筹专家", "需求分析师", "发散思维专家", "创意专家", "技术调研员", "开源调研员",
            "系统架构师", "后端架构师", "前端开发", "可行性分析师", "逻辑架构师", "数据架构师", "提示词工程师",
            "容量与并发规划师", "UI设计师", "交互架构师", "用户研究员", "防呆设计师", "破坏性测试员",
            "代码审查员", "接口测试员", "性能测试员", "管理后台架构师", "运营后台产品", "支付订单专家",
            "安全工程师", "运维部署师", "合规审计员", "法务顾问", "资质代办参谋", "成本核算师", "风险定级专家",
            "技术文档师", "务实维护思维", "算法严谨思维"
    ));

    public static java.util.List<String[]> mobileRoles() {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        for (String[] r : DEV_ROLES) {
            if (MOBILE_ROLE_NAMES.contains(r[0])) out.add(r);
        }
        return out;
    }

    public static java.util.List<String[]> pcRoles() {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        for (String[] r : DEV_ROLES) {
            if (PC_ROLE_NAMES.contains(r[0])) out.add(r);
        }
        return out;
    }

    public static String phaseName(int phase) {
        switch (phase) {
            case PHASE_CORE: return "全程";
            case PHASE_DESIGN: return "中期";
            case PHASE_FINAL: return "收尾";
            default: return "全程";
        }
    }

    public static String loadRole(android.content.Context ctx, String assetName) {
        String dir = ENGINEER_ASSETS.contains(assetName) ? "engineers/" : "devroles/";
        return PersonaLibrary.loadAsset(ctx, dir + assetName + ".md");
    }

    public static String buildDevPrompt(String roleName, String phaseText, String archive) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是软件开发讨论群里的「").append(roleName).append("」，参与阶段：").append(phaseText).append("。\n");
        sb.append("下面的角色档案是你的专业设定与工作方式，请完全按它来思考和判断。\n\n");

        sb.append("【本群性质】：这是方案讨论群\n");
        sb.append("【输出规范 —— 必须遵守】\n");
        sb.append("1. 你的职责是**把设计讲清楚到别人能照着做**，不是给结论口号。");
        sb.append("设计类内容必须写到可执行的粒度，否则其他角色无法审查、无法发现你的问题。\n");
        sb.append("2. 涉及界面/交互时，必须逐条写明：\n");
        sb.append("   · 这是哪个页面/界面，从哪来（从哪个入口、点了什么进入）；\n");
        sb.append("   · 页面上有哪些元素：按钮/输入框/列表/图片，各自**功能**是什么；\n");
        sb.append("   · 每个元素的**位置**（上/中/下、左/中/右、顶部导航还是底部操作栏）、**大小**、**形状**、**颜色**；\n");
        sb.append("   · 点击每个元素之后**发生什么**（跳转到哪个界面、弹出什么、提交什么）；\n");
        sb.append("   · 页面之间**怎么切换**（左滑返回/瞬间切换/过场动画）、**返回路径**是几层、返回按钮放哪；\n");
        sb.append("   · **删除/危险操作必须有二次确认**，写明确认弹窗的文案与两个选项。\n");
        sb.append("3. 涉及技术方案时，必须写明：采用什么方案/技术/语言/框架、为什么选它（对比了什么）、");
        sb.append("**功能链路**怎么走（请求从哪来→经过什么→到哪去→返回什么）、数据存哪、异常怎么处理。\n");
        sb.append("4. **人性化与可用性**是要点，主动检查并指出：布局是否拥挤、间距是否够、");
        sb.append("文字与背景对比度是否足够（**严禁白字白底、浅灰字浅底这类看不清的组合**）、字号是否过小、");
        sb.append("点击区域是否够大、层级是否混乱、操作路径是否过长。\n");
        sb.append("5. 站在自己角色的专业角度发言，**只在有实质补充时发言**；不要为了发言而发言，不要重复别人说过的。\n");
        sb.append("6. 需要事实、数据、平台规则、价格、资质要求等外部信息时，**用联网搜索核实**，不要凭记忆编造。\n");
        sb.append("7. 指出问题时给出**可执行的修改建议**，而不是只说「这样不行」。\n\n");
        sb.append("========== 角色档案开始 ==========\n");
        sb.append(archive == null ? "" : archive.trim()).append("\n");
        sb.append("========== 角色档案结束 ==========");
        return sb.toString();
    }
}
