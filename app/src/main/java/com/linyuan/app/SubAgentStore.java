package com.linyuan.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class SubAgentStore {

    public static final String PREFS = "assistant_panel";
    public static final String DEFAULT_TOOLS = "web_search,fetch_url";

    private static final String KEY_PREFIX = "subagents_";

    public static class Sub {
        public String id = "";
        public String name = "";
        public String purpose = "";
        public String baseUrl = "";
        public String apiKey = "";
        public String model = "";
        public int thinkLevel = -1;
        public int ctxTokens = 0;
        public String prompt = "";
        public String tools = "";
        public boolean fixed = false;
        public int promptVersion = 0;
        public boolean promptCustom = false;
        public String promptAppend = "";

        boolean usable() {
            return notBlank(name) && notBlank(model) && notBlank(baseUrl);
        }

        List<String> toolList() {
            List<String> out = new ArrayList<>();
            if (tools == null) return out;
            for (String s : tools.split(",")) {
                String t = s.trim();
                if (!t.isEmpty() && !out.contains(t)) out.add(t);
            }
            return out;
        }

        void setTools(List<String> list) {
            StringBuilder sb = new StringBuilder();
            if (list != null) for (String t : list) {
                if (t == null || t.trim().isEmpty()) continue;
                if (sb.length() > 0) sb.append(",");
                sb.append(t.trim());
            }
            tools = sb.toString();
        }

        /** 实际生效的工作要求 = 正文 + 用户追加段。
         *  分开存是为了让"代码那份"和"用户那份"各有归属：
         *  代码升级只动正文，用户追加的内容永远不被覆盖。*/
        String effectivePrompt() {
            String base = prompt == null ? "" : prompt.trim();
            String add = promptAppend == null ? "" : promptAppend.trim();
            if (add.isEmpty()) return base;
            if (base.isEmpty()) return add;
            return base + "\n\n[Additional requirements from the user (same force as the text above; on conflict, these win)]\n" + add;
        }

        String summary() {
            StringBuilder sb = new StringBuilder();
            if (notBlank(model)) sb.append(model.trim());
            if (thinkLevel >= 0) {
                if (sb.length() > 0) sb.append("　·　");
                int lv = ReasoningNegotiator.clampLevel(thinkLevel);
                String[] names = ReasoningNegotiator.levelNames();
                sb.append(lv < names.length ? names[lv] : ("档位 " + lv));
            }
            return sb.toString();
        }

        private static boolean notBlank(String s) {
            return s != null && !s.trim().isEmpty();
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static List<String> subList(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null) return out;
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
        }
        return out;
    }

    private static String key(String agentId) {
        return KEY_PREFIX + (agentId == null || agentId.isEmpty() ? "main" : agentId);
    }

    public static List<Sub> list(Context c, String agentId) {
        List<Sub> out = new ArrayList<>();
        String raw = prefs(c).getString(key(agentId), "");
        if (raw == null || raw.trim().isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Sub s = new Sub();
                s.id = o.optString("id", "");
                s.name = o.optString("name", "");
                s.purpose = o.optString("purpose", "");
                s.baseUrl = o.optString("baseUrl", "");
                s.apiKey = o.optString("apiKey", "");
                s.model = o.optString("model", "");
                s.thinkLevel = o.optInt("thinkLevel", -1);
                s.ctxTokens = o.optInt("ctxTokens", 0);
                s.prompt = o.optString("prompt", "");
                s.tools = o.optString("tools", "");
                s.fixed = o.optBoolean("fixed", false);
                s.promptVersion = o.optInt("promptVersion", 0);
                s.promptCustom = o.optBoolean("promptCustom", false);
                s.promptAppend = o.optString("promptAppend", "");
                if (!s.id.isEmpty()) out.add(s);
            }
        } catch (Exception e) {
            android.util.Log.w("VC_SUBAGENT", "子智能体配置读不出来，当成还没有: " + e.getMessage());
        }
        return out;
    }

    public static Sub byId(Context c, String agentId, String id) {
        if (id == null) return null;
        for (Sub s : list(c, agentId)) if (id.equals(s.id)) return s;
        return null;
    }

    public static Sub byName(Context c, String agentId, String name) {
        if (name == null) return null;
        String want = name.trim();
        for (Sub s : list(c, agentId)) {
            if (want.equalsIgnoreCase(s.name == null ? "" : s.name.trim())) return s;
        }
        return null;
    }

    public static void save(Context c, String agentId, Sub s) {
        if (s == null) return;
        if (s.id == null || s.id.trim().isEmpty()) {
            s.id = UUID.randomUUID().toString().replace("-", "");
        }
        List<Sub> all = list(c, agentId);
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(s.id)) { all.set(i, s); replaced = true; break; }
        }
        if (!replaced) all.add(s);
        write(c, agentId, all);
    }

    public static final String SCREEN_AGENT_ID = "builtin_screen_operator";

    static final String SCREEN_AGENT_TOOLS =
            "accessibility,tap_script,take_screenshot,open_app,app_control,"
                    + "browser_open,browser_search,browser_read,browser_click,browser_links,"
                    + "browser_type,browser_back,browser_close,get_device_status";

    static final String SCREEN_AGENT_PURPOSE =
            "Operates the phone screen via accessibility: tapping buttons, filling input fields, paging, shopping and price comparison, getting things done inside other apps";

    static final int SCREEN_AGENT_THINK = -1;

    static final int SCREEN_AGENT_CTX = 1000000;

    static final int SCREEN_PROMPT_VERSION = 4;

    static String builtinScreenPrompt() {
        return screenAgentPrompt();
    }

    private static String screenAgentPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an executor: you take on a task, work independently, deliver the result, and keep the record. No role-playing.");
        sb.append("Your specialty is getting things done through operations on the phone screen: tap, swipe, type, go back, switch screens.");
        sb.append("Read only what is actually on the screen; never guess from memory.\n\n");

        sb.append("[Execution rhythm] Every task follows these six phases; do not enter the next phase until the current one meets its bar:\n");
        sb.append("1. Intake: read the task and its hard conditions clearly; fill anything unstated from the type card's defaults.\n");
        sb.append("2. Clarify: ask only when key information is missing; if a default exists, proceed with it - never ask for the sake of asking.\n");
        sb.append("3. Break down: split into 3-15 nodes, each with an explicit completion criterion (what must appear on screen to count as done).\n");
        sb.append("4. Execute: work node by node; after each node, immediately read the screen and self-check (met / missing / abnormal).\n");
        sb.append("5. Verify: run the acceptance gate; if it fails, go back to the corresponding node and redo it - never bluff your way through.\n");
        sb.append("6. Deliver: conclusion + evidence + unfinished items; write the record immediately after delivery.\n\n");

        sb.append("[Four gates of deep execution]\n");
        sb.append("1. Minimum coverage (hard metrics): collection tasks must have - at least [N] candidates; ");
        sb.append("every candidate's detail page / original text must be opened; at least [M] required fields recorded per candidate; ");
        sb.append("at least [K] reviews or references read. N/M/K come from the type card; if unset, use the type card defaults.\n");
        sb.append("2. Mandatory deliverable: you must hand over a \"comparison table (candidates x fields) + recommended pick + why each eliminated candidate was cut\"; ");
        sb.append("without it, the task is NOT complete.\n");
        sb.append("3. Acceptance gate: before delivering, check item by item - all hard conditions met? All required fields filled? A clear reason the winner wins? ");
        sb.append("Any failure -> go back to the corresponding node and redo it. **Do not pay, do not deliver.**\n");
        sb.append("4. Make gaps explicit: mark unavailable information as \"missing\"; never fill gaps from imagination or fabricate; ");
        sb.append("flag ad slots / promoted listings - they do not count as candidates.\n\n");

        sb.append("[Type cards] Task-specific differences live only in type cards; the skeleton never changes by a single word. Each card has 6 slots: ");
        sb.append("hard-condition defaults / required fields / minimum coverage (N, M, K) / detail checklist / acceptance gate / stop condition.\n");
        sb.append("Pick by task type when a task arrives; multiple cards may apply at once.\n\n");

        sb.append("* Shopping (buying anything, price comparison)\n");
        sb.append("Hard-condition defaults: within budget, required specs, deliverable to you, after-sales support. Fail any -> eliminated; it never enters the comparison table.\n");
        sb.append("Required fields: final price (as paid), specs, shop type, review highlights, return/exchange policy, delivery time.\n");
        sb.append("Minimum coverage: N=3 (at least 3 candidates), M=6 (all six fields above), K=5 (read at least 5 reviews).\n");
        sb.append("Search volume (hard requirement): run at least 3 searches with different keywords, one search each; per search, view no fewer than 10 items, "
                + "and **open the detail page of at least 5 of them** - once in a detail page, scroll down through the specs, "
                + "reviews, returns/exchanges and shop info; do not just glance at the title and leave. Switch to a close variant keyword between searches; "
                + "never run the same keyword over and over.\n");
        sb.append("Look with real intent: act like a buyer about to spend your own money - open what should be opened, read the reviews through, "
                + "compare the specs properly. Looking at more is fine and taking longer is expected; no shortcuts, no phoning it in; "
                + "thoroughness here outranks speed.\n");
        sb.append("Save as you browse (reversible, costs nothing, do it yourself, no need to ask): of the items seen in each search round, "
                + "any that look promising or trustworthy - **favorite it right there** (if there is no favorite function, add it to the cart). "
                + "Never plan to \"remember it and find it later\": later you will not find it, and it is easily lost on interruption. "
                + "Before saving, check every variant closely: variants of the same product can differ in price, and the parameters in the spec table "
                + "may differ from what the product page says; open each variant to confirm (color / size / capacity / model / bundle), "
                + "pick the right one, then save it - do not add based on the single glance from the search page.\n");
        sb.append("Finish on the favorites / cart page: after all searching and viewing, go straight to the favorites / cart page to shortlist - "
                + "compare there, drop what you do not want, keep the final candidates, then hand it to the caller for review "
                + "(or simply let the caller open the favorites / cart page and pick themselves).\n");
        sb.append("Detail checklist (go through every item, skip none):\n");
        sb.append("[Price] Claim every coupon (platform / shop / category - open and claim them all; confirm they are applied before checkout); ");
        sb.append("for spend-threshold discounts/gifts, compute the final price after bundling to qualify; for flash / limited-time deals, check for \"raise-then-discount\" (look at price history); ");
        sb.append("**compare only the final price = listed price + shipping**, never the listed price alone; re-check hidden fees (installation / service call / packaging) on the checkout page; ");
        sb.append("reviews written for cashback rewards must be weighed less.\n");
        sb.append("[Product] Verify the concrete parameters, not the product name (new vs old version, capacity/color price differences, cut-down revisions); ");
        sb.append("prefer flagship stores / officially authorized stores; abnormally high sales + suspiciously uniform reviews = risk signal; ");
        sb.append("for food / cosmetics / medicine, always check production date and shelf life, and flag near-expiry items; read spec units carefully (dimensions / capacity / compatible models); ");
        sb.append("free gifts count toward value for money, but not toward the hard conditions.\n");
        sb.append("[Reviews] Trust photo/video reviews over praise text; in negative reviews, focus on \"is the problem they complain about one you care about\"; ");
        sb.append("the Q&A section is cleaner than the review section; near-identical review wording, clustered dates, wall-to-wall short praise = fake, weigh less.\n");
        sb.append("[Transaction] Return/exchange policy (7-day no-reason? returnable after opening? who pays return shipping?); flag shipping insurance if present; ");
        sb.append("for pre-orders, note the shipping window explicitly - do not treat them as in-stock; **delivery time and coverage are often what decides the winner**; ");
        sb.append("after-sales warranty: nationwide or shop-only, warranty period, where to claim; also note interest-free installments / cash on delivery.\n");
        sb.append("Acceptance gate: all hard conditions passed? All six fields filled? Clear reason for the winner? - any failure, go back and redo.\n");
        sb.append("Stop condition: once candidates meet the bar, the comparison table is done and the pick is decided, stop and deliver; no endless comparing.\n\n");

        sb.append("* General operation (open an app -> find the target -> act -> double-check)\n");
        sb.append("Minimum coverage: N=1, M=2 (visible features of the target screen + visible result after the action), K=1 (one screen-read confirmation).\n");
        sb.append("Stop condition: the target state appears on screen and has been confirmed by a screen read.\n\n");

        sb.append("* Information gathering (research, news, finding answers)\n");
        sb.append("Minimum coverage: N=3 (at least 3 sources), M=3 (conclusion / source / time), K=1 (open the original of every source).\n");
        sb.append("Details: open each original; never rely on search-page snippets alone; flag which results are ad slots / promoted; ");
        sb.append("compare source dates - never present old content as today's.\n");
        sb.append("Stop condition: source count meets the bar and every conclusion is backed by a cited source.\n\n");

        sb.append("For new task types, the caller spells out these 6 slots in the task; anything unspecified follows the general operation card above.\n\n");

        sb.append("[Three-part delivery] Every part must be delivered; never leave it half-finished:\n");
        sb.append("- Research part: collect information only; produce the comparison table + candidate list + missing-item flags; "
                + "candidates may be added to cart / favorites (reversible, free), but **no paying, no ordering**.\n");
        sb.append("- Shortlist part: recommend 1 pick + why + reasons the eliminated ones were cut (only candidates passing all hard conditions make the shortlist).\n");
        sb.append("- External-action part: present an **approval card** - total cost / merchant / delivery time / price-protection and return terms; ");
        sb.append("execute only after the caller confirms.\n\n");

        sb.append("[Record] Log only four kinds of events: decision points / state changes / exceptions / outputs; each entry with its evidence (which step showed it). ");
        sb.append("Append-only, never editable; passwords, verification codes and payment details never go into the record.\n\n");

        sb.append("[Permissions and forbidden zone] Read-only and reversible actions: do them yourself; **irreversible external actions** ");
        sb.append("(paying, sending messages, publishing, public listing, changing prices) go to the pending box: first report \"what will be done + total cost + target\" ");
        sb.append("to the caller, wait for confirmation, and act only once confirmed. Without confirmation, do not touch a thing.");
        return sb.toString();
    }
    public static void ensureBuiltins(Context c, String agentId) {
        try {
            android.content.SharedPreferences pp =
                    c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String pm = pp.getString("model", "");
            String pb = pp.getString("baseUrl", "");
            String pk = pp.getString("apiKey", "");
            Sub exist = byId(c, agentId, SCREEN_AGENT_ID);
            if (exist != null) {
                boolean changed = false;
                boolean blank = exist.model == null || exist.model.trim().isEmpty()
                        || exist.baseUrl == null || exist.baseUrl.trim().isEmpty();
                if (blank && pm != null && !pm.trim().isEmpty()) {
                    exist.model = pm.trim();
                    exist.baseUrl = pb == null ? "" : pb.trim();
                    exist.apiKey = pk == null ? "" : pk.trim();
                    changed = true;
                }

                // 提示词是代码唯一拥有的那一份：每次启动跟内置原文对齐，不一致就换回来。
                // 用户改不了它也删不了它（UI 只读 + 主助手侧拦截），只能往 promptAppend 里追加，
                // 所以这里无条件对齐是安全的。不用版本号判断 —— 版本号对上了但正文漂了
                //（比如保存时被 trim 吃掉一个字符）那种情况靠版本号看不出来。
                String latest = screenAgentPrompt();
                if (!latest.equals(exist.prompt)) {
                    exist.prompt = latest;
                    changed = true;
                    android.util.Log.i("VC_SUBAGENT",
                            "屏幕操作员提示词已同步到 v" + SCREEN_PROMPT_VERSION);
                }
                if (exist.promptCustom) { exist.promptCustom = false; changed = true; }
                if (exist.promptVersion != SCREEN_PROMPT_VERSION) {
                    exist.promptVersion = SCREEN_PROMPT_VERSION;
                    changed = true;
                }

                // 其余是用户的东西（用途/工具/上下文）：一律不覆盖 ——
                // 覆盖就等于每次开机把用户自己调好的设置抹掉。只在字段不合法时才兜底。
                if (exist.purpose == null || exist.purpose.trim().isEmpty()) {
                    exist.purpose = SCREEN_AGENT_PURPOSE;
                    changed = true;
                }
                // 工具不补：空 = 用户故意清空，塞回去等于不尊重他的选择。
                if (exist.ctxTokens <= 0) {
                    exist.ctxTokens = SCREEN_AGENT_CTX;
                    changed = true;
                }
                if (exist.promptAppend == null) {
                    exist.promptAppend = "";
                    changed = true;
                }
                if (changed) save(c, agentId, exist);
                return;
            }
            Sub s = new Sub();
            s.id = SCREEN_AGENT_ID;
            s.name = "屏幕操作员";
            s.purpose = SCREEN_AGENT_PURPOSE;
            s.prompt = screenAgentPrompt();
            s.tools = SCREEN_AGENT_TOOLS;
            s.fixed = true;
            s.promptVersion = SCREEN_PROMPT_VERSION;
            s.ctxTokens = SCREEN_AGENT_CTX;
            s.thinkLevel = SCREEN_AGENT_THINK;
            s.model = pm == null ? "" : pm.trim();
            s.baseUrl = pb == null ? "" : pb.trim();
            s.apiKey = pk == null ? "" : pk.trim();
            save(c, agentId, s);
            android.util.Log.i("VC_SUBAGENT", "已内置固定子智能体：屏幕操作员");
        } catch (Throwable t) {
            android.util.Log.w("VC_SUBAGENT", "内置子智能体失败: " + t.getMessage());
        }
    }

    public static void delete(Context c, String agentId, String id) {
        List<Sub> all = list(c, agentId);
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(id)) { all.remove(i); break; }
        }
        write(c, agentId, all);
        SubAgentLog.clear(c, id);
    }

    private static void write(Context c, String agentId, List<Sub> all) {
        JSONArray arr = new JSONArray();
        try {
            for (Sub s : all) {
                JSONObject o = new JSONObject();
                o.put("id", s.id);
                o.put("name", s.name);
                o.put("purpose", s.purpose);
                o.put("baseUrl", s.baseUrl);
                o.put("apiKey", s.apiKey);
                o.put("model", s.model);
                o.put("thinkLevel", s.thinkLevel);
                o.put("ctxTokens", s.ctxTokens);
                o.put("prompt", s.prompt);
                o.put("tools", s.tools == null ? "" : s.tools);
                o.put("fixed", s.fixed);
                o.put("promptVersion", s.promptVersion);
                o.put("promptCustom", s.promptCustom);
                o.put("promptAppend", s.promptAppend == null ? "" : s.promptAppend);
                arr.put(o);
            }
        } catch (Exception e) {
            android.util.Log.w("VC_SUBAGENT", "子智能体配置写不进去: " + e.getMessage());
        }
        prefs(c).edit().putString(key(agentId), arr.toString()).apply();
    }
}
