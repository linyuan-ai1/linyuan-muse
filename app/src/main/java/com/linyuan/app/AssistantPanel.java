package com.linyuan.app;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

public class AssistantPanel {

    private static final String TAG = "VC_ASSISTANT";

    static final String PREFS = "assistant_panel";

    private static final String AGENT_MAIN = "main";

    private static final String[][] AGENTS = {
            {AGENT_MAIN, "主助手", "面板默认助手"},
    };

    private int msgSeq = 0;

    private final MainActivity act;
    private final Dialog dlg;

    private String model = "";
    private String baseUrl = "";
    private String apiKey = "";

    private String activeAgent = AGENT_MAIN;

    private int thinkLevel = ReasoningNegotiator.LEVEL_MEDIUM;

    private final List<ChatMsg> msgs = new ArrayList<>();
    private volatile boolean busy = false;

    private volatile java.util.concurrent.atomic.AtomicBoolean stopFlag = null;

    private Button sendBtn;

    private long convId = 0;
    private AppDatabase db;
    private int bubbleWidthCache = 0;
    private long lastUserMsgIdForEdit = -1L;

    private LinearLayout msgContainer;
    private ScrollView scroll;
    private FrameLayout downLayer;
    private EditText input;
    private TextView thinkBtn;
    private TextView modelLine;
    private View inputPlusPanel;
    private Runnable refreshPlusConv;
    private Runnable refreshPlusModel;

    private static class ChatMsg {
        String role;
        String text;
        String thinking;
        String toolLog;
        String memoryLog;
        String memUse;
        String docBody;
        java.util.List<String> imgPaths;
        int seq;
        long dbId = -1L;
        long createdAt = 0L;
        String model;
        int totalTokens;
        String audioPath;
        ChatMsg(String role, String text) { this.role = role; this.text = text; }
    }

    public AssistantPanel(MainActivity act) {
        this.act = act;
        this.dlg = new Dialog(act);
        loadConfig();
        initConversation();
        buildUi();
    }

    private void initConversation() {
        try {
            db = new AppDatabase(act);
            long saved = prefs().getLong("convId", 0);
            List<AppDatabase.ConversationSummary> list = db.listAssistantConversations();
            // 存着的会话号可能是修复前留下的：那时助手的会话号会撞上主对话已占用的号，
            // 里面一条助手消息都没有。这种情况回到最近的助手会话，别给用户一个空面板。
            if (saved > 0 && !db.listMessagesInConversation(saved, true).isEmpty()) {
                convId = saved;
            } else if (!list.isEmpty()) {
                convId = list.get(0).conversationId;
                prefs().edit().putLong("convId", convId).apply();
            } else {
                convId = db.getFreshAssistantConversationId();
                prefs().edit().putLong("convId", convId).apply();
            }
            loadMessages();
        } catch (Throwable t) {
            android.util.Log.w(TAG, "载入助手对话失败: " + t);
        }
    }

    private void setConversation(long id) {
        convId = id;
        prefs().edit().putLong("convId", id).apply();
        loadMessages();
        renderMsgs();
    }

    private void loadMessages() {
        msgs.clear();
        lastUserMsgIdForEdit = -1L;
        if (db == null) return;
        List<AppDatabase.MessageItem> items = db.listMessagesInConversation(convId, true);
        for (AppDatabase.MessageItem it : items) {
            ChatMsg m = new ChatMsg(it.role, "");
            parseStored(m, it.content);
            m.seq = ++msgSeq;
            m.dbId = it.id;
            m.createdAt = it.createdAt;
            m.model = it.model;
            m.totalTokens = it.totalTokens;
            m.audioPath = it.audioPath;
            if (notBlank(m.text) || notBlank(m.thinking) || notBlank(m.toolLog)) {
                msgs.add(m);
                if ("user".equals(m.role)) lastUserMsgIdForEdit = it.id;
            }
        }
    }

    private void parseStored(ChatMsg m, String stored) {
        String s = stored == null ? "" : stored;
        int ti = s.indexOf("@@THINKING:");
        if (ti >= 0) {
            int next = s.indexOf("@@", ti + 11);
            m.thinking = (next < 0 ? s.substring(ti + 11) : s.substring(ti + 11, next)).trim();
            s = s.substring(0, ti) + (next < 0 ? "" : s.substring(next));
        }
        int oi = s.indexOf("@@TOOLS:");
        if (oi >= 0) {
            int next = s.indexOf("@@", oi + 8);
            m.toolLog = (next < 0 ? s.substring(oi + 8) : s.substring(oi + 8, next)).trim();
            s = s.substring(0, oi) + (next < 0 ? "" : s.substring(next));
        }
        int mi = s.indexOf("@@MEMORY:");
        if (mi >= 0) {
            int next = s.indexOf("@@", mi + 9);
            m.memoryLog = (next < 0 ? s.substring(mi + 9) : s.substring(mi + 9, next)).trim();
            s = s.substring(0, mi) + (next < 0 ? "" : s.substring(next));
        }
        int ui = s.indexOf("@@MEMUSE:");
        if (ui >= 0) {
            int nl = s.indexOf('\n', ui);
            int next = s.indexOf("@@", ui + 9);
            if (next < 0) next = s.length();
            if (nl >= 0 && nl < next) next = nl;
            m.memUse = s.substring(ui + 9, next).trim();
            s = s.substring(0, ui) + (next < 0 ? "" : s.substring(next));
        }
        int di = s.indexOf("@@DOC:");
        if (di >= 0) {
            int next = s.indexOf("@@", di + 6);
            m.docBody = (next < 0 ? s.substring(di + 6) : s.substring(di + 6, next)).trim();
            s = s.substring(0, di) + (next < 0 ? "" : s.substring(next));
        }
        int ii = s.indexOf("@@IMG:");
        if (ii >= 0) {
            int nl = s.indexOf('\n', ii);
            int next = s.indexOf("@@", ii + 6);
            if (next < 0) next = s.length();
            if (nl >= 0 && nl < next) next = nl;
            String p = s.substring(ii + 6, next).trim();
            if (!p.isEmpty()) {
                if (m.imgPaths == null) m.imgPaths = new ArrayList<>();
                m.imgPaths.add(p);
            }
            s = s.substring(0, ii) + (next < 0 ? "" : s.substring(next));
        }
        int ei = s.indexOf("@@ERR:");
        if (ei >= 0) {
            int nl = s.indexOf('\n', ei);
            s = s.substring(0, ei) + (nl < 0 ? "" : s.substring(nl));
        }
        m.text = s.trim();
    }

    private void persist(ChatMsg m) {
        if (db == null) return;
        try {
            String stored = m.text == null ? "" : m.text;
            if (m.docBody != null) stored += "\n@@DOC:" + m.docBody;
            if (m.imgPaths != null) {
                for (String p : m.imgPaths) stored += "\n@@IMG:" + p;
            }
            if (notBlank(m.thinking)) stored += "\n@@THINKING:" + m.thinking;
            if (notBlank(m.toolLog)) stored += "\n@@TOOLS:" + m.toolLog;
            if (notBlank(m.memoryLog)) stored += "\n@@MEMORY:" + m.memoryLog;
            if (notBlank(m.memUse)) stored += "\n@@MEMUSE:" + m.memUse;
            long id = db.insertMessage(m.role, stored, m.audioPath,
                    "assistant".equals(m.role) ? effectiveModelName() : null,
                    0, convId, true);
            if (id > 0) {
                m.dbId = id;
                if ("user".equals(m.role)) lastUserMsgIdForEdit = id;
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "助手消息入库失败: " + t);
        }
    }

    private SharedPreferences prefs() {
        return act.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void loadConfig() {
        SharedPreferences sp = prefs();
        model = sp.getString("model", "");
        baseUrl = sp.getString("baseUrl", "");
        apiKey = sp.getString("apiKey", "");
        activeAgent = sp.getString("activeAgent", AGENT_MAIN);
        if (!agentExists(activeAgent)) activeAgent = AGENT_MAIN;
        // 面板的思考档位是它自己的（输入框上方那个按钮点开就能调滑杆）；
        // 从没设过时跟随主对话，避免新面板一上来就是错的档位。
        if (sp.contains("thinkLevel")) {
            thinkLevel = ReasoningNegotiator.clampLevel(sp.getInt("thinkLevel", thinkLevel));
        } else {
            thinkLevel = ReasoningNegotiator.clampLevel(act.effectiveGlobalThinkLevel());
            saveConfig();
        }
    }

    private void saveConfig() {
        prefs().edit()
                .putString("model", model == null ? "" : model)
                .putString("baseUrl", baseUrl == null ? "" : baseUrl)
                .putString("apiKey", apiKey == null ? "" : apiKey)
                .putInt("thinkLevel", thinkLevel)
                .apply();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(act.col(R.color.bg));
        bg.setCornerRadius(act.dp(20));
        root.setBackground(bg);
        root.setPadding(act.dp(10), act.dp(12), act.dp(10), act.dp(8));

        // 顶部整块不要了：标题对用户没信息量，✕ 也不需要（手机返回键就能关）。
        // 这一整行的高度全部让给下面的对话区。

        modelLine = new TextView(act);
        modelLine.setTextColor(act.col(R.color.searchAccent));
        modelLine.setTextSize(12);
        modelLine.setPadding(act.dp(4), act.dp(2), 0, act.dp(2));
        modelLine.setVisibility(View.GONE);
        root.addView(modelLine);

        scroll = new ScrollView(act);
        msgContainer = new LinearLayout(act);
        msgContainer.setOrientation(LinearLayout.VERTICAL);
        msgContainer.setPadding(act.dp(4), act.dp(8), act.dp(4), act.dp(8));
        scroll.addView(msgContainer);
        downLayer = new FrameLayout(act);
        downLayer.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(downLayer, slp);
        bindScrollWatcher(root);

        LinearLayout thinkBar = new LinearLayout(act);
        thinkBar.setGravity(Gravity.CENTER_VERTICAL);
        thinkBar.setMinimumHeight(act.dp(48));

        // 输入框上方这个按钮：外观与主对话那个一致（模型名卡片），
        // 点开是**思考档位滑杆**（与主对话同一个弹窗）。
        // 服务商/模型的选择在加号面板里，不在这里重复（用户 2026-10-15 定）。
        thinkBtn = new TextView(act);
        thinkBtn.setTextColor(act.col(R.color.accent));
        thinkBtn.setTextSize(13);
        thinkBtn.setBackgroundResource(R.drawable.model_btn_card);
        thinkBtn.setGravity(Gravity.CENTER);
        thinkBtn.setPadding(act.dp(14), 0, act.dp(14), 0);
        thinkBtn.setOnClickListener(v -> pickThinkLevel());
        thinkBar.addView(thinkBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, act.dp(28)));
        refreshThinkLabel();

        thinkBar.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));

        root.addView(thinkBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout inputBar = new LinearLayout(act);
        inputBar.setOrientation(LinearLayout.HORIZONTAL);
        inputBar.setGravity(Gravity.CENTER_VERTICAL);
        inputBar.setBackgroundResource(R.drawable.input_bar_soft);
        inputBar.setClipToOutline(true);
        inputBar.setClipChildren(true);
        inputBar.setClipToPadding(true);
        // 高度与主对话输入条一致（用户 2026-10-15：「框不要那么大，缩小一些」）。
        // 主对话那边是 layout minHeight=46dp，这里原来是 58dp，视觉上明显更大。
        inputBar.setMinimumHeight(act.dp(46));
        inputBar.setPadding(act.dp(16), act.dp(5), act.dp(6), act.dp(5));

        input = new EditText(act);
        input.setHint("输入消息");
        input.setTextColor(act.col(R.color.text));
        input.setHintTextColor(act.col(R.color.muted));
        input.setTextSize(15);
        input.setBackgroundColor(0x00000000);
        input.setGravity(Gravity.CENTER_VERTICAL);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setMaxLines(6);
        input.setMinLines(1);
        input.setPadding(0, act.dp(6), 0, act.dp(6));
        input.setVerticalScrollBarEnabled(true);
        input.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                onSend();
                return true;
            }
            return false;
        });
        inputBar.addView(input, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void afterTextChanged(android.text.Editable e) {
                updateSendBtnMode();
                if (!input.getText().toString().trim().isEmpty()) hideAsstPlusPanel();
            }
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
        });
        input.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) hideAsstPlusPanel();
        });

        Button send = new Button(act);
        send.setText("");
        send.setTextColor(act.col(R.color.onAccent));
        send.setTextSize(14);
        send.setTypeface(null, Typeface.BOLD);
        send.setAllCaps(false);
        send.setBackgroundResource(R.drawable.ic_plus_ring);
        send.setMinWidth(0);
        send.setMinHeight(0);
        send.setPadding(0, 0, 0, 0);
        send.setOnClickListener(v -> {
            if (busy) stopGenerating();
            else if (input.getText().toString().trim().isEmpty()) toggleAsstPlusPanel();
            else onSend();
        });
        sendBtn = send;
        inputBar.addView(send, new LinearLayout.LayoutParams(act.dp(32), act.dp(32)));

        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.setMargins(0, act.dp(6), 0, act.dp(4));
        root.addView(inputBar, ilp);

        buildAsstPlusPanel(root, inputBar);

        refillInputWidth();

        dlg.setContentView(root);
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(new ColorDrawable(0));
            WindowManager.LayoutParams lp = dlg.getWindow().getAttributes();
            lp.width = act.dialogWidthPx(0.94F);
            lp.height = (int) (act.getResources().getDisplayMetrics().heightPixels * 0.88f);
            dlg.getWindow().setAttributes(lp);
        }
        refreshModelLine();
        renderMsgs();
        updateSendBtnMode();
    }

    private void refillInputWidth() {

    }

    private void showEditAndResendDialog(final ChatMsg target) {
        if (target == null) return;
        if (busy) { act.toast("助手正在思考…"); return; }

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(act.col(R.color.surface));
        bg.setCornerRadius(act.dp(20));
        bg.setStroke(act.dp(1), act.col(R.color.border));
        root.setBackground(bg);
        root.setPadding(act.dp(18), act.dp(18), act.dp(18), act.dp(14));

        TextView title = new TextView(act);
        title.setText("✏️ 编辑并重新发送");
        title.setTextColor(act.col(R.color.text));
        title.setTextSize(16);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView warn = new TextView(act);
        warn.setText("重发后，这条消息和它之后的回复会被删除。");
        warn.setTextColor(act.col(R.color.muted));
        warn.setTextSize(12);
        warn.setLineSpacing(act.dp(3), 1f);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = act.dp(8);
        root.addView(warn, wlp);

        final EditText editor = new EditText(act);
        editor.setText(target.text == null ? "" : target.text);
        editor.setSelection(editor.getText().length());
        editor.setTextColor(act.col(R.color.text));
        editor.setTextSize(14);
        editor.setBackgroundResource(R.drawable.bg_edit);
        editor.setPadding(act.dp(12), act.dp(10), act.dp(12), act.dp(10));
        editor.setMinLines(3);
        editor.setGravity(Gravity.TOP | Gravity.START);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = act.dp(12);
        root.addView(editor, elp);

        LinearLayout row = new LinearLayout(act);
        row.setGravity(Gravity.END);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = act.dp(14);
        row.setLayoutParams(rlp);

        final Dialog d = new Dialog(act);
        TextView cancel = mkSheetBtn("取消");
        cancel.setTextColor(act.col(R.color.muted));
        cancel.setBackgroundResource(R.drawable.btn_ghost);
        cancel.setOnClickListener(v -> d.dismiss());
        row.addView(cancel);

        TextView resend = mkSheetBtn("重新发送");
        resend.setTextColor(act.col(R.color.onAccent));
        resend.setBackgroundResource(R.drawable.btn_primary);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMarginStart(act.dp(8));
        row.addView(resend, slp);
        root.addView(row);

        d.setContentView(root);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(0));
            WindowManager.LayoutParams lp = d.getWindow().getAttributes();
            lp.width = act.dialogWidthPx(0.9F);
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            d.getWindow().setAttributes(lp);
            d.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        resend.setOnClickListener(v -> {
            String newText = editor.getText().toString().trim();
            if (newText.isEmpty()) { act.toast("内容不能为空"); return; }
            if (busy) { act.toast("助手正在思考…"); return; }
            d.dismiss();
            deleteFromAndResend(target, newText);
        });

        d.show();
    }

    private void deleteFromAndResend(ChatMsg target, String newText) {
        if (db == null) db = new AppDatabase(act);
        try {
            db.deleteMessagesInConversationFrom(convId, target.dbId, true);
        } catch (Throwable t) {
            act.toast("删除旧消息失败：" + t.getMessage());
            return;
        }
        loadMessages();
        renderMsgs();

        input.setText(newText);
        input.setSelection(input.getText().length());
        onSend();
    }

    private void showHistoryList() {
        final Dialog d = new Dialog(act);
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(act.col(R.color.surface));
        bg.setCornerRadius(act.dp(20));
        bg.setStroke(act.dp(1), act.col(R.color.border));
        root.setBackground(bg);
        root.setPadding(act.dp(16), act.dp(16), act.dp(16), act.dp(12));

        final LinearLayout listBox = new LinearLayout(act);
        listBox.setOrientation(LinearLayout.VERTICAL);
        MaxHeightScrollView sc = new MaxHeightScrollView(act);
        sc.setMaxHeightPx((int) (act.getResources().getDisplayMetrics().heightPixels * 0.72f)
                - act.dp(120));
        sc.addView(listBox);
        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(act);
        row.setGravity(Gravity.END);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(0, act.dp(10), 0, 0);
        row.setLayoutParams(rlp);
        TextView close = mkSheetBtn("关闭");
        close.setTextColor(act.col(R.color.muted));
        close.setBackgroundResource(R.drawable.btn_ghost);
        close.setMinimumHeight(act.dp(34));   // 不许被压扁（主对话那张历史弹窗同一处坑）
        close.setOnClickListener(v -> d.dismiss());
        row.addView(close);
        root.addView(row);

        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> buildHistoryList(listBox, refresh[0], d);
        refresh[0].run();

        d.setContentView(root);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(0));
            WindowManager.LayoutParams lp = d.getWindow().getAttributes();
            lp.width = act.dialogWidthPx(0.92F);
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            d.getWindow().setAttributes(lp);
        }
        d.show();
    }

    private void buildHistoryList(LinearLayout list, final Runnable refresh, final Dialog dlg) {
        list.removeAllViews();
        if (db == null) db = new AppDatabase(act);
        final List<AppDatabase.ConversationSummary> convs;
        try {
            convs = db.listAssistantConversations();
        } catch (Throwable t) {
            act.toast("读取历史失败：" + t.getMessage());
            return;
        }

        TextView title = new TextView(act);
        title.setText("历史对话");
        title.setTextColor(act.col(R.color.text));
        title.setTextSize(17);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(act.dp(4), 0, 0, act.dp(10));
        list.addView(title);

        if (convs.isEmpty()) {
            TextView empty = new TextView(act);
            empty.setText("还没有历史对话");
            empty.setTextColor(act.col(R.color.faint));
            empty.setTextSize(13);
            empty.setPadding(act.dp(4), act.dp(8), act.dp(4), act.dp(12));
            list.addView(empty);
            return;
        }

        java.text.SimpleDateFormat fmt =
                new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA);
        for (int i = 0; i < convs.size(); i++) {
            final AppDatabase.ConversationSummary sum = convs.get(i);
            final long cid = sum.conversationId;
            final int no = i + 1;

            String preview = sum.preview == null ? "" : sum.preview;
            int mk = preview.length();
            for (String marker : new String[]{"@@THINKING:", "@@DOC:", "@@IMG:", "@@MEMUSE:",
                    "@@TOOLS:", "@@MEMORY:", "@@UPTO:", "@@DUR:", "@@ERR:"}) {
                int mi = preview.indexOf(marker);
                if (mi >= 0 && mi < mk) mk = mi;
            }
            preview = preview.substring(0, mk).replace("\n", " ").trim();
            if (preview.isEmpty()) preview = "（图片/文档消息）";
            if (preview.length() > 40) preview = preview.substring(0, 40) + "…";
            String time = sum.firstCreatedAt > 0
                    ? fmt.format(new java.util.Date(sum.firstCreatedAt)) : "";

            boolean current = (cid == convId);
            LinearLayout card = new LinearLayout(act);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setColor(act.col(R.color.bg));
            cardBg.setCornerRadius(act.dp(16));
            cardBg.setStroke(act.dp(current ? 2 : 1),
                    act.col(current ? R.color.accent : R.color.border));
            card.setBackground(cardBg);
            card.setPadding(act.dp(12), act.dp(10), act.dp(8), act.dp(10));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.setMargins(0, 0, 0, act.dp(8));
            card.setLayoutParams(cp);

            LinearLayout body = new LinearLayout(act);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            LinearLayout headRow = new LinearLayout(act);
            headRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView head = new TextView(act);
            head.setText("对话" + no + (current ? "（当前）" : "") + "  ·  " + sum.count + " 条");
            head.setTextColor(act.col(R.color.text));
            head.setTextSize(13);
            head.setTypeface(null, Typeface.BOLD);
            headRow.addView(head);
            if (notBlank(sum.note)) {
                TextView badge = new TextView(act);
                badge.setText(sum.note.trim());
                badge.setTextColor(act.col(R.color.accent));
                badge.setTextSize(11);
                badge.setTypeface(null, Typeface.BOLD);
                GradientDrawable badgeBg = new GradientDrawable();
                badgeBg.setColor(act.col(R.color.accentSoft));
                badgeBg.setCornerRadius(act.dp(9));
                badge.setBackground(badgeBg);
                badge.setPadding(act.dp(8), act.dp(2), act.dp(8), act.dp(2));
                badge.setMaxLines(1);
                badge.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                blp.setMarginStart(act.dp(6));
                badge.setLayoutParams(blp);
                headRow.addView(badge);
            }
            body.addView(headRow);

            TextView sub = new TextView(act);
            sub.setText(time + "   " + preview);
            sub.setTextColor(act.col(R.color.muted));
            sub.setTextSize(11);
            sub.setPadding(0, act.dp(3), 0, 0);
            body.addView(sub);
            card.addView(body);
            card.setOnClickListener(v -> {
                if (busy) { act.toast("正在生成回复，稍后再切对话"); return; }
                setConversation(cid);
                act.toast("已切换到对话 " + no);
                dlg.dismiss();
            });

            Button del = mkRowBtn("删除", act.col(R.color.danger));
            del.setOnClickListener(v -> confirmDeleteConversation(sum, refresh, dlg));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dlp.setMarginStart(act.dp(6));
            card.addView(del, dlp);

            Button noteBtn = mkRowBtn("备注", act.col(R.color.accent));
            noteBtn.setOnClickListener(v -> showEditNoteDialog(sum, refresh));
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nlp.setMarginStart(act.dp(4));
            card.addView(noteBtn, nlp);

            list.addView(card);
        }
    }

    private Button mkRowBtn(String text, int color) {
        Button b = new Button(act);
        b.setText(text);
        b.setTextColor(color);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setBackgroundResource(R.drawable.btn_ghost);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setMinimumWidth(0);
        b.setMinimumHeight(0);
        b.setPadding(act.dp(10), act.dp(6), act.dp(10), act.dp(6));
        return b;
    }

    private void showEditNoteDialog(final AppDatabase.ConversationSummary s, final Runnable onSaved) {
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(act.col(R.color.surface));
        bg.setCornerRadius(act.dp(20));
        bg.setStroke(act.dp(1), act.col(R.color.border));
        root.setBackground(bg);
        root.setPadding(act.dp(18), act.dp(18), act.dp(18), act.dp(14));

        TextView title = new TextView(act);
        title.setText("✏️ 编辑备注");
        title.setTextColor(act.col(R.color.text));
        title.setTextSize(16);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        final EditText editor = new EditText(act);
        editor.setText(notBlank(s.note) ? s.note : "");
        editor.setSelection(editor.getText().length());
        editor.setTextColor(act.col(R.color.text));
        editor.setTextSize(14);
        editor.setHint("比如：买手机那次");
        editor.setHintTextColor(act.col(R.color.muted));
        editor.setBackgroundResource(R.drawable.bg_edit);
        editor.setPadding(act.dp(12), act.dp(10), act.dp(12), act.dp(10));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = act.dp(12);
        root.addView(editor, elp);

        LinearLayout row = new LinearLayout(act);
        row.setGravity(Gravity.END);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = act.dp(14);
        row.setLayoutParams(rlp);

        final Dialog d = new Dialog(act);
        TextView cancel = mkSheetBtn("取消");
        cancel.setTextColor(act.col(R.color.muted));
        cancel.setBackgroundResource(R.drawable.btn_ghost);
        cancel.setOnClickListener(v -> d.dismiss());
        row.addView(cancel);

        TextView save = mkSheetBtn("保存");
        save.setTextColor(act.col(R.color.onAccent));
        save.setBackgroundResource(R.drawable.btn_primary);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMarginStart(act.dp(8));
        row.addView(save, slp);
        root.addView(row);

        d.setContentView(root);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(0));
            WindowManager.LayoutParams lp = d.getWindow().getAttributes();
            lp.width = act.dialogWidthPx(0.9F);
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            d.getWindow().setAttributes(lp);
        }
        save.setOnClickListener(v -> {
            String note = editor.getText().toString().trim();
            try {
                db.updateConversationNote(s.conversationId, note, true);
                act.toast(note.isEmpty() ? "已清空备注" : "备注已保存");
            } catch (Throwable t) {
                act.toast("保存失败：" + t.getMessage());
            }
            d.dismiss();
            if (onSaved != null) onSaved.run();
        });
        d.show();
    }

    private void confirmDeleteConversation(final AppDatabase.ConversationSummary s,
                                           final Runnable onSaved, final Dialog dlg) {
        act.showStyledConfirm("删除对话",
                "「对话 " + s.count + " 条」和它的全部消息都会被删掉，不可恢复。",
                "删除", true, () -> {
                    try {
                        db.deleteAssistantConversation(s.conversationId);
                        act.toast("已删除");
                    } catch (Throwable t) {
                        act.toast("删除失败：" + t.getMessage());
                        return;
                    }
                    if (s.conversationId == convId) {
                        List<AppDatabase.ConversationSummary> rest = db.listAssistantConversations();
                        setConversation(rest.isEmpty()
                                ? db.getFreshAssistantConversationId()
                                : rest.get(0).conversationId);
                    }
                    if (onSaved != null) onSaved.run();
                    if (db.listAssistantConversations().isEmpty() && dlg != null) dlg.dismiss();
                });
    }

    private void pickAttachmentForPanel(int kind) {
        if (input == null) return;

        act.setAttachmentTarget(input);
        if (kind == 0) act.launchCameraForAttachment();
        else if (kind == 1) act.openGalleryForAttachment();
        else act.pickDocumentForAttachment();
    }

    /** 按钮上显示当前模型名；面板没单独设过就显示主对话在用的那个（与发送时一致）。 */
    private void refreshThinkLabel() {
        if (thinkBtn == null) return;
        String m = notBlank(model) ? model : act.currentChatModelLabel();
        thinkBtn.setText(notBlank(m) ? shortModel(m) : "选模型");
    }

    /**
     * 思考档位滑杆：与主对话同一个弹窗（用户 2026-10-15 定）。
     * 卡片里点模型名展开的清单 = 这个助手当前生效的服务商：面板自己设过就用面板的，
     * 没设过就跟随主对话；两边都从"已知服务商"里找模型。
     */
    private void pickThinkLevel() {
        final String effBase = notBlank(baseUrl) ? baseUrl.trim() : act.currentChatBaseUrl();
        final String effModel = notBlank(model) ? model.trim() : act.currentChatModelLabel();
        final MainActivity.ProviderRef ref = act.providerByBaseUrl(effBase, true, effModel);
        final MainActivity.ModelSource src = (ref == null || ref.models.isEmpty())
                ? null : new MainActivity.ModelSource() {
            @Override public java.util.List<String> models() { return ref.models; }

            @Override public String current() {
                return notBlank(model) ? model.trim() : act.currentChatModelLabel();
            }

            @Override public void pick(String m) {
                model = m;
                // 选完把服务商钉在这一个上：之前"跟随主对话"的话，
                // 主对话以后换了服务商，这个模型可能在新服务商里不存在。
                if (notBlank(ref.baseUrl)) {
                    baseUrl = ref.baseUrl;
                    apiKey = ref.apiKey == null ? "" : ref.apiKey;
                }
                saveConfig();
                refreshThinkLabel();
                if (refreshPlusModel != null) refreshPlusModel.run();
                refreshModelLine();
                act.toast("这个助手将使用：" + m);
            }
        };
        act.showThinkLevelSlider(thinkLevel,
                notBlank(model) ? shortModel(model) : act.currentChatModelLabel(),
                src,
                lv -> {
                    thinkLevel = ReasoningNegotiator.clampLevel(lv);
                    saveConfig();
                });
    }

    private void refreshModelLine() {
        if (modelLine == null) return;
        if (notBlank(model)) {
            modelLine.setVisibility(View.GONE);
        } else {
            modelLine.setVisibility(View.VISIBLE);
            modelLine.setText("先点输入框右侧的 ＋ 选一个服务商和模型");
        }
    }

    private int bubbleMaxWidthPx() {
        if (bubbleWidthCache <= 0) {
            bubbleWidthCache = (int) (act.dialogWidthPx(0.94F) * 0.85F);
        }
        return bubbleWidthCache;
    }

    private void renderMsgs() {
        msgContainer.removeAllViews();
        liveThinking = liveTools = liveMemory = liveBody = null;
        liveStatus = null;
        if (msgs.isEmpty()) {

            TextView empty = new TextView(act);
            empty.setText("还没有对话");
            empty.setTextColor(act.col(R.color.faint));
            empty.setTextSize(13);
            empty.setLineSpacing(act.dp(3), 1.2f);
            empty.setPadding(act.dp(8), act.dp(16), act.dp(8), act.dp(16));
            msgContainer.addView(empty);
            return;
        }
        for (ChatMsg m : msgs) msgContainer.addView(buildMsgView(m));
        scrollToBottom();
    }

    private boolean userTouching = false;
    private Button scrollDownBtn;

    private void scrollToBottom() {
        scroll.post(() -> {
            if (userTouching) return;
            scroll.fullScroll(View.FOCUS_DOWN);
        });
    }

    private void bindScrollWatcher(LinearLayout root) {

        scroll.setOnTouchListener((v, e) -> {
            if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) userTouching = true;
            else if (e.getAction() == android.view.MotionEvent.ACTION_UP
                    || e.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                scroll.postDelayed(() -> { userTouching = false; }, 1200);
            }
            return false;
        });

        scrollDownBtn = new Button(act);
        scrollDownBtn.setText("↓");
        scrollDownBtn.setTextColor(act.col(R.color.text));
        scrollDownBtn.setTextSize(14);
        scrollDownBtn.setAllCaps(false);
        scrollDownBtn.setBackgroundResource(R.drawable.fab_accent);
        scrollDownBtn.setMinWidth(0);
        scrollDownBtn.setMinHeight(0);
        scrollDownBtn.setPadding(0, 0, 0, 0);
        scrollDownBtn.setVisibility(View.GONE);
        scrollDownBtn.setOnClickListener(v -> {
            userTouching = false;
            scroll.fullScroll(View.FOCUS_DOWN);
            scrollDownBtn.setVisibility(View.GONE);
        });
        android.widget.FrameLayout.LayoutParams blp = new android.widget.FrameLayout.LayoutParams(
                act.dp(40), act.dp(40));
        blp.gravity = Gravity.BOTTOM | Gravity.END;
        blp.setMargins(0, 0, act.dp(10), act.dp(10));
        downLayer.addView(scrollDownBtn, blp);

        scroll.getViewTreeObserver().addOnScrollChangedListener(() -> {
            if (scrollDownBtn == null) return;
            View child = scroll.getChildAt(0);
            if (child == null) return;
            int diff = child.getBottom() - (scroll.getHeight() + scroll.getScrollY());
            scrollDownBtn.setVisibility(diff > act.dp(80) ? View.VISIBLE : View.GONE);
        });
    }

    private View buildMsgView(ChatMsg m) {
        boolean isUser = "user".equals(m.role);

        boolean live = (m == streamingMsg);

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setGravity(isUser ? Gravity.END : Gravity.START);

        MaxWidthLinearLayout bubble = new MaxWidthLinearLayout(act);
        bubble.setMaxWidthPx(bubbleMaxWidthPx());
        bubble.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setBackgroundResource(isUser ? R.drawable.bubble_user : R.drawable.bubble_ai);
        bubble.setElevation(act.dp(1));
        bubble.setPadding(act.dp(15), act.dp(11), act.dp(15), act.dp(11));

        final String tagBase = "asst" + m.seq + "_";
        final boolean hasThinking = notBlank(m.thinking);
        final boolean hasTools = notBlank(m.toolLog);
        final boolean hasMemory = notBlank(m.memoryLog);
        final String timeText = timeTextOf(m);
        final boolean hasMemUse = notBlank(m.memUse);

        LinearLayout headerInfoCard = null;
        if (!isUser && hasThinking) {
            headerInfoCard = act.buildInfoCard(timeText, tokenTextOf(m));
        }

        if (live && !isUser) {
            TextView st = new TextView(act);
            st.setText("⏳ 正在准备…");
            st.setTextColor(act.col(R.color.muted));
            st.setTextSize(11);
            st.setPadding(act.dp(2), 0, 0, act.dp(4));
            st.setTag("liveStatus");
            bubble.addView(st);
            liveStatus = st;
        }

        if (!isUser && hasThinking) {
            act.addFoldedSection(bubble, tagBase + "think", "💭 思考过程",
                    m.thinking, act.col(R.color.accentBright), 0x0F2F6BE6, true, false,
                    headerInfoCard);
            if (live) liveThinking = bubble.findViewWithTag("sectionBody_" + tagBase + "think");
        }
        if (!isUser && hasTools) {
            act.addFoldedSection(bubble, tagBase + "tools", toolSectionTitle(m.toolLog),
                    m.toolLog, act.col(R.color.searchAccent), 0x14E8791A, true, true);
            if (live) liveTools = bubble.findViewWithTag("sectionBody_" + tagBase + "tools");
        }
        if (!isUser && hasMemory) {
            act.addFoldedSection(bubble, tagBase + "mem", "🧠 记忆",
                    m.memoryLog, act.col(R.color.muted), 0x0F8A8A8A, true, false);
            if (live) liveMemory = bubble.findViewWithTag("sectionBody_" + tagBase + "mem");
        }
        if (!isUser && hasMemUse) {
            TextView mu = new TextView(act);
            mu.setText("🧠 用了 " + m.memUse + " 条记忆");
            mu.setTextColor(act.col(R.color.muted));
            mu.setTextSize(11);
            mu.setPadding(act.dp(4), act.dp(2), 0, act.dp(2));
            bubble.addView(mu);
        }

        boolean hasUpper = !isUser && (hasThinking || hasTools || hasMemory);
        if (hasUpper) {
            LinearLayout replyHead = new LinearLayout(act);
            replyHead.setOrientation(LinearLayout.HORIZONTAL);
            replyHead.setGravity(Gravity.CENTER_VERTICAL);
            replyHead.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            View bar = new View(act);
            bar.setBackgroundColor(act.col(R.color.accent));
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(act.dp(3), act.dp(14));
            barLp.setMarginEnd(act.dp(8));
            replyHead.addView(bar, barLp);
            TextView rt = new TextView(act);
            rt.setText("💬 回复");
            rt.setTextColor(act.col(R.color.accent));
            rt.setTextSize(12);
            rt.setTypeface(null, Typeface.BOLD);
            replyHead.addView(rt);
            replyHead.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            chipLp.setMarginStart(act.dp(6));
            replyHead.addView(act.buildModelChip(modelNameOf(m)), chipLp);
            LinearLayout.LayoutParams rhLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rhLp.setMargins(0, 0, 0, act.dp(4));
            replyHead.setLayoutParams(rhLp);
            bubble.addView(replyHead);
        }

        final String raw = m.text == null ? "" : m.text;

        TextView body = new TextView(act);
        body.setTextIsSelectable(!isUser);
        if (isUser) {
            body.setText(raw);
            body.setTextColor(act.col(R.color.userBubbleText));
            body.setTextSize(15);
            body.setLineSpacing(0f, 1.18f);
            bubble.addView(body);
        } else if (raw.isEmpty() && live) {

            body.setText("…");
            body.setTextColor(act.col(R.color.muted));
            body.setTextSize(15);
            body.setTag("livePlaceholder");
            bubble.addView(body);
            liveBody = body;
        } else if (raw.length() > LONG_REPLY_CHARS && !live) {

            LinearLayout head = new LinearLayout(act);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            final TextView toggle = new TextView(act);
            toggle.setText("💬 回复（" + raw.length() + " 字）  ▾");
            toggle.setTextColor(act.col(R.color.accent));
            toggle.setTextSize(12);
            toggle.setTypeface(null, Typeface.BOLD);
            head.addView(toggle);
            bubble.addView(head);

            TextView inner = new TextView(act);
            inner.setTextIsSelectable(true);
            inner.setText(MarkdownRenderer.render(raw,
                    act.col(R.color.aiBubbleText), act.col(R.color.muted),
                    act.col(R.color.accent), false));
            inner.setTextColor(act.col(R.color.aiBubbleText));
            inner.setTextSize(15);
            inner.setLineSpacing(0f, 1.18f);
            inner.setVisibility(View.GONE);
            ScrollView box = act.wrapLiveScroll(inner, 460);
            box.setVisibility(View.GONE);
            bubble.addView(box);
            toggle.setOnClickListener(v -> {
                boolean show = box.getVisibility() != View.VISIBLE;
                box.setVisibility(show ? View.VISIBLE : View.GONE);
                toggle.setText("💬 回复（" + raw.length() + " 字）  " + (show ? "▴" : "▾"));
            });
        } else {
            body.setText(MarkdownRenderer.render(raw,
                    act.col(R.color.aiBubbleText), act.col(R.color.muted),
                    act.col(R.color.accent), false));
            body.setTextColor(act.col(R.color.aiBubbleText));
            body.setTextSize(15);
            body.setLineSpacing(0f, 1.18f);
            bubble.addView(body);
        }
        if (live) liveBody = body;

        if (m.imgPaths != null && !m.imgPaths.isEmpty()) {
            for (String p : m.imgPaths) {
                final String path = p;
                java.io.File f = new java.io.File(path);
                if (!f.exists()) continue;
                android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeFile(path);
                if (bm == null) continue;
                android.widget.ImageView iv = new android.widget.ImageView(act);
                iv.setImageBitmap(bm);
                iv.setAdjustViewBounds(true);
                iv.setScaleType(android.widget.ImageView.ScaleType.FIT_START);
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                ip.topMargin = act.dp(6);
                iv.setLayoutParams(ip);
                iv.setOnClickListener(v -> act.showFullscreenImage(path));
                bubble.addView(iv);
            }
        }

        if (notBlank(m.docBody)) {
            act.addFoldedSection(bubble, tagBase + "doc", "📄 文档内容",
                    m.docBody, act.col(R.color.accent), 0x0F2F6BE6, true, true);
        }

        makeToolLogClickable(bubble, tagBase + "tools");

        if (isUser && m.dbId > 0 && m.dbId == lastUserMsgIdForEdit) {
            bubble.setOnClickListener(v -> showEditAndResendDialog(m));
        }

        row.addView(bubble);

        LinearLayout infoRow = new LinearLayout(act);
        infoRow.setOrientation(LinearLayout.HORIZONTAL);
        infoRow.setGravity(Gravity.CENTER_VERTICAL);
        infoRow.setPadding(act.dp(6), act.dp(4), act.dp(6), 0);
        if (isUser) {
            TextView time = new TextView(act);
            time.setText(timeText);
            time.setTextColor(act.fade(act.col(R.color.userBubbleText), 0xB3));
            time.setTextSize(11);
            infoRow.addView(time);
        } else if (!hasThinking && !live) {
            infoRow.addView(act.buildInfoCard(timeText, tokenTextOf(m)));
        }
        if (infoRow.getChildCount() > 0) {
            bubble.addView(infoRow);
        }

        LinearLayout wrapper = new LinearLayout(act);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.setMargins(0, 0, 0, act.dp(10));
        wrapper.setLayoutParams(wlp);
        wrapper.addView(row);
        return wrapper;
    }

    private static final int LONG_REPLY_CHARS = 1500;

    private String toolSectionTitle(String toolLog) {
        int search = 0, fetch = 0;
        if (toolLog != null) {
            String[] lines = toolLog.split("\n");
            for (String line : lines) {
                if (line.contains("web_search") || line.contains("搜索")) search++;
                if (line.contains("fetch_url") || line.contains("抓取")) fetch++;
            }
        }
        StringBuilder sb = new StringBuilder("🔧 工具调用");
        if (search > 0 || fetch > 0) {
            sb.append("（");
            if (search > 0) sb.append("🔍 搜索 ").append(search).append(" 次");
            if (search > 0 && fetch > 0) sb.append(" · ");
            if (fetch > 0) sb.append("📄 抓取 ").append(fetch).append(" 次");
            sb.append("）");
        }
        return sb.toString();
    }

    private void makeToolLogClickable(View bubble, String tag) {
        View body = bubble.findViewWithTag("sectionBody_" + tag);
        if (!(body instanceof TextView)) return;
        TextView tv = (TextView) body;
        CharSequence cs = act.buildColoredToolLog(tv.getText() == null ? "" : tv.getText().toString());
        tv.setText(cs);
        tv.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        tv.setClickable(true);
        tv.setFocusable(true);
    }

    private String timeTextOf(ChatMsg m) {
        if (m.createdAt > 0) {
            return new java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
                    .format(new java.util.Date(m.createdAt));
        }
        return nowTimeText();
    }

    private String modelNameOf(ChatMsg m) {
        return notBlank(m.model) ? m.model : effectiveModelName();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private String tokenTextOf(ChatMsg m) {
        if (m.totalTokens > 0) return m.totalTokens + " tok";
        return act.estimateTokens(m.text == null ? "" : m.text) + " tok";
    }

    private String nowTimeText() {
        return new java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
                .format(new java.util.Date());
    }

    private String effectiveModelName() {
        return notBlank(model) ? model : "未设置";
    }

    private ChatMsg streamingMsg;
    private TextView liveThinking, liveTools, liveMemory, liveBody;
    private TextView liveStatus;
    private volatile long streamStartMs = 0L;
    private volatile String streamPhase = null;
    private final android.os.Handler uiTicker =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable statusTick = new Runnable() {
        @Override public void run() {
            if (!busy) return;
            paintLiveStatus();
            uiTicker.postDelayed(this, 1000);
        }
    };

    private void paintLiveStatus() {
        TextView v = liveStatus;
        if (v == null) return;
        ChatMsg am = streamingMsg;
        String phase = streamPhase;
        String s;
        if (phase != null) {
            s = phase;
        } else if (am != null && notBlank(am.text)) {
            s = "正在生成… " + am.text.length() + " 字";
        } else if (am != null && notBlank(am.thinking)) {
            s = "正在思考…";
        } else {
            s = "正在准备…";
        }
        long sec = streamStartMs <= 0 ? 0
                : (System.currentTimeMillis() - streamStartMs) / 1000;
        if (sec > 0) s += " · " + sec + "s";
        v.setText("⏳ " + s);
    }

    private class PanelStreamListener implements AiClient.StreamListener {
        private final ChatMsg am;
        private boolean materialized = false;
        private long lastPaintMs = 0;

        PanelStreamListener(ChatMsg am) { this.am = am; }

        @Override
        public void onReasoningDelta(String delta) {
            if (delta == null || delta.isEmpty()) return;
            am.thinking = (am.thinking == null ? "" : am.thinking) + delta;
            schedulePaint();
        }

        @Override
        public void onContentDelta(String delta) {
            if (delta == null || delta.isEmpty()) return;
            am.text = (am.text == null ? "" : am.text) + delta;
            schedulePaint();
        }

        @Override
        public void onToolStart(String name, String argSummary) {
            streamPhase = "正在用 " + name + "…";
            appendTool("▶ " + name + "（" + nz(argSummary) + "）");
        }

        @Override
        public void onToolEnd(String name, String resultSummary) {
            streamPhase = null;
            appendTool("✓ " + name + " → " + nz(resultSummary));
        }

        @Override
        public void onRoundDiscarded(String reason) {
            streamPhase = reason == null || reason.trim().isEmpty()
                    ? "这一轮没拿到可用内容，正在重来…" : "正在重来：" + reason;
            act.runOnUiThread(AssistantPanel.this::paintLiveStatus);
        }

        private void appendTool(String line) {
            am.toolLog = (am.toolLog == null ? "" : am.toolLog + "\n") + line;
            schedulePaint();
        }

        private void schedulePaint() {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastPaintMs < 60) return;
            lastPaintMs = now;
            act.runOnUiThread(this::paint);
        }

        private void paint() {
            if (act.isFinishing() || act.isDestroyed()) return;
            if (!materialized) {
                materialized = true;
                renderMsgs();
                return;
            }

            if (liveThinking == null && notBlank(am.thinking)) { renderMsgs(); return; }
            if (liveTools == null && notBlank(am.toolLog)) { renderMsgs(); return; }
            if (liveMemory == null && notBlank(am.memoryLog)) { renderMsgs(); return; }
            if (notBlank(am.text) && liveBody != null
                    && "livePlaceholder".equals(liveBody.getTag())) { renderMsgs(); return; }

            if (liveThinking != null) liveThinking.setText(am.thinking == null ? "" : am.thinking);
            if (liveTools != null) liveTools.setText(am.toolLog == null ? "" : am.toolLog);
            if (liveMemory != null) liveMemory.setText(am.memoryLog == null ? "" : am.memoryLog);
            if (notBlank(am.text) && liveBody != null) liveBody.setText(am.text);
            paintLiveStatus();
            scrollToBottom();
        }
    }

    private static String nz(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() <= 40 ? t : t.substring(0, 40) + "…";
    }

    static String friendlyError(Throwable t) {
        String m = t == null || t.getMessage() == null ? "" : t.getMessage();
        String low = m.toLowerCase();
        if (low.contains("429") || low.contains("rate_limit") || low.contains("insufficient_quota")
                || low.contains("tpm") || low.contains("rpm")) {
            return "这个模型这会儿被限流了（发得太快）。等十几秒再发一次就行。\n\n"
                    + "可以点输入框右侧的 ＋ 换一个模型，那边的额度是另一份。";
        }
        if (low.contains("timeout") || low.contains("timed out")) {
            return "等超时了 —— 这个模型一直没返回。可以点输入框右侧的 ＋ 换一个快点的模型再试。";
        }
        if (low.contains("401") || low.contains("unauthorized") || low.contains("invalid api key")) {
            return "这个模型的密钥不对或过期了。点输入框右侧的 ＋ 重新选一次服务商和模型。";
        }
        if (low.contains("404") || low.contains("model_not_found")) {
            return "这个模型在服务商那边不存在了。点输入框右侧的 ＋ 换一个。";
        }
        return "出错了：" + (m.isEmpty() ? t : m);
    }

    private void onSendOrStop() {
        if (busy) {
            stopGenerating();
        } else {
            onSend();
        }
    }

    private void stopGenerating() {
        final java.util.concurrent.atomic.AtomicBoolean sf = stopFlag;
        if (sf != null) {
            sf.set(true);

            AiClient.abortGeneration(sf);
        }
        act.toast("已暂停");
    }

    private void updateSendBtnMode() {
        act.runOnUiThread(() -> {
            if (sendBtn == null) return;
            if (busy) {
                sendBtn.setText("");
                sendBtn.setRotation(0f);
                sendBtn.setBackgroundResource(R.drawable.ic_pause_ring);
                sendBtn.setContentDescription("暂停生成");
            } else if (input != null && !input.getText().toString().trim().isEmpty()) {
                sendBtn.setText("↑");
                sendBtn.setRotation(0f);
                sendBtn.setBackgroundResource(R.drawable.fab_accent);
                sendBtn.setContentDescription("发送");
            } else {
                sendBtn.setText("");
                sendBtn.setRotation(inputPlusPanel != null
                        && inputPlusPanel.getVisibility() == View.VISIBLE ? 45f : 0f);
                sendBtn.setBackgroundResource(R.drawable.ic_plus_ring);
                sendBtn.setContentDescription("更多");
            }
        });
    }

    private void onSend() {
        final String rawInput = input.getText().toString().trim();
        if (rawInput.isEmpty()) return;
        if (busy) { act.toast("助手正在思考…"); return; }
        if (!notBlank(model)) {

            act.toast("请先选一个服务商和模型");
            return;
        }

        String text = rawInput;
        String imagePath = null;
        String docBody = null;

        int di = text.indexOf("@@DOC:");
        if (di >= 0) {
            int end = text.indexOf("@@", di + 6);
            docBody = (end < 0 ? text.substring(di + 6) : text.substring(di + 6, end)).trim();
            text = (text.substring(0, di) + (end < 0 ? "" : text.substring(end))).trim();
        }
        int ii = text.indexOf("@@IMG:");
        if (ii >= 0) {
            int nl = text.indexOf('\n', ii);
            int end = text.indexOf("@@", ii + 6);
            if (end < 0) end = text.length();
            if (nl >= 0 && nl < end) end = nl;
            imagePath = text.substring(ii + 6, end).trim();
            text = (text.substring(0, ii) + (end < 0 ? "" : text.substring(end))).trim();
        }
        if (text.isEmpty()) text = imagePath != null ? "请看这张图片" : "请看这份文档";

        input.setText("");
        final ChatMsg um = new ChatMsg("user", text);
        um.seq = ++msgSeq;
        um.imgPaths = imagePath == null ? null : new ArrayList<>(java.util.Collections.singletonList(imagePath));
        um.docBody = docBody;
        msgs.add(um);
        persist(um);
        final ChatMsg am = new ChatMsg("assistant", "");
        am.seq = ++msgSeq;
        msgs.add(am);
        streamingMsg = am;
        renderMsgs();
        busy = true;
        streamStartMs = System.currentTimeMillis();
        streamPhase = null;
        uiTicker.removeCallbacks(statusTick);
        uiTicker.post(statusTick);
        stopFlag = new java.util.concurrent.atomic.AtomicBoolean(false);
        final String imgB64 = imagePath == null ? null : act.readImageAsBase64(imagePath);
        final String sendText = docBody == null ? text
                : text + "\n\n【文档内容】\n" + docBody;
        final String promptText = text;
        updateSendBtnMode();

        final List<AppDatabase.MessageItem> hist = new ArrayList<>();
        for (int i = 0; i + 2 < msgs.size(); i++) {
            ChatMsg m = msgs.get(i);
            if (m == null || !notBlank(m.text)) continue;
            hist.add(new AppDatabase.MessageItem(-1,
                    "assistant".equals(m.role) ? "assistant" : "user", m.text, null, 0));
        }

        new Thread(() -> {
            AiClient.bindGenerationToken(stopFlag);
            final PanelStreamListener listener = new PanelStreamListener(am);
            try {
                AppSettings s = act.buildAssistantSettings(model, baseUrl, apiKey, thinkLevel);
                String sys = act.buildAssistantPrompt(promptText, rolePromptOf(activeAgent));
                sys = sys + act.buildAssistantSubAgentBlock(activeAgent);
                int maxIter = Math.max(4, Math.min(20,
                        act.getSharedPreferences("voice_companion", Context.MODE_PRIVATE)
                                .getInt("maxToolIterations", 4)));
                AssistantToolHandler handler = new AssistantToolHandler(act, activeAgent);
                JSONArray asstTools = SubAgentRunner.buildTools(act, mainGrantedTools());
                JSONArray subTools = SubAgentRunner.buildTool(
                        SubAgentStore.list(act, activeAgent));
                for (int i = 0; i < subTools.length(); i++) {
                    asstTools.put(subTools.get(i));
                }
                List<String> grantable = SubAgentRunner.grantableNames(true);
                List<String> provNames = new ArrayList<>();
                for (AsstProviderStore.Prov p : AsstProviderStore.list(act)) {
                    provNames.add(p.name + "（" + SubAgentRunner.joinText(p.modelList()) + "）");
                }
                JSONArray mgTools = SubAgentRunner.buildManageTool(grantable, provNames);
                for (int i = 0; i < mgTools.length(); i++) {
                    asstTools.put(mgTools.get(i));
                }
                AiClient.ChatResult r = AiClient.chatWithToolsStream(
                        s, sys, hist, sendText, asstTools, handler, maxIter, listener,
                        stopFlag, imgB64);
                if (r != null) {
                    if (r.content != null && !r.content.trim().isEmpty()) am.text = r.content.trim();
                    if (r.reasoning != null && !r.reasoning.trim().isEmpty()) am.thinking = r.reasoning;
                    if (r.toolLog != null && !r.toolLog.trim().isEmpty()) am.toolLog = r.toolLog;
                    am.memoryLog = handler.memoryLog();
                }
                boolean paused = stopFlag != null && stopFlag.get();
                if (paused) {
                    if (!notBlank(am.text)) am.text = "（已暂停，还没有生成内容）";
                } else {

                    if (!notBlank(am.text)) am.text = "（没有返回内容）";
                    else if (r != null && r.truncated) {
                        am.text = am.text + "\n\n（这段可能没写完——上游把输出掐了，续了 "
                                + r.continueRounds + " 次还是断的。说「继续」我再往下写。）";
                    }
                }
                persist(am);
            } catch (Throwable t) {
                if (stopFlag != null && stopFlag.get()) {

                    if (!notBlank(am.text)) am.text = "（已暂停）";
                    persist(am);
                } else {
                    am.text = friendlyError(t);
                    android.util.Log.w(TAG, "助手对话失败: " + t);
                }
            } finally {
                busy = false;
                stopFlag = null;
                streamPhase = null;
                uiTicker.removeCallbacks(statusTick);
                AiClient.clearGenerationToken();
                act.runOnUiThread(() -> {
                    if (act.isFinishing() || act.isDestroyed()) return;
                    streamingMsg = null;
                    updateSendBtnMode();
                    renderMsgs();
                });
            }
        }, "assistant-chat").start();
    }

    private boolean agentExists(String id) {
        if (id == null) return false;
        for (String[] a : AGENTS) if (a[0].equals(id)) return true;
        return false;
    }

    private String agentName(String id) {
        for (String[] a : AGENTS) if (a[0].equals(id)) return a[1];
        return AGENTS[0][1];
    }

    private String rolePromptOf(String id) {
        return prefs().getString("role_" + id, "");
    }

    private void saveRolePrompt(String id, String text) {
        prefs().edit().putString("role_" + id, text == null ? "" : text.trim()).apply();
    }

    private void buildAgentGrid(LinearLayout grid, final Runnable refresh) {
        grid.removeAllViews();
        List<View> cells = new ArrayList<>();
        for (String[] a : AGENTS) cells.add(agentCell(a, refresh));
        cells.add(subAgentsCell(refresh));
        cells.add(rolePromptCell(refresh));
        cells.add(asstProviderCell(refresh));
        cells.add(subAgentToolsCell(refresh));
        cells.add(timerCell(refresh));

        final int cols = 2;
        final int cardH = act.dp(44);
        for (int i = 0; i < cells.size(); i += cols) {
            int n = Math.min(cols, cells.size() - i);
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = 0; j < n; j++) {
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        0, cardH, 1f);
                if (j < n - 1) clp.setMarginEnd(act.dp(6));
                row.addView(cells.get(i + j), clp);
            }
            if (n < cols) {
                row.addView(new View(act), new LinearLayout.LayoutParams(
                        0, cardH, 1f));
            }
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, cardH);
            rlp.setMargins(0, 0, 0, act.dp(6));
            grid.addView(row, rlp);
        }
    }

    private View mkAgentCard(String title, String sub, boolean current, boolean action) {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cb = new GradientDrawable();
        cb.setColor(act.col(R.color.bg));
        cb.setCornerRadius(act.dp(14));
        cb.setStroke(act.dp(current ? 2 : 1),
                act.col(current ? R.color.accent : R.color.border));
        card.setBackground(cb);
        card.setPadding(act.dp(9), act.dp(6), act.dp(9), act.dp(6));
        card.setMinimumHeight(act.dp(44));

        TextView t = new TextView(act);
        t.setText(title);
        t.setTextColor(act.col(current || action ? R.color.accent : R.color.text));
        t.setTextSize(13);
        t.setTypeface(null, Typeface.BOLD);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(t);

        TextView s = new TextView(act);
        s.setText(sub);
        s.setTextColor(act.col(R.color.muted));
        s.setTextSize(10);
        s.setPadding(0, act.dp(2), 0, 0);
        s.setMaxLines(1);
        s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(s);
        return card;
    }

    private View agentCell(final String[] agent, final Runnable refresh) {
        final String id = agent[0];
        final boolean current = id.equals(activeAgent);
        String sub = current ? "当前使用" : "点一下切过去";
        if (notBlank(rolePromptOf(id))) sub = sub + " · 自定角色";
        View card = mkAgentCard("🤖 " + agent[1], sub, current, false);
        card.setOnClickListener(v -> {
            if (id.equals(activeAgent)) { act.toast("现在就是" + agent[1]); return; }
            if (busy) { act.toast("正在生成回复，稍后再换助手"); return; }
            activeAgent = id;
            prefs().edit().putString("activeAgent", id).apply();
            if (refresh != null) refresh.run();
            act.toast("已切到" + agent[1] + "，之后的消息归它");
        });
        return card;
    }

    private View subAgentsCell(final Runnable refresh) {
        int n = SubAgentStore.list(act, activeAgent).size();
        String sub = n > 0 ? ("已配 " + n + " 个 · 点一下管理") : "还没有 · 点一下创建";
        View card = mkAgentCard("🧩 子智能体", sub, false, true);
        card.setOnClickListener(v -> showSubAgentManager(activeAgent, refresh));
        return card;
    }

    private View rolePromptCell(final Runnable refresh) {
        final String rp = rolePromptOf(activeAgent);
        String sub = notBlank(rp) ? "已自定义 · 点一下改" : "沿用默认 · 点一下改";
        View card = mkAgentCard("🎭 角色提示词", sub, false, true);
        card.setOnClickListener(v ->
                showRolePromptDialog(activeAgent, agentName(activeAgent), refresh));
        return card;
    }

    private View asstProviderCell(final Runnable refresh) {
        int n = AsstProviderStore.list(act).size();
        String sub = n > 0 ? (n + " 个 · 点一下管理") : "还没有 · 点一下加";
        View card = mkAgentCard("🏢 助手服务商", sub, false, true);
        card.setOnClickListener(v -> showAsstProviderManager(refresh));
        return card;
    }

    private View subAgentToolsCell(final Runnable refresh) {
        int n = mainGrantedTools().size();
        View card = mkAgentCard("🧰 助手的工具", n + " 个 · 点一下改", false, true);
        card.setOnClickListener(v -> showMainToolPicker(refresh));
        return card;
    }

    /**
     * 「定时」卡片：主助手自己按时醒来干活。
     * 右边挂一个红色小气泡显示"没看过的失败次数"（跟微信那个一个意思）。
     */
    private View timerCell(final Runnable refresh) {
        boolean on = AssistTimerStore.enabled(act);
        List<String> times = AssistTimerStore.times(act);
        int selfN = AssistTimerStore.selfList(act).size();
        String sub;
        if (!on) {
            sub = "已关闭 · 点一下开";
        } else if (times.isEmpty() && selfN == 0) {
            sub = "已开启 · 还没设时间";
        } else {
            StringBuilder s = new StringBuilder();
            if (!times.isEmpty()) {
                s.append(times.size()).append(" 个时间点");
                String days = AssistTimerStore.daysRaw(act);
                s.append(days == null || days.trim().isEmpty() ? "(每天)" : "(每星期 " + days + ")");
            }
            if (selfN > 0) {
                if (s.length() > 0) s.append(" · ");
                s.append("它自己排了 ").append(selfN).append(" 个");
            }
            sub = s.toString();
        }

        // 卡片本体还是 mkAgentCard，气泡叠在外层 FrameLayout 的右上角
        View card = mkAgentCard("⏰ 定时", sub, false, true);
        final FrameLayout wrap = new FrameLayout(act);
        wrap.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        int unseen = AssistTimerStore.failUnseen(act);
        if (unseen > 0) {
            TextView dot = new TextView(act);
            dot.setText(unseen > 99 ? "99+" : String.valueOf(unseen));
            dot.setTextColor(act.col(R.color.onAccent));
            dot.setTextSize(9);
            dot.setTypeface(null, Typeface.BOLD);
            dot.setGravity(Gravity.CENTER);
            GradientDrawable db = new GradientDrawable();
            db.setColor(act.col(R.color.danger));
            db.setCornerRadius(act.dp(999));
            dot.setBackground(db);
            int w = act.dp(16);
            dot.setMinWidth(w);
            dot.setMinimumWidth(w);
            dot.setPadding(act.dp(4), 0, act.dp(4), 0);
            FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, w);
            dlp.gravity = Gravity.END | Gravity.TOP;
            dlp.setMargins(0, act.dp(-4), act.dp(2), 0);
            wrap.addView(dot, dlp);
        }

        wrap.setOnClickListener(v -> showTimerSheet(refresh));
        return wrap;
    }

    /**
     * 「定时」设置弹窗。
     * 层次：总开关 → 触发时间（可多个）→ 重复（每天/星期几）→ 它自己排的 → 失败记录 → 保存。
     * 全部改动先存在临时变量里，点「保存」才落盘（用户定：默认关闭，必须选好保存）。
     */
    private void showTimerSheet(final Runnable onSaved) {
        final Dialog d = new Dialog(act);
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(act.col(R.color.surface));
        bg.setCornerRadius(act.dp(18));
        bg.setStroke(act.dp(1), act.col(R.color.border));
        root.setBackground(bg);
        root.setPadding(act.dp(14), act.dp(12), act.dp(14), act.dp(10));

        TextView title = new TextView(act);
        title.setText("⏰ 定时激活");
        title.setTextColor(act.col(R.color.text));
        title.setTextSize(15);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView hint = new TextView(act);
        hint.setText("到点自动唤醒主助手，让它接着干活（不用你开口）。默认关闭。");
        hint.setTextColor(act.col(R.color.muted));
        hint.setTextSize(11);
        hint.setPadding(0, act.dp(3), 0, act.dp(2));
        root.addView(hint);

        MaxHeightScrollView sc = new MaxHeightScrollView(act);
        sc.setMaxHeightPx((int) (act.getResources().getDisplayMetrics().heightPixels * 0.52f));
        final LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        sc.addView(body, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 暂存区（保存才落盘） ----
        final boolean[] on = {AssistTimerStore.enabled(act)};
        final List<String> times = new ArrayList<>(AssistTimerStore.times(act));
        final String[] days = {AssistTimerStore.daysRaw(act) == null ? "" : AssistTimerStore.daysRaw(act)};

        // ===== 1) 总开关 =====
        LinearLayout swRow = new LinearLayout(act);
        swRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams swlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        swlp.setMargins(0, act.dp(10), 0, 0);
        swRow.setLayoutParams(swlp);
        GradientDrawable swBg = new GradientDrawable();
        swBg.setColor(act.col(R.color.bg));
        swBg.setCornerRadius(act.dp(12));
        swBg.setStroke(act.dp(1), act.col(R.color.border));
        swRow.setBackground(swBg);
        swRow.setPadding(act.dp(12), act.dp(8), act.dp(8), act.dp(8));

        TextView swLabel = new TextView(act);
        swLabel.setText("定时激活");
        swLabel.setTextColor(act.col(R.color.text));
        swLabel.setTextSize(13.5f);
        swLabel.setTypeface(null, Typeface.BOLD);
        swRow.addView(swLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final android.widget.Switch sw = new android.widget.Switch(act);
        sw.setChecked(on[0]);
        swRow.addView(sw);
        body.addView(swRow);

        // ===== 2) 触发时间：我的 + 它的，统一列表，各带标签 =====
        final LinearLayout[] timesBox = new LinearLayout[1];
        final Runnable[] refreshTimes = new Runnable[1];
        refreshTimes[0] = () -> {
            if (timesBox[0] == null) return;
            timesBox[0].removeAllViews();
            timesBox[0].addView(sectionTitle("触发时间"));

            // --- 我的（周期性）---
            TextView myHead = new TextView(act);
            myHead.setText("我设的（每天 / 每星期，重复触发 · 它动不了）");
            myHead.setTextColor(act.col(R.color.muted));
            myHead.setTextSize(10.5f);
            myHead.setPadding(act.dp(2), act.dp(2), 0, act.dp(4));
            timesBox[0].addView(myHead);

            if (times.isEmpty()) {
                TextView e = new TextView(act);
                e.setText("还没有。点下面「＋ 添加时间」加一个，比如 00:00、03:00。");
                e.setTextColor(act.col(R.color.faint));
                e.setTextSize(11.5f);
                e.setPadding(act.dp(2), 0, 0, act.dp(6));
                timesBox[0].addView(e);
            }
            java.util.Collections.sort(times);
            for (int i = 0; i < times.size(); i++) {
                final String t = times.get(i);
                LinearLayout row = new LinearLayout(act);
                row.setGravity(Gravity.CENTER_VERTICAL);
                GradientDrawable rb = new GradientDrawable();
                rb.setColor(act.col(R.color.bg));
                rb.setCornerRadius(act.dp(10));
                rb.setStroke(act.dp(1), act.col(R.color.border));
                row.setBackground(rb);
                row.setPadding(act.dp(10), act.dp(8), act.dp(8), act.dp(8));
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.setMargins(0, 0, 0, act.dp(5));
                row.setLayoutParams(rlp);

                row.addView(ownerTag("用户", false));

                TextView tv = new TextView(act);
                tv.setText("🕐 " + t);
                tv.setTextColor(act.col(R.color.text));
                tv.setTextSize(14);
                tv.setTypeface(null, Typeface.BOLD);
                tv.setPadding(act.dp(6), 0, 0, 0);
                row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView del = mkSheetBtn("删除");
                del.setTextColor(act.col(R.color.danger));
                del.setBackgroundResource(R.drawable.btn_ghost);
                del.setOnClickListener(v -> {
                    times.remove(t);
                    refreshTimes[0].run();
                });
                row.addView(del);
                timesBox[0].addView(row);
            }

            TextView add = mkSheetBtn("＋ 添加时间");
            add.setTextColor(act.col(R.color.accent));
            add.setBackgroundResource(R.drawable.btn_ghost);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            alp.setMargins(0, act.dp(2), 0, act.dp(10));
            add.setLayoutParams(alp);
            add.setOnClickListener(v -> pickTime(t -> {
                String nt = AssistTimerStore.norm(t);
                if (!times.contains(nt)) times.add(nt);
                refreshTimes[0].run();
            }));
            timesBox[0].addView(add);

            // --- 它的（一次性）---
            TextView itHead = new TextView(act);
            itHead.setText("它自己设的（只响一次 · 你可以撤，它自己也能撤）");
            itHead.setTextColor(act.col(R.color.muted));
            itHead.setTextSize(10.5f);
            itHead.setPadding(act.dp(2), 0, 0, act.dp(4));
            timesBox[0].addView(itHead);

            List<AssistTimerStore.SelfAt> selfList = AssistTimerStore.selfList(act);
            if (selfList.isEmpty()) {
                TextView e = new TextView(act);
                e.setText("暂时没有。它需要接着干活时会自己排。");
                e.setTextColor(act.col(R.color.faint));
                e.setTextSize(11.5f);
                e.setPadding(act.dp(2), 0, 0, 0);
                timesBox[0].addView(e);
            } else {
                java.text.SimpleDateFormat f =
                        new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA);
                for (AssistTimerStore.SelfAt a : selfList) {
                    LinearLayout row = new LinearLayout(act);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    GradientDrawable rb = new GradientDrawable();
                    rb.setColor(act.col(R.color.bg));
                    rb.setCornerRadius(act.dp(10));
                    rb.setStroke(act.dp(1), act.col(R.color.border));
                    row.setBackground(rb);
                    row.setPadding(act.dp(10), act.dp(8), act.dp(8), act.dp(8));
                    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    rlp.setMargins(0, 0, 0, act.dp(5));
                    row.setLayoutParams(rlp);

                    row.addView(ownerTag("助手", true));

                    LinearLayout col = new LinearLayout(act);
                    col.setOrientation(LinearLayout.VERTICAL);
                    TextView tv = new TextView(act);
                    tv.setText("⏱ " + f.format(new java.util.Date(a.at)));
                    tv.setTextColor(act.col(R.color.text));
                    tv.setTextSize(13.5f);
                    tv.setTypeface(null, Typeface.BOLD);
                    col.addView(tv);
                    if (a.note != null && !a.note.trim().isEmpty()) {
                        TextView nt = new TextView(act);
                        nt.setText(a.note.trim());
                        nt.setTextColor(act.col(R.color.faint));
                        nt.setTextSize(10.5f);
                        col.addView(nt);
                    }
                    LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    clp.setMarginStart(act.dp(6));
                    row.addView(col, clp);

                    TextView rm = mkSheetBtn("撤掉");
                    rm.setTextColor(act.col(R.color.danger));
                    rm.setBackgroundResource(R.drawable.btn_ghost);
                    rm.setOnClickListener(v -> {
                        AssistTimerStore.removeSelf(act, a.id);
                        AssistTimer.reschedule(act);
                        act.toast("已撤掉");
                        refreshTimes[0].run();
                    });
                    row.addView(rm);
                    timesBox[0].addView(row);
                }
            }
        };

        // ===== 3) 重复 =====
        final LinearLayout[] daysBox = new LinearLayout[1];
        final Runnable[] refreshDays = new Runnable[1];
        final String[][] DAY_NAMES = {{"1", "周一"}, {"2", "周二"}, {"3", "周三"}, {"4", "周四"},
                {"5", "周五"}, {"6", "周六"}, {"7", "周日"}};
        refreshDays[0] = () -> {
            if (daysBox[0] == null) return;
            daysBox[0].removeAllViews();
            daysBox[0].addView(sectionTitle("重复"));

            final boolean everyDay = days[0].trim().isEmpty();

            LinearLayout two = new LinearLayout(act);
            two.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.setMargins(0, 0, 0, act.dp(6));
            two.setLayoutParams(tlp);

            TextView bEvery = mkSheetBtn("每天");
            TextView bPick = mkSheetBtn("按星期选");
            LinearLayout.LayoutParams h1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            LinearLayout.LayoutParams h2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            h2.setMarginStart(act.dp(6));
            bEvery.setLayoutParams(h1);
            bPick.setLayoutParams(h2);
            // 选中的那个用实心（btn_primary），没选中的用描边（btn_ghost）——
            // 跟 App 里其它"二选一"的按钮同一套样式，不另造。
            bEvery.setBackgroundResource(everyDay ? R.drawable.btn_primary : R.drawable.btn_ghost);
            bEvery.setTextColor(act.col(everyDay ? R.color.onAccent : R.color.muted));
            bEvery.setTypeface(null, everyDay ? Typeface.BOLD : Typeface.NORMAL);
            bPick.setBackgroundResource(everyDay ? R.drawable.btn_ghost : R.drawable.btn_primary);
            bPick.setTextColor(act.col(everyDay ? R.color.muted : R.color.onAccent));
            bPick.setTypeface(null, everyDay ? Typeface.NORMAL : Typeface.BOLD);
            bEvery.setOnClickListener(v -> {
                days[0] = "";
                refreshDays[0].run();
            });
            bPick.setOnClickListener(v -> {
                if (days[0].trim().isEmpty()) days[0] = currentIsoDow();
                refreshDays[0].run();
            });
            two.addView(bEvery);
            two.addView(bPick);
            daysBox[0].addView(two);

            if (!everyDay) {
                final List<String> sel = new ArrayList<>();
                for (String s : days[0].split(",")) if (!s.trim().isEmpty()) sel.add(s.trim());
                LinearLayout chips = new LinearLayout(act);
                chips.setOrientation(LinearLayout.HORIZONTAL);
                for (String[] dn : DAY_NAMES) {
                    final String iso = dn[0];
                    final boolean on2 = sel.contains(iso);
                    TextView chip = new TextView(act);
                    chip.setText(dn[1]);
                    chip.setTextSize(11.5f);
                    chip.setGravity(Gravity.CENTER);
                    chip.setPadding(act.dp(6), act.dp(6), act.dp(6), act.dp(6));
                    GradientDrawable cb = new GradientDrawable();
                    cb.setColor(on2 ? act.col(R.color.accentSoft) : act.col(R.color.bg));
                    cb.setCornerRadius(act.dp(10));
                    cb.setStroke(act.dp(on2 ? 2 : 1),
                            on2 ? act.col(R.color.accent) : act.col(R.color.border));
                    chip.setBackground(cb);
                    chip.setTextColor(on2 ? act.col(R.color.accent) : act.col(R.color.text));
                    chip.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    chip.setOnClickListener(v -> {
                        if (sel.contains(iso)) sel.remove(iso);
                        else sel.add(iso);
                        java.util.Collections.sort(sel);
                        StringBuilder sb = new StringBuilder();
                        for (String s : sel) {
                            if (sb.length() > 0) sb.append(",");
                            sb.append(s);
                        }
                        days[0] = sb.toString();
                        refreshDays[0].run();
                    });
                    chips.addView(chip);
                }
                daysBox[0].addView(chips);

                TextView tip = new TextView(act);
                tip.setText("选中的星期才会触发；一个都不选就等于每天。");
                tip.setTextColor(act.col(R.color.faint));
                tip.setTextSize(10.5f);
                tip.setPadding(act.dp(2), act.dp(5), 0, 0);
                daysBox[0].addView(tip);
            }
        };

        // ===== 4) 失败记录 =====
        LinearLayout failBox = new LinearLayout(act);
        failBox.setOrientation(LinearLayout.VERTICAL);
        failBox.addView(sectionTitle("失败记录"));
        List<AssistTimerStore.FailRec> fails = AssistTimerStore.failLog(act);
        if (fails.isEmpty()) {
            TextView e = new TextView(act);
            e.setText("没有失败过。");
            e.setTextColor(act.col(R.color.faint));
            e.setTextSize(11.5f);
            e.setPadding(act.dp(2), act.dp(2), 0, 0);
            failBox.addView(e);
        } else {
            java.text.SimpleDateFormat f =
                    new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA);
            for (AssistTimerStore.FailRec fr : fails) {
                TextView tv = new TextView(act);
                tv.setText("✕ " + f.format(new java.util.Date(fr.at)) + "  " + fr.reason);
                tv.setTextColor(act.col(R.color.danger));
                tv.setTextSize(11f);
                tv.setPadding(act.dp(2), act.dp(2), 0, act.dp(2));
                failBox.addView(tv);
            }
            TextView clr = mkSheetBtn("清空失败记录");
            LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp2.setMargins(0, act.dp(4), 0, 0);
            clr.setLayoutParams(clp2);
            clr.setOnClickListener(v -> {
                AssistTimerStore.clearFailLog(act);
                if (onSaved != null) onSaved.run();
                d.dismiss();
            });
            failBox.addView(clr);
        }

        body.addView(timesBox[0] = new LinearLayout(act));
        timesBox[0].setOrientation(LinearLayout.VERTICAL);
        refreshTimes[0].run();

        body.addView(daysBox[0] = new LinearLayout(act));
        daysBox[0].setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams dlp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp2.setMargins(0, act.dp(12), 0, 0);
        daysBox[0].setLayoutParams(dlp2);
        refreshDays[0].run();

        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.setMargins(0, act.dp(12), 0, 0);
        failBox.setLayoutParams(flp);
        body.addView(failBox);

        // ===== 底部：取消 / 保存 =====
        LinearLayout btnRow = new LinearLayout(act);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(0, act.dp(12), 0, 0);
        btnRow.setLayoutParams(blp);

        TextView cancel = mkSheetBtn("取消");
        cancel.setTextColor(act.col(R.color.muted));
        cancel.setBackgroundResource(R.drawable.btn_ghost);
        cancel.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        cancel.setOnClickListener(v -> d.dismiss());

        TextView save = mkSheetBtn("保存");
        save.setTextColor(act.col(R.color.onAccent));
        save.setTypeface(null, Typeface.BOLD);
        save.setBackgroundResource(R.drawable.btn_primary);
        LinearLayout.LayoutParams slp3 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        slp3.setMarginStart(act.dp(8));
        save.setLayoutParams(slp3);
        save.setOnClickListener(v -> {
            boolean want = sw.isChecked();
            if (want && times.isEmpty()) {
                act.toast("开着就得至少有一个时间点，先加一个");
                return;
            }
            AssistTimerStore.setEnabled(act, want);
            AssistTimerStore.setTimes(act, times);
            AssistTimerStore.setDays(act, days[0]);
            AssistTimerStore.clearFailUnseen(act);
            AssistTimer.reschedule(act);
            act.toast(want ? "定时激活已保存" : "定时激活已关闭");
            if (onSaved != null) onSaved.run();
            d.dismiss();
        });
        btnRow.addView(cancel);
        btnRow.addView(save);
        root.addView(btnRow);

        d.setContentView(root);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(0));
            WindowManager.LayoutParams lp = d.getWindow().getAttributes();
            lp.width = act.dialogWidthPx(0.92F);
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            d.getWindow().setAttributes(lp);
        }
        d.show();
    }

    private TextView sectionTitle(String text) {
        TextView h = new TextView(act);
        h.setText(text);
        h.setTextColor(act.col(R.color.accent));
        h.setTextSize(11.5f);
        h.setTypeface(null, Typeface.BOLD);
        h.setPadding(act.dp(2), act.dp(10), 0, act.dp(5));
        return h;
    }

    /**
     * 归属标签：这条定时是谁建的。
     * 「用户」=你设的（重复触发，助手动不了）；「助手」=它自己排的（只响一次，可撤）。
     */
    private TextView ownerTag(String who, boolean isAssistant) {
        TextView t = new TextView(act);
        t.setText(who);
        t.setTextSize(9.5f);
        t.setTypeface(null, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(act.dp(6), act.dp(2), act.dp(6), act.dp(2));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(act.col(isAssistant ? R.color.accentSoft : R.color.surfaceInput));
        bg.setCornerRadius(act.dp(999));
        bg.setStroke(act.dp(1), act.col(isAssistant ? R.color.accent : R.color.borderStrong));
        t.setBackground(bg);
        t.setTextColor(act.col(isAssistant ? R.color.accent : R.color.muted));
        t.setSingleLine(true);
        return t;
    }

    private static String currentIsoDow() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return String.valueOf((c.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7 + 1);
    }

    /**
     * 选时间：像系统闹钟那样两个滚轮直接滑（小时 + 分钟），滑完点「确定」。
     *
     * <p>为什么不用两步列表选择器：用户明确要求"像手机系统里面的那种定时闹钟一样，
     * 直接滑动就可以了，滑动多少时间多少分钟，然后点击创建一个就可以了。
     * 把所有的时间节点做成两个窗口去选择"。
     *
     * <p>为什么不用系统 {@link android.widget.NumberPicker}：它上下两行是画在画布上的、
     * 颜色字号在私有字段里，反射改在 ColorOS 上实测"只看得见中间一行，一滑动整片空白"
     * （用户 2026-10-13 反馈）。改用自绘的 {@link WheelView}：三行全部自己画。
     *
     * <p>分钟 **00–59 自由选**（用户 2026-10-13 澄清：5 分钟是**助手自己创建**时的节流，
     * 不是选择器的档位限制）。
     */
    private void pickTime(final java.util.function.Consumer<String> onPicked) {
        pickTime(onPicked, 0, 0);
    }

    private void pickTime(final java.util.function.Consumer<String> onPicked, int initH, int initM) {
        final Dialog d = new Dialog(act);
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(act.col(R.color.surface));
        bg.setCornerRadius(act.dp(18));
        bg.setStroke(act.dp(1), act.col(R.color.border));
        root.setBackground(bg);
        root.setPadding(act.dp(16), act.dp(14), act.dp(16), act.dp(12));

        TextView title = new TextView(act);
        title.setText("选择时间");
        title.setTextColor(act.col(R.color.text));
        title.setTextSize(15);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        // ---- 两个自绘滚轮（上/中/下三行都画）----
        LinearLayout wheels = new LinearLayout(act);
        wheels.setOrientation(LinearLayout.HORIZONTAL);
        wheels.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.setMargins(0, act.dp(12), 0, act.dp(4));
        wheels.setLayoutParams(wlp);

        // 预览行：随滚轮实时更新（"当前是几点了"一眼可见）
        TextView preview = new TextView(act);
        preview.setTextSize(22);
        preview.setTypeface(null, Typeface.BOLD);
        preview.setTextColor(act.col(R.color.accent));
        preview.setGravity(Gravity.CENTER);

        final WheelView hourPick = new WheelView(act);
        final WheelView minPick = new WheelView(act);
        for (WheelView w : new WheelView[]{hourPick, minPick}) {
            w.applyColors(act.col(R.color.text), act.col(R.color.accent),
                    act.col(R.color.accentSoft), act.col(R.color.border), act.col(R.color.surface));
        }
        String[] hours = new String[24];
        for (int i = 0; i < 24; i++) hours[i] = String.format(java.util.Locale.ROOT, "%02d 时", i);
        String[] mins = new String[60];
        for (int i = 0; i < 60; i++) mins[i] = String.format(java.util.Locale.ROOT, "%02d 分", i);

        final Runnable refreshPreview = () -> preview.setText(
                String.format(java.util.Locale.ROOT, "%02d:%02d",
                        hourPick.getIndex(), minPick.getIndex()));
        hourPick.setUp(hours, Math.max(0, Math.min(23, initH)), refreshPreview);
        minPick.setUp(mins, Math.max(0, Math.min(59, initM)), refreshPreview);
        refreshPreview.run();

        hourPick.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        wheels.addView(hourPick);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mlp.setMarginStart(act.dp(10));
        minPick.setLayoutParams(mlp);
        wheels.addView(minPick);
        root.addView(wheels);

        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.setMargins(0, act.dp(6), 0, 0);
        root.addView(preview, plp);

        TextView tip = new TextView(act);
        tip.setText("上下滑动选择");
        tip.setTextColor(act.col(R.color.faint));
        tip.setTextSize(11);
        tip.setGravity(Gravity.CENTER);
        root.addView(tip);

        // ---- 底部按钮（跟 App 其它弹窗同一套样式）----
        LinearLayout row = new LinearLayout(act);
        row.setGravity(Gravity.END);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(0, act.dp(14), 0, 0);
        row.setLayoutParams(rlp);

        TextView cancel = mkSheetBtn("取消");
        cancel.setTextColor(act.col(R.color.muted));
        cancel.setBackgroundResource(R.drawable.btn_ghost);
        cancel.setOnClickListener(v -> d.dismiss());
        row.addView(cancel);

        TextView ok = mkSheetBtn("确定");
        ok.setTextColor(act.col(R.color.onAccent));
        ok.setTypeface(null, Typeface.BOLD);
        ok.setBackgroundResource(R.drawable.btn_primary);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.setMarginStart(act.dp(8));
        ok.setOnClickListener(v -> {
            int h = hourPick.getIndex();
            int m = minPick.getIndex();
            d.dismiss();
            onPicked.accept(String.format(java.util.Locale.ROOT, "%02d:%02d", h, m));
        });
        row.addView(ok, olp);
        root.addView(row);

        d.setContentView(root);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(0));
            WindowManager.LayoutParams lp = d.getWindow().getAttributes();
            lp.width = act.dialogWidthPx(0.86F);
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            d.getWindow().setAttributes(lp);
        }
        d.show();
    }

    List<String> mainGrantedTools() {
        String raw = prefs().getString("mainTools", "");
        List<String> l = SubAgentStore.subList(raw);
        if (l.isEmpty()) {
            l.addAll(defaultGrantedTools());
        } else {
            // 老用户存的是旧列表：新加的工具（比如第一三九轮的 schedule_self）
            // 不在里面，不补的话他永远拿不到。这里按"清单里新出现的一律补上"处理，
            // 用户在工具授权里**主动取消勾选**的则尊重他的选择（记在 mainToolsOff）。
            List<String> off = SubAgentStore.subList(prefs().getString("mainToolsOff", ""));
            for (String t : defaultGrantedTools()) {
                if (!l.contains(t) && !off.contains(t)) l.add(t);
            }
        }
        return l;
    }

    /** 工具授权弹窗保存时调用：把"这次没勾的"记下来，别在下次读取时又给补回来。 */
    void saveGrantedTools(List<String> sel) {
        StringBuilder sb = new StringBuilder();
        for (String t : sel) {
            if (sb.length() > 0) sb.append(",");
            sb.append(t);
        }
        StringBuilder off = new StringBuilder();
        for (String t : defaultGrantedTools()) {
            if (!sel.contains(t)) {
                if (off.length() > 0) off.append(",");
                off.append(t);
            }
        }
        prefs().edit()
                .putString("mainTools", sb.toString())
                .putString("mainToolsOff", off.toString())
                .apply();
    }

    static List<String> defaultGrantedTools() {
        return SubAgentRunner.grantableNames(false);
    }

    private void showMainToolPicker(final Runnable onSaved) {
        final List<String> sel = new ArrayList<>(mainGrantedTools());
        showToolPicker(sel, false, () -> {
            saveGrantedTools(sel);
            act.toast("助手现在能用 " + sel.size() + " 个工具");
            if (onSaved != null) onSaved.run();
        });
    }

    private void showAsstProviderManager(final Runnable onChanged) {
        final Dialog d = new Dialog(act);
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(act.col(R.color.surface));
        bg.setCornerRadius(act.dp(18));
        bg.setStroke(act.dp(1), act.col(R.color.border));
        root.setBackground(bg);
        root.setPadding(act.dp(14), act.dp(12), act.dp(14), act.dp(10));

        LinearLayout head = new LinearLayout(act);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(act);
        title.setText("🏢 助手服务商");
        title.setTextColor(act.col(R.color.text));
        title.setTextSize(15);
        title.setTypeface(null, Typeface.BOLD);
        head.addView(title);
        head.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView add = mkSheetBtn("＋ 新建");
        add.setTextColor(act.col(R.color.accent));
        head.addView(add);
        root.addView(head);

        final LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        MaxHeightScrollView sc = new MaxHeightScrollView(act);
        sc.setMaxHeightPx((int) (act.getResources().getDisplayMetrics().heightPixels * 0.5f));
        sc.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMargins(0, act.dp(8), 0, 0);
        root.addView(sc, slp);

        LinearLayout foot = new LinearLayout(act);
        foot.setGravity(Gravity.END);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.setMargins(0, act.dp(10), 0, 0);
        foot.setLayoutParams(flp);
        TextView close = mkSheetBtn("关闭");
        close.setOnClickListener(v -> d.dismiss());
        foot.addView(close);
        root.addView(foot);

        final Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
            list.removeAllViews();
            List<AsstProviderStore.Prov> provs = AsstProviderStore.list(act);
            if (provs.isEmpty()) {
                TextView empty = new TextView(act);
                empty.setText("还没有。加几个只有这个助手能用的服务商，它建子智能体时也从这里挑模型。");
                empty.setTextColor(act.col(R.color.muted));
                empty.setTextSize(11.5f);
                empty.setLineSpacing(act.dp(2), 1f);
                empty.setPadding(act.dp(2), act.dp(6), act.dp(2), act.dp(8));
                list.addView(empty);
            }
            for (final AsstProviderStore.Prov p : provs) {
                LinearLayout card = new LinearLayout(act);
                card.setOrientation(LinearLayout.HORIZONTAL);
                card.setGravity(Gravity.CENTER_VERTICAL);
                GradientDrawable cb = new GradientDrawable();
                cb.setColor(act.col(R.color.bg));
                cb.setCornerRadius(act.dp(12));
                cb.setStroke(act.dp(1), act.col(R.color.border));
                card.setBackground(cb);
                card.setPadding(act.dp(10), act.dp(8), act.dp(6), act.dp(8));
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cp.setMargins(0, 0, 0, act.dp(6));
                card.setLayoutParams(cp);

                LinearLayout body = new LinearLayout(act);
                body.setOrientation(LinearLayout.VERTICAL);
                body.setLayoutParams(new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                TextView nm = new TextView(act);
                nm.setText("🏢 " + p.name);
                nm.setTextColor(act.col(R.color.text));
                nm.setTextSize(13);
                nm.setTypeface(null, Typeface.BOLD);
                nm.setSingleLine(true);
                nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
                body.addView(nm);
                TextView meta = new TextView(act);
                int mc = p.modelList().size();
                meta.setText(shortUrl(p.baseUrl) + "　·　" + mc + " 个模型");
                meta.setTextColor(act.col(R.color.muted));
                meta.setTextSize(11);
                meta.setPadding(0, act.dp(2), 0, 0);
                meta.setMaxLines(1);
                meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
                body.addView(meta);
                card.addView(body);

                Button edit = mkRowBtn("编辑", act.col(R.color.accent));
                edit.setOnClickListener(v -> showAsstProviderEditor(p, rebuild[0], onChanged));
                LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                elp.setMarginStart(act.dp(4));
                card.addView(edit, elp);

                Button del = mkRowBtn("删除", act.col(R.color.danger));
                del.setOnClickListener(v -> act.showStyledConfirm("删除服务商",
                        "「" + p.name + "」会从助手这里移除，不可恢复。",
                        "删除", true, () -> {
                            AsstProviderStore.delete(act, p.id);
                            act.toast("已删除");
                            rebuild[0].run();
                            if (onChanged != null) onChanged.run();
                        }));
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                dlp.setMarginStart(act.dp(2));
                card.addView(del, dlp);
                list.addView(card);
            }
        };
        rebuild[0].run();
        add.setOnClickListener(v ->
                showAsstProviderEditor(null, rebuild[0], onChanged));

        d.setContentView(root);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(0));
            WindowManager.LayoutParams lp = d.getWindow().getAttributes();
            lp.width = act.dialogWidthPx(0.94F);
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            d.getWindow().setAttributes(lp);
        }
        d.show();
    }

    private void showAsstProviderEditor(final AsstProviderStore.Prov existing,
                                        final Runnable onSaved, final Runnable onChanged) {
        final AsstProviderStore.Prov cur =
                existing == null ? new AsstProviderStore.Prov() : existing;
        final Dialog d = new Dialog(act);
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(act.col(R.color.surface));
        bg.setCornerRadius(act.dp(18));
        bg.setStroke(act.dp(1), act.col(R.color.border));
        root.setBackground(bg);
        root.setPadding(act.dp(16), act.dp(12), act.dp(16), act.dp(10));

        TextView t = new TextView(act);
        t.setText(existing == null ? "新建助手服务商" : ("编辑：" + cur.name));
        t.setTextColor(act.col(R.color.text));
        t.setTextSize(15);
        t.setTypeface(null, Typeface.BOLD);
        root.addView(t);

        root.addView(mkFieldLabel("名字"));
        final EditText nameEd = mkEditor(cur.name, "比如：本地中转", 1, false);
        root.addView(nameEd);

        root.addView(mkFieldLabel("接口地址"));
        final EditText urlEd = mkEditor(cur.baseUrl, "https://…/v1", 1, false);
        root.addView(urlEd);

        root.addView(mkFieldLabel("密钥"));
        final EditText keyEd = mkEditor(cur.apiKey, "sk-…", 1, false);
        keyEd.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(keyEd);

        root.addView(mkFieldLabel("模型"));
        final List<String> modelSel = new ArrayList<>(AsstProviderStore.parseModels(cur.models));
        final List<String> modelPool = new ArrayList<>(modelSel);

        final EditText modelSearch = mkEditor("", "🔍 搜索模型名", 1, false);
        LinearLayout.LayoutParams mslp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mslp.setMargins(0, act.dp(3), 0, 0);
        root.addView(modelSearch, mslp);

        LinearLayout mOpRow = new LinearLayout(act);
        mOpRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams morlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        morlp.setMargins(0, act.dp(6), 0, 0);
        mOpRow.setLayoutParams(morlp);
        final TextView modelCount = new TextView(act);
        modelCount.setTextColor(act.col(R.color.muted));
        modelCount.setTextSize(11);
        mOpRow.addView(modelCount, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView mClear = mkSheetBtn("清空");
        mClear.setBackgroundResource(R.drawable.btn_ghost);
        mOpRow.addView(mClear);
        TextView mAll = mkSheetBtn("全选");
        mAll.setTextColor(act.col(R.color.onAccent));
        mAll.setBackgroundResource(R.drawable.btn_primary);
        mAll.setPadding(act.dp(14), act.dp(6), act.dp(14), act.dp(6));
        LinearLayout.LayoutParams malp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        malp.setMarginStart(act.dp(6));
        mOpRow.addView(mAll, malp);
        root.addView(mOpRow);

        final LinearLayout modelListBox = new LinearLayout(act);
        modelListBox.setOrientation(LinearLayout.VERTICAL);
        final MaxHeightScrollView modelScroll = new MaxHeightScrollView(act);
        modelScroll.setMaxHeightPx(
                (int) (act.getResources().getDisplayMetrics().heightPixels * 0.24f));
        modelScroll.addView(modelListBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams mscp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mscp.setMargins(0, act.dp(6), 0, 0);
        root.addView(modelScroll, mscp);

        final String[] modelFilter = {""};
        final Runnable[] renderModels = new Runnable[1];
        renderModels[0] = () -> {
            modelListBox.removeAllViews();
            String f = modelFilter[0].toLowerCase();
            int shown = 0;
            for (final String m : modelPool) {
                if (!f.isEmpty() && !m.toLowerCase().contains(f)) continue;
                shown++;
                LinearLayout mrow = new LinearLayout(act);
                mrow.setOrientation(LinearLayout.HORIZONTAL);
                mrow.setGravity(Gravity.CENTER_VERTICAL);
                GradientDrawable mrb = new GradientDrawable();
                mrb.setColor(act.col(R.color.bg));
                mrb.setCornerRadius(act.dp(10));
                mrb.setStroke(act.dp(1), modelSel.contains(m)
                        ? act.col(R.color.accent) : act.col(R.color.border));
                mrow.setBackground(mrb);
                mrow.setPadding(act.dp(10), act.dp(2), act.dp(10), act.dp(2));
                LinearLayout.LayoutParams mrp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                mrp.setMargins(0, 0, 0, act.dp(4));
                mrow.setLayoutParams(mrp);
                final android.widget.CheckBox mcb = new android.widget.CheckBox(act);
                mcb.setText(m);
                mcb.setTextColor(act.col(R.color.text));
                mcb.setTextSize(12);
                mcb.setChecked(modelSel.contains(m));
                mcb.setLayoutParams(new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                mcb.setOnCheckedChangeListener((b, on) -> {
                    if (on) {
                        if (!modelSel.contains(m)) modelSel.add(m);
                    } else {
                        modelSel.remove(m);
                    }
                    modelCount.setText("已选 " + modelSel.size() + " / 共 " + modelPool.size() + " 个");
                });
                mrow.setOnClickListener(v -> mcb.setChecked(!mcb.isChecked()));
                mrow.addView(mcb);
                modelListBox.addView(mrow);
            }
            if (shown == 0) {
                TextView none = new TextView(act);
                none.setText(modelPool.isEmpty()
                        ? "还没有模型 —— 点下面「⬇ 一键获取模型」，或在下面手动加一个"
                        : "没有匹配的模型");
                none.setTextColor(act.col(R.color.faint));
                none.setTextSize(11.5f);
                none.setPadding(act.dp(2), act.dp(8), act.dp(2), act.dp(8));
                modelListBox.addView(none);
            }
            modelCount.setText("已选 " + modelSel.size() + " / 共 " + modelPool.size() + " 个");
        };
        renderModels[0].run();

        modelSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable e) {
                modelFilter[0] = e == null ? "" : e.toString().trim();
                renderModels[0].run();
            }
        });
        mClear.setOnClickListener(v -> {
            modelSel.clear();
            renderModels[0].run();
        });
        mAll.setOnClickListener(v -> {
            String f = modelFilter[0].toLowerCase();
            for (String m : modelPool) {
                if (!f.isEmpty() && !m.toLowerCase().contains(f)) continue;
                if (!modelSel.contains(m)) modelSel.add(m);
            }
            renderModels[0].run();
        });

        LinearLayout mAddRow = new LinearLayout(act);
        mAddRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams marlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        marlp.setMargins(0, act.dp(6), 0, 0);
        mAddRow.setLayoutParams(marlp);
        final EditText mOne = mkEditor("", "手动加一个模型名", 1, false);
        mAddRow.addView(mOne, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView mAdd = mkSheetBtn("＋ 添加");
        mAdd.setTextColor(act.col(R.color.accent));
        mAdd.setBackgroundResource(R.drawable.btn_secondary);
        mAdd.setPadding(act.dp(12), act.dp(6), act.dp(12), act.dp(6));
        LinearLayout.LayoutParams maalp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        maalp.setMarginStart(act.dp(6));
        mAdd.setOnClickListener(v -> {
            String nm = mOne.getText() == null ? "" : mOne.getText().toString().trim();
            if (nm.isEmpty()) { act.toast("先填模型名"); return; }
            if (modelPool.contains(nm)) { act.toast("列表里已经有了"); return; }
            modelPool.add(nm);
            if (!modelSel.contains(nm)) modelSel.add(nm);
            mOne.setText("");
            renderModels[0].run();
        });
        mAddRow.addView(mAdd, maalp);
        root.addView(mAddRow);

        LinearLayout toolRow = new LinearLayout(act);
        toolRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams trlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        trlp.setMargins(0, act.dp(6), 0, 0);
        toolRow.setLayoutParams(trlp);

        TextView fetch = mkSheetBtn("⬇ 一键获取模型");
        fetch.setTextColor(act.col(R.color.accent));
        fetch.setBackgroundResource(R.drawable.btn_secondary);
        fetch.setPadding(act.dp(12), act.dp(6), act.dp(12), act.dp(6));
        fetch.setOnClickListener(v -> {
            String bu = urlEd.getText() == null ? "" : urlEd.getText().toString().trim();
            String ak = keyEd.getText() == null ? "" : keyEd.getText().toString().trim();
            if (bu.isEmpty()) { act.toast("请先填接口地址"); return; }
            act.toast("正在获取模型列表…");
            new Thread(() -> {
                try {
                    final List<String> fetched = AiClient.fetchModelList(bu, ak);
                    act.runOnUiThread(() -> {
                        int added = 0;
                        for (String m : fetched) {
                            if (!modelPool.contains(m)) { modelPool.add(m); added++; }
                        }
                        renderModels[0].run();
                        act.toast("拿到 " + fetched.size() + " 个模型"
                                + (added > 0 ? "，新来 " + added + " 个，勾上要用的" : ""));
                    });
                } catch (final Exception e) {
                    final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                    act.runOnUiThread(() -> act.toast("获取失败：" + msg));
                }
            }).start();
        });
        toolRow.addView(fetch, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView testAll = mkSheetBtn("🧪 测试全部模型");
        testAll.setTextColor(act.col(R.color.accent));
        testAll.setBackgroundResource(R.drawable.btn_secondary);
        testAll.setPadding(act.dp(12), act.dp(6), act.dp(12), act.dp(6));
        LinearLayout.LayoutParams talp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        talp.setMarginStart(act.dp(6));
        testAll.setOnClickListener(v -> {
            String bu = urlEd.getText() == null ? "" : urlEd.getText().toString().trim();
            String ak = keyEd.getText() == null ? "" : keyEd.getText().toString().trim();
            final List<String> list = new ArrayList<>(modelSel);
            if (list.isEmpty()) { act.toast("还没有模型可测"); return; }
            if (bu.isEmpty()) { act.toast("请先填接口地址"); return; }
            act.toast("开始测试 " + list.size() + " 个模型…");
            final TextView btn = testAll;
            new Thread(() -> {
                int ok = 0, fail = 0;
                for (String m : list) {
                    String r = AiClient.testModel(bu, ak, m);
                    if (r != null && r.startsWith("✅")) ok++; else fail++;
                }
                final int fOk = ok, fFail = fail;
                act.runOnUiThread(() -> {
                    btn.setText("🧪 " + fOk + " 通 / " + fFail + " 不通");
                    act.toast("测试完成：" + fOk + " 个可用，" + fFail + " 个不可用");
                });
            }).start();
        });
        toolRow.addView(testAll, talp);
        root.addView(toolRow);

        LinearLayout row = new LinearLayout(act);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(0, act.dp(12), 0, 0);
        row.setLayoutParams(rlp);
        TextView cancel = mkSheetBtn("取消");
        cancel.setOnClickListener(v -> d.dismiss());
        row.addView(cancel);
        row.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView save = mkSheetBtn("保存");
        save.setTextColor(act.col(R.color.onAccent));
        save.setBackgroundResource(R.drawable.btn_primary);
        save.setPadding(act.dp(18), act.dp(6), act.dp(18), act.dp(6));
        row.addView(save);
        root.addView(row);

        save.setOnClickListener(v -> {
            String nm = nameEd.getText() == null ? "" : nameEd.getText().toString().trim();
            String url = urlEd.getText() == null ? "" : urlEd.getText().toString().trim();
            if (nm.isEmpty()) { act.toast("先给它起个名字"); return; }
            if (url.isEmpty()) { act.toast("接口地址不能空"); return; }
            AsstProviderStore.Prov other = AsstProviderStore.byName(act, nm);
            if (other != null && !other.id.equals(cur.id)) {
                act.toast("已经有一个叫「" + nm + "」的了");
                return;
            }
            cur.name = nm;
            cur.baseUrl = url;
            cur.apiKey = keyEd.getText() == null ? "" : keyEd.getText().toString().trim();
            cur.models = AsstProviderStore.joinModels(modelSel);
            AsstProviderStore.save(act, cur);
            act.toast("已保存：" + cur.name);
            d.dismiss();
            if (onSaved != null) onSaved.run();
            if (onChanged != null) onChanged.run();
        });

        d.setContentView(root);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(0));
            WindowManager.LayoutParams lp = d.getWindow().getAttributes();
            lp.width = act.dialogWidthPx(0.92F);
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            d.getWindow().setAttributes(lp);
        }
        d.show();
    }


    static String shortUrl(String u) {
        if (u == null) return "";
        String s = u.trim();
        int i = s.indexOf("://");
        if (i > 0) s = s.substring(i + 3);
        if (s.startsWith("www.")) s = s.substring(4);
        int q = s.indexOf('?');
        if (q > 0) s = s.substring(0, q);
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    private void showRolePromptDialog(final String agentId, final String aName,
                                      final Runnable onSaved) {
        final Dialog d = new Dialog(act);
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(act.col(R.color.surface));
        bg.setCornerRadius(act.dp(20));
        bg.setStroke(act.dp(1), act.col(R.color.border));
        root.setBackground(bg);
        root.setPadding(act.dp(18), act.dp(18), act.dp(18), act.dp(14));

        TextView title = new TextView(act);
        title.setText("🎭 角色提示词 · " + aName);
        title.setTextColor(act.col(R.color.text));
        title.setTextSize(16);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView tip = new TextView(act);
        tip.setText("只对" + aName + "生效，不影响主对话。");
        tip.setTextColor(act.col(R.color.muted));
        tip.setTextSize(12);
        tip.setPadding(0, act.dp(6), 0, 0);
        root.addView(tip);

        final EditText editor = new EditText(act);
        String saved = rolePromptOf(agentId);
        editor.setText(notBlank(saved) ? saved : act.defaultAssistantSystemPrompt());
        editor.setSelection(editor.getText().length());
        editor.setHint("比如：你是一个耐心的健身教练，说话直接，每次都追问进度");
        editor.setHintTextColor(act.col(R.color.muted));
        editor.setTextColor(act.col(R.color.text));
        editor.setTextSize(14);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editor.setBackgroundResource(R.drawable.bg_edit);
        editor.setPadding(act.dp(12), act.dp(10), act.dp(12), act.dp(10));
        editor.setMinLines(5);

        MaxHeightScrollView sc = new MaxHeightScrollView(act);
        sc.setMaxHeightPx((int) (act.getResources().getDisplayMetrics().heightPixels * 0.26f));
        sc.addView(editor, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = act.dp(12);
        root.addView(sc, elp);

        LinearLayout row = new LinearLayout(act);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = act.dp(14);
        row.setLayoutParams(rlp);

        TextView reset = mkSheetBtn("恢复默认");
        reset.setTextColor(act.col(R.color.muted));
        reset.setBackgroundResource(R.drawable.btn_ghost);
        reset.setOnClickListener(v -> {
            editor.setText(act.defaultAssistantSystemPrompt());
            editor.setSelection(editor.getText().length());
            act.toast("已填回默认，点「保存」生效");
        });
        row.addView(reset);
        row.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));

        TextView save = mkSheetBtn("保存");
        save.setTextColor(act.col(R.color.onAccent));
        save.setBackgroundResource(R.drawable.btn_primary);
        save.setPadding(act.dp(18), act.dp(6), act.dp(18), act.dp(6));
        row.addView(save);
        root.addView(row);

        d.setContentView(root);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(0));
            WindowManager.LayoutParams lp = d.getWindow().getAttributes();
            lp.width = act.dialogWidthPx(0.92F);
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            d.getWindow().setAttributes(lp);
        }
        save.setOnClickListener(v -> {
            String t = editor.getText() == null ? "" : editor.getText().toString().trim();
            boolean sameAsDefault = t.equals(act.defaultAssistantSystemPrompt().trim());
            saveRolePrompt(agentId, sameAsDefault ? "" : t);
            act.toast(notBlank(rolePromptOf(agentId))
                    ? aName + " 的角色提示词已保存"
                    : aName + " 用回默认角色（跟主对话同一份）");
            d.dismiss();
            if (onSaved != null) onSaved.run();
        });
        d.show();
    }

    private void showUsageSheet() {
        final long cid = convId;
        act.toast("正在统计…");
        new Thread(() -> {
            final String report = act.buildAssistantUsageReport(cid);
            act.runOnUiThread(() -> {
                if (act.isFinishing() || act.isDestroyed()) return;
                act.showStyledConfirm("📊 用量 / 上下文", report, "知道了", false, null);
            });
        }, "asst-usage").start();
    }

    /**
     * 加号面板（2026-10-14，与主对话同构）：输入框为空时右侧是「＋」，点它从输入框下方展开。
     * 第一页：拍照 / 相册 / 文件 + 新建对话 / 历史对话 + 用量 / 模型；
     * 第二页：智能体与角色提示词等（原「设置」弹窗的全部内容）。两页可左右滑动。
     */
    private void buildAsstPlusPanel(LinearLayout root, final View inputBar) {
        final SwipePages pages = new SwipePages(act);

        LinearLayout pageTalk = new LinearLayout(act);
        pageTalk.setOrientation(LinearLayout.VERTICAL);
        LinearLayout pageAgent = new LinearLayout(act);
        pageAgent.setOrientation(LinearLayout.VERTICAL);

        pages.addView(pageTalk, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        pages.addView(pageAgent, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 第一页 ----
        LinearLayout attachRow = new LinearLayout(act);
        attachRow.setOrientation(LinearLayout.HORIZONTAL);
        attachRow.addView(mkPlusCard("📷", "拍照", v -> {
            hideAsstPlusPanel();
            pickAttachmentForPanel(0);
        }), plusCardLp(0));
        attachRow.addView(mkPlusCard("🖼", "相册", v -> {
            hideAsstPlusPanel();
            pickAttachmentForPanel(1);
        }), plusCardLp(act.dp(6)));
        attachRow.addView(mkPlusCard("📄", "文件", v -> {
            hideAsstPlusPanel();
            pickAttachmentForPanel(2);
        }), plusCardLp(act.dp(6)));
        pageTalk.addView(attachRow);

        LinearLayout convRow = new LinearLayout(act);
        convRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams convLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        convLp.setMargins(0, act.dp(6), 0, 0);

        TextView newConv = mkSheetBtn("＋ 新建对话");
        newConv.setOnClickListener(v -> {
            if (busy) { act.toast("正在生成回复，稍后再开新对话"); return; }
            if (db == null) db = new AppDatabase(act);
            setConversation(db.getFreshAssistantConversationId());
            hideAsstPlusPanel();
        });
        final TextView histConv = mkSheetBtn("🕘 历史对话");
        histConv.setOnClickListener(v -> {
            hideAsstPlusPanel();
            showHistoryList();
        });
        refreshPlusConv = () -> {
            int n = 0;
            try {
                if (db == null) db = new AppDatabase(act);
                n = db.listAssistantConversations().size();
            } catch (Throwable ignored) { }
            histConv.setText("🕘 历史对话（" + Math.max(1, n) + "）");
        };
        refreshPlusConv.run();

        newConv.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        half2.setMarginStart(act.dp(6));
        histConv.setLayoutParams(half2);
        convRow.addView(newConv);
        convRow.addView(histConv);
        pageTalk.addView(convRow, convLp);

        LinearLayout toolRow = new LinearLayout(act);
        toolRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams toolLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toolLp.setMargins(0, act.dp(6), 0, 0);

        TextView usageBtn = mkSheetBtn("📊 用量");
        usageBtn.setOnClickListener(v -> {
            hideAsstPlusPanel();
            showUsageSheet();
        });
        final TextView modelBtn = mkSheetBtn("");
        modelBtn.setSingleLine(true);
        refreshPlusModel = () -> modelBtn.setText(
                (notBlank(model) ? shortModel(model) : "选模型") + "  ▾");
        refreshPlusModel.run();
        modelBtn.setTextColor(act.col(R.color.text));
        modelBtn.setBackgroundResource(R.drawable.bg_edit);
        modelBtn.setOnClickListener(v -> {
            final String[] mo = {model}, bo = {baseUrl}, ko = {apiKey};
            act.showAsstProviderModelPicker(mo, bo, ko, () -> {
                model = mo[0]; baseUrl = bo[0]; apiKey = ko[0];
                saveConfig();
                if (refreshPlusModel != null) refreshPlusModel.run();
                refreshModelLine();
                act.toast("这个助手将使用：" + model);
            });
        });
        usageBtn.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mlp.setMarginStart(act.dp(6));
        toolRow.addView(usageBtn);
        toolRow.addView(modelBtn, mlp);
        pageTalk.addView(toolRow, toolLp);

        // ---- 第二页：智能体 / 角色提示词等（原「设置」弹窗内容）----
        final LinearLayout grid = new LinearLayout(act);
        grid.setOrientation(LinearLayout.VERTICAL);
        pageAgent.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final Runnable[] refreshAgent = new Runnable[1];
        refreshAgent[0] = () -> buildAgentGrid(grid, refreshAgent[0]);
        refreshAgent[0].run();

        final TextView pageMark = new TextView(act);
        pageMark.setTextColor(act.col(R.color.faint));
        pageMark.setTextSize(10);
        pageMark.setGravity(Gravity.CENTER);
        pages.setOnPageChanged(() -> pageMark.setText(
                (pages.currentPage() + 1) + " / " + pages.pageCount()));
        pageMark.setText("1 / " + pages.pageCount());

        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(act.col(R.color.surface));
        pbg.setCornerRadius(act.dp(16));
        pbg.setStroke(act.dp(1), act.col(R.color.border));
        panel.setBackground(pbg);
        panel.setPadding(act.dp(8), act.dp(6), act.dp(8), act.dp(2));
        panel.addView(pages);
        LinearLayout.LayoutParams markLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        markLp.setMargins(0, act.dp(2), 0, 0);
        panel.addView(pageMark, markLp);
        panel.setVisibility(View.GONE);
        inputPlusPanel = panel;

        LinearLayout.LayoutParams wrap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wrap.setMargins(0, 0, 0, act.dp(2));
        root.addView(panel, root.indexOfChild(inputBar) + 1, wrap);
    }

    private void toggleAsstPlusPanel() {
        if (inputPlusPanel == null) return;
        if (inputPlusPanel.getVisibility() == View.VISIBLE) {
            hideAsstPlusPanel();
            return;
        }
        if (refreshPlusConv != null) refreshPlusConv.run();
        if (refreshPlusModel != null) refreshPlusModel.run();
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            act.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        } catch (Throwable ignored) { }
        inputPlusPanel.setVisibility(View.VISIBLE);
        inputPlusPanel.setAlpha(0f);
        inputPlusPanel.setTranslationY(act.dp(24));
        inputPlusPanel.animate().alpha(1f).translationY(0f).setDuration(180).start();
        if (sendBtn != null) sendBtn.animate().rotation(45f).setDuration(200).start();
    }

    private void hideAsstPlusPanel() {
        if (inputPlusPanel != null) inputPlusPanel.setVisibility(View.GONE);
        if (sendBtn != null && sendBtn.getRotation() != 0f) {
            sendBtn.animate().rotation(0f).setDuration(200).start();
        }
    }

    private View mkPlusCard(String icon, String label, View.OnClickListener listener) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        GradientDrawable cb = new GradientDrawable();
        cb.setColor(act.col(R.color.bg));
        cb.setCornerRadius(act.dp(12));
        cb.setStroke(act.dp(1), act.col(R.color.border));
        box.setBackground(cb);
        box.setPadding(0, act.dp(7), 0, act.dp(7));
        TextView ic = new TextView(act);
        ic.setText(icon);
        ic.setTextSize(17);
        ic.setGravity(Gravity.CENTER);
        TextView lb = new TextView(act);
        lb.setText(label);
        lb.setTextSize(10);
        lb.setTextColor(act.col(R.color.muted));
        lb.setGravity(Gravity.CENTER);
        box.addView(ic);
        box.addView(lb);
        box.setOnClickListener(listener);
        return box;
    }

    private LinearLayout.LayoutParams plusCardLp(int startMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (startMargin > 0) lp.setMarginStart(startMargin);
        return lp;
    }

    private TextView mkSheetBtn(String text) {
        TextView b = new TextView(act);
        b.setText(text);
        b.setTextColor(act.col(R.color.text));
        b.setTextSize(12);
        b.setBackgroundResource(R.drawable.btn_secondary);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.setPadding(act.dp(10), act.dp(6), act.dp(10), act.dp(6));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        return b;
    }

    private TextView mkSheetWideBtn(String text) {
        TextView b = mkSheetBtn(text);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private static String shortModel(String m) {
        if (m == null) return "";
        String s = m.trim();
        int i = s.lastIndexOf('/');
        if (i >= 0 && i < s.length() - 1) s = s.substring(i + 1);
        return s.length() > 18 ? s.substring(0, 18) + "…" : s;
    }

    private void showSubAgentManager(final String agentId, final Runnable onChanged) {
        final Dialog d = new Dialog(act);
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(act.col(R.color.surface));
        bg.setCornerRadius(act.dp(18));
        bg.setStroke(act.dp(1), act.col(R.color.border));
        root.setBackground(bg);
        root.setPadding(act.dp(16), act.dp(12), act.dp(16), act.dp(10));

        final Runnable[] refresh = new Runnable[1];

        LinearLayout head = new LinearLayout(act);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(act);
        t.setText("🧩 子智能体");
        t.setTextColor(act.col(R.color.text));
        t.setTextSize(15);
        t.setTypeface(null, Typeface.BOLD);
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView add = mkSheetBtn("＋ 新建");
        add.setOnClickListener(v -> showSubAgentEditor(agentId, null, () -> {
            if (refresh[0] != null) refresh[0].run();
            if (onChanged != null) onChanged.run();
        }));
        head.addView(add);
        root.addView(head);

        final LinearLayout listBox = new LinearLayout(act);
        listBox.setOrientation(LinearLayout.VERTICAL);
        MaxHeightScrollView sc = new MaxHeightScrollView(act);
        sc.setMaxHeightPx((int) (act.getResources().getDisplayMetrics().heightPixels * 0.62f)
                - act.dp(140));
        sc.addView(listBox);
        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        refresh[0] = () -> buildSubAgentList(listBox, agentId, refresh[0], onChanged);
        refresh[0].run();

        LinearLayout row = new LinearLayout(act);
        row.setGravity(Gravity.END);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(0, act.dp(8), 0, 0);
        row.setLayoutParams(rlp);
        TextView close = mkSheetBtn("关闭");
        close.setTextColor(act.col(R.color.muted));
        close.setBackgroundResource(R.drawable.btn_ghost);
        close.setOnClickListener(v -> {
            d.dismiss();
            if (onChanged != null) onChanged.run();
        });
        row.addView(close);
        root.addView(row);

        d.setContentView(root);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(0));
            WindowManager.LayoutParams lp = d.getWindow().getAttributes();
            lp.width = act.dialogWidthPx(0.92F);
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            d.getWindow().setAttributes(lp);
        }
        d.show();
    }

    private void buildSubAgentList(LinearLayout list, final String agentId,
                                   final Runnable refresh, final Runnable onChanged) {
        list.removeAllViews();
        SubAgentStore.ensureBuiltins(act, agentId);
        List<SubAgentStore.Sub> subs = SubAgentStore.list(act, agentId);
        if (subs.isEmpty()) {
            TextView empty = new TextView(act);
            empty.setText("还没有");
            empty.setTextColor(act.col(R.color.faint));
            empty.setTextSize(12);
            empty.setPadding(act.dp(4), act.dp(6), act.dp(4), act.dp(10));
            list.addView(empty);
            return;
        }
        for (SubAgentStore.Sub s : subs) {
            LinearLayout card = new LinearLayout(act);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable cb = new GradientDrawable();
            cb.setColor(act.col(R.color.bg));
            cb.setCornerRadius(act.dp(12));
            cb.setStroke(act.dp(1), act.col(R.color.border));
            card.setBackground(cb);
            card.setPadding(act.dp(10), act.dp(8), act.dp(6), act.dp(8));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.setMargins(0, 0, 0, act.dp(6));
            card.setLayoutParams(cp);

            LinearLayout body = new LinearLayout(act);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView name = new TextView(act);
            name.setText("🤖 " + s.name + (s.usable() ? "" : "（还没配好）"));
            name.setTextColor(s.usable() ? act.col(R.color.text) : act.col(R.color.danger));
            name.setTextSize(13);
            name.setTypeface(null, Typeface.BOLD);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            body.addView(name);
            card.addView(body);

            Button log = mkRowBtn("记录", act.col(R.color.searchAccent));
            log.setOnClickListener(v -> showSubAgentLog(agentId, s));
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.setMarginStart(act.dp(4));
            card.addView(log, llp);

            Button edit = mkRowBtn("编辑", act.col(R.color.accent));
            edit.setOnClickListener(v -> showSubAgentEditor(agentId, s, refresh));
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            elp.setMarginStart(act.dp(4));
            card.addView(edit, elp);

            Button del = mkRowBtn("删除", act.col(R.color.danger));
            if (s.fixed) {
                del.setVisibility(View.GONE);
            } else {
                del.setOnClickListener(v -> act.showStyledConfirm("删除子智能体",
                        "「" + s.name + "」会从「" + agentName(agentId) + "」手下移除，不可恢复。",
                        "删除", true, () -> {
                            SubAgentStore.delete(act, agentId, s.id);
                            act.toast("已删除");
                            refresh.run();
                            if (onChanged != null) onChanged.run();
                        }));
            }
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dlp.setMarginStart(act.dp(2));
            card.addView(del, dlp);

            list.addView(card);
        }
    }

    private void showSubAgentEditor(final String agentId, final SubAgentStore.Sub existing,
                                    final Runnable onSaved) {
        final SubAgentStore.Sub cur = existing == null ? new SubAgentStore.Sub() : existing;
        final Dialog d = new Dialog(act);
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(act.col(R.color.surface));
        bg.setCornerRadius(act.dp(18));
        bg.setStroke(act.dp(1), act.col(R.color.border));
        root.setBackground(bg);
        root.setPadding(act.dp(16), act.dp(12), act.dp(16), act.dp(10));

        final boolean locked = cur.fixed && SubAgentStore.SCREEN_AGENT_ID.equals(cur.id);

        TextView t = new TextView(act);
        t.setText(existing == null ? "新建子智能体"
                : (locked ? ("编辑：" + cur.name + "（提示词正文只读）") : ("编辑：" + cur.name)));
        t.setTextColor(act.col(R.color.text));
        t.setTextSize(15);
        t.setTypeface(null, Typeface.BOLD);
        root.addView(t);

        root.addView(mkFieldLabel("名字"));
        final EditText nameEd = mkEditor(cur.name, "比如：新闻搜索员", 1, false);
        root.addView(nameEd);

        root.addView(mkFieldLabel("用途"));
        final EditText purposeEd = mkEditor(cur.purpose,
                "比如：查最新的科技/财经新闻，只要最近三天的", 1, false);
        root.addView(purposeEd);

        root.addView(mkFieldLabel("服务商 / 模型"));
        final String[] mo = {cur.model}, bo = {cur.baseUrl}, ko = {cur.apiKey};
        final TextView modelBtn = mkSheetWideBtn("");
        modelBtn.setBackgroundResource(R.drawable.bg_edit);
        modelBtn.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        final Runnable refreshModel = () -> modelBtn.setText(
                notBlank(mo[0]) ? (mo[0] + "  ▾") : "选服务商和模型  ▾");
        refreshModel.run();
        modelBtn.setOnClickListener(v -> act.showAsstProviderModelPicker(mo, bo, ko, () -> {
            refreshModel.run();
        }));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.setMargins(0, act.dp(3), 0, 0);
        root.addView(modelBtn, mlp);

        root.addView(mkFieldLabel("思考档位"));
        final int[] lv = {cur.thinkLevel};
        final TextView thinkPick = mkSheetWideBtn("");
        thinkPick.setBackgroundResource(R.drawable.bg_edit);
        thinkPick.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        final Runnable refreshThink = () -> {
            String cur0 = lv[0] >= 0
                    ? ReasoningNegotiator.levelName(lv[0])
                    : "跟服务商默认";
            thinkPick.setText(cur0 + "  ▾");
        };
        refreshThink.run();
        thinkPick.setOnClickListener(v -> {
            String[] names = ReasoningNegotiator.levelNames();
            String[] items = new String[names.length + 1];
            String[] subs2 = new String[names.length + 1];
            items[0] = "跟服务商默认";
            subs2[0] = "不指定，由服务商决定怎么思考";
            for (int i = 0; i < names.length; i++) {
                items[i + 1] = names[i];
                subs2[i + 1] = i == ReasoningNegotiator.LEVEL_OFF ? "不思考，最快最省"
                        : (i == ReasoningNegotiator.LEVEL_MAX ? "Ultra：最深，最慢但最稳" : "越深越慢");
            }
            act.showStyledListPicker(thinkPick, "子智能体的思考档位", items, subs2,
                    lv[0] < 0 ? 0 : lv[0] + 1, which -> {
                        lv[0] = which == 0 ? -1 : which - 1;
                        refreshThink.run();
                    });
        });
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.setMargins(0, act.dp(3), 0, 0);
        root.addView(thinkPick, tlp);

        root.addView(mkFieldLabel("上下文（token）"));
        final EditText ctxEd = new EditText(act);
        ctxEd.setText(String.valueOf(cur.ctxTokens > 0 ? cur.ctxTokens : SubAgentRunner.DEFAULT_CTX_TOKENS));
        ctxEd.setSelection(ctxEd.getText().length());
        ctxEd.setHint(String.valueOf(SubAgentRunner.DEFAULT_CTX_TOKENS));
        ctxEd.setHintTextColor(act.col(R.color.muted));
        ctxEd.setTextColor(act.col(R.color.text));
        ctxEd.setTextSize(13);
        ctxEd.setBackgroundResource(R.drawable.bg_edit);
        ctxEd.setPadding(act.dp(10), act.dp(7), act.dp(10), act.dp(7));
        ctxEd.setSingleLine(true);
        ctxEd.setInputType(InputType.TYPE_CLASS_NUMBER);
        LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp2.setMargins(0, act.dp(3), 0, 0);
        root.addView(ctxEd, clp2);

        root.addView(mkFieldLabel("工具权限"));
        final List<String> toolSel = new ArrayList<>(cur.toolList());
        if (toolSel.isEmpty()) toolSel.addAll(SubAgentStore.subList(SubAgentStore.DEFAULT_TOOLS));
        final TextView toolBtn = mkSheetWideBtn("");
        toolBtn.setBackgroundResource(R.drawable.bg_edit);
        toolBtn.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        toolBtn.setSingleLine(true);
        toolBtn.setEllipsize(android.text.TextUtils.TruncateAt.END);
        final Runnable refreshTools = () -> toolBtn.setText(
                toolSel.isEmpty() ? "没给工具  ▾" : (toolSel.size() + " 个：" + toolNamesShort(toolSel) + "  ▾"));
        refreshTools.run();
        toolBtn.setOnClickListener(v -> showToolPicker(toolSel, refreshTools));
        LinearLayout.LayoutParams tpl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tpl.setMargins(0, act.dp(3), 0, 0);
        root.addView(toolBtn, tpl);

        if (cur.fixed) {
            TextView fix = new TextView(act);
            fix.setText(locked
                    ? "🧩 内置子智能体 · 助手改不了也删不了。提示词正文只读（随代码版本升级），"
                            + "但你可以往下追加要求；其余字段都由你调"
                    : "这个是你固定下来的，助手改不了也删不了。提示词随代码版本升级；你改过之后就锁住不动了");
            fix.setTextColor(act.col(R.color.muted));
            fix.setTextSize(11);
            fix.setPadding(0, act.dp(6), 0, 0);
            root.addView(fix);
        }

        root.addView(mkFieldLabel(locked ? "提示词正文（代码内置 · 只读）" : "提示词"));
        if (locked) {
            TextView lk = new TextView(act);
            lk.setText("这段正文由代码提供，随版本升级，谁都改不了它 —— "
                    + "要改只能改代码重新编译。你要加自己的要求，写在下面的「追加要求」里。");
            lk.setTextColor(act.col(R.color.searchAccent));
            lk.setTextSize(11);
            lk.setLineSpacing(act.dp(2), 1f);
            lk.setPadding(0, 0, 0, act.dp(6));
            root.addView(lk);
        }

        FrameLayout promptWrap = new FrameLayout(act);
        final EditText promptEd = mkEditor(cur.prompt,
                "比如：只找最近三天内的新闻；每条给出标题、发布时间、链接和一句话摘要；"
                        + "找不到就直说，不要编。", 6, true);
        promptEd.setPadding(act.dp(10), act.dp(7), act.dp(10), act.dp(30));
        if (locked) {
            // 只读正文用 TextView，不用 EditText：
            // 禁用的 EditText 仍然会消费触摸（Android 的既定行为），手势传不到外面，
            // 所以既滚不动它、也滚不动父容器。TextView + ScrollingMovementMethod 自己处理
            // 拖动并滚动，高度固定，不牵扯任何外层容器。
            TextView ro = new TextView(act);
            ro.setText(cur.prompt == null ? "" : cur.prompt);
            ro.setTextColor(act.col(R.color.text));
            ro.setTextSize(13);
            ro.setBackgroundResource(R.drawable.bg_edit);
            ro.setPadding(act.dp(10), act.dp(7), act.dp(10), act.dp(30));
            ro.setGravity(Gravity.TOP | Gravity.START);
            ro.setVerticalScrollBarEnabled(true);
            ro.setMovementMethod(new android.text.method.ScrollingMovementMethod());
            // 0.12 屏高：再高就会把底部的保存/取消挤出可视区。
            int roH = (int) (act.getResources().getDisplayMetrics().heightPixels * 0.12f);
            promptWrap.addView(ro, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, roH));
        } else {
            promptWrap.addView(promptEd, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        final TextView promptExpand = new TextView(act);
        promptExpand.setText(locked ? "⤢ 查看" : "⤢ 放大");
        promptExpand.setTextSize(11);
        promptExpand.setTextColor(act.col(R.color.accent));
        promptExpand.setContentDescription(locked ? "查看全文" : "放大编辑");
        promptExpand.setPadding(act.dp(6), act.dp(2), act.dp(6), act.dp(2));
        promptExpand.setOnClickListener(v ->
                showSubAgentPromptEditor(promptEd, locked, () -> { }));
        FrameLayout.LayoutParams pelp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pelp.gravity = Gravity.END | Gravity.BOTTOM;
        pelp.setMargins(0, 0, act.dp(6), act.dp(4));
        promptWrap.addView(promptExpand, pelp);
        root.addView(promptWrap);

        final EditText appendEd = mkEditor(cur.promptAppend,
                "你额外要它遵守的要求（可选）。和上面的正文同等效力，冲突时以这里为准；"
                        + "代码升级正文时，这里一个字都不会动。", 2, true);
        root.addView(mkFieldLabel("追加要求"));
        root.addView(appendEd);

        LinearLayout row = new LinearLayout(act);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(0, act.dp(12), 0, 0);
        row.setLayoutParams(rlp);
        TextView cancel = mkSheetBtn("取消");
        cancel.setOnClickListener(v -> d.dismiss());
        row.addView(cancel);
        row.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));

        TextView save = mkSheetBtn("保存");
        save.setTextColor(act.col(R.color.onAccent));
        save.setBackgroundResource(R.drawable.btn_primary);
        save.setPadding(act.dp(18), act.dp(6), act.dp(18), act.dp(6));
        row.addView(save);
        root.addView(row);

        d.setContentView(root);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(0));
            WindowManager.LayoutParams lp = d.getWindow().getAttributes();
            lp.width = act.dialogWidthPx(0.92F);
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            d.getWindow().setAttributes(lp);
        }
        save.setOnClickListener(v -> {
            String nm = nameEd.getText() == null ? "" : nameEd.getText().toString().trim();
            if (nm.isEmpty()) { act.toast("先给它起个名字"); return; }
            SubAgentStore.Sub other = SubAgentStore.byName(act, agentId, nm);
            if (other != null && !other.id.equals(cur.id)) {
                act.toast("已经有一个叫「" + nm + "」的了，换个名字");
                return;
            }
            if (notBlank(mo[0]) && !notBlank(bo[0])) {
                act.toast("这个模型没带服务商地址，重新选一次");
                return;
            }
            cur.model = mo[0] == null ? "" : mo[0].trim();
            cur.baseUrl = bo[0] == null ? "" : bo[0].trim();
            cur.apiKey = ko[0] == null ? "" : ko[0].trim();
            // 只有提示词正文例外：锁定态一律从代码取，表单里那份是只读镜像，
            // 不读它 —— 万一有人绕过 UI 改了内存里的文本，保存也不会把它写下去。
            cur.name = nm;
            cur.purpose = purposeEd.getText() == null ? ""
                    : purposeEd.getText().toString().trim();
            cur.thinkLevel = lv[0];
            cur.ctxTokens = parseCtx(ctxEd.getText() == null ? "" : ctxEd.getText().toString());
            cur.setTools(toolSel);
            cur.promptAppend = appendEd.getText() == null ? ""
                    : appendEd.getText().toString().trim();
            if (locked) {
                cur.prompt = SubAgentStore.builtinScreenPrompt();
                cur.promptCustom = false;
            } else {
                String newPrompt = promptEd.getText() == null ? ""
                        : promptEd.getText().toString().trim();
                if (newPrompt.isEmpty()) {
                    cur.promptCustom = false;
                } else if (!newPrompt.equals(cur.prompt == null ? "" : cur.prompt.trim())) {
                    cur.promptCustom = true;
                }
                cur.prompt = newPrompt;
            }
            SubAgentStore.save(act, agentId, cur);
            act.toast(notBlank(cur.model)
                    ? (locked ? ("已保存「" + cur.name + "」（提示词正文没动）")
                            : ("已保存：" + cur.name))
                    : ("已保存 " + cur.name + "（还没选模型，它暂时不能干活）"));
            d.dismiss();
            if (onSaved != null) onSaved.run();
        });
        d.show();
    }

    void showSubAgentPromptEditor(final EditText target, final Runnable onDone) {
        showSubAgentPromptEditor(target, false, onDone);
    }

    void showSubAgentPromptEditor(final EditText target, boolean readOnly,
                                  final Runnable onDone) {
        final Dialog fd = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout fr = new LinearLayout(act);
        fr.setOrientation(LinearLayout.VERTICAL);
        fr.setBackgroundColor(act.col(R.color.bg));
        fr.setPadding(act.dp(16), act.dp(16), act.dp(16), act.dp(16));

        LinearLayout head = new LinearLayout(act);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView ht = new TextView(act);
        ht.setText(readOnly ? "查看提示词（只读）" : "编辑提示词");
        ht.setTextColor(act.col(R.color.text));
        ht.setTextSize(17);
        ht.setTypeface(null, Typeface.BOLD);
        head.addView(ht, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView cnt = new TextView(act);
        cnt.setTextColor(act.col(R.color.muted));
        cnt.setTextSize(12);
        head.addView(cnt);
        fr.addView(head);

        final EditText big = new EditText(act);
        big.setText(target.getText() == null ? "" : target.getText().toString());
        big.setSelection(big.getText().length());
        big.setHint(target.getHint() == null ? "" : target.getHint());
        big.setBackgroundResource(R.drawable.bg_edit);
        big.setPadding(act.dp(12), act.dp(12), act.dp(12), act.dp(12));
        big.setTextColor(act.col(R.color.text));
        big.setHintTextColor(act.col(R.color.muted));
        big.setTextSize(14);
        big.setGravity(Gravity.TOP | Gravity.START);
        big.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        big.setVerticalScrollBarEnabled(true);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        blp.setMargins(0, act.dp(10), 0, act.dp(10));
        if (readOnly) {
            // 只读展示不用 EditText：它被禁用后仍会吃掉手势。
            // TextView 不消费触摸，套 ScrollView 由父容器滚 —— 这是标准做法。
            TextView tv = new TextView(act);
            tv.setText(big.getText() == null ? "" : big.getText().toString());
            tv.setBackgroundResource(R.drawable.bg_edit);
            tv.setPadding(act.dp(12), act.dp(12), act.dp(12), act.dp(12));
            tv.setTextColor(act.col(R.color.text));
            tv.setTextSize(14);
            tv.setGravity(Gravity.TOP | Gravity.START);
            tv.setLineSpacing(act.dp(2), 1f);
            ScrollView vs = new ScrollView(act);
            vs.addView(tv);
            fr.addView(vs, blp);
        } else {
            fr.addView(big, blp);
        }
        cnt.setText(big.getText().length() + " 字");
        big.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                cnt.setText(s.length() + " 字");
            }
        });

        LinearLayout row = new LinearLayout(act);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView cancel = mkSheetBtn("取消");
        cancel.setOnClickListener(v -> fd.dismiss());
        row.addView(cancel);
        row.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView ok = mkSheetBtn(readOnly ? "关闭" : "完成");
        ok.setTextColor(act.col(R.color.onAccent));
        ok.setBackgroundResource(R.drawable.btn_primary);
        ok.setPadding(act.dp(18), act.dp(6), act.dp(18), act.dp(6));
        ok.setOnClickListener(v -> {
            if (!readOnly) {
                target.setText(big.getText() == null ? "" : big.getText().toString());
                target.setSelection(target.getText().length());
            }
            fd.dismiss();
            if (onDone != null) onDone.run();
        });
        row.addView(ok);
        fr.addView(row);

        fd.setContentView(fr);
        if (fd.getWindow() != null) {
            fd.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            fd.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        fd.show();
    }
    private void showToolPicker(final List<String> sel, final Runnable onDone) {
        showToolPicker(sel, true, onDone);
    }

    private void showToolPicker(final List<String> sel, final boolean forSubAgent,
                                final Runnable onDone) {
        final Dialog d = new Dialog(act);
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(act.col(R.color.surface));
        bg.setCornerRadius(act.dp(18));
        bg.setStroke(act.dp(1), act.col(R.color.border));
        root.setBackground(bg);
        root.setPadding(act.dp(14), act.dp(12), act.dp(14), act.dp(10));

        TextView title = new TextView(act);
        title.setText("工具授权");
        title.setTextColor(act.col(R.color.text));
        title.setTextSize(15);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        MaxHeightScrollView sc = new MaxHeightScrollView(act);
        sc.setMaxHeightPx((int) (act.getResources().getDisplayMetrics().heightPixels * 0.52f));
        LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        sc.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final List<android.widget.CheckBox> boxes = new ArrayList<>();
        final List<String> shown = new ArrayList<>();
        String lastGroup = null;
        for (final String[] row : SubAgentRunner.TOOL_CATALOG) {
            if (forSubAgent && SubAgentRunner.asstOnly(row[0])) continue;
            shown.add(row[0]);
            if (!row[2].equals(lastGroup)) {
                lastGroup = row[2];
                TextView gl = new TextView(act);
                gl.setText(lastGroup);
                gl.setTextColor(act.col(R.color.accent));
                gl.setTextSize(11);
                gl.setTypeface(null, Typeface.BOLD);
                gl.setPadding(act.dp(2), act.dp(10), 0, act.dp(4));
                list.addView(gl);
            }
            LinearLayout card = new LinearLayout(act);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable cb = new GradientDrawable();
            cb.setColor(act.col(R.color.bg));
            cb.setCornerRadius(act.dp(10));
            cb.setStroke(act.dp(1), sel.contains(row[0])
                    ? act.col(R.color.accent) : act.col(R.color.border));
            card.setBackground(cb);
            card.setPadding(act.dp(10), act.dp(7), act.dp(10), act.dp(7));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.setMargins(0, 0, 0, act.dp(5));
            card.setLayoutParams(cp);

            final android.widget.CheckBox cbx = new android.widget.CheckBox(act);
            cbx.setText(row[1]);
            cbx.setTextColor(act.col(R.color.text));
            cbx.setTextSize(12.5f);
            cbx.setChecked(sel.contains(row[0]));
            cbx.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            boxes.add(cbx);

            card.setOnClickListener(v -> cbx.setChecked(!cbx.isChecked()));
            card.addView(cbx);
            list.addView(card);
        }

        for (int i = 0; i < boxes.size(); i++) {
            final android.widget.CheckBox cb = boxes.get(i);
            final String name = shown.get(i);
            final View parent = (View) cb.getParent();
            Runnable sync = () -> {
                GradientDrawable bg2 = new GradientDrawable();
                bg2.setColor(act.col(R.color.bg));
                bg2.setCornerRadius(act.dp(10));
                bg2.setStroke(act.dp(1), sel.contains(name)
                        ? act.col(R.color.accent) : act.col(R.color.border));
                parent.setBackground(bg2);
            };
            cb.setOnCheckedChangeListener((b, checked) -> {
                if (checked) {
                    if (!sel.contains(name)) sel.add(name);
                } else {
                    sel.remove(name);
                }
                sync.run();
            });
        }

        LinearLayout row = new LinearLayout(act);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(0, act.dp(10), 0, 0);
        row.setLayoutParams(rlp);
        TextView onlyWeb = mkSheetBtn("只要联网");
        onlyWeb.setOnClickListener(v -> {
            final List<String> web = SubAgentStore.subList(SubAgentStore.DEFAULT_TOOLS);
            sel.clear();
            for (int i = 0; i < boxes.size(); i++) {
                boolean want = web.contains(shown.get(i));
                if (boxes.get(i).isChecked() != want) boxes.get(i).setChecked(want);
                else if (want) sel.add(shown.get(i));
            }
        });
        row.addView(onlyWeb);
        row.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView done = mkSheetBtn("完成");
        done.setTextColor(act.col(R.color.onAccent));
        done.setBackgroundResource(R.drawable.btn_primary);
        done.setPadding(act.dp(18), act.dp(6), act.dp(18), act.dp(6));
        done.setOnClickListener(v -> {
            d.dismiss();
            if (onDone != null) onDone.run();
        });
        row.addView(done);
        root.addView(row);

        d.setContentView(root);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(0));
            WindowManager.LayoutParams lp = d.getWindow().getAttributes();
            lp.width = act.dialogWidthPx(0.92F);
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            d.getWindow().setAttributes(lp);
        }
        d.show();
    }

    static String toolNamesShort(List<String> names) {
        if (names == null || names.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String name : names) {
            if (n >= 3) { sb.append(" 等"); break; }
            if (sb.length() > 0) sb.append("、");
            sb.append(SubAgentRunner.toolLabel(name));
            n++;
        }
        return sb.toString();
    }

    static int parseCtx(String raw) {
        if (raw == null) return SubAgentRunner.DEFAULT_CTX_TOKENS;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') sb.append(c);
        }
        if (sb.length() == 0) return SubAgentRunner.DEFAULT_CTX_TOKENS;
        if (sb.length() > 9) return 2000000;
        try {
            long v = Long.parseLong(sb.toString());
            if (v <= 0) return SubAgentRunner.DEFAULT_CTX_TOKENS;
            if (v > 2000000) return 2000000;
            return (int) v;
        } catch (Exception e) {
            return SubAgentRunner.DEFAULT_CTX_TOKENS;
        }
    }

    private TextView mkFieldLabel(String text) {
        TextView v = new TextView(act);
        v.setText(text);
        v.setTextColor(act.col(R.color.muted));
        v.setTextSize(11);
        v.setPadding(0, act.dp(9), 0, act.dp(2));
        return v;
    }

    private EditText mkEditor(String value, String hint, int minLines, boolean wrap) {
        EditText e = new EditText(act);
        e.setText(value == null ? "" : value);
        e.setSelection(e.getText().length());
        e.setHint(hint);
        e.setHintTextColor(act.col(R.color.muted));
        e.setTextColor(act.col(R.color.text));
        e.setTextSize(13);
        e.setBackgroundResource(R.drawable.bg_edit);
        e.setPadding(act.dp(10), act.dp(7), act.dp(10), act.dp(7));
        e.setMinLines(minLines);
        if (wrap) {
            e.setGravity(Gravity.TOP | Gravity.START);
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            e.setMaxLines(6);
        } else {
            e.setSingleLine(true);
            e.setInputType(InputType.TYPE_CLASS_TEXT);
        }
        return e;
    }

    private void showSubAgentLog(final String agentId, final SubAgentStore.Sub s) {
        final Dialog d = new Dialog(act);
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(act.col(R.color.surface));
        bg.setCornerRadius(act.dp(18));
        bg.setStroke(act.dp(1), act.col(R.color.border));
        root.setBackground(bg);
        root.setPadding(act.dp(16), act.dp(12), act.dp(16), act.dp(10));

        LinearLayout head = new LinearLayout(act);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(act);
        t.setText("📒 " + s.name);
        t.setTextColor(act.col(R.color.text));
        t.setTextSize(15);
        t.setTypeface(null, Typeface.BOLD);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView clear = mkSheetBtn("清空");
        clear.setTextColor(act.col(R.color.danger));
        clear.setOnClickListener(v -> act.showStyledConfirm("清空记录",
                "「" + s.name + "」的全部任务记录会被删掉，不可恢复。", "清空", true, () -> {
                    SubAgentLog.clear(act, s.id);
                    act.toast("已清空");
                    d.dismiss();
                    showSubAgentLog(agentId, s);
                }));
        head.addView(clear);
        root.addView(head);

        final LinearLayout listBox = new LinearLayout(act);
        listBox.setOrientation(LinearLayout.VERTICAL);
        MaxHeightScrollView sc = new MaxHeightScrollView(act);
        sc.setMaxHeightPx((int) (act.getResources().getDisplayMetrics().heightPixels * 0.68f)
                - act.dp(150));
        sc.addView(listBox);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMargins(0, act.dp(8), 0, 0);
        root.addView(sc, slp);

        buildSubAgentLog(listBox, s);

        LinearLayout row = new LinearLayout(act);
        row.setGravity(Gravity.END);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(0, act.dp(8), 0, 0);
        row.setLayoutParams(rlp);
        TextView close = mkSheetBtn("关闭");
        close.setTextColor(act.col(R.color.muted));
        close.setBackgroundResource(R.drawable.btn_ghost);
        close.setOnClickListener(v -> d.dismiss());
        row.addView(close);
        root.addView(row);

        d.setContentView(root);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(0));
            WindowManager.LayoutParams lp = d.getWindow().getAttributes();
            lp.width = act.dialogWidthPx(0.92F);
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            d.getWindow().setAttributes(lp);
        }
        d.show();
    }

    private void buildSubAgentLog(LinearLayout list, SubAgentStore.Sub s) {
        list.removeAllViews();
        List<SubAgentLog.Run> runs = SubAgentLog.list(act, s.id);
        if (runs.isEmpty()) {
            TextView empty = new TextView(act);
            empty.setText("还没有");
            empty.setTextColor(act.col(R.color.faint));
            empty.setTextSize(12);
            empty.setPadding(act.dp(4), act.dp(6), act.dp(4), act.dp(10));
            list.addView(empty);
            return;
        }
        for (SubAgentLog.Run r : runs) {
            LinearLayout card = new LinearLayout(act);
            card.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable cb = new GradientDrawable();
            cb.setColor(act.col(R.color.bg));
            cb.setCornerRadius(act.dp(12));
            cb.setStroke(act.dp(1), r.ok() ? act.col(R.color.border) : act.col(R.color.danger));
            card.setBackground(cb);
            card.setPadding(act.dp(10), act.dp(8), act.dp(10), act.dp(8));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.setMargins(0, 0, 0, act.dp(6));
            card.setLayoutParams(cp);

            LinearLayout head = new LinearLayout(act);
            head.setGravity(Gravity.CENTER_VERTICAL);

            final TextView arrow = new TextView(act);
            arrow.setText("▸");
            arrow.setTextColor(act.col(R.color.muted));
            arrow.setTextSize(11);
            arrow.setPadding(0, 0, act.dp(5), 0);
            head.addView(arrow);

            String mark = "ok".equals(r.outcome) ? "✅" : ("part".equals(r.outcome) ? "⚠️" : "❌");
            TextView title = new TextView(act);
            title.setText(mark + " " + briefText(r.task, 30));
            title.setTextColor(act.col(R.color.text));
            title.setTextSize(12);
            title.setTypeface(null, Typeface.BOLD);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            head.addView(title, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView when = new TextView(act);
            when.setText(SubAgentLog.fmtTime(r.at)
                    + (SubAgentLog.fmtCost(r.costMs).isEmpty() ? "" : " · " + SubAgentLog.fmtCost(r.costMs)));
            when.setTextColor(act.col(R.color.faint));
            when.setTextSize(10);
            when.setPadding(act.dp(6), 0, 0, 0);
            head.addView(when);
            card.addView(head);

            final LinearLayout detail = new LinearLayout(act);
            detail.setOrientation(LinearLayout.VERTICAL);
            detail.setVisibility(View.GONE);

            TextView taskLine = logLine("委托方", callerName(r.caller));
            detail.addView(taskLine);
            detail.addView(logLine("任务", r.task));
            detail.addView(logLine("结果", "ok".equals(r.outcome) ? "成功"
                    : ("part".equals(r.outcome) ? "部分完成" : "失败")));
            if (notBlank(r.summary)) detail.addView(logLine("交付", r.summary));
            for (int i = 0; i < r.steps.size(); i++) {
                SubAgentLog.Node n = r.steps.get(i);
                detail.addView(logLine("节点 " + (i + 1) + " · " + SubAgentLog.typeName(n.type),
                        joinNonBlank(n.judge, n.action, n.result, n.remark)));
            }
            if (notBlank(r.lesson)) detail.addView(logLine("教训", r.lesson));
            card.addView(detail);

            card.setOnClickListener(v -> {
                boolean open = detail.getVisibility() != View.VISIBLE;
                detail.setVisibility(open ? View.VISIBLE : View.GONE);
                arrow.setText(open ? "▾" : "▸");
            });

            list.addView(card);
        }
    }

    private TextView logLine(String label, String body) {
        TextView v = new TextView(act);
        v.setText(label + "：" + (body == null ? "" : body));
        v.setTextColor(act.col(R.color.muted));
        v.setTextSize(11);
        v.setPadding(act.dp(14), act.dp(3), 0, 0);
        v.setLineSpacing(act.dp(2), 1f);
        return v;
    }

    private String callerName(String id) {
        if (id == null || id.trim().isEmpty()) return "（未知）";
        for (String[] a : AGENTS) if (a[0].equals(id)) return a[1];
        return id;
    }

    private static String joinNonBlank(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(p.trim());
        }
        return sb.toString();
    }

    private static String briefText(String s, int max) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    public void show() { dlg.show(); }
}
