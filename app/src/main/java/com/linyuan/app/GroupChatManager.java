package com.linyuan.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Build.VERSION;
import android.provider.MediaStore.Downloads;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.View.MeasureSpec;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import com.linyuan.app.R.color;
import com.linyuan.app.R.drawable;
import com.linyuan.app.R.id;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;
import org.json.JSONArray;
import org.json.JSONObject;

public class GroupChatManager {
   private final Activity activity;
   private final AppDatabase db;

   private volatile AppSettings settings;
   private LinearLayout gmPanel;
   private FrameLayout content;
   private TextView titleText;
   private Button membersButton;
   private Button settingsButton;
   private Button adminButton;
   private Button backButton;
   private final Handler ui = new Handler(Looper.getMainLooper());

   private static final long LIVE_REPAINT_MS = 80L;
   private long liveLastRepaintAt = 0L;
   private boolean liveRepaintPosted = false;

   private void requestLiveRepaint() {
      long now = System.currentTimeMillis();
      long since = now - this.liveLastRepaintAt;
      if (since >= LIVE_REPAINT_MS) {
         this.liveLastRepaintAt = now;
         this.ui.post(this::repaintLiveFromBuffers);
         return;
      }
      if (this.liveRepaintPosted) return;
      this.liveRepaintPosted = true;
      this.ui.postDelayed(() -> {
         this.liveRepaintPosted = false;
         this.liveLastRepaintAt = System.currentTimeMillis();
         this.repaintLiveFromBuffers();
      }, LIVE_REPAINT_MS - since);
   }

   private final ExecutorService executor = Executors.newFixedThreadPool(5);

   private final ExecutorService discussExecutor =
           Executors.newFixedThreadPool(MAX_PARALLEL_ROOMS + 1);

   private volatile boolean shutDown = false;

   private void bgExecute(ExecutorService pool, Runnable task) {
      if (task == null || pool == null || this.shutDown) return;
      if (pool.isShutdown() || pool.isTerminated()) return;
      try {
         pool.execute(task);
      } catch (java.util.concurrent.RejectedExecutionException e) {
         android.util.Log.i("VC_GM", "后台任务在 shutdown 后到达，已丢弃: " + e.getMessage());
      }
   }

   private final java.util.concurrent.ConcurrentHashMap<Long, Integer> pendingStartTries =
           new java.util.concurrent.ConcurrentHashMap<>();

   private static final class RoomRun {

      final AtomicBoolean stop = new AtomicBoolean(false);

      volatile boolean awaitingInquiry = false;

      final Set<Integer> announcedPhases = java.util.Collections.synchronizedSet(new HashSet<Integer>());

      volatile long lastTurnEndAt = 0L;

      int allSilentRounds = 0;

      volatile boolean minorityPhase = false;

      volatile int minorityStartRound = 0;

      final Set<Long> pulledMemberIds = java.util.Collections.synchronizedSet(new HashSet<Long>());
   }

   private static final class Convergence {
      int active;
      int confirmed;
      final List<String> notConfirmed = new ArrayList<>();

      int notConfirmedButSilent2;

      boolean done() {
         return active > 0 && confirmed == active;
      }

      boolean doneByDefault() {
         if (active <= 0 || confirmed >= active) return false;
         if (confirmed * 100 < active * 90) return false;
         return notConfirmedButSilent2 >= notConfirmed.size();
      }

      int phase(int budgetUsedPct) {

         if (budgetUsedPct >= 95) return 3;
         if (active <= 0 || confirmed == 0) return 0;
         if (confirmed * 100 < active * 50) return 1;
         if (confirmed * 100 < active * 80) return 2;
         return 3;
      }

      static String phaseName(int p) {
         switch (p) {
            case 1: return "深化";
            case 2: return "收敛";
            case 3: return "定稿";
            default: return "探索";
         }
      }

      static String phaseHint(int p) {
         switch (p) {
            case 1: return "已经有角色认可当前方案。继续细化执行细节、对方案挑错；你自己没有遗留异议时再确认。";
            case 2: return "过半数已确认。只处理遗留争议；认同就用 confirm_convergence 工具确认，不要开新话题。";
            case 3: return "只剩少数未确认或预算紧张。直接确认（confirm_convergence），或指出必须改的地方；不再展开。";
            default: return "把做什么、怎么做讨论清楚：充分发散、互相质疑，不要急着收口。";
         }
      }
   }

   private Convergence computeConvergence(long roomId, RoomRun run) {
      Convergence c = new Convergence();
      for (AppDatabase.GmMember m : this.db.listGmMembers(roomId)) {
         if (!m.active) continue;
         c.active++;
         if (m.confirmedRound > 0) {
            c.confirmed++;
         } else {
            c.notConfirmed.add(m.name);

            if (this.db.countGmTrailingThoughts(roomId, m.id) >= 2) c.notConfirmedButSilent2++;
         }
      }
      return c;
   }

   private static final String[] CHALLENGER_KEYS = {
      "质疑", "核查", "可行性", "逻辑架构", "测试", "审查", "审计", "安全", "合规", "法务", "法律", "防呆", "苏格拉底", "塔勒布"
   };

   private static final String[] SUMMARIZER_KEYS = {
      "总协调", "统筹", "技术文档", "全局参谋", "风险定级", "决策分析"
   };

   private static int roleGroupOf(String name) {
      if (name == null) return 0;
      for (String k : SUMMARIZER_KEYS) if (name.contains(k)) return 2;
      for (String k : CHALLENGER_KEYS) if (name.contains(k)) return 1;
      return 0;
   }

   private List<AppDatabase.GmMember> orderMembersForRound(List<AppDatabase.GmMember> members) {
      List<AppDatabase.GmMember> out = new ArrayList<>(members);
      Collections.sort(out, (a, b) -> {
         boolean aSet = a.sortOrder > 0, bSet = b.sortOrder > 0;
         if (aSet && bSet) return Integer.compare(a.sortOrder, b.sortOrder);
         if (aSet != bSet) return aSet ? -1 : 1;
         int ga = roleGroupOf(a.name), gb = roleGroupOf(b.name);
         if (ga != gb) return ga - gb;
         return Long.compare(a.id, b.id);
      });
      return out;
   }

   private String joinNames(List<String> names, int max) {
      if (names == null || names.isEmpty()) return "";
      StringBuilder sb = new StringBuilder();
      int n = 0;
      for (String s : names) {
         if (n >= max) break;
         if (sb.length() > 0) sb.append("、");
         sb.append(s);
         n++;
      }
      if (names.size() > max) sb.append("…等 ").append(names.size()).append(" 人");
      return sb.toString();
   }

   private List<AppDatabase.GmMember> computeMinority(long roomId, List<AppDatabase.GmMember> orderedMembers) {
      List<AppDatabase.GmMember> active = new ArrayList<>();
      for (AppDatabase.GmMember m : orderedMembers) {
         if (m.active) active.add(m);
      }
      if (active.size() < 2) return new ArrayList<>();
      Convergence c = this.computeConvergence(roomId, this.roomRuns.get(roomId));
      if (c.confirmed * 100 <= active.size() * MINORITY_MAJORITY_PERCENT) return new ArrayList<>();
      List<AppDatabase.GmMember> left = new ArrayList<>();
      for (AppDatabase.GmMember m : active) {
         if (c.notConfirmed.indexOf(m.name) >= 0) left.add(m);
      }
      return left;
   }

   private String buildPullableNames(long roomId, RoomRun run) {
      if (run == null || !run.minorityPhase) return null;
      List<AppDatabase.GmMember> all = this.orderMembersForRound(this.db.listGmMembers(roomId));
      StringBuilder sb = new StringBuilder();
      Convergence c = this.computeConvergence(roomId, run);
      for (AppDatabase.GmMember m : all) {
         if (!m.active) continue;
         if (c.notConfirmed.indexOf(m.name) >= 0) continue;
         if (run.pulledMemberIds.contains(m.id)) continue;
         sb.append(m.name).append("\n");
      }
      return sb.length() == 0 ? null : sb.toString();
   }

   private final java.util.concurrent.ConcurrentHashMap<Long, RoomRun> roomRuns = new java.util.concurrent.ConcurrentHashMap<>();

   private static final int MAX_PARALLEL_ROOMS = 20;

   private static final int INSTANT_QUESTION_FROM_ROUND = 3;

   private static final int MINORITY_SPLIT_FROM_ROUND = 3;

   private static final int MINORITY_MAJORITY_PERCENT = 50;

   private static final int MAX_AUTO_RETRY = 3;

   private static final int MAX_PLAIN_RETRY = 2;

   private static final long PLAIN_RETRY_WAIT_MS = 30000L;

   private static final long ADMIN_TAKEOVER_WAIT_MS = 600000L;

   private static final int MAX_ADMIN_TAKEOVER = 2;

   private static final java.util.Random RANDOM = new java.util.Random();

   private android.os.PowerManager.WakeLock discussWakeLock;

   private void acquireDiscussWakeLock() {
      try {
         if (this.discussWakeLock != null && this.discussWakeLock.isHeld()) return;
         android.os.PowerManager pm = (android.os.PowerManager)
                 this.activity.getSystemService(android.content.Context.POWER_SERVICE);
         if (pm == null) return;
         if (this.discussWakeLock == null) {
            this.discussWakeLock = pm.newWakeLock(
                    android.os.PowerManager.PARTIAL_WAKE_LOCK, "linyuan:group_discuss");
            this.discussWakeLock.setReferenceCounted(false);
         }

         this.discussWakeLock.acquire(6 * 3600_000L);
         android.util.Log.i("VC_GM", "已申请唤醒锁（熄屏后讨论继续）");

         GroupDiscussService.start(this.activity.getApplicationContext());
      } catch (Exception e) {
         android.util.Log.w("VC_GM", "申请唤醒锁失败: " + e.getMessage());
      }
   }

   private final java.util.Set<String> bgWorkActive =
           java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

   private void beginBgWork(String who) {
      boolean first;
      synchronized (this.bgWorkActive) {
         first = this.bgWorkActive.isEmpty();
         this.bgWorkActive.add(who);
      }
      if (first) this.acquireDiscussWakeLock();
   }

   private void endBgWork(String who) {
      boolean none;
      synchronized (this.bgWorkActive) {
         this.bgWorkActive.remove(who);
         none = this.bgWorkActive.isEmpty();
      }
      if (none) this.releaseDiscussWakeLock();
   }

   private void releaseDiscussWakeLock() {
      try {
         if (this.discussWakeLock != null && this.discussWakeLock.isHeld()) {
            this.discussWakeLock.release();
            android.util.Log.i("VC_GM", "已释放唤醒锁");
         }

         GroupDiscussService.stop(this.activity.getApplicationContext());
      } catch (Exception e) {
         android.util.Log.w("VC_GM", "释放唤醒锁失败: " + e.getMessage());
      }
   }

   private RoomRun runOf(long roomId) {
      return this.roomRuns.computeIfAbsent(roomId, k -> new RoomRun());
   }

   private void restoreMinorityIfNeeded(long roomId, RoomRun run, int startRound) {
      if (run == null || run.minorityPhase) return;
      if (startRound < MINORITY_SPLIT_FROM_ROUND) return;
      List<AppDatabase.GmMember> orderedAll = this.orderMembersForRound(this.db.listGmMembers(roomId));
      List<AppDatabase.GmMember> minority = this.computeMinority(roomId, orderedAll);
      if (minority.isEmpty()) return;
      run.minorityPhase = true;
      run.minorityStartRound = startRound;
      android.util.Log.i("VC_GM", "续跑恢复少数派续议 room=" + roomId
              + " round=" + startRound + " minority=" + minority.size());
   }

   private boolean isRoomRunning(long roomId) {
      return roomId > 0L && this.roomRuns.containsKey(roomId);
   }

   private static final int ROOM_ST_NONE = 0;
   private static final int ROOM_ST_RUNNING = 1;
   private static final int ROOM_ST_ALL_SUBMITTED = 2;
   private static final int ROOM_ST_NEED_ANSWER = 3;
   private static final int ROOM_ST_DELIVERED = 4;

   private int roomStatusOf(long roomId) {

      try {
         AppDatabase.GmRoom r = this.db.getGmRoom(roomId);
         if (r != null && "done".equals(r.exportState)) return ROOM_ST_DELIVERED;
      } catch (Exception ignored) { }
      boolean running = this.isRoomRunning(roomId);

      int pendingQ = 0;
      try { pendingQ = this.db.countGmUnansweredQuestions(roomId); } catch (Exception ignored) { }
      if (pendingQ > 0) return ROOM_ST_NEED_ANSWER;
      if (!running) return ROOM_ST_NONE;

      if (this.isRoomDelivered(roomId)) return ROOM_ST_ALL_SUBMITTED;

      return ROOM_ST_RUNNING;
   }

   private boolean isRoomDelivered(long roomId) {
      Convergence c = this.computeConvergence(roomId, this.roomRuns.get(roomId));
      return c.active > 0 && c.confirmed == c.active;
   }

   private final java.util.List<Runnable> roomTypeRunnables = new java.util.ArrayList<>();

   private void stopRoomTypeAnimations() {
      for (Runnable r : this.roomTypeRunnables) this.ui.removeCallbacks(r);
      this.roomTypeRunnables.clear();
   }

   private void startRoomTypewriter(TextView tv, String fullText) {
      final int[] idx = new int[]{0};
      final Runnable tick = new Runnable() {
         public void run() {
            if (GroupChatManager.this.currentPage != 0 || !GroupChatManager.this.isVisible()) return;
            if (idx[0] <= fullText.length()) {
               tv.setText(fullText.substring(0, idx[0]));
               idx[0]++;
               GroupChatManager.this.ui.postDelayed(this, 180L);
            } else {
               idx[0] = 0;
               GroupChatManager.this.ui.postDelayed(this, 1000L);
            }
         }
      };
      this.roomTypeRunnables.add(tick);
      this.ui.post(tick);
   }

   private boolean isRoomStopped(long roomId) {
      RoomRun r = this.roomRuns.get(roomId);
      return r == null || r.stop.get();
   }

   private volatile boolean running = false;

   private volatile long runningRoomId = 0L;
   private long currentRoomId = 0L;
   private final Set<Long> injectedMsgIds = java.util.Collections.synchronizedSet(new HashSet<Long>());
   private Runnable onExitToSettings = () -> {
   };
   private static final int PAGE_LIST = 0;
   private static final int PAGE_ROOM = 1;
   private static final int PAGE_MEMBERS = 2;
   private static final int PAGE_SETTINGS = 3;
   private static final int PAGE_PROVIDERS = 4;
   private static final int PAGE_PLANS = 5;
   private int pageBeforeSub = 1;
   private int currentPage = 0;

   private FrameLayout renderTarget;
   private boolean inHub = false;
   private int hubTab = 3;
   private FrameLayout hubBody;
   private Button[] hubTabButtons;
   private static final String[][] DEFAULT_EXPERTS = ExpertLibrary.EXPERTS;

   private static final String[][] GROUP_TEMPLATES = TemplateLibrary.TEMPLATES;

   private static final String DEV_GLOBAL_RULES = "【本群目的】用户说出想做什么软件/系统/小程序，各角色围绕它讨论出一份**可直接交给开发执行的实施方案**。\n【讨论纪律】\n1. 按阶段推进：先定「做什么、给谁用、核心功能」→ 再定「技术方案与架构」→ 再打磨「界面与交互设计」→ 最后做「安全、合规、上线与验收」。\n2. 不要跳步：方案还没定就讨论资质、备案、安全加固，是浪费时间——后面还会改，之前的结论会作废。\n3. 每个角色发言必须**具体到别人能照着做**：说清楚是什么、放哪里、长什么样、点了会怎样、为什么这么选。\n4. 指出问题时给可执行的修改方案，不要只说「不行」；不同意见要摆出取舍（成本/工期/风险）。\n5. 涉及外部事实（平台规则、资质要求、价格、技术版本）一律**联网核实**，不凭记忆编造。\n6. 人性化与可用性是硬要求：布局别拥挤、对比度要够（严禁白字白底）、危险操作必须有二次确认、返回层级要清晰。\n7. **分清「讨论」与「提问」两种输出，不要混淆**：\n   · **讨论的受众是其他专家，不是用户** —— 讨论本身才是这个群的核心价值，靠的是你们相互挑错、相互补全、把方案打磨到能落地。**绝不能因为「用户可能不看」就写简短、省细节**；恰恰相反，用户不看过程，所以方案更必须在讨论里做扎实（最终交给他的是讨论的产物，不是过程）。\n   · **提问的受众是用户** —— 他不会逐条读讨论（产出太快太多，看不过来），所以凡是需要他拍板的事（选方向、定边界、确认预算/资质、选平台…）**必须调用 ask_user 工具**，否则他不知道你们在等他决定，讨论会空转；不值得他决策的别去问，不要打扰。\n   · ⚠️ **只有调用了 ask_user 工具才算提问**：在发言正文里写「我要提一个问题」「这个问题需要用户拍板」这类话，系统不会把它变成提问，同伴却会以为你在等用户回答，全群白白空等。要问就调工具；不打算调工具就别在正文里写这种话。\n   · 能列选项的一律给选项，且选项要**具体到他能不看讨论就做决定**（如「按月 29.9 元 / 按次 0.5 元」比「方案 A / 方案 B」有用得多）。\n【最终产出】讨论充分后，由「技术文档师」汇总一份实施方案，包含：需求与范围、技术选型与架构、数据与接口设计、界面与交互明细（逐页面逐按钮）、阶段计划、风险与对策、验收标准。";

   private static final String SCENE_GLOBAL_RULES =
           "【讨论纪律】\n"
         + "1. 每个角色只贡献**你自己专业范围内**的判断，不要越界替别人下结论，也不要综合所有人的视角写大而全的东西。\n"
         + "2. 发言要具体到能照着做：给出依据、数字、边界条件；指出问题时同时给可执行的修改方案。\n"
         + "3. 不要重复别人已说过的内容；只在你有实质补充、质疑或需要回应时发言。\n"
         + "4. 涉及外部事实（政策、价格、平台规则、最新数据）一律**联网核实**，不凭记忆编造。\n"
         + "5. 需要用户拍板的事（选方向、定边界、确认预算/资质）走 ask_user 工具提问，不要只在讨论里说。";

   private static final String PERSONAL_GLOBAL_RULES =
           "【本群目的】用户说出想做什么软件/系统/小程序，各角色围绕它讨论出一份**可直接交给开发执行的实施方案**。\n"

         + "【本群阵容】这是用户自己选定的一套核心班底，全部参与讨论。\n"
         + "【讨论纪律】\n"
         + "1. 按阶段推进：先定「做什么、给谁用、核心功能」→ 再定「技术方案与架构」→ 再打磨「界面与交互设计」→ 最后做「安全、上线与验收」。\n"
         + "2. 每个角色发言必须**具体到别人能照着做**：说清楚是什么、放哪里、长什么样、点了会怎样、为什么这么选。\n"
         + "3. 指出问题时给可执行的修改方案，不要只说「不行」；不同意见要摆出取舍（成本/工期/风险）。\n"
         + "4. 涉及外部事实（平台规则、价格、技术版本）一律**联网核实**，不凭记忆编造。\n"
         + "5. 人性化与可用性是硬要求：布局别拥挤、对比度要够（严禁白字白底）、危险操作必须有二次确认、返回层级要清晰。\n"
         + "【最终产出】讨论充分后，由「技术文档师」汇总一份实施方案，包含：需求与范围、技术选型与架构、"
         + "数据与接口设计、界面与交互明细（逐页面逐按钮）、阶段计划、风险与对策、验收标准。";
   private ScrollView chatScroll;
   private LinearLayout chatList;
   private Uri lastExportUri;
   private String lastExportName;
   private AiClient.ToolHandler webToolHandler;
   private AttachListener attachListener;
   private LinearLayout liveMemberCard;
   private TextView liveMemberThought;
   private TextView liveMemberSpeech;
   private String liveMemberName;
   private static final int RENDER_MAX_MSGS = 60;

   private int renderMsgLimit = RENDER_MAX_MSGS;

   private int exportThinkLevel = 0;

   private final StringBuilder refineLiveBuf = new StringBuilder();
   private volatile long refineRoomId = 0L;
   private Dialog refineLiveDialog = null;
   private TextView refineLiveBody = null;
   private ScrollView refineLiveScroll = null;

   private TextView refineJumpBtn = null;

   private final AtomicBoolean exportCancel = new AtomicBoolean(false);
   private volatile boolean exportRunning = false;
   private volatile long exportLiveRoomId = 0L;
   private TextView chatJumpArrow;
   private volatile boolean userReadingHistory = false;

   private volatile boolean chatUserTouching = false;

   private volatile boolean refineUserTouching = false;

   private volatile boolean refineFollowPaused = false;

   private volatile boolean rebuildingChat = false;

   private static final class RoomLiveBuf {
      final StringBuilder content = new StringBuilder();
      final StringBuilder thought = new StringBuilder();
      final StringBuilder tool = new StringBuilder();

      String memberName;
   }

   private final java.util.HashMap<Long, RoomLiveBuf> liveBufs = new java.util.HashMap<>();

   private RoomLiveBuf bufOf(long roomId) {
      RoomLiveBuf b = this.liveBufs.get(roomId);
      if (b == null) {
         b = new RoomLiveBuf();
         this.liveBufs.put(roomId, b);
      }
      return b;
   }

   private static void clearBuf(RoomLiveBuf b) {
      b.content.setLength(0);
      b.thought.setLength(0);
      b.tool.setLength(0);
   }

   private void resetRoomBuf(long roomId) {
      if (roomId <= 0L) this.liveBufs.clear();
      else this.liveBufs.remove(roomId);
   }

   private volatile long viewRoomId = 0L;

   private Button confirmButton;

   private final java.util.Set<Long> expandedMsgIds = java.util.Collections.synchronizedSet(new java.util.HashSet<Long>());

   private final java.util.Set<String> expandedSections = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

   private static String sectionKey(long msgId, String section) {
      return msgId + "|" + section;
   }

   private static final long ADMIN_SECTION_BASE = 1000000000L;
   private TextView liveMemberThoughtTag;
   private TextView liveMemberToolTag;
   private TextView liveMemberSpeechTag;
   private TextView liveMemberTool;
   private View liveMemberThoughtWrapper;
   private View liveMemberToolWrapper;
   private View liveMemberSpeechWrapper;
   private Runnable providerEditorDone = null;

   public GroupChatManager(Activity activity, AppDatabase db, AppSettings settings) {
      this.activity = activity;
      this.db = db;
      this.settings = settings;
   }

   public void setOnExitToSettings(Runnable r) {
      this.onExitToSettings = r == null ? () -> {
      } : r;
   }

   public void setSettings(AppSettings s) {
      if (s != null) this.settings = s;
   }

   public void bind() {
      this.gmPanel = (LinearLayout)this.activity.findViewById(id.gmPanel);
      this.content = (FrameLayout)this.activity.findViewById(id.gmContent);
      this.renderTarget = this.content;
      this.titleText = (TextView)this.activity.findViewById(id.gmTitleText);

      this.membersButton = (Button)this.activity.findViewById(id.gmMembersButton);
      this.adminButton = (Button)this.activity.findViewById(id.gmAdminButton);
      this.settingsButton = (Button)this.activity.findViewById(id.gmSettingsButton);
      this.backButton = (Button)this.activity.findViewById(id.gmBackButton);
      if (this.backButton != null) {
         this.backButton.setOnClickListener((v) -> this.handleBack());
      }

      if (this.confirmButton != null) {
         this.confirmButton.setOnClickListener((v) -> this.showConfirmList());
      }

      if (this.membersButton != null) {
         this.membersButton.setOnClickListener((v) -> {
            this.pageBeforeSub = 1;
            this.showSettingsHub(0);
         });
      }

      if (this.adminButton != null) {
         this.adminButton.setOnClickListener((v) -> this.showAdminPage());
      }

      if (this.settingsButton != null) {
         this.settingsButton.setOnClickListener((v) -> {
            this.pageBeforeSub = 1;
            this.showSettingsHub(3);
         });
      }

   }

   public void show() {
      if (this.gmPanel != null) {
         this.gmPanel.setVisibility(0);
         this.gmPanelVisible = true;
         this.setTopBarVisible(false);
         this.showRoomList();
      }
   }

   private void setTopBarVisible(boolean visible) {
      View bar = this.activity.findViewById(id.settingsBackBar);
      if (bar != null) {
         bar.setVisibility(visible ? 0 : 8);
      }

   }

   private void syncTopBarTitle(String text) {
   }

   public boolean handleBack() {
      if (!this.isVisible()) {
         return false;
      } else {
         switch (this.currentPage) {
            case 1:
               this.showRoomList();
               return true;
            case 2:
            case 3:
               if (this.pageBeforeSub == 1 && this.currentRoomId > 0L) {
                  this.openRoom(this.currentRoomId);
               } else {
                  this.showRoomList();
               }

               return true;
            case 4:
               this.showRoomList();
               return true;
            case 5:
               if (this.pageBeforeSub == 3 && this.currentRoomId > 0L) {
                  this.showRoomSettings();
               } else if (this.currentRoomId > 0L) {
                  this.openRoom(this.currentRoomId);
               } else {
                  this.showRoomList();
               }

               return true;
            case 6:

               this.showRoomList();
               return true;
            case 7:

               if (this.currentRoomId > 0L) {
                  this.openRoom(this.currentRoomId);
               } else {
                  this.showRoomList();
               }
               return true;
            default:
               this.hide();
               this.onExitToSettings.run();
               return true;
         }
      }
   }

   public void hide() {
      if (this.gmPanel != null) {
         this.gmPanel.setVisibility(8);
         this.gmPanelVisible = false;
      }

      this.setTopBarVisible(true);
   }

   private volatile boolean gmPanelVisible = false;

   public boolean isVisible() {
      return this.gmPanelVisible;
   }

   private void showToast(String s) {
      if (Looper.myLooper() != Looper.getMainLooper()) {
         this.ui.post(() -> Toast.makeText(this.activity, s, 0).show());
      } else {
         Toast.makeText(this.activity, s, 0).show();
      }
   }

   private static volatile boolean archiveBackfilled = false;

   private void backfillArchiveOnce() {
      if (archiveBackfilled) return;
      archiveBackfilled = true;
      this.bgExecute(this.executor, () -> {
         try {
            this.db.backfillGmQaArchiveFromInquiries();
         } catch (Exception e) {
            android.util.Log.w("VC_GM", "补档用户信息库失败: " + e.getMessage());
         }
      });
   }

   private void showRoomList() {
      try { PhoneAwareness.noteInAppStep(this.activity, "群聊列表"); } catch (Throwable ignored) { }
      this.stopUsageTicker();
      this.stopRoomTypeAnimations();
      this.inHub = false;
      this.renderTarget = this.content;

      this.currentPage = 0;
      this.currentRoomId = 0L;
      this.viewRoomId = 0L;
      if (this.titleText != null) {
         this.titleText.setText("群聊");
      }

      this.syncTopBarTitle("群聊");
      this.setHeaderButtons(false);
      this.content.removeAllViews();

      this.detachLiveMemberCard();

      this.backfillArchiveOnce();
      LinearLayout root = new LinearLayout(this.activity);
      root.setOrientation(1);
      root.setPadding(this.dp(14), 0, this.dp(14), 0);
      this.content.addView(root, new FrameLayout.LayoutParams(-1, -1));

      try { MaskedFieldMenu.attachTree(root); } catch (Throwable ignored) { }
      LinearLayout newRow = new LinearLayout(this.activity);
      newRow.setOrientation(0);
      LinearLayout.LayoutParams nrLp = new LinearLayout.LayoutParams(-1, -2);
      nrLp.setMargins(0, 0, 0, this.dp(10));

      Button newBtn = this.compactRowButton("＋ 空白群聊", true, (v) -> this.promptNewRoom());
      Button tplBtn = this.compactRowButton("\ud83d\udccb 模板", false, (v) -> this.showTemplatePicker());
      Button infoBtn = this.compactRowButton("\ud83d\udccc 基础信息", false, (v) -> this.showBasicInfoManager());
      newRow.addView(newBtn, new LinearLayout.LayoutParams(0, -2, 1.0F));
      LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1.0F);
      tl.setMarginStart(this.dp(6));
      newRow.addView(tplBtn, tl);
      LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(0, -2, 1.0F);
      il.setMarginStart(this.dp(6));
      newRow.addView(infoBtn, il);
      nrLp.setMargins(0, this.dp(2), 0, this.dp(12));
      root.addView(newRow, nrLp);
      TextView provHint = new TextView(this.activity);
      provHint.setVisibility(android.view.View.GONE);
      provHint.setTextColor(this.col(color.muted));
      provHint.setTextSize(11.0F);
      provHint.setLineSpacing((float)this.dp(2), 1.0F);
      LinearLayout.LayoutParams phlp = new LinearLayout.LayoutParams(-1, -2);
      phlp.setMargins(0, this.dp(2), 0, this.dp(12));
      root.addView(provHint, phlp);
      List<AppDatabase.GmRoom> rooms = this.db.listGmRooms();
      if (rooms.isEmpty()) {
         TextView empty = new TextView(this.activity);
         empty.setText("还没有群聊。");
         empty.setTextColor(this.col(color.muted));
         empty.setTextSize(13.0F);
         empty.setLineSpacing((float)this.dp(3), 1.0F);
         root.addView(empty);
      } else {
         ScrollView sv = new ScrollView(this.activity);
         LinearLayout list = new LinearLayout(this.activity);
         list.setOrientation(1);
         sv.addView(list);
         root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1.0F));

         for(AppDatabase.GmRoom r : rooms) {
            int memberCount = this.db.listGmMembers(r.id).size();

            int msgCount = this.db.countGmSpeeches(r.id);
            LinearLayout card = new LinearLayout(this.activity);
            card.setOrientation(1);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(this.col(color.surface));
            bg.setCornerRadius((float)this.dp(14));
            bg.setStroke(this.dp(1), this.col(color.border));
            card.setBackground(bg);
            card.setPadding(this.dp(14), this.dp(12), this.dp(14), this.dp(12));

            LinearLayout titleRow = new LinearLayout(this.activity);
            titleRow.setOrientation(0);
            titleRow.setGravity(16);
            TextView t = new TextView(this.activity);
            t.setText(r.title != null && !r.title.isEmpty() ? r.title : "群聊");
            t.setTextColor(this.col(color.text));
            t.setTextSize(15.0F);
            t.setTypeface((Typeface)null, 1);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            titleRow.addView(t, new LinearLayout.LayoutParams(0, -2, 1.0F));

            int adminUnread = 0;
            try { adminUnread = this.db.countUnreadGmAdminMessages(r.id); } catch (Exception ignored) { }
            if (adminUnread > 0) {
               TextView badge = new TextView(this.activity);
               badge.setText(String.valueOf(adminUnread));
               badge.setTextColor(0xFFFFFFFF);
               badge.setTextSize(10.0F);
               badge.setTypeface((Typeface) null, 1);
               badge.setGravity(17);
               GradientDrawable bgbg = new GradientDrawable();
               bgbg.setColor(0xFFC93B3B);
               bgbg.setCornerRadius((float) this.dp(9));
               badge.setBackground(bgbg);
               badge.setMinWidth(this.dp(18));
               badge.setMinHeight(this.dp(18));
               badge.setPadding(this.dp(5), 0, this.dp(5), 0);
               LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, this.dp(18));
               blp.setMarginStart(this.dp(6));
               titleRow.addView(badge, blp);
               this.cardAdminBadges.put(r.id, badge);
            } else {
               this.cardAdminBadges.remove(r.id);
            }

            int st = this.roomStatusOf(r.id);
            if (st != ROOM_ST_NONE) {
               TextView status = new TextView(this.activity);
               status.setTextSize(12.0F);
               status.setTypeface((Typeface)null, 1);

               status.setTextColor(st == ROOM_ST_DELIVERED ? 0xFF3FB950
                       : (st == ROOM_ST_NEED_ANSWER ? 0xFFD98324 : this.col(color.accent)));
               LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(-2, -2);
               stLp.setMarginStart(this.dp(8));
               titleRow.addView(status, stLp);
               String stText;
               switch (st) {
                  case ROOM_ST_DELIVERED:      stText = "已交付"; break;
                  case ROOM_ST_NEED_ANSWER:    stText = "需提问..."; break;
                  case ROOM_ST_ALL_SUBMITTED:  stText = "已确认..."; break;
                  default:                     stText = "讨论中..."; break;
               }
               if (st == ROOM_ST_DELIVERED) {
                  status.setText(stText);
               } else {
                  this.startRoomTypewriter(status, stText);
               }
            }
            card.addView(titleRow);
            TextView sub = new TextView(this.activity);

            int pendingQ = 0;
            try { pendingQ = this.db.countGmUnansweredQuestions(r.id); } catch (Exception ignored) { }
            long roomTok = 0L;
            try { roomTok = this.db.sumGmRoomTokens(r.id); } catch (Exception ignored) { }
            StringBuilder subSb = new StringBuilder();
            subSb.append("\ud83d\udc65 ").append(memberCount).append(" 位成员 · \ud83d\udcac ")
                 .append(msgCount).append(" 条消息");
            if (pendingQ > 0) {
               subSb.append(" · \u2753 提问 ").append(pendingQ);
            }
            if (roomTok > 0L) {
               subSb.append(" · \ud83d\udd25 消耗 ").append(this.fmtTokens(roomTok));
            }
            sub.setText(subSb.toString());
            sub.setTextColor(this.col(color.muted));
            sub.setTextSize(12.0F);
            sub.setPadding(0, this.dp(4), 0, 0);
            card.addView(sub);

            LinearLayout btnRow = new LinearLayout(this.activity);
            btnRow.setOrientation(0);
            btnRow.setGravity(8388613);
            LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(-1, -2);
            brLp.setMargins(0, this.dp(10), 0, 0);
            btnRow.setLayoutParams(brLp);
            final int cardBtnH = this.dp(28);

            int unreadCite = 0;
            try { unreadCite = this.db.countUnreadGmCitations(r.id); } catch (Exception ignored) { }
            Button cite = this.smallCardButton(
                    unreadCite > 0 ? "\ud83d\udcce 引用 " + unreadCite : "\ud83d\udcce 引用",
                    drawable.btn_ghost, unreadCite > 0 ? color.danger : color.accent, 11.0F);
            final long citeRoomId = r.id;
            cite.setOnClickListener((v) -> this.showCitationsDialog(citeRoomId));
            btnRow.addView(cite, new LinearLayout.LayoutParams(0, cardBtnH, 1.0F));

            int unreadFb = 0;
            try { unreadFb = this.db.countUnreadGmFeedback(r.id); } catch (Exception ignored) { }
            Button fb = this.smallCardButton(unreadFb > 0 ? "\ud83d\udcac 反馈 " + unreadFb : "\ud83d\udcac 反馈",
                    drawable.btn_ghost, unreadFb > 0 ? color.danger : color.accent, 11.0F);
            final long fbRoomId = r.id;
            fb.setOnClickListener((v) -> this.showFeedbackInbox(fbRoomId));
            LinearLayout.LayoutParams fblp = new LinearLayout.LayoutParams(0, cardBtnH, 1.0F);
            fblp.setMarginStart(this.dp(5));
            btnRow.addView(fb, fblp);
            Button del = this.smallCardButton("删除", drawable.btn_ghost, color.danger, 11.0F);
            del.setOnClickListener((v) -> this.confirmDeleteRoom(r));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, cardBtnH, 1.0F);
            blp.setMarginStart(this.dp(5));
            btnRow.addView(del, blp);
            Button dup = this.smallCardButton("复制", drawable.btn_ghost, color.accent, 11.0F);
            dup.setOnClickListener((v) -> this.promptDuplicateRoom(r));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0, cardBtnH, 1.0F);
            dlp.setMarginStart(this.dp(5));
            btnRow.addView(dup, dlp);
            Button open = this.smallCardButton("进入", drawable.btn_primary, color.onAccent, 11.0F);
            open.setOnClickListener((v) -> this.openRoom(r.id));
            LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(0, cardBtnH, 1.0F);
            olp.setMarginStart(this.dp(5));
            btnRow.addView(open, olp);
            card.addView(btnRow);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
            clp.setMargins(0, this.dp(8), 0, 0);
            list.addView(card, clp);
         }
      }
   }

   private void promptNewRoom() {
      EditText input = new EditText(this.activity);
      input.setHint("群聊名称，如：AI 创业讨论组");
      input.setBackgroundResource(drawable.bg_edit);
      input.setPadding(this.dp(12), this.dp(12), this.dp(12), this.dp(12));
      input.setTextColor(this.col(color.text));
      input.setHintTextColor(this.col(color.muted));
      LinearLayout box = new LinearLayout(this.activity);
      box.setOrientation(1);
      box.setPadding(this.dp(18), this.dp(18), this.dp(18), this.dp(12));
      GradientDrawable bg = new GradientDrawable();
      bg.setColor(this.col(color.surface));
      bg.setCornerRadius((float)this.dp(20));
      bg.setStroke(this.dp(1), this.col(color.border));
      box.setBackground(bg);
      TextView h = new TextView(this.activity);
      h.setText("新建空白群聊");
      h.setTextColor(this.col(color.text));
      h.setTextSize(16.0F);
      h.setTypeface((Typeface)null, 1);
      box.addView(h);
      TextView hSub = new TextView(this.activity);
      hSub.setVisibility(android.view.View.GONE);
      hSub.setTextColor(this.col(color.muted));
      hSub.setTextSize(12.0F);
      LinearLayout.LayoutParams hsLp = new LinearLayout.LayoutParams(-1, -2);
      hsLp.setMargins(0, this.dp(6), 0, 0);
      box.addView(hSub, hsLp);
      LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-1, -2);
      ilp.setMargins(0, this.dp(12), 0, 0);
      box.addView(input, ilp);
      LinearLayout row = new LinearLayout(this.activity);
      row.setGravity(8388613);
      LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
      rlp.setMargins(0, this.dp(14), 0, 0);
      row.setLayoutParams(rlp);
      Button cancel = new Button(this.activity);
      cancel.setText("取消");
      cancel.setTextColor(this.col(color.muted));
      cancel.setTextSize(13.0F);
      cancel.setAllCaps(false);
      cancel.setBackgroundResource(drawable.btn_ghost);
      cancel.setMinHeight(0);
      row.addView(cancel);
      Button ok = new Button(this.activity);
      ok.setText("创建");
      ok.setTextColor(this.col(color.onAccent));
      ok.setTextSize(13.0F);
      ok.setAllCaps(false);
      ok.setBackgroundResource(drawable.btn_primary);
      ok.setMinHeight(0);
      LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-2, -2);
      olp.setMarginStart(this.dp(8));
      row.addView(ok, olp);
      box.addView(row);
      Dialog dlg = new Dialog(this.activity);
      dlg.setContentView(box);
      if (dlg.getWindow() != null) {
         dlg.getWindow().setBackgroundDrawable(new ColorDrawable(0));
         WindowManager.LayoutParams lp = dlg.getWindow().getAttributes();
         lp.width = (int)((float)this.activity.getResources().getDisplayMetrics().widthPixels * 0.88F);
         dlg.getWindow().setAttributes(lp);
      }

      cancel.setOnClickListener((v) -> dlg.dismiss());
      ok.setOnClickListener((v) -> {
         String name = input.getText().toString().trim();
         if (name.isEmpty()) {
            name = "群聊";
         }

         long rid = this.db.insertGmRoom(name, 1000000, 0, 30, "");
         dlg.dismiss();
         if (rid <= 0L) { this.showToast("创建失败，请重试"); return; }
         this.openRoom(rid);
         this.showToast("已创建空白群聊，勾选要加入的角色（也可直接关掉，稍后在成员页再加）");
         this.showRoleLibraryPicker();
      });
      dlg.show();
   }

   private void createDevRoom() {
      this.createDevRoom(false);
   }

   private void createDevRoom(boolean solo) {
      String title = solo ? "个人小项目" : "开发全流程";
      String roomPrompt = solo
         ? "【本群目的】用户是**一个人（或带一个 AI 助手）做东西**：自用小工具、小程序、脚本、个人 App、副业项目。\n"
           + "各角色围绕它讨论出一份**一个人真能做完、做完还能自己维护**的实施方案。\n"
           + "【本群纪律（与「开发全流程」不同，请严格遵守）】\n"
           + "1. **默认假定没有团队、没有预算、没有运维、用户很少**（初期可能只有自己用）。\n"
           + "2. **一律从最小可用版开始**：先问「不做哪些也能跑」，而不是把功能排满。能手工做的先不自动化。\n"
           + "3. **反对过度设计**：能单机/本地/单文件解决的不上服务器，能一个数据库解决的不上中间件；\n"
           + "   多一个组件就多一处会挂、多一笔月费、多一份你一个人要修的负担。\n"
           + "4. **成本是硬约束**：服务器与模型谁的钱都是用户自己掏，每个方案要说清月花多少；没收入就先用免费/最低档。\n"
           + "5. **不要讨论这些**（除非用户主动问）：高并发集群、微服务、多人协作流程、企业资质与备案、"
           + "   融资与商业化模型、专业运维监控体系。真要正式推向市场时，另开「开发全流程」群。\n"
           + "6. 每个角色发言必须**具体到能照着做**：放哪个文件、哪个页面、点了会怎样、为什么这么选。\n"
           + "7. 涉及平台规则、价格、技术版本一律**联网核实**，不凭记忆编造。\n"
           + "【最终产出】讨论充分后由「技术文档师」汇总一份实施方案，必须包含："
           + "最小可用版到底做哪几个功能、技术选型与理由、数据怎么存与怎么备份、"
           + "界面与操作明细、**分几步做（每步做完能验证什么）**、月成本测算、风险按高/中/低排序。"
         : "【本群目的】用户说出想做什么软件/系统/小程序，各角色围绕它讨论出一份**可直接交给开发执行的实施方案**。\n【讨论纪律】\n1. 按阶段推进：先定「做什么、给谁用、核心功能」→ 再定「技术方案与架构」→ 再打磨「界面与交互设计」→ 最后做「安全、合规、上线与验收」。\n2. 不要跳步：方案还没定就讨论资质、备案、安全加固，是浪费时间——后面还会改，之前的结论会作废。\n3. 每个角色发言必须**具体到别人能照着做**：说清楚是什么、放哪里、长什么样、点了会怎样、为什么这么选。\n4. 指出问题时给可执行的修改方案，不要只说「不行」；不同意见要摆出取舍（成本/工期/风险）。\n5. 涉及外部事实（平台规则、资质要求、价格、技术版本）一律**联网核实**，不凭记忆编造。\n6. 人性化与可用性是硬要求：布局别拥挤、对比度要够（严禁白字白底）、危险操作必须有二次确认、返回层级要清晰。\n【最终产出】讨论充分后，由「技术文档师」汇总一份实施方案，包含：需求与范围、技术选型与架构、数据与接口设计、界面与交互明细（逐页面逐按钮）、阶段计划、风险与对策、验收标准。";
      long rid = this.db.insertGmRoom(title, 1000000, 0, 30, roomPrompt);
      if (rid <= 0L) {
         this.showToast("创建失败，请重试");
      } else {
         int core = 0;
         int later = 0;
         java.util.List<String[]> roles = solo
               ? DevRoleLibrary.soloRoles()
               : java.util.Arrays.asList(DevRoleLibrary.DEV_ROLES);

         for(String[] r : roles) {
            String asset = r[3];
            int phase = 0;

            try {
               phase = Integer.parseInt(r[5]);
            } catch (Exception var16) {
            }

            String archive = DevRoleLibrary.loadRole(this.activity, asset);
            String prompt;
            if (archive.isEmpty()) {
               Log.w("VC_DEVROLE", "角色档案为空: " + asset);
               prompt = r[2];
            } else {
               prompt = DevRoleLibrary.buildDevPrompt(r[0], DevRoleLibrary.phaseName(phase), archive);
            }

            long mid = this.db.insertGmMember(rid, r[0], r[1], r[2], prompt, this.settings.chatModel, this.settings.chatBaseUrl, this.settings.chatApiKey, true, 0, true, phase);

            this.db.setGmMemberActiveBySystem(mid, phase == 0);
            if (phase == 0) {
               ++core;
            } else {
               ++later;
            }
         }

         this.showToast("已创建「" + title + "」：前期 " + core + " 位参与讨论，" + later + " 位配在中期/收尾阶段（当前待命，需要时你在成员里打开）");
         this.openRoom(rid);
      }
   }

   private void createDevRoomPlatform(boolean mobile) {
      String title = mobile ? "手机App小程序" : "电脑服务器端";
      java.util.List<String[]> roles = mobile ? DevRoleLibrary.mobileRoles() : DevRoleLibrary.pcRoles();
      String roomPrompt = mobile
         ? "【本群目的】用户要做**手机 App 或微信小程序**，各角色围绕它讨论出一份**可直接交给开发执行的实施方案**。\n【讨论纪律】\n"
           + "1. 一切围绕手机端：屏幕就那么大、手指操作、要装到手机上或在微信里跑，别按电脑软件的思路设计。\n"
           + "2. 每个角色发言必须具体到别人能照着做：是什么、放哪个页面、长什么样、点了会怎样、为什么这么选。\n"
           + "3. 界面与交互是重点：布局别拥挤、对比度要够（严禁白字白底）、点击区域够大、危险操作二次确认、返回层级清晰。\n"
           + "4. 涉及平台规则（应用市场/App Store/微信小程序审核、资质备案、价格、SDK 版本）一律联网核实，不凭记忆编造。\n"
           + "5. 后端只做支撑手机端所必需的接口与数据，不铺开服务器端的重型架构（那是「电脑/服务器端组」的事）。\n"
           + "【最终产出】由「技术文档师」汇总：核心功能、技术选型与理由、界面与交互明细（逐页面逐按钮）、数据怎么存与备份、上架/审核要点、分几步做、月成本、风险分级。"
         : "【本群目的】用户要做**电脑软件 / 后端系统 / 服务器端**（电脑与服务器一起考虑），各角色围绕它讨论出一份**可直接交给开发执行的实施方案**。\n【讨论纪律】\n"
           + "1. 一切围绕电脑/服务器端：桌面软件或浏览器界面、后端服务、数据库、部署与运维，别按手机 App 的思路设计。\n"
           + "2. 每个角色发言必须具体到别人能照着做：是什么、放哪、功能链路怎么走（请求从哪来→经过什么→到哪去→返回什么）、为什么这么选。\n"
           + "3. 后端与数据是重点：接口设计、数据模型与索引、并发与容量、缓存与限流、部署架构、监控告警、备份与回滚。\n"
           + "4. 有管理/运营需求时，配齐管理后台与运营后台（权限、看板、审核、操作日志）。\n"
           + "5. 涉及外部事实（技术版本、云服务价格、合规备案）一律联网核实，不凭记忆编造。\n"
           + "【最终产出】由「技术文档师」汇总：需求与范围、技术选型与架构、数据与接口设计、后台功能明细、部署与运维方案、容量与性能、风险与对策、月成本、验收标准。";
      long rid = this.db.insertGmRoom(title, 1000000, 0, 30, roomPrompt);
      if (rid <= 0L) {
         this.showToast("创建失败，请重试");
         return;
      }
      int n = 0;
      for (String[] r : roles) {
         String archive = DevRoleLibrary.loadRole(this.activity, r[3]);
         String prompt = archive.isEmpty()
               ? r[2]
               : DevRoleLibrary.buildDevPrompt(r[0], DevRoleLibrary.phaseName(0), archive);

         long mid = this.db.insertGmMember(rid, r[0], r[1], r[2], prompt, this.settings.chatModel, this.settings.chatBaseUrl, this.settings.chatApiKey, true, 0, true, 0);
         this.db.setGmMemberActiveBySystem(mid, true);
         ++n;
      }
      this.showToast("已创建「" + title + "」：" + n + " 位角色全部参与讨论（谁发言由各角色自己判断）");
      this.openRoom(rid);
   }

   private void createPersonalRoom() {
      String title = "个人自用";
      long rid = this.db.insertGmRoom(title, 1000000, 0, 30, PERSONAL_GLOBAL_RULES);
      if (rid <= 0L) {
         this.showToast("创建失败，请重试");
         return;
      }

      try { this.db.setGmRoomIntervalRange(rid, 30, 60); } catch (Exception ignored) { }

      int n = 0;
      for (String[] r : DevRoleLibrary.personalRoles()) {
         String archive = DevRoleLibrary.loadRole(this.activity, r[3]);
         String prompt = archive.isEmpty()
               ? r[2]
               : DevRoleLibrary.buildDevPrompt(r[0], DevRoleLibrary.phaseName(0), archive);

         long mid = this.db.insertGmMember(rid, r[0], r[1], r[2], prompt,
                 this.settings.chatModel, this.settings.chatBaseUrl, this.settings.chatApiKey, true, 0, true, 0);
         this.db.setGmMemberActiveBySystem(mid, true);
         ++n;
      }

      int want = DevRoleLibrary.personalRoleCount();
      if (n < want) {
         this.showToast("已创建「" + title + "」，但有 " + (want - n) + " 位角色在库里没找到（可能已改名）");
      } else {
         this.showToast("已创建「" + title + "」：" + n + " 位角色全部参与讨论，发言间隔 30~60 秒");
      }
      this.openRoom(rid);
   }

   private void announcePhaseIfNeeded(long roomId, int round) {
      AppDatabase.GmRoom room = this.db.getGmRoom(roomId);
      int budget = room == null || room.contextLimit <= 0 ? 1000000 : room.contextLimit;
      int usedPct = (int) Math.min(100L, this.estimateRoomTokens(roomId) * 100L / Math.max(1, budget));
      RoomRun run = this.runOf(roomId);
      Convergence conv = this.computeConvergence(roomId, run);
      int wantPhase = conv.phase(usedPct);
      if (wantPhase == 0) return;

      if (run.announcedPhases.contains(wantPhase)) return;

      int memberPhaseNow = wantPhase == 1 ? 1 : 2;
      List<AppDatabase.GmMember> members = this.db.listGmMembers(roomId);

      List<String> pending = new ArrayList();

      for (AppDatabase.GmMember m : members) {
         if (m.manualStandby) continue;
         if (m.phase != 0 && m.phase <= memberPhaseNow && !m.active) pending.add(m.name);
      }

      String stageName = Convergence.phaseName(wantPhase);
      String marker = "进入「" + stageName + "」阶段";

      try {
         for (AppDatabase.GmMessage gm : this.db.listGmMessages(roomId, 400)) {
            if ("system".equals(gm.kind) && gm.content != null && gm.content.contains(marker)) {
               run.announcedPhases.add(wantPhase);
               return;
            }
         }
      } catch (Exception ignored) { }
      run.announcedPhases.add(wantPhase);

      String progress = "已确认 " + conv.confirmed + "/" + conv.active;
      if (!pending.isEmpty()) {
         this.db.insertGmMessage(roomId, 0L, "系统", "\ud83d\udccc",
                 "\u23f8 已进入「" + stageName + "」阶段（" + progress + "）。以下角色配了本阶段、但当前是**待命**状态："
                         + this.joinNames(pending, 12) + "\n（是否参与讨论由你决定：在「成员」里把需要的角色设为「参与讨论」；不设就继续待命。）",
                 false, "system", 0);
      } else {
         this.db.insertGmMessage(roomId, 0L, "系统", "\ud83d\udccc",
                 "\u23f8 已进入「" + stageName + "」阶段（" + progress + "）。",
                 false, "system", 0);
      }
   }

   private void createRoomFromTemplate(String[] tpl) {
      if ("__DEV_PERSONAL__".equals(tpl[3])) {

         this.createPersonalRoom();
      } else if ("__DEV_FULL__".equals(tpl[3])) {
         this.createDevRoom(false);
      } else if ("__DEV_SOLO__".equals(tpl[3])) {

         this.createDevRoom(true);
      } else if ("__DEV_MOBILE__".equals(tpl[3])) {
         this.createDevRoomPlatform(true);
      } else if ("__DEV_PC__".equals(tpl[3])) {
         this.createDevRoomPlatform(false);
      } else {
         String title = tpl[0];
         String roleNames = tpl[3];

         long rid = this.db.insertGmRoom(title, 1000000, 0, 30, SCENE_GLOBAL_RULES);
         if (rid <= 0L) {
            this.showToast("创建失败，请重试");
         } else {

            Map<String, Integer> phaseByName = new HashMap<>();
            for(String tok : roleNames.split(",")) {
               String t = tok.trim();
               if (t.isEmpty()) continue;
               int ph = 0;
               int at = t.indexOf('@');
               if (at > 0) {
                  try { ph = Integer.parseInt(t.substring(at + 1).trim()); } catch (Exception ignored) { }
                  t = t.substring(0, at).trim();
               }
               phaseByName.put(t, ph);
            }

            int added = 0;
            int coreCnt = 0;

            for(String[] e : DEFAULT_EXPERTS) {
               if (phaseByName.containsKey(e[0])) {
                  int ph = phaseByName.get(e[0]);
                  long mid = this.db.insertGmMember(rid, e[0], e[1], e[2], e[3], this.settings.chatModel, this.settings.chatBaseUrl, this.settings.chatApiKey, true, 0, true, ph);
                  this.db.setGmMemberActiveBySystem(mid, ph == 0);
                  if (ph == 0) ++coreCnt;
                  ++added;
               }
            }

            for(String[] p : PersonaLibrary.CELEBRITY_PERSONAS) {
               if (phaseByName.containsKey(p[0])) {
                  int ph = phaseByName.get(p[0]);
                  long mid = this.db.insertGmMember(rid, p[0], p[1], p[2], this.buildCelebrityPrompt(p), this.settings.chatModel, this.settings.chatBaseUrl, this.settings.chatApiKey, true, 0, true, ph);
                  this.db.setGmMemberActiveBySystem(mid, ph == 0);
                  if (ph == 0) ++coreCnt;
                  ++added;
               }
            }

            this.showToast("已按「" + title + "」模板创建，含 " + added + " 位角色（前期 " + coreCnt + " 位参与讨论，其余配在中期/收尾阶段、当前待命）");
            this.openRoom(rid);
         }
      }
   }

   private void showTemplatePicker() {
      LinearLayout card = new LinearLayout(this.activity);
      card.setOrientation(1);
      GradientDrawable bg = new GradientDrawable();
      bg.setColor(this.col(color.surface));
      bg.setCornerRadius((float)this.dp(16));
      bg.setStroke(this.dp(1), this.col(color.border));
      card.setBackground(bg);
      card.setPadding(this.dp(16), this.dp(14), this.dp(16), this.dp(10));
      TextView h = new TextView(this.activity);
      h.setText("群聊模板");
      h.setTextColor(this.col(color.text));
      h.setTextSize(15.0F);
      h.setTypeface((Typeface)null, 1);
      card.addView(h);
      TextView tip = new TextView(this.activity);
      tip.setText("选一个场景，自动建群并配好对应角色（模板角色默认全部参与讨论，可再调整）");
      tip.setTextColor(this.col(color.muted));
      tip.setTextSize(11.0F);
      tip.setPadding(0, this.dp(6), 0, this.dp(4));
      card.addView(tip);
      ScrollView sv = new ScrollView(this.activity);
      LinearLayout list = new LinearLayout(this.activity);
      list.setOrientation(1);
      sv.addView(list);
      LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 0, 1.0F);
      slp.setMargins(0, this.dp(8), 0, 0);
      card.addView(sv, slp);
      Dialog dlg = new Dialog(this.activity);

      for(String[] tpl : GROUP_TEMPLATES) {
         LinearLayout row = new LinearLayout(this.activity);
         row.setOrientation(1);
         GradientDrawable rowBg = new GradientDrawable();
         rowBg.setColor(this.col(color.surfaceInput));
         rowBg.setCornerRadius((float)this.dp(12));
         rowBg.setStroke(this.dp(1), this.col(color.border));
         row.setBackground(rowBg);
         row.setPadding(this.dp(12), this.dp(10), this.dp(12), this.dp(10));
         TextView t = new TextView(this.activity);
         t.setText(tpl[1] + " " + tpl[0]);
         t.setTextColor(this.col(color.text));
         t.setTextSize(14.0F);
         t.setTypeface((Typeface)null, 1);
         row.addView(t);
         TextView sub = new TextView(this.activity);
         sub.setText(tpl[2]);
         sub.setTextColor(this.col(color.muted));
         sub.setTextSize(11.0F);
         sub.setLineSpacing((float)this.dp(2), 1.0F);
         sub.setPadding(0, this.dp(3), 0, 0);
         row.addView(sub);
         String rolesText;
         if (tpl[3] == null || !tpl[3].startsWith("__DEV_")) {
            String[] toks = tpl[3].split(",");
            int core = 0;
            int mid = 0;
            int fin = 0;
            StringBuilder names = new StringBuilder();

            for(String tok : toks) {
               String tk = tok.trim();
               if (tk.isEmpty()) continue;
               int ph = 0;
               int at = tk.indexOf('@');
               if (at > 0) {
                  try { ph = Integer.parseInt(tk.substring(at + 1).trim()); } catch (Exception ignored) { }
                  tk = tk.substring(0, at).trim();
               }
               if (ph == 0) { ++core; } else if (ph == 1) { ++mid; } else { ++fin; }
               if (names.length() > 0) { names.append(" · "); }
               names.append(tk);
            }

            rolesText = "\ud83d\udc65 " + toks.length + " 位（前期 " + core + " / 中期 " + mid + " / 收尾 " + fin + "）：" + names;
         } else {

            java.util.List<String[]> devList = "__DEV_PERSONAL__".equals(tpl[3]) ? DevRoleLibrary.personalRoles()
                  : "__DEV_SOLO__".equals(tpl[3]) ? DevRoleLibrary.soloRoles()
                  : "__DEV_MOBILE__".equals(tpl[3]) ? DevRoleLibrary.mobileRoles()
                  : "__DEV_PC__".equals(tpl[3]) ? DevRoleLibrary.pcRoles()
                  : java.util.Arrays.asList(DevRoleLibrary.DEV_ROLES);
            boolean flatTpl = "__DEV_MOBILE__".equals(tpl[3]) || "__DEV_PC__".equals(tpl[3])
                    || "__DEV_PERSONAL__".equals(tpl[3]);
            int core = 0;
            int mid = 0;
            int fin = 0;
            StringBuilder names = new StringBuilder();

            for(String[] dr : devList) {
               int ph = 0;

               try {
                  ph = Integer.parseInt(dr[5]);
               } catch (Exception var29) {
               }

               if (ph == 0) {
                  ++core;
               } else if (ph == 1) {
                  ++mid;
               } else {
                  ++fin;
               }

               if (names.length() > 0) {
                  names.append(" · ");
               }

               names.append(dr[0]);
            }

            rolesText = flatTpl
                  ? "\ud83d\udc65 " + devList.size() + " 位全部参与讨论：" + names
                  : "\ud83d\udc65 " + devList.size() + " 位（全程 " + core + " / 中期 " + mid + " / 收尾 " + fin + " 分阶段进场）：" + names;
         }

         TextView roles = new TextView(this.activity);
         roles.setText(rolesText);
         roles.setTextColor(this.col(color.accent));
         roles.setTextSize(10.0F);
         roles.setLineSpacing((float)this.dp(2), 1.0F);
         roles.setPadding(0, this.dp(5), 0, 0);
         row.addView(roles);
         row.setOnClickListener((v) -> {
            dlg.dismiss();
            this.createRoomFromTemplate(tpl);
         });
         LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
         rlp.setMargins(0, 0, 0, this.dp(8));
         list.addView(row, rlp);
      }

      java.util.List<AppDatabase.GmCustomTemplate> customTpls = this.db.listCustomTemplates();
      if (!customTpls.isEmpty()) {
         TextView ch = new TextView(this.activity);
         ch.setText("── 我自己保存的模板 ──");
         ch.setTextColor(this.col(color.muted));
         ch.setTextSize(11.0F);
         ch.setPadding(0, this.dp(8), 0, this.dp(4));
         list.addView(ch);
         for (AppDatabase.GmCustomTemplate ct : customTpls) {
            LinearLayout crow = new LinearLayout(this.activity);
            crow.setOrientation(1);
            GradientDrawable crBg = new GradientDrawable();
            crBg.setColor(this.col(color.surfaceInput));
            crBg.setCornerRadius((float)this.dp(12));
            crBg.setStroke(this.dp(1), this.col(color.border));
            crow.setBackground(crBg);
            crow.setPadding(this.dp(12), this.dp(10), this.dp(12), this.dp(10));
            TextView ct1 = new TextView(this.activity);
            ct1.setText((ct.avatar == null || ct.avatar.isEmpty() ? "\ud83e\udde9" : ct.avatar) + " " + ct.title);
            ct1.setTextColor(this.col(color.text));
            ct1.setTextSize(14.0F);
            ct1.setTypeface((Typeface)null, 1);
            crow.addView(ct1);
            int cnt = 0;
            try { cnt = new org.json.JSONArray(ct.membersJson == null ? "[]" : ct.membersJson).length(); } catch (Exception ignored) { }
            TextView csub = new TextView(this.activity);
            csub.setText("\ud83d\udc65 " + cnt + " 个角色 · 我自己保存的模板（点卡片建群）");
            csub.setTextColor(this.col(color.accent));
            csub.setTextSize(10.0F);
            csub.setPadding(0, this.dp(4), 0, 0);
            crow.addView(csub);
            LinearLayout ops = new LinearLayout(this.activity);
            ops.setGravity(8388613);
            LinearLayout.LayoutParams opLp = new LinearLayout.LayoutParams(-1, -2);
            opLp.setMargins(0, this.dp(8), 0, 0);
            ops.setLayoutParams(opLp);
            Button del = this.ghostButton("\ud83d\uddd1 删除");
            del.setTextColor(this.col(color.danger));
            del.setOnClickListener((v) -> {
               StyledDialog.confirm(this.activity, "删除模板",
                       "确定删除自定义模板「" + ct.title + "」吗？",
                       "删除", true,
                       () -> {
                           this.db.deleteCustomTemplate(ct.id);
                           this.showToast("已删除模板「" + ct.title + "」");
                           dlg.dismiss();
                           this.showTemplatePicker();
                       });
            });
            ops.addView(del);
            crow.addView(ops);
            crow.setOnClickListener((v) -> {
               dlg.dismiss();
               this.createRoomFromCustomTemplate(ct);
            });
            LinearLayout.LayoutParams crlp = new LinearLayout.LayoutParams(-1, -2);
            crlp.setMargins(0, 0, 0, this.dp(8));
            list.addView(crow, crlp);
         }
      }
      TextView howto = new TextView(this.activity);
      howto.setVisibility(android.view.View.GONE);
      howto.setTextColor(this.col(color.muted));
      howto.setTextSize(10.0F);
      howto.setPadding(0, this.dp(2), 0, this.dp(2));
      list.addView(howto);

      Button cancel = this.ghostButton("取消");
      LinearLayout btnRow = new LinearLayout(this.activity);
      btnRow.setGravity(8388613);
      LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(-1, -2);
      brLp.setMargins(0, this.dp(6), 0, 0);
      btnRow.setLayoutParams(brLp);
      btnRow.addView(cancel);
      card.addView(btnRow);
      dlg.setContentView(card);
      if (dlg.getWindow() != null) {
         dlg.getWindow().setBackgroundDrawable(new ColorDrawable(0));
         WindowManager.LayoutParams lp = dlg.getWindow().getAttributes();
         lp.width = (int)((float)this.activity.getResources().getDisplayMetrics().widthPixels * 0.92F);
         lp.height = (int)((float)this.activity.getResources().getDisplayMetrics().heightPixels * 0.78F);
         dlg.getWindow().setAttributes(lp);
      }

      cancel.setOnClickListener((v) -> dlg.dismiss());
      dlg.show();
   }

   private void saveRoomAsTemplate() {
      if (this.currentRoomId <= 0L) { this.showToast("先进入一个群聊，再存为模板"); return; }
      final List<AppDatabase.GmMember> members = this.db.listGmMembers(this.currentRoomId);
      if (members.isEmpty()) { this.showToast("这个群还没有角色，先添加角色再存"); return; }
      AppDatabase.GmRoom room = this.db.getGmRoom(this.currentRoomId);
      final String roomPrompt = (room == null || room.globalPrompt == null) ? "" : room.globalPrompt;
      final EditText et = new EditText(this.activity);
      et.setText((room == null || room.title == null) ? "我的模板" : room.title);
      et.setSelectAllOnFocus(true);
      et.setSingleLine(true);
      et.setTextColor(this.col(color.text));
      et.setHintTextColor(this.col(color.muted));
      et.setTextSize(15.0F);
      et.setBackgroundResource(drawable.bg_edit);
      et.setPadding(this.dp(12), this.dp(12), this.dp(12), this.dp(12));

      LinearLayout box = new LinearLayout(this.activity);
      box.setOrientation(1);
      box.setPadding(this.dp(20), this.dp(20), this.dp(20), this.dp(14));
      GradientDrawable boxBg = new GradientDrawable();
      boxBg.setColor(this.col(color.surface));
      boxBg.setCornerRadius((float)this.dp(20));
      boxBg.setStroke(this.dp(1), this.col(color.border));
      box.setBackground(boxBg);

      TextView h = new TextView(this.activity);
      h.setText("\ud83d\udcbe 存为自定义模板");
      h.setTextColor(this.col(color.text));
      h.setTextSize(17.0F);
      h.setTypeface((Typeface)null, 1);
      box.addView(h);

      TextView sub = new TextView(this.activity);
      sub.setVisibility(android.view.View.GONE);
      sub.setTextColor(this.col(color.muted));
      sub.setTextSize(12.5F);
      LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
      subLp.setMargins(0, this.dp(6), 0, 0);
      box.addView(sub, subLp);

      TextView chip = new TextView(this.activity);
      chip.setText("\ud83e\udde9 含当前 " + members.size() + " 位角色");
      chip.setTextColor(this.col(color.accent));
      chip.setTextSize(12.0F);
      chip.setPadding(this.dp(10), this.dp(5), this.dp(10), this.dp(5));
      GradientDrawable chipBg = new GradientDrawable();
      chipBg.setColor(0);
      chipBg.setCornerRadius((float)this.dp(20));
      chipBg.setStroke(this.dp(1), this.col(color.accent));
      chip.setBackground(chipBg);
      LinearLayout chipWrap = new LinearLayout(this.activity);
      chipWrap.setOrientation(0);
      chipWrap.addView(chip);
      LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(-1, -2);
      chipLp.setMargins(0, this.dp(12), 0, 0);
      box.addView(chipWrap, chipLp);

      LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-1, -2);
      ilp.setMargins(0, this.dp(12), 0, 0);
      box.addView(et, ilp);

      LinearLayout row = new LinearLayout(this.activity);
      row.setGravity(8388613);
      LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
      rlp.setMargins(0, this.dp(16), 0, 0);
      row.setLayoutParams(rlp);
      Button cancel = new Button(this.activity);
      cancel.setText("取消");
      cancel.setTextColor(this.col(color.muted));
      cancel.setTextSize(13.0F);
      cancel.setAllCaps(false);
      cancel.setBackgroundResource(drawable.btn_ghost);
      cancel.setMinHeight(0);
      row.addView(cancel);
      Button ok = new Button(this.activity);
      ok.setText("保存模板");
      ok.setTextColor(this.col(color.onAccent));
      ok.setTextSize(13.0F);
      ok.setAllCaps(false);
      ok.setBackgroundResource(drawable.btn_primary);
      ok.setMinHeight(0);
      LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(-2, -2);
      okLp.setMarginStart(this.dp(8));
      row.addView(ok, okLp);
      box.addView(row);

      final Dialog dlg = new Dialog(this.activity);
      dlg.setContentView(box);
      if (dlg.getWindow() != null) {
         dlg.getWindow().setBackgroundDrawable(new ColorDrawable(0));
         WindowManager.LayoutParams wlp = dlg.getWindow().getAttributes();
         wlp.width = (int)((float)this.activity.getResources().getDisplayMetrics().widthPixels * 0.88F);
         dlg.getWindow().setAttributes(wlp);
      }
      cancel.setOnClickListener((v) -> dlg.dismiss());
      ok.setOnClickListener((v) -> {
         String name = et.getText().toString().trim();
         if (name.isEmpty()) name = "我的模板";
         try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (AppDatabase.GmMember m : members) {
               org.json.JSONObject o = new org.json.JSONObject();
               o.put("name", m.name);
               o.put("avatar", m.avatar == null ? "" : m.avatar);
               o.put("roleDesc", m.roleDesc == null ? "" : m.roleDesc);
               o.put("prompt", m.prompt == null ? "" : m.prompt);
               o.put("canSearch", m.canSearch);
               o.put("phase", m.phase);
               o.put("thinkLevel", m.thinkLevel);
               o.put("active", m.active);
               arr.put(o);
            }
            String avatar = members.get(0).avatar;
            if (avatar == null || avatar.isEmpty()) avatar = "\ud83e\udde9";
            long id = this.db.insertCustomTemplate(name, avatar, arr.toString(), roomPrompt);
            this.showToast(id > 0 ? "已存为模板「" + name + "」，去「群聊模板」里能看到" : "保存失败，请重试");
            dlg.dismiss();
         } catch (Exception e) {
            this.showToast("保存失败：" + e.getMessage());
         }
      });
      dlg.show();
   }

   private void createRoomFromCustomTemplate(AppDatabase.GmCustomTemplate ct) {
      try {
         long rid = this.db.insertGmRoom(ct.title, 1000000, 0, 30, ct.roomPrompt == null ? "" : ct.roomPrompt);
         if (rid <= 0L) { this.showToast("创建失败，请重试"); return; }
         org.json.JSONArray arr = new org.json.JSONArray(ct.membersJson == null ? "[]" : ct.membersJson);
         int n = 0;
         for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String name = o.optString("name", "");
            if (name.isEmpty()) continue;
            String avatar = o.optString("avatar", "");
            String roleDesc = o.optString("roleDesc", "");
            String prompt = o.optString("prompt", "");
            boolean canSearch = o.optBoolean("canSearch", true);
            int phase = o.optInt("phase", 0);
            boolean active = o.optBoolean("active", true);
            long mid = this.db.insertGmMember(rid, name, avatar, roleDesc, prompt,
                    this.settings.chatModel, this.settings.chatBaseUrl, this.settings.chatApiKey,
                    canSearch, 0, false, phase);
            this.db.setGmMemberActiveBySystem(mid, active);
            ++n;
         }
         this.showToast("已按模板「" + ct.title + "」创建，含 " + n + " 个角色");
         this.openRoom(rid);
      } catch (Exception e) {
         this.showToast("创建失败：" + e.getMessage());
      }
   }

   private String buildCelebrityPrompt(String[] personaRow) {
      String assetName = personaRow.length > 3 ? personaRow[3] : null;
      String archive = PersonaLibrary.loadPersona(this.activity, assetName);
      if (archive.isEmpty()) {
         Log.w("VC_PERSONA", "蒸馏档案为空，退回身份定位: " + personaRow[0]);
         return personaRow[2];
      } else {
         return PersonaLibrary.buildPersonaPrompt(personaRow[0], archive);
      }
   }

   private void addDefaultMembers(long roomId) {
      for(String[] e : DEFAULT_EXPERTS) {
         this.db.insertGmMember(roomId, e[0], e[1], e[2], e[3], this.settings.chatModel, this.settings.chatBaseUrl, this.settings.chatApiKey, true, 0, true);
      }

      for(String[] p : PersonaLibrary.CELEBRITY_PERSONAS) {
         long id = this.db.insertGmMember(roomId, p[0], p[1], p[2], this.buildCelebrityPrompt(p), this.settings.chatModel, this.settings.chatBaseUrl, this.settings.chatApiKey, true, 0, true);

         this.db.setGmMemberActiveBySystem(id, false);
      }

   }

   private void promptDuplicateRoom(AppDatabase.GmRoom src) {
      String baseTitle = src.title != null && !src.title.isEmpty() ? src.title : "群聊";
      EditText input = new EditText(this.activity);
      input.setText(baseTitle + "-副本");
      input.setSelection(input.getText().length());
      input.setBackgroundResource(drawable.bg_edit);
      input.setPadding(this.dp(12), this.dp(12), this.dp(12), this.dp(12));
      input.setTextColor(this.col(color.text));
      input.setHintTextColor(this.col(color.muted));
      LinearLayout box = new LinearLayout(this.activity);
      box.setOrientation(1);
      GradientDrawable bg = new GradientDrawable();
      bg.setColor(this.col(color.surface));
      bg.setCornerRadius((float)this.dp(20));
      bg.setStroke(this.dp(1), this.col(color.border));
      box.setBackground(bg);
      box.setPadding(this.dp(18), this.dp(18), this.dp(18), this.dp(14));
      TextView h = new TextView(this.activity);
      h.setText("复制群聊");
      h.setTextColor(this.col(color.text));
      h.setTextSize(16.0F);
      h.setTypeface((Typeface)null, 1);
      box.addView(h);
      TextView tip = new TextView(this.activity);
      tip.setText("会复制：全部角色（人设/提示词/模型/额度/思考档位/是否参与讨论）、房间设置（上下文与输出上限、配额、发言间隔、共享规则）。\n不会复制：聊天记录（新房间从空开始，角色用量清零，状态为待命）。");
      tip.setTextColor(this.col(color.muted));
      tip.setTextSize(11.0F);
      tip.setLineSpacing((float)this.dp(3), 1.0F);
      tip.setPadding(0, this.dp(8), 0, 0);
      box.addView(tip);
      TextView lbl = new TextView(this.activity);
      lbl.setText("新群聊名称");
      lbl.setTextColor(this.col(color.muted));
      lbl.setTextSize(11.0F);
      lbl.setPadding(0, this.dp(10), 0, 0);
      box.addView(lbl);
      box.addView(input, new LinearLayout.LayoutParams(-1, -2));
      LinearLayout row = new LinearLayout(this.activity);
      row.setGravity(8388613);
      LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
      rlp.setMargins(0, this.dp(16), 0, 0);
      row.setLayoutParams(rlp);
      Button cancel = this.ghostButton("取消");
      Button ok = this.primaryButton("复制", this.col(color.onAccent));
      row.addView(cancel);
      LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-2, -2);
      olp.setMarginStart(this.dp(8));
      row.addView(ok, olp);
      box.addView(row);
      Dialog dlg = this.showDialogWide(box);
      cancel.setOnClickListener((v) -> dlg.dismiss());
      ok.setOnClickListener((v) -> {
         String nm = input.getText().toString().trim();
         if (nm.isEmpty()) {
            nm = baseTitle + "-副本";
         }

         long newId = this.db.duplicateGmRoom(src.id, nm);
         dlg.dismiss();
         if (newId > 0L) {
            this.showRoomList();
            this.showToast("已复制群聊「" + nm + "」（含 " + this.db.listGmMembers(newId).size() + " 个角色，无聊天记录）");
         } else {
            this.showToast("复制失败");
         }

      });
      dlg.show();
   }

   private void confirmDeleteRoom(AppDatabase.GmRoom r) {
      LinearLayout box = this.dialogBox("删除群聊", "将删除「" + (r.title == null ? "群聊" : r.title) + "」及其全部成员和消息，无法恢复。");
      LinearLayout row = new LinearLayout(this.activity);
      row.setGravity(8388613);
      LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
      rlp.setMargins(0, this.dp(16), 0, 0);
      row.setLayoutParams(rlp);
      Button cancel = this.ghostButton("取消");
      Button ok = this.primaryButton("删除", this.col(color.danger));
      ok.setBackgroundResource(drawable.btn_danger);
      row.addView(cancel);
      LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-2, -2);
      olp.setMarginStart(this.dp(8));
      row.addView(ok, olp);
      box.addView(row);
      Dialog dlg = this.showDialog(box);
      cancel.setOnClickListener((v) -> dlg.dismiss());
      ok.setOnClickListener((v) -> {

         this.stopDiscussion(r.id);

         this.resetRoomBuf(r.id);
         if (this.viewRoomId == r.id) {
            this.detachLiveMemberCard();
         }
         this.db.deleteGmRoom(r.id);
         dlg.dismiss();
         this.showRoomList();
      });
   }

   private void openRoom(long roomId) {
      this.stopUsageTicker();
      this.inHub = false;
      this.renderTarget = this.content;
      this.currentPage = 1;
      this.currentRoomId = roomId;
      this.viewRoomId = roomId;

      this.userReadingHistory = false;
      this.chatUserTouching = false;

      this.renderMsgLimit = RENDER_MAX_MSGS;

      this.ui.post(() -> { this.maybeShowPendingInquiry(roomId); this.maybeResumeInterrupted(roomId); });
      AppDatabase.GmRoom room = this.db.getGmRoom(roomId);
      if (room == null) {
         this.showRoomList();
      } else {
         if (this.titleText != null) {
            this.titleText.setText(room.title == null ? "群聊" : room.title);
         }

         this.syncTopBarTitle(room.title == null ? "群聊" : room.title);
         this.setHeaderButtons(true);
         this.content.removeAllViews();

         FrameLayout root = new FrameLayout(this.activity);
         root.setPadding(this.dp(14), 0, this.dp(14), 0);
         this.content.addView(root, new FrameLayout.LayoutParams(-1, -1));
         try { MaskedFieldMenu.attachTree(root); } catch (Throwable ignored) { }

      try { MaskedFieldMenu.attachTree(root); } catch (Throwable ignored) { }
         Button startBtn = this.primaryButton("", this.col(color.onAccent));
         LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(-1, -2);
         stlp.setMargins(0, this.dp(6), 0, 0);

         this.startButton = startBtn;
         this.startButtonRoomId = roomId;
         this.refreshStartButton();
         startBtn.setOnClickListener((v) -> this.onStartButtonClick(roomId));
         Button exportBtn = this.primaryButton("\ud83d\udcc4 导出", this.col(color.accent));
         exportBtn.setBackgroundResource(drawable.btn_secondary);
         exportBtn.setOnClickListener((v) -> this.startExportFlow((Long)null));

         this.chatScroll = new ScrollView(this.activity);
         this.chatScroll.setClipToPadding(false);
         this.chatList = new LinearLayout(this.activity);
         this.chatList.setOrientation(1);

         this.detachLiveMemberCard();

         this.chatList.setPadding(0, 0, 0, this.dp(96));
         this.chatScroll.addView(this.chatList);
         this.chatScroll.setOnScrollChangeListener((v, sx, sy, osx, osy) -> {
            this.updateChatArrow();
            this.updateReadingHistoryFlag();
         });
         this.chatScroll.setOnTouchListener((v, ev) -> {
            int act = ev.getActionMasked();
            if (act == 0) {

               this.chatUserTouching = true;
               this.updateReadingHistoryFlag();
            } else if (act == 1 || act == 3) {
               this.chatUserTouching = false;

               this.updateReadingHistoryFlag();
            }

            return false;
         });

         root.addView(this.chatScroll, new FrameLayout.LayoutParams(-1, -1));
         this.chatJumpArrow = new TextView(this.activity);
         this.chatJumpArrow.setText("↓");
         this.chatJumpArrow.setTextColor(this.col(color.onAccent));
         this.chatJumpArrow.setTextSize(20.0F);
         this.chatJumpArrow.setTypeface((Typeface)null, 1);
         this.chatJumpArrow.setGravity(17);
         GradientDrawable arrowBg = new GradientDrawable();
         arrowBg.setColor(this.col(color.accentBright));
         arrowBg.setShape(1);
         this.chatJumpArrow.setBackground(arrowBg);
         this.chatJumpArrow.setElevation((float)this.dp(4));
         FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(this.dp(44), this.dp(44));
         alp.gravity = 8388693;
         alp.rightMargin = this.dp(14);
         alp.bottomMargin = this.dp(14);
         this.chatJumpArrow.setLayoutParams(alp);
         this.chatJumpArrow.setVisibility(8);
         this.chatJumpArrow.setOnClickListener((v) -> {
            this.jumpToLatest();
            this.chatJumpArrow.setVisibility(8);
         });
         this.content.addView(this.chatJumpArrow, alp);
         LinearLayout inputWrap = new LinearLayout(this.activity);
         inputWrap.setOrientation(1);
         inputWrap.setBaselineAligned(false);

         FrameLayout.LayoutParams wlp = new FrameLayout.LayoutParams(-1, -2);
         wlp.gravity = android.view.Gravity.BOTTOM;
         wlp.setMargins(0, 0, 0, this.dp(6));
         inputWrap.setLayoutParams(wlp);
         EditText input = new EditText(this.activity);
         input.setHint("说点什么，让群里的 AI 讨论…");
         input.setBackgroundColor(0);
         input.setTextColor(this.col(color.text));
         input.setHintTextColor(this.col(color.muted));
         input.setTextSize(14.0F);
         input.setMaxLines(5);
         input.setMinLines(1);
         input.setGravity(8388659);
         input.setInputType(131073);

         input.setPadding(this.dp(4), this.dp(6), this.dp(4), this.dp(6));
         input.setVerticalScrollBarEnabled(true);
         input.setScrollBarStyle(0);
         input.setOverScrollMode(1);

         LinearLayout toolsRow = new LinearLayout(this.activity);
         toolsRow.setOrientation(0);
         toolsRow.setGravity(16);
         toolsRow.setBaselineAligned(false);
         toolsRow.setPadding(this.dp(2), 0, this.dp(2), this.dp(2));

         int toolW = this.dp(34);
         int toolH = this.dp(30);
         TextView docBtn = this.bareIcon("\ud83d\udcce", "上传文件");
         docBtn.setOnClickListener((v) -> {
            if (this.attachListener != null) {
               this.attachListener.onPickDoc(inputWrap, input);
            }

         });
         toolsRow.addView(docBtn, new LinearLayout.LayoutParams(toolW, toolH));
         TextView imgBtn = this.bareIcon("\ud83d\udcf7", "拍照/图片");
         imgBtn.setOnClickListener((v) -> {
            if (this.attachListener != null) {
               this.attachListener.onPickImage(inputWrap, input);
            }

         });
         toolsRow.addView(imgBtn, new LinearLayout.LayoutParams(toolW, toolH));

         final TextView expandBtn = this.bareIcon("\u2922", "全屏编辑");
         expandBtn.setTextSize(16.0F);
         expandBtn.setContentDescription("全屏编辑");
         expandBtn.setVisibility(8);
         expandBtn.setOnClickListener((v) -> this.showFullScreenEditor(input));
         toolsRow.addView(expandBtn, new LinearLayout.LayoutParams(toolW, toolH));

         this.confirmButton = (Button) this.activity.findViewById(id.gmConfirmButton);
         if (this.confirmButton != null) {
            this.confirmButton.setVisibility(8);
            this.confirmButton.setOnClickListener((v) -> this.showConfirmList());
         }

         toolsRow.addView(new View(this.activity), new LinearLayout.LayoutParams(0, 1, 1.0F));

         LinearLayout.LayoutParams sbl = new LinearLayout.LayoutParams(-2, this.dp(30));
         sbl.setMarginEnd(this.dp(6));
         startBtn.setTextSize(12.0F);
         startBtn.setPadding(this.dp(10), 0, this.dp(10), 0);
         startBtn.setElevation(0.0F);
         startBtn.setMinWidth(0);
         startBtn.setMinimumWidth(0);
         startBtn.setMinHeight(0);
         startBtn.setMinimumHeight(0);

         startBtn.setMaxWidth(this.dp(76));
         toolsRow.addView(startBtn, sbl);

         LinearLayout.LayoutParams ebl = new LinearLayout.LayoutParams(-2, this.dp(30));
         exportBtn.setTextSize(12.0F);
         exportBtn.setPadding(this.dp(10), 0, this.dp(10), 0);
         exportBtn.setElevation(0.0F);
         exportBtn.setMinWidth(0);
         exportBtn.setMinimumWidth(0);
         exportBtn.setMinHeight(0);
         exportBtn.setMinimumHeight(0);
         toolsRow.addView(exportBtn, ebl);
         inputWrap.addView(toolsRow, new LinearLayout.LayoutParams(-1, -2));

         input.addTextChangedListener(new android.text.TextWatcher() {
            public void afterTextChanged(android.text.Editable e) {
               input.post(() -> {
                  boolean over = input.getLineCount() > input.getMaxLines();
                  expandBtn.setVisibility(over ? 0 : 8);
               });
            }
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
         });
         LinearLayout inputRow = new LinearLayout(this.activity);
         inputRow.setOrientation(0);

         inputRow.setGravity(16);
         inputRow.setBaselineAligned(false);

         inputRow.setBackgroundResource(drawable.input_bar);

         inputRow.setClipToOutline(true);
         inputRow.setPadding(this.dp(10), this.dp(4), this.dp(4), this.dp(4));
         inputRow.addView(input, new LinearLayout.LayoutParams(0, -2, 1.0F));
         Button send = new Button(this.activity);
         send.setText("↑");
         send.setTextColor(this.col(color.onAccent));
         send.setTextSize(17.0F);
         send.setTypeface((Typeface)null, 1);
         send.setBackgroundResource(drawable.fab_accent);
         send.setMinWidth(0);
         send.setMinimumWidth(0);
         send.setMinHeight(0);
         send.setMinimumHeight(0);
         send.setPadding(0, 0, 0, 0);
         LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(this.dp(36), this.dp(36));
         slp.setMarginStart(this.dp(6));

         inputRow.addView(send, slp);
         inputWrap.addView(inputRow, new LinearLayout.LayoutParams(-1, -2));
         root.addView(inputWrap);

         final String draftKey = "room_" + roomId;
         this.bindDraft(input, draftKey);
         send.setOnClickListener((v) -> {
            String t = input.getText().toString().trim();
            if (!t.isEmpty()) {
               if (this.onUserSays(t)) {
                  input.setText("");
                  this.saveDraft(draftKey, "");
               }

            }
         });
         this.renderChat();

         this.flushLiveBuffersToChat();

         this.refreshConfirmButton();
      }
   }

   public void setWebToolHandler(AiClient.ToolHandler h) {
      this.webToolHandler = h;
   }

   private String handleGroupTool(long roomId, String name, org.json.JSONObject args) throws Exception {
      return this.handleGroupTool(roomId, name, args, null);
   }

   private String handleGroupTool(long roomId, String name, org.json.JSONObject args,
                                  String citeOwnerOverride) throws Exception {
      if ("read_questions".equals(name)) {

         return new org.json.JSONObject()
                 .put("success", true)
                 .put("board", this.buildQuestionBoard(roomId, true))
                 .toString();
      }

      if ("confirm_convergence".equals(name)) {
         if (citeOwnerOverride != null) {
            return "{\"success\":false,\"error\":\"管理员不参与讨论，无需做收敛确认\"}";
         }
         Long boxedMid = this.roomSpeakMemberId.get(roomId);
         long mid = boxedMid == null ? 0L : boxedMid;
         if (mid <= 0L) {
            return "{\"success\":false,\"error\":\"当前没有正在发言的角色，无法确认收敛\"}";
         }

         return "{\"success\":true,\"message\":\"已记录你的最终确认（跨轮有效，之后不必每轮重复）。\"}";
      }

      if ("search_user_archive".equals(name)) {
         String kw = args == null ? "" : args.optString("keyword", "").trim();
         int limit = args == null ? 30 : args.optInt("limit", 30);
         if (limit <= 0) limit = 30;

         final int FULL = 30;
         List<AppDatabase.GmQaRoomBrief> rooms = this.db.searchGmQaRooms(kw, limit);
         int totalRooms = 0;
         try { totalRooms = this.db.countGmQaArchiveRooms(); } catch (Exception ignored) { }
         StringBuilder sb = new StringBuilder();
         sb.append("用户信息库 · 需求一览（每一项 = 一整套：那个群的需求 + 它绑定的问答）");
         if (!kw.isEmpty()) sb.append("（关键词：").append(kw).append("）");
         sb.append("\n命中 ").append(rooms.size()).append(" 套（库里一共 ")
           .append(totalRooms).append(" 套）。\n\n");
         int idx = 0;
         for (AppDatabase.GmQaRoomBrief b : rooms) {
            ++idx;
            String demand = b.roomGoal != null && !b.roomGoal.trim().isEmpty()
                    ? b.roomGoal.trim() : (b.roomTitle == null ? "" : b.roomTitle.trim());
            if (demand.isEmpty()) demand = "需求 #" + b.roomId;
            sb.append(idx).append(". demand_id=").append(b.roomId).append("（一整套）\n");
            if (idx <= FULL) {

               sb.append("   【该群需求】").append(demand.replace("\n", " ")).append("\n");
            } else {

               String head = demand.replace("\n", " ");
               if (head.length() > 100) head = head.substring(0, 100) + "…";
               sb.append("   【该群需求（开头）】").append(head).append("\n");
            }
            sb.append("   【这套里的问答】共 ").append(b.pairCount)
              .append(" 组（哪位专家问了什么 + 用户怎么答的，"
                      + "用 fetch_user_archive 传 demand_id=").append(b.roomId).append(" 取回）\n");
         }
         sb.append("\n（上面只给了需求层面。要看某一套里**具体每个专家问了什么、"
                 + "用户当时怎么回答的**，用 fetch_user_archive 传对应的 demand_id —— 那才是深入。）");
         return new org.json.JSONObject().put("success", true)
                 .put("list", sb.toString()).toString();
      }
      if ("fetch_user_archive".equals(name)) {
         long demandId = args == null ? 0L : args.optLong("demand_id", 0L);
         if (demandId <= 0L) {
            return "{\"success\":false,\"error\":\"demand_id 必填（用 search_user_archive 拿到的 demand_id）\"}";
         }
         String kw = args.optString("keyword", "").trim();
         int offset = Math.max(0, args.optInt("offset", 0));
         int limit = args.optInt("limit", 20);
         if (limit <= 0) limit = 20;
         int total = this.db.countGmQaPairs(demandId, kw);
         if (total == 0) {
            return "{\"success\":false,\"error\":\"这个需求下没有匹配的问答"
                    + (kw.isEmpty() ? "" : "（关键词：" + kw + "）")
                    + "。换个关键词，或用 search_user_archive 换一个需求。\"}";
         }
         List<AppDatabase.GmQaPair> pairs = this.db.fetchGmQaPairs(demandId, kw, offset, limit);
         String title = pairs.isEmpty() ? null : pairs.get(0).roomTitle;
         StringBuilder sb = new StringBuilder();
         sb.append("用户信息库 · 需求「")
           .append(title == null || title.trim().isEmpty() ? ("#" + demandId) : title.trim())
           .append("」的问答");
         if (!kw.isEmpty()) sb.append("（关键词：").append(kw).append("）");
         sb.append("\n本次取第 ").append(offset + 1).append("~").append(offset + pairs.size())
           .append(" 组，该需求下共 ").append(total).append(" 组匹配。\n\n");
         for (AppDatabase.GmQaPair p : pairs) {
            sb.append("【").append(p.asker == null || p.asker.trim().isEmpty() ? "成员" : p.asker.trim())
              .append(" 提问】").append(p.question == null ? "" : p.question.trim()).append("\n");
            if (p.answered && p.answer != null && !p.answer.trim().isEmpty()) {
               sb.append("【用户当时的回答】").append(p.answer.trim()).append("\n\n");
            } else {
               sb.append("【用户当时没有回答（跳过了这题）】\n\n");
            }
         }
         if (offset + pairs.size() < total) {
            sb.append("（还有 ").append(total - offset - pairs.size())
              .append(" 组没取。接着取就传 offset=").append(offset + pairs.size())
              .append("，或加 keyword 只取你要的。）\n");
         }

         sb.append("（以上是用户在其它群里、**过去某个时间点**的回答，只能作为参考——"
                 + "情况会变，是否仍然成立由你自己判断。）");

         if (citeOwnerOverride == null) {
            String who = this.currentSpeakerName(roomId);
            if (who != null && !who.trim().isEmpty()) {
               try {
                  String dTitle = pairs.isEmpty() ? null : pairs.get(0).roomTitle;
                  String dGoal = pairs.isEmpty() ? null : pairs.get(0).roomGoal;
                  for (AppDatabase.GmQaPair p : pairs) {
                     this.db.insertGmCitation(roomId, who, demandId, dTitle, dGoal, p.id,
                             p.asker, p.question, p.answer);
                  }
               } catch (Exception ignored) { }
            }
         }
         return new org.json.JSONObject().put("success", true)
                 .put("content", sb.toString()).toString();
      }

      if ("remember_research".equals(name)) {
         String content = args == null ? "" : args.optString("content", "").trim();
         if (content.isEmpty()) {
             return "{\"success\":false,\"error\":\"content 不能为空（要存下来的压缩结论）\"}";
         }
         Long boxed = this.roomSpeakMemberId.get(roomId);
         long mid = boxed == null ? 0L : boxed;
         if (mid <= 0L) {
            return "{\"success\":false,\"error\":\"当前没有正在发言的角色，无法写入压缩结论\"}";
         }
         boolean dropRaw = args != null && args.optBoolean("drop_raw_materials", false);

         int pooled = 0;
         if (dropRaw) {
            AppDatabase.GmMember me = null;
            try { me = this.db.getGmMember(mid); } catch (Exception ignored) { }
            String myName = me == null ? null : me.name;
            try {

               List<AppDatabase.GmMessage> own = this.db.listGmMessagesBySpeaker(roomId, myName);
               if (own != null) {
                  for (AppDatabase.GmMessage m : own) {
                     if ("system".equals(m.kind) || m.content == null) continue;
                     int oi = m.content.indexOf("@@TOOLS:");
                     if (oi < 0) continue;
                     String tools = AiClient.stripFinalMarkers(
                             m.content.substring(oi + "@@TOOLS:".length())).trim();
                     int lateTi = tools.indexOf("@@THINKING:");
                     if (lateTi >= 0) tools = tools.substring(0, lateTi).trim();
                     if (tools.isEmpty()) continue;
                     StringBuilder bag = new StringBuilder();
                     for (String raw : tools.split("\n")) {
                        String line = raw.trim();
                        if (!line.startsWith("└") && !line.startsWith("   └")) continue;

                        int ci = line.indexOf("资料内容：");
                        String body = ci >= 0 ? line.substring(ci + "资料内容：".length()).trim() : line;
                        if (body.isEmpty()) continue;
                        if (bag.length() > 0) bag.append("\n");
                        bag.append(body);
                     }
                     if (bag.length() > 0) {
                        long pid = this.db.insertGmPoolItem(roomId, myName,
                                "#" + m.seq + " 的资料", bag.toString());
                        if (pid > 0) ++pooled;
                     }
                  }
               }
            } catch (Exception e) {
               android.util.Log.w("VC_GM", "资料入池失败: " + e.getMessage());
            }
         }
         int tok = AiClient.estimateTokensText(content);
         boolean ok = this.db.setGmAgentMemory(roomId, mid, content, dropRaw);

         if (!ok) {
            return new org.json.JSONObject().put("success", false)
                    .put("error", "你压缩出的结论 " + tok + " token，超过资料额度 "
                            + AiClient.TOOLS_DETAIL_TOKEN_CAP + " token（超出 "
                            + (tok - AiClient.TOOLS_DETAIL_TOKEN_CAP) + "），没有写入。"
                            + "资料额度是原文和结论合起来的那一份；"
                            + "你可以改小后重新调用；原始资料仍在共享资料池里，"
                            + "需要细节时用 search_material_pool / fetch_material_pool 取回。")
                    .toString();
         }
         return new org.json.JSONObject().put("success", true)
                 .put("message", "已存下你的压缩结论（" + content.length() + " 字 ≈ " + tok
                         + " token；资料额度共 " + AiClient.TOOLS_DETAIL_TOKEN_CAP + "，"
                         + "原文和结论合起来算）。"
                         + "它和你抓回的原文一样，会随你之后每轮发言注入上下文，"
                         + "所以被压掉的原文不会让你丢失这些结论。"
                         + (dropRaw
                            ? ("已把 " + pooled + " 份原始资料移出你的上下文并放进**共享资料池**"
                               + "（你自己或别人以后都能用 search_material_pool 搜到、"
                               + "fetch_material_pool 按需取回）。")
                            : "原始资料目前仍留在你的上下文里；"
                              + "想让它们不再占位，下次调用时传 drop_raw_materials=true。"))
                 .toString();
      }

      if ("search_material_pool".equals(name)) {
         String kw = args == null ? "" : args.optString("keyword", "").trim();
         int limit = args == null ? 30 : args.optInt("limit", 30);
         if (limit <= 0) limit = 30;
         List<AppDatabase.GmPoolItem> items = this.db.searchGmPool(roomId, kw, limit);
         long[] st = this.db.poolStats(roomId);
         StringBuilder sb = new StringBuilder();
         sb.append("共享资料池 · 资料清单");
         if (!kw.isEmpty()) sb.append("（关键词：").append(kw).append("）");
         sb.append("\n池中共 ").append(st[0]).append(" 份资料（合计约 ")
           .append(st[1]).append(" token）；本次列出 ").append(items.size()).append(" 份。\n\n");
         for (AppDatabase.GmPoolItem it : items) {
            sb.append("- pool_id=").append(it.id)
              .append(" ｜ 来自：").append(it.owner == null || it.owner.trim().isEmpty() ? "成员" : it.owner.trim());
            if (it.sourceTag != null && !it.sourceTag.trim().isEmpty()) {
               sb.append("（").append(it.sourceTag.trim()).append("）");
            }
            sb.append(" ｜ ").append(it.contentLen).append(" 字 ≈ ").append(it.tokens).append(" token\n");
            String head = it.content == null ? "" : it.content.replace("\n", " ").trim();
            if (head.length() > 160) head = head.substring(0, 160) + "…";
            if (!head.isEmpty()) sb.append("    开头：").append(head).append("\n");
         }
         if (items.isEmpty()) sb.append("（没有匹配的资料）\n");
         sb.append("\n（要看某份资料的正文，用 fetch_material_pool 传 pool_id —— 可分段只取你需要的那部分，"
                 + "不必整份取回。）");
         return new org.json.JSONObject().put("success", true)
                 .put("list", sb.toString()).toString();
      }
      if ("fetch_material_pool".equals(name)) {
         long pid = args == null ? 0L : args.optLong("pool_id", 0L);
         if (pid <= 0L) {
            return "{\"success\":false,\"error\":\"pool_id 必填（从 search_material_pool 得到）\"}";
         }
         int offset = Math.max(0, args.optInt("offset_chars", 0));
         int maxChars = args.optInt("max_chars", 6000);
         if (maxChars <= 0) maxChars = 6000;
         if (maxChars > 20000) maxChars = 20000;

         long total = this.db.gmPoolContentLen(roomId, pid);
         if (total <= 0L) {
            return "{\"success\":false,\"error\":\"池子里没有 pool_id=" + pid + " 的资料（它可能是别的群的编号，"
                    + "只有本群池子里的资料能取）\"}";
         }
         String part = this.db.fetchGmPoolContent(roomId, pid, offset, maxChars);
         if (part == null || part.isEmpty()) {
            return "{\"success\":false,\"error\":\"pool_id=" + pid + " 已在末尾（该资料共 "
                    + total + " 字，offset=" + offset + " 超出）\"}";
         }
         StringBuilder sb = new StringBuilder();
         sb.append("共享资料池 · pool_id=").append(pid).append(" 的正文\n")
           .append("本次取第 ").append(offset + 1).append("~").append(offset + part.length())
           .append(" 字，该资料共 ").append(total).append(" 字。\n\n")
           .append(part);
         long nextOff = (long) offset + part.length();
         if (nextOff < total) {
            sb.append("\n\n（后面还有 ").append(total - nextOff)
              .append(" 字没取。要接着取就传 offset_chars=").append(nextOff).append("。）");
         }
         return new org.json.JSONObject().put("success", true)
                 .put("content", sb.toString()).toString();
      }
      return this.webToolHandler == null
              ? "{\"success\":false,\"error\":\"工具未配置\"}"
              : this.webToolHandler.handle(name, args);
   }

   private final java.util.concurrent.ConcurrentHashMap<Long, Long> roomSpeakMemberId =
           new java.util.concurrent.ConcurrentHashMap<>();

   private String currentSpeakerName(long roomId) {
      Long boxed = this.roomSpeakMemberId.get(roomId);
      long mid = boxed == null ? 0L : boxed;
      if (mid <= 0L) return null;
      try {
         for (AppDatabase.GmMember m : this.db.listGmMembers(roomId)) {
            if (m.id == mid) return m.name == null ? "成员" : m.name;
         }
      } catch (Exception ignored) { }
      return "成员";
   }

   public void setAttachListener(AttachListener l) {
      this.attachListener = l;
   }

   private Button compactRowButton(String text, boolean primary, View.OnClickListener onClick) {
      Button b = new Button(this.activity);
      b.setText(text);
      b.setTextColor(this.col(primary ? color.onAccent : color.accent));
      b.setTextSize(13.0F);
      b.setAllCaps(false);
      b.setBackgroundResource(primary ? drawable.btn_primary : drawable.btn_secondary);
      b.setMinHeight(0);
      b.setMinimumHeight(0);
      b.setMinWidth(0);
      b.setMinimumWidth(0);
      b.setPadding(this.dp(6), this.dp(5), this.dp(6), this.dp(5));
      b.setSingleLine(true);
      b.setEllipsize(android.text.TextUtils.TruncateAt.END);
      if (onClick != null) b.setOnClickListener(onClick);
      return b;
   }

   private TextView bareIcon(String glyph, String desc) {
      TextView t = new TextView(this.activity);
      t.setText(glyph);
      t.setTextSize(17.0F);
      t.setTextColor(this.col(color.text2));
      t.setGravity(17);
      t.setContentDescription(desc);
      t.setClickable(true);
      t.setFocusable(true);
      return t;
   }

   private Button iconButton(String text) {
      Button b = new Button(this.activity);
      b.setText(text);
      b.setTextColor(this.col(color.text));
      b.setTextSize(17.0F);
      b.setAllCaps(false);
      b.setBackgroundResource(drawable.icon_btn);
      b.setMinWidth(0);
      b.setMinimumWidth(0);
      b.setMinHeight(0);
      b.setMinimumHeight(0);
      b.setPadding(0, 0, 0, 0);
      return b;
   }

   public void appendToInput(EditText target, String text) {
      if (target != null) {
         target.setText(target.getText().toString() + text);
         target.setSelection(target.getText().length());
      }
   }

   private void saveDraft(String key, String text) {
      DraftStore.save(this.activity, key, text);
   }

   private String loadDraft(String key) {
      return DraftStore.load(this.activity, key);
   }

   private void bindDraft(final EditText et, final String key) {
      DraftStore.bind(et, this.activity, key);
   }

   private ScrollView wrapMemberScroll(View body, int maxHeightDp) {
      NestedLimitScrollView sv = new NestedLimitScrollView(this.activity);
      sv.setMaxHeightPx(this.dp(maxHeightDp));
      sv.addView(body, new FrameLayout.LayoutParams(-1, -2));
      body.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
         if (b != ob || t != ot) {
            sv.post(() -> {
               if (!sv.isUserTouching()) {
                  sv.fullScroll(130);
               }

            });
         }

      });
      return sv;
   }

   private void appendLiveMemberTool(long roomId, String name, String avatar, String line, boolean isStart) {
      if (line != null && !line.isEmpty()) {

         if (isStart) {
            try { WorkSound.play(this.activity, WorkSound.EV_TOOL, false); } catch (Exception ignored) {}
         }
         this.ui.post(() -> {

            if (this.isRoomStopped(roomId)) return;
            RoomLiveBuf buf = this.bufOf(roomId);

            if (buf.memberName != null && !buf.memberName.equals(name)) {
               clearBuf(buf);
               this.detachCardForSwitch(roomId);
            }
            buf.memberName = name;
            if (buf.tool.length() > 0) buf.tool.append("\n");
            buf.tool.append(line);
            if (this.viewRoomId != roomId || this.chatList == null) return;

            this.ensureLiveMemberCard(name, avatar);

            this.requestLiveRepaint();
            this.followLiveScrollIfWatching();
         });
      }
   }

   private void detachLiveMemberCard() {
      this.liveMemberCard = null;
      this.liveMemberThought = null;
      this.liveMemberTool = null;
      this.liveMemberSpeech = null;
      this.liveMemberThoughtTag = this.liveMemberToolTag = this.liveMemberSpeechTag = null;
      this.liveMemberThoughtWrapper = this.liveMemberToolWrapper = this.liveMemberSpeechWrapper = null;

      this.liveMemberName = null;
   }

   private void detachCardForSwitch(long roomId) {
      if (this.viewRoomId != roomId) return;
      if (this.liveMemberCard != null && this.liveMemberCard.getParent() != null) {
         ((ViewGroup) this.liveMemberCard.getParent()).removeView(this.liveMemberCard);
      }
      this.detachLiveMemberCard();
   }

   private void ensureLiveMemberCard(String name, String avatar) {
      if (this.liveMemberCard == null || name == null || !name.equals(this.liveMemberName)) {

         boolean roleChanged = this.liveMemberName != null && name != null && !name.equals(this.liveMemberName);
         if (roleChanged) {

            RoomLiveBuf b = this.liveBufs.get(this.viewRoomId);
            if (b != null) clearBuf(b);
         }
         this.liveMemberCard = new LinearLayout(this.activity);
         this.liveMemberCard.setOrientation(1);
         GradientDrawable bg = new GradientDrawable();
         bg.setColor(this.col(color.surface));
         bg.setCornerRadius((float)this.dp(14));
         bg.setStroke(this.dp(1), this.col(color.accent));
         this.liveMemberCard.setBackground(bg);
         this.liveMemberCard.setPadding(this.dp(12), this.dp(10), this.dp(12), this.dp(10));
         LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
         lp.setMargins(0, this.dp(5), 0, 0);
         this.liveMemberCard.setLayoutParams(lp);
         TextView head = new TextView(this.activity);
         head.setText((avatar == null ? "\ud83e\udd16" : avatar) + " " + name + "  ✍️ 正在输入…");
         head.setTextColor(this.col(color.accent));
         head.setTextSize(13.0F);
         head.setTypeface((Typeface)null, 1);
         this.liveMemberCard.addView(head);
         this.liveMemberThoughtTag = this.sectionTag("\ud83d\udcad 思考过程", this.col(color.accentBright));
         this.liveMemberCard.addView(this.liveMemberThoughtTag);
         this.liveMemberThought = new TextView(this.activity);
         this.liveMemberThought.setTextColor(this.col(color.muted));
         this.liveMemberThought.setTextSize(11.0F);
         this.liveMemberThoughtWrapper = this.wrapMemberScroll(this.liveMemberThought, 110);
         this.liveMemberThoughtWrapper.setVisibility(8);
         this.liveMemberCard.addView(this.liveMemberThoughtWrapper);
         this.liveMemberToolTag = this.sectionTag("\ud83d\udd27 工具调用", this.col(color.searchAccent));
         this.liveMemberCard.addView(this.liveMemberToolTag);
         this.liveMemberTool = new TextView(this.activity);
         this.liveMemberTool.setTextColor(this.col(color.searchAccent));
         this.liveMemberTool.setTextSize(11.0F);
         this.liveMemberToolWrapper = this.wrapMemberScroll(this.liveMemberTool, 120);
         this.liveMemberToolWrapper.setVisibility(8);
         this.liveMemberCard.addView(this.liveMemberToolWrapper);
         this.liveMemberSpeechTag = this.sectionTag("\ud83d\udcac 回复", this.col(color.accent));
         this.liveMemberCard.addView(this.liveMemberSpeechTag);
         this.liveMemberSpeech = new TextView(this.activity);
         this.liveMemberSpeech.setTextColor(this.col(color.text));
         this.liveMemberSpeech.setTextSize(14.0F);
         this.liveMemberSpeechWrapper = this.wrapMemberScroll(this.liveMemberSpeech, 200);
         this.liveMemberSpeechWrapper.setVisibility(8);
         this.liveMemberCard.addView(this.liveMemberSpeechWrapper);
         this.chatList.addView(this.liveMemberCard);
         this.liveMemberName = name;

         this.repaintLiveFromBuffers();
      }
   }

   private void repaintLiveFromBuffers() {

      RoomLiveBuf buf = this.viewRoomId > 0L ? this.liveBufs.get(this.viewRoomId) : null;
      String think = buf == null ? "" : buf.thought.toString();
      String tool = buf == null ? "" : buf.tool.toString();
      String all = buf == null ? "" : buf.content.toString();

      if (!all.isEmpty()) {

         int spIdx = findLineStartMarker(all, "发言：");
         if (spIdx < 0) spIdx = findLineStartMarker(all, "发言:");
         String contentThink;
         String speech;
         if (spIdx >= 0) {
            contentThink = all.substring(0, spIdx);
            speech = all.substring(spIdx + 3);
         } else {
            contentThink = all;
            speech = "";
         }
         contentThink = contentThink.replaceFirst("^\\s*思考\\s*[:：]", "");

         String mergedThink = think.isEmpty() ? contentThink : (contentThink.isEmpty() ? think : think + contentThink);
         this.setLiveSection(this.liveMemberThoughtTag, this.liveMemberThoughtWrapper, this.liveMemberThought, mergedThink);
         this.setLiveSection(this.liveMemberSpeechTag, this.liveMemberSpeechWrapper, this.liveMemberSpeech, speech);
      } else {
         this.setLiveSection(this.liveMemberThoughtTag, this.liveMemberThoughtWrapper, this.liveMemberThought, think);
      }
      this.setLiveToolSection(tool);
   }

   private int findLineStartMarker(String text, String marker) {
      if (text == null || marker == null || marker.isEmpty()) return -1;
      int from = 0;
      while (true) {
         int i = text.indexOf(marker, from);
         if (i < 0) return -1;

         int j = i - 1;
         boolean onlyBlankBefore = true;
         while (j >= 0 && text.charAt(j) != '\n') {
            if (!Character.isWhitespace(text.charAt(j))) { onlyBlankBefore = false; break; }
            j--;
         }
         if (onlyBlankBefore) return i;
         from = i + marker.length();
      }
   }

   private void setLiveToolSection(String tool) {
      TextView body = this.liveMemberTool;
      if (body == null) return;
      boolean has = tool != null && !tool.trim().isEmpty();
      if (this.liveMemberToolTag != null) {
         if (has) {
            int fb = 0, total = 0;
            for (String ln : tool.split("\n")) {
               if (ln.trim().isEmpty()) continue;
               ++total;
               if (AiClient.isFallbackLine(ln)) ++fb;
            }
            this.liveMemberToolTag.setText("\ud83d\udd27 工具调用"
                    + (fb > 0 ? "（" + fb + "/" + total + " 条降级）" : "")
                    + (this.liveMemberToolWrapper != null && this.liveMemberToolWrapper.getVisibility() == 0 ? "  ▴" : "  ▾"));
            this.liveMemberToolTag.setTextColor(this.col(
                    fb > 0 ? color.accentBright : color.searchAccent));
         }
         this.liveMemberToolTag.setVisibility(has ? 0 : 8);
      }
      if (this.liveMemberToolWrapper != null) {
         this.liveMemberToolWrapper.setVisibility(has ? 0 : 8);
      }
      if (!has) {
         body.setText("");
         return;
      }

      StringBuilder sb = new StringBuilder();
      int[] starts = new int[64];
      boolean[] isFb = new boolean[64];
      int n = 0;
      for (String ln : tool.split("\n")) {
         if (ln.trim().isEmpty()) continue;
         String show = ln.replaceFirst("^\\[FB\\]\\s*", "").replaceFirst("^\\[TQ\\]\\s*", "")
                 .replaceFirst("^\\[API\\]\\s*", "");
         boolean fb = AiClient.isFallbackLine(ln);
         if (fb) show = "⚠ 降级 · " + show;
         if (sb.length() > 0) sb.append("\n");
         if (n < starts.length) { starts[n] = sb.length(); isFb[n] = fb; ++n; }
         sb.append(show);
      }
      android.text.SpannableString sp = new android.text.SpannableString(sb.toString());
      int lineStart = 0;
      String full = sb.toString();
      for (int i = 0; i < n; ++i) {
         int s = starts[i];
         int e = (i + 1 < n) ? starts[i + 1] - 1 : full.length();
         if (e > s && isFb[i]) {
            sp.setSpan(new android.text.style.ForegroundColorSpan(this.col(color.accentBright)),
                    s, e, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
         }
      }
      body.setText(sp);
   }

   private void setLiveSection(TextView tag, View wrapper, TextView body, String text) {
      if (body == null) return;
      boolean has = text != null && !text.trim().isEmpty();
      if (tag != null) tag.setVisibility(has ? 0 : 8);
      if (wrapper != null) wrapper.setVisibility(has ? 0 : 8);

      String next = text == null ? "" : text;
      Object prev = body.getTag();
      if (prev instanceof CharSequence && prev.toString().equals(next)) return;
      body.setTag(next);
      body.setText(next);
   }

   private TextView sectionTag(String text, int color) {
      TextView t = new TextView(this.activity);
      t.setText(text);
      t.setTextColor(color);
      t.setTextSize(11.0F);
      t.setTypeface((Typeface)null, 1);
      t.setPadding(0, this.dp(6), 0, this.dp(2));
      t.setVisibility(8);
      return t;
   }

   private void appendLiveMemberContent(long roomId, String name, String avatar, String delta) {
      if (delta == null || delta.isEmpty()) return;

      try { WorkSound.play(this.activity, WorkSound.EV_REPLY, false); } catch (Exception ignored) {}
      this.ui.post(() -> {

         if (this.isRoomStopped(roomId)) return;
         RoomLiveBuf buf = this.bufOf(roomId);

         if (buf.memberName != null && !buf.memberName.equals(name)) {
            clearBuf(buf);
            this.detachCardForSwitch(roomId);
         }
         buf.memberName = name;
         buf.content.append(delta);

         if (this.viewRoomId != roomId || this.chatList == null) return;

         this.ensureLiveMemberCard(name, avatar);

         this.requestLiveRepaint();

         this.followLiveScrollIfWatching();
      });
   }

   private void flushLiveBuffersToChat() {
      this.ui.post(() -> {
         if (this.chatList == null || this.viewRoomId <= 0L) return;

         RoomLiveBuf buf = this.liveBufs.get(this.viewRoomId);
         if (buf == null) return;
         if (buf.content.length() == 0 && buf.thought.length() == 0 && buf.tool.length() == 0) {
            return;
         }

         String who = buf.memberName != null ? buf.memberName : this.liveMemberName;
         if (who == null) return;
         this.ensureLiveMemberCard(who, null);
         this.repaintLiveFromBuffers();
      });
   }

   private void appendLiveMemberText(long roomId, String name, String avatar, String delta, boolean isThought) {
      if (delta != null && !delta.isEmpty()) {

         if (isThought) {
            try { WorkSound.play(this.activity, WorkSound.EV_THINK, false); } catch (Exception ignored) {}
         }
         this.ui.post(() -> {

            if (this.isRoomStopped(roomId)) return;
            RoomLiveBuf buf = this.bufOf(roomId);

            if (buf.memberName != null && !buf.memberName.equals(name)) {
               clearBuf(buf);
               this.detachCardForSwitch(roomId);
            }
            buf.memberName = name;

            if (isThought) {
               buf.thought.append(delta);
            } else {
               buf.content.append(delta);
            }
            if (this.viewRoomId != roomId || this.chatList == null) return;

            this.ensureLiveMemberCard(name, avatar);

            this.requestLiveRepaint();

            this.followLiveScrollIfWatching();
         });
      }
   }

   private void discardLiveMemberRound(long roomId, String reason) {
      this.ui.post(() -> {

         RoomLiveBuf buf = this.liveBufs.get(roomId);
         if (buf != null) clearBuf(buf);
         this.liveRepaintPosted = false;
         this.liveLastRepaintAt = 0L;
         if (this.viewRoomId == roomId) {

            if (this.liveMemberCard != null && this.liveMemberCard.getParent() != null) {
               ((ViewGroup) this.liveMemberCard.getParent()).removeView(this.liveMemberCard);
            }
            this.detachLiveMemberCard();
            if (reason != null) this.showToast(reason);
         }
      });
   }

   private void clearLiveMemberCard(long roomId) {
      this.ui.post(() -> {

         this.liveRepaintPosted = false;
         this.liveLastRepaintAt = 0L;
         this.repaintLiveFromBuffers();
         this.resetRoomBuf(roomId);
         if (this.viewRoomId != roomId) return;
         if (this.liveMemberCard != null && this.liveMemberCard.getParent() != null) {
            ((ViewGroup)this.liveMemberCard.getParent()).removeView(this.liveMemberCard);
         }
         this.detachLiveMemberCard();
      });
   }

   private void refreshConfirmButton() {
      if (this.confirmButton == null) return;
      final long roomId = this.currentRoomId;
      if (roomId <= 0) return;
      int active = 0, confirmed = 0;
      try {
         Convergence c = this.computeConvergence(roomId, this.roomRuns.get(roomId));
         active = c.active;
         confirmed = c.confirmed;
      } catch (Exception ignored) { }
      final int a = active, cf = confirmed;
      this.ui.post(() -> {
         if (this.confirmButton == null || this.currentRoomId != roomId) return;

         if (a <= 0 || cf <= 0) {
            this.confirmButton.setVisibility(8);
            this.confirmButton.setText("定稿");
            this.confirmButton.setTextColor(this.col(color.text2));
            this.confirmButton.setTypeface(null, 0);
            return;
         }

         this.confirmButton.setText("定稿 " + cf + "/" + a);
         this.confirmButton.setTextColor(this.col(color.accent));
         this.confirmButton.setTypeface(null, cf >= a ? 1 : 0);
         this.confirmButton.setVisibility(0);
      });
   }

   private void showConfirmList() {
      if (this.currentRoomId <= 0) return;
      final long roomId = this.currentRoomId;
      Convergence c = this.computeConvergence(roomId, this.roomRuns.get(roomId));
      if (c.active <= 0) {
         this.showToast("这个群还没有参战角色");
         return;
      }
      List<AppDatabase.GmMember> members = this.orderMembersForRound(this.db.listGmMembers(roomId));
      List<String> labels = new ArrayList<>();
      final List<Integer> seqs = new ArrayList<>();
      int waiting = 0;
      for (AppDatabase.GmMember m : members) {
         if (!m.active || c.notConfirmed.indexOf(m.name) < 0) continue;
         waiting++;
         labels.add("\u23f3 " + m.name + "（还没确认）");
         seqs.add(this.lastSeqOf(roomId, m.id));
      }
      for (AppDatabase.GmMember m : members) {
         if (!m.active || c.notConfirmed.indexOf(m.name) >= 0) continue;
         labels.add("\u2705 " + m.name + "（已确认）");
         seqs.add(this.lastSeqOf(roomId, m.id));
      }

      String title = "定稿 " + c.confirmed + "/" + c.active
              + (waiting == 0 ? "（全员已确认，讨论可以结束）" : "（还差 " + waiting + " 人）");
      this.showActionSheet(title, labels.toArray(new String[0]), (w) -> {
         if (w >= 0 && w < seqs.size() && seqs.get(w) > 0) this.jumpToMessageSeq(seqs.get(w));
      });
   }

   private int lastSeqOf(long roomId, long memberId) {
      try {
         AppDatabase.GmMessage last = this.db.getLastGmSpeech(roomId, memberId);
         return last == null ? 0 : last.seq;
      } catch (Exception ignored) {
         return 0;
      }
   }

   private void notifyInquiry(long roomId, int count) {      final boolean watching = this.isVisible() && this.viewRoomId == roomId;
      if (watching) {

         try {
            com.linyuan.app.ChatNotifier.playSound(
                    this.activity.getApplicationContext(),
                    com.linyuan.app.ChatNotifier.soundUri(this.activity.getApplicationContext(), true));
         } catch (Exception ignored) { }
         return;
      }
      try {
         AppDatabase.GmRoom r = this.db.getGmRoom(roomId);
         String t = (r == null || r.title == null) ? "群聊" : r.title;
         com.linyuan.app.ChatNotifier.notifyGroupDone(
                 this.activity.getApplicationContext(), roomId + 100, t,
                 "有 " + count + " 个问题需要你确认，点开填写后继续讨论");
      } catch (Exception e) {
         android.util.Log.w("VC_GM", "询问稿提醒失败: " + e.getMessage());
      }
   }

   private void showFeedbackInbox(long roomId) {
      if (roomId <= 0L) return;

      this.currentPage = 6;
      AppDatabase.GmRoom room = this.db.getGmRoom(roomId);
      this.setHeaderButtons(false);
      this.renderTarget.removeAllViews();
      LinearLayout root = new LinearLayout(this.activity);
      root.setOrientation(1);
      this.renderTarget.addView(root, new FrameLayout.LayoutParams(-1, -1));

      LinearLayout bar = new LinearLayout(this.activity);
      bar.setOrientation(0);
      bar.setGravity(16);
      bar.setPadding(this.dp(14), this.dp(10), this.dp(14), this.dp(4));
      TextView title = new TextView(this.activity);
      List<AppDatabase.GmFeedback> items = this.db.listGmFeedback(roomId);
      int unread = 0;
      for (AppDatabase.GmFeedback f : items) if ("unread".equals(f.status)) unread++;
      title.setText("\ud83d\udcac 角色反馈 · " + (room == null || room.title == null ? "群聊" : room.title)
              + (unread > 0 ? "（" + unread + " 条未读）" : ""));
      title.setTextColor(this.col(color.text));
      title.setTextSize(15.0F);
      title.setTypeface((Typeface) null, 1);
      bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1.0F));
      root.addView(bar);

      TextView hint = new TextView(this.activity);
      hint.setVisibility(android.view.View.GONE);
      hint.setTextColor(this.col(color.muted));
      hint.setTextSize(11.0F);
      hint.setLineSpacing((float) this.dp(2), 1.0F);
      hint.setPadding(this.dp(14), 0, this.dp(14), this.dp(8));
      root.addView(hint);

      ScrollView sv = new ScrollView(this.activity);
      LinearLayout list = new LinearLayout(this.activity);
      list.setOrientation(1);
      list.setPadding(this.dp(14), 0, this.dp(14), this.dp(14));
      sv.addView(list);
      root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1.0F));

      if (items.isEmpty()) {
         TextView empty = new TextView(this.activity);
         empty.setText("还没有反馈。");
         empty.setTextColor(this.col(color.muted));
         empty.setTextSize(13.0F);
         empty.setLineSpacing((float) this.dp(3), 1.0F);
         list.addView(empty);
      }

      for (AppDatabase.GmFeedback f : items) {
         LinearLayout card = new LinearLayout(this.activity);
         card.setOrientation(1);
         GradientDrawable bg = new GradientDrawable();
         bg.setColor(this.col(color.surface));
         bg.setCornerRadius((float) this.dp(14));

         boolean isUnread = "unread".equals(f.status);
         bg.setStroke(this.dp(1), this.col(isUnread ? color.accent : color.border));
         card.setBackground(bg);
         card.setPadding(this.dp(14), this.dp(12), this.dp(14), this.dp(12));

         LinearLayout head = new LinearLayout(this.activity);
         head.setOrientation(0);
         head.setGravity(16);
         TextView tag = new TextView(this.activity);
         String[] catText = feedbackCategoryText(f.category);
         tag.setText(catText[0]);

         tag.setTextColor("bug".equals(f.category) ? this.col(color.danger) : this.col(color.accent));
         tag.setTextSize(10.0F);
         tag.setTypeface((Typeface) null, 1);
         GradientDrawable tbg = new GradientDrawable();
         tbg.setColor("bug".equals(f.category) ? 0x1AC93B3B : 0x1A2F6BE6);
         tbg.setCornerRadius((float) this.dp(6));
         tbg.setStroke(this.dp(1), this.col(color.border));
         tag.setBackground(tbg);
         tag.setPadding(this.dp(6), this.dp(2), this.dp(6), this.dp(2));
         head.addView(tag);
         TextView who = new TextView(this.activity);
         who.setText((f.avatar == null ? "" : f.avatar) + (f.author == null ? "成员" : f.author)
                 + " · " + this.hhmm(f.createdAt)
                 + (f.context != null && !f.context.isEmpty() ? " · " + f.context : ""));
         who.setTextColor(this.col(color.muted));
         who.setTextSize(11.0F);
         head.addView(who);
         card.addView(head);

         TextView t = new TextView(this.activity);
         t.setText(f.title == null ? "" : f.title);
         t.setTextColor(this.col(color.text));
         t.setTextSize(14.0F);
         t.setTypeface((Typeface) null, 1);
         t.setPadding(0, this.dp(6), 0, 0);
         card.addView(t);

         if (f.detail != null && !f.detail.trim().isEmpty()) {
            TextView d = new TextView(this.activity);
            d.setText(f.detail);
            d.setTextColor(this.col(color.text2));
            d.setTextSize(12.0F);
            d.setLineSpacing((float) this.dp(3), 1.0F);
            d.setPadding(0, this.dp(4), 0, 0);
            card.addView(d);
         }

         LinearLayout row = new LinearLayout(this.activity);
         row.setGravity(8388613);
         LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
         rlp.setMargins(0, this.dp(10), 0, 0);
         row.setLayoutParams(rlp);
         Button copy = this.smallCardButton("复制", drawable.btn_ghost, color.accent);
         copy.setOnClickListener((v) -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    this.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cm != null) {
               String txt = "[" + (f.author == null ? "成员" : f.author) + " · " + catText[1] + "]\n"
                       + (f.title == null ? "" : f.title) + "\n\n" + (f.detail == null ? "" : f.detail);
               cm.setPrimaryClip(android.content.ClipData.newPlainText("feedback", txt));
               this.showToast("已复制");
            }
         });
         row.addView(copy, new LinearLayout.LayoutParams(this.dp(64), this.dp(30)));
         card.addView(row);

         LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
         clp.setMargins(0, this.dp(8), 0, 0);
         list.addView(card, clp);
      }

      if (unread > 0) this.db.markGmFeedbackRead(roomId);
   }

   private static String[] feedbackCategoryText(String category) {
      if (category == null) return new String[]{"\u2753 异常", "异常"};
      switch (category) {
         case "bug": return new String[]{"\ud83d\udc1b BUG", "BUG"};
         case "suggestion": return new String[]{"\ud83d\udca1 建议", "改进建议"};
         case "tool": return new String[]{"\ud83e\uddf0 求工具", "想要的新工具"};
         case "order": return new String[]{"\ud83d\udd22 顺序", "发言顺序建议"};
         default: return new String[]{"\u2753 异常", "异常"};
      }
   }

   private void showAdminPage() {
      try { PhoneAwareness.noteInAppStep(this.activity, "群聊 · 管理员页"); } catch (Throwable ignored) { }
      if (this.currentRoomId <= 0L) return;

      final boolean isRebuild = (this.currentPage == 7 && this.liveAdminScroll != null);

      this.currentPage = 7;
      AppDatabase.GmRoom room = this.db.getGmRoom(this.currentRoomId);

      boolean wasNearBottom = true;
      int prevScrollY = 0;
      if (isRebuild) {
         try {
            if (this.liveAdminScroll != null) {

               prevScrollY = this.liveAdminScroll.getScrollY();
               View oldChild = this.liveAdminScroll.getChildAt(0);
               if (oldChild != null) {
                  wasNearBottom = oldChild.getHeight()
                          - (prevScrollY + this.liveAdminScroll.getHeight()) < this.dp(160);
               }
            }
         } catch (Exception ignored) { }
      } else {

         this.adminReadingHistory = false;
         this.adminUserTouching = false;
      }

      final boolean forceFollow = !isRebuild || this.adminForceFollowOnce;
      this.adminForceFollowOnce = false;
      final boolean keepFollowing = forceFollow
              || (wasNearBottom && !this.adminReadingHistory && !this.adminUserTouching);

      this.rebuildingAdminPage = true;
      this.setHeaderButtons(false);
      this.renderTarget.removeAllViews();
      LinearLayout root = new LinearLayout(this.activity);
      root.setOrientation(1);
      this.renderTarget.addView(root, new FrameLayout.LayoutParams(-1, -1));

      LinearLayout bar = new LinearLayout(this.activity);
      bar.setOrientation(0);
      bar.setGravity(16);
      bar.setPadding(this.dp(14), this.dp(10), this.dp(14), this.dp(4));
      TextView title = new TextView(this.activity);
      title.setText("\ud83e\uddd1\u200d\u2708\ufe0f 群管理员 · "
              + (room == null || room.title == null ? "群聊" : room.title));
      title.setTextColor(this.col(color.text));
      title.setTextSize(15.0F);
      title.setTypeface((Typeface) null, 1);
      bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1.0F));
      Button clear = this.ghostButton("\ud83d\uddd1 清空对话");
      clear.setTextSize(11.0F);
      clear.setOnClickListener((v) -> this.confirmDanger("清空管理员对话",
              "只清空你和管理员之间的对话记录，不影响群聊讨论内容。确定吗？", () -> {
                 this.db.clearGmAdminMessages(this.currentRoomId);
                 this.showAdminPage();
              }));
      bar.addView(clear);
      Button cfg = this.ghostButton("\u2699 管理员设置");
      cfg.setTextSize(11.0F);
      LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
      clp.setMarginStart(this.dp(6));
      cfg.setOnClickListener((v) -> this.showAdminModelPicker());
      bar.addView(cfg, clp);
      root.addView(bar);

      List<AppDatabase.GmAdminProposal> pend = this.db.listPendingGmAdminProposals(this.currentRoomId);
      if (!pend.isEmpty()) {

         java.util.Collections.sort(pend, (a, b) -> {
            int pa = kindPriority(a.kind), pb = kindPriority(b.kind);
            if (pa != pb) return pa - pb;
            return Long.compare(a.id, b.id);
         });
         LinearLayout pendBox = new LinearLayout(this.activity);
         pendBox.setOrientation(1);
         GradientDrawable pbg = new GradientDrawable();
         pbg.setColor(0x1AC93B3B);
         pbg.setCornerRadius((float) this.dp(14));
         pbg.setStroke(this.dp(1), this.col(color.danger));
         pendBox.setBackground(pbg);
         pendBox.setPadding(this.dp(14), this.dp(10), this.dp(14), this.dp(10));

         NestedLimitScrollView pendScroll = new NestedLimitScrollView(this.activity);
         pendScroll.setMaxHeightPx((int) ((float) this.activity.getResources()
                 .getDisplayMetrics().heightPixels * 0.42F));
         TextView pt = new TextView(this.activity);
         pt.setText("\u26a0\ufe0f 有 " + pend.size() + " 项改动等你确认"
                 );
         pt.setTextColor(this.col(color.danger));
         pt.setTextSize(13.0F);
         pt.setTypeface((Typeface) null, 1);
         pt.setLineSpacing((float) this.dp(3), 1.0F);
         pendBox.addView(pt);

         if (pend.size() > 1) {
            Button all = this.primaryButton("\u2714 全部按此顺序确认（" + pend.size() + " 项）",
                    this.col(color.onAccent));
            all.setTextSize(12.0F);
            LinearLayout.LayoutParams allp = new LinearLayout.LayoutParams(-1, -2);
            allp.setMargins(0, this.dp(8), 0, 0);
            all.setOnClickListener((v) -> this.confirmApplyAllProposals(pend));
            pendBox.addView(all, allp);
         }

         LinearLayout pendList = new LinearLayout(this.activity);
         pendList.setOrientation(1);
         pendScroll.addView(pendList, new LinearLayout.LayoutParams(-1, -2));
         int seq = 0;
         for (AppDatabase.GmAdminProposal p : pend) {
            ++seq;
            TextView pd = new TextView(this.activity);
            pd.setText(seq + ". 【" + kindNameOf(p.kind) + "】" + (p.summary == null ? "" : p.summary)
                    + "\n  " + this.describeProposal(p));
            pd.setTextColor(this.col(color.text2));
            pd.setTextSize(11.0F);
            pd.setLineSpacing((float) this.dp(2), 1.0F);
            pd.setPadding(0, this.dp(8), 0, 0);
            pendList.addView(pd);
            LinearLayout prow = new LinearLayout(this.activity);
            prow.setGravity(8388613);
            LinearLayout.LayoutParams prlp = new LinearLayout.LayoutParams(-1, -2);
            prlp.setMargins(0, this.dp(6), 0, 0);
            prow.setLayoutParams(prlp);
            Button reject = this.ghostButton("拒绝");
            reject.setTextColor(this.col(color.muted));
            reject.setOnClickListener((v) -> {
               this.db.setGmAdminProposalStatus(p.id, "rejected");
               this.showAdminPage();
               this.refreshAdminButtonBadge();
            });
            prow.addView(reject, new LinearLayout.LayoutParams(this.dp(70), this.dp(32)));
            Button allow = this.primaryButton("\u2714 允许", this.col(color.onAccent));
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(this.dp(80), this.dp(32));
            alp.setMarginStart(this.dp(8));
            allow.setOnClickListener((v) -> {
               String err = this.applyAdminProposal(p);
               if (err == null) {
                  this.db.setGmAdminProposalStatus(p.id, "applied");
                  this.showToast("已应用");
               } else {
                  this.showToast("应用失败：" + err);
               }
               this.showAdminPage();
               this.refreshAdminButtonBadge();
            });
            prow.addView(allow, alp);
            pendList.addView(prow);
         }
         LinearLayout.LayoutParams pslp = new LinearLayout.LayoutParams(-1, -2);
         pslp.setMargins(0, this.dp(2), 0, 0);
         pendBox.addView(pendScroll, pslp);
         LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
         plp.setMargins(this.dp(14), this.dp(4), this.dp(14), this.dp(6));
         pendBox.setLayoutParams(plp);
         root.addView(pendBox);
      }

      ScrollView sv = new ScrollView(this.activity);
      LinearLayout list = new LinearLayout(this.activity);
      list.setOrientation(1);
      list.setPadding(this.dp(14), this.dp(6), this.dp(14), this.dp(10));
      sv.addView(list);
      root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1.0F));

      this.liveAdminContainer = list;
      this.liveAdminScroll = sv;

      sv.setOnTouchListener((v, ev) -> {
         int act = ev.getActionMasked();
         if (act == 0) {

            this.adminUserTouching = true;
            this.updateAdminReadingHistoryFlag();
         } else if (act == 1 || act == 3) {
            this.adminUserTouching = false;

            this.updateAdminReadingHistoryFlag();
         }
         return false;
      });

      sv.setOnScrollChangeListener((v, sx, sy, osx, osy) -> {
         if (!this.adminUserTouching) this.updateAdminReadingHistoryFlag();
      });

      List<AppDatabase.GmAdminMessage> msgs = this.db.listGmAdminMessages(this.currentRoomId, 100);

      boolean trulyEmpty = msgs.isEmpty()
              && (this.pendingAdminUserText == null || this.pendingAdminUserText.trim().isEmpty())
              && this.liveAdminCard == null;
      if (trulyEmpty) {
         TextView empty = new TextView(this.activity);
          empty.setText("还没有对话。");
         empty.setTextColor(this.col(color.muted));
         empty.setTextSize(12.0F);
         empty.setLineSpacing((float) this.dp(3), 1.0F);
         list.addView(empty);
      }
      for (AppDatabase.GmAdminMessage m : msgs) {
         boolean isUser = "user".equals(m.role);

         LinearLayout wrap = new LinearLayout(this.activity);
         wrap.setOrientation(1);
         wrap.setGravity(isUser ? 8388613 : 8388611);

         if (!isUser && m.reasoning != null && !m.reasoning.trim().isEmpty()) {
            this.addGmSection(list, "\ud83d\udcad 思考过程", m.reasoning.trim(),
                    this.col(color.accentBright), 254766054, true, false,
                    ADMIN_SECTION_BASE + m.id, "thought");
         }
         if (!isUser && m.toolLog != null && !m.toolLog.trim().isEmpty()) {
            this.addToolSection(list, m.toolLog.trim(), ADMIN_SECTION_BASE + m.id);
         }

         boolean hasContent = m.content != null && !m.content.trim().isEmpty();
         if (hasContent) {
            TextView bubble = new TextView(this.activity);

            bubble.setText(MarkdownRenderer.render(m.content, this.col(isUser ? color.onAccent : color.text),
                    this.col(isUser ? color.onAccent : color.muted),
                    this.col(isUser ? color.onAccent : color.accent), false));
            bubble.setTextColor(this.col(isUser ? color.onAccent : color.text));
            bubble.setTextSize(13.0F);
            bubble.setLineSpacing((float) this.dp(3), 1.0F);
            bubble.setTextIsSelectable(true);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(this.col(isUser ? color.accent : color.surface));
            bg.setCornerRadius((float) this.dp(12));
            bubble.setBackground(bg);
            bubble.setPadding(this.dp(12), this.dp(9), this.dp(12), this.dp(9));
            LinearLayout.LayoutParams blp2 = new LinearLayout.LayoutParams(-2, -2);
            blp2.setMargins(isUser ? this.dp(40) : 0, this.dp(5), isUser ? 0 : this.dp(40), 0);
            bubble.setLayoutParams(blp2);
            wrap.addView(bubble);
         }
         list.addView(wrap);
      }

      if (this.pendingAdminUserText != null && !this.pendingAdminUserText.trim().isEmpty()) {
         LinearLayout pwrap = new LinearLayout(this.activity);
         pwrap.setOrientation(1);
         pwrap.setGravity(8388613);
         TextView pb = new TextView(this.activity);
         pb.setText(this.pendingAdminUserText);
         pb.setTextColor(this.col(color.onAccent));
         pb.setTextSize(13.0F);
         pb.setLineSpacing((float) this.dp(3), 1.0F);
         GradientDrawable pbg2 = new GradientDrawable();
         pbg2.setColor(this.col(color.accent));
         pbg2.setCornerRadius((float) this.dp(12));
         pb.setBackground(pbg2);
         pb.setPadding(this.dp(12), this.dp(9), this.dp(12), this.dp(9));
         LinearLayout.LayoutParams pblp = new LinearLayout.LayoutParams(-2, -2);
         pblp.setMargins(this.dp(40), this.dp(5), 0, 0);
         pb.setLayoutParams(pblp);
         pwrap.addView(pb);
         list.addView(pwrap);
      }

      if (this.liveAdminCard != null && this.liveAdminRoomId == this.currentRoomId) {
         android.view.ViewParent oldParent = this.liveAdminCard.getParent();
         if (oldParent instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) oldParent).removeView(this.liveAdminCard);
         }
         LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
         llp.setMargins(0, this.dp(6), this.dp(30), 0);
         list.addView(this.liveAdminCard, llp);
      }

      LinearLayout inputRow = new LinearLayout(this.activity);
      inputRow.setOrientation(0);
      inputRow.setGravity(16);
      inputRow.setPadding(this.dp(14), this.dp(6), this.dp(14), this.dp(12));
      final EditText et = new EditText(this.activity);
      et.setHint("跟管理员说点什么…");

      this.bindDraft(et, "admin_" + this.currentRoomId);
      et.setTextColor(this.col(color.text));
      et.setHintTextColor(this.col(color.muted));
      et.setTextSize(13.0F);
      et.setBackgroundResource(drawable.bg_edit);
      et.setPadding(this.dp(12), this.dp(9), this.dp(12), this.dp(9));
      et.setMaxLines(4);
      inputRow.addView(et, new LinearLayout.LayoutParams(0, -2, 1.0F));
      final Button send = this.primaryButton("发送", this.col(color.onAccent));
      LinearLayout.LayoutParams slp3 = new LinearLayout.LayoutParams(-2, -2);
      slp3.setMarginStart(this.dp(8));
      inputRow.addView(send, slp3);
      root.addView(inputRow);

      final boolean adminBusy = this.adminGeneratingRoomId == this.currentRoomId
              && this.adminGeneratingStop != null && !this.adminGeneratingStop.get();
      if (adminBusy) {
         send.setText("\u23f8");
         send.setContentDescription("暂停生成");
      }
      send.setOnClickListener((v) -> {
         if (this.adminGeneratingRoomId == this.currentRoomId
                 && this.adminGeneratingStop != null && !this.adminGeneratingStop.get()) {

            this.adminGeneratingStop.set(true);
            this.showToast("已暂停生成（已生成的部分会保留）");
            send.setText("暂停中…");
            return;
         }
         String text = et.getText() == null ? "" : et.getText().toString().trim();
         if (text.isEmpty()) return;
         et.setText("");
         this.sendToAdmin(text);
      });

      if (keepFollowing) {
         this.ui.postDelayed(() -> {
            if (this.adminUserTouching || this.adminReadingHistory) return;
            try { sv.fullScroll(android.view.View.FOCUS_DOWN); } catch (Exception ignored) { }
         }, 80);
      } else {

         final int restoreTo = prevScrollY;
         sv.post(() -> {
            if (sv == this.liveAdminScroll) sv.scrollTo(0, restoreTo);
         });
      }

      this.ui.post(() -> this.rebuildingAdminPage = false);

      this.db.markGmAdminMessagesRead(this.currentRoomId);
      this.refreshAdminButtonBadge();
      this.refreshAdminCardBadges();
   }

   private String readAssetPrompt(String fileName, String fallbackDesc) {
      if (fileName == null || fileName.trim().isEmpty()) return fallbackDesc == null ? "" : fallbackDesc;
      String[] dirs = new String[]{"devroles/", "engineers/", "personas/"};
      for (String dir : dirs) {
         java.io.InputStream is = null;
         try {
            is = this.activity.getAssets().open(dir + fileName + ".md");
            byte[] buf = new byte[is.available()];
            int n = is.read(buf);
            if (n > 0) return new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8);
         } catch (Exception ignored) {
         } finally {
            if (is != null) try { is.close(); } catch (Exception ignored) { }
         }
      }
      android.util.Log.w("VC_GM", "读不到内置角色提示词 assets: " + fileName);
      return fallbackDesc == null ? "" : fallbackDesc;
   }

   private boolean isCelebrityRole(String nm) {
      if (nm == null) return false;
      for (String[] p : PersonaLibrary.CELEBRITY_PERSONAS) {
         if (p != null && p.length >= 1 && nm.equals(p[0])) return true;
      }
      return false;
   }

   private String[] lookupRoleInLibraries(String nm) {
      if (nm == null || nm.trim().isEmpty()) return null;

      for (String[] r : DevRoleLibrary.DEV_ROLES) {
         if (r != null && r.length >= 4 && nm.equals(r[0])) {
            return new String[]{r[2], r[1], "@@ASSET:" + r[3]};
         }
      }

      for (String[] p : PersonaLibrary.CELEBRITY_PERSONAS) {
         if (p != null && p.length >= 4 && nm.equals(p[0])) {
            return new String[]{p[2], p[1], "@@ASSET:" + p[3]};
         }
      }

      for (String[] e : ExpertLibrary.EXPERTS) {
         if (e != null && e.length >= 4 && nm.equals(e[0])) {
            return new String[]{e[2], e[1], e[3]};
         }
      }
      return null;
   }

   private org.json.JSONObject buildAddMemberPayload(org.json.JSONObject src) {
      if (src == null) return null;
      String nm = src.optString("name", "").trim();
      if (nm.isEmpty()) return null;
      String desc = src.optString("roleDesc", "").trim();
      String prompt = src.optString("prompt", "").trim();
      String[] lib = lookupRoleInLibraries(nm);
      String libDesc = lib == null ? null : lib[0];
      String libAvatar = lib == null ? "" : lib[1];
      String libPrompt = lib == null ? null : lib[2];

      if (libDesc == null && (desc.isEmpty() || prompt.isEmpty())) return null;
      try {
         org.json.JSONObject o = new org.json.JSONObject();
         o.put("name", nm);
         o.put("roleDesc", libDesc != null ? libDesc : desc);
         o.put("prompt", libPrompt != null ? libPrompt : prompt);
         o.put("fromLibrary", libDesc != null);

         if (libAvatar != null && !libAvatar.trim().isEmpty()) o.put("avatar", libAvatar.trim());
         if (src.has("thinkLevel")) o.put("thinkLevel", ReasoningNegotiator.clampLevel(src.optInt("thinkLevel", 2)));
         if (src.has("canSearch")) o.put("canSearch", src.optBoolean("canSearch", true));
         String md = src.optString("model", "").trim();
         if (!md.isEmpty()) o.put("model", md);
         String pv = src.optString("provider", "").trim();
         if (!pv.isEmpty()) o.put("provider", pv);
         return o;
      } catch (Exception e) {
         return null;
      }
   }

   private String resolveLibraryPrompt(String nm, String promptOrAsset, String desc) {
      if (promptOrAsset == null) return desc == null ? "" : desc;
      if (!promptOrAsset.startsWith("@@ASSET:")) return promptOrAsset;
      String file = promptOrAsset.substring("@@ASSET:".length()).trim();
      String body = this.readAssetPrompt(file, null);
      if (body == null || body.trim().isEmpty()) {
         android.util.Log.w("VC_GM", "读不到角色「" + nm + "」的 assets 提示词: " + file);
         return desc == null ? "" : desc;
      }
      return body;
   }

   private String describeProposal(AppDatabase.GmAdminProposal p) {      try {
         if ("add_member".equals(p.kind)) {
            org.json.JSONArray arr = new org.json.JSONArray(p.payload == null ? "[]" : p.payload);
            if (arr.length() == 0) return "（空）";

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
               org.json.JSONObject o = arr.optJSONObject(i);
               if (o == null) continue;
               if (i > 0) sb.append("\n");
               sb.append("添加角色：").append(o.optString("name"))
                       .append(o.optBoolean("fromLibrary") ? "（来自角色库）" : "（自定义）")
                       .append("\n  定位：").append(o.optString("roleDesc"));
            }
            return sb.length() == 0 ? "（空）" : sb.toString();
         }
         if ("remove_member".equals(p.kind)) {
            org.json.JSONArray arr = new org.json.JSONArray(p.payload == null ? "[]" : p.payload);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
               org.json.JSONObject o = arr.optJSONObject(i);
               if (o == null) continue;
               if (i > 0) sb.append("\n");
               sb.append("移除角色：").append(o.optString("name"))
                       .append("（历史发言保留，角色库不受影响）");
            }
            return sb.length() == 0 ? "（空）" : sb.toString();
         }
         if ("order".equals(p.kind)) {
            org.json.JSONArray arr = new org.json.JSONArray(p.payload == null ? "[]" : p.payload);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
               if (i > 0) sb.append(" → ");
               sb.append(arr.getString(i));
            }
            return sb.length() == 0 ? "（空）" : sb.toString();
         } else {

            org.json.JSONArray arr = new org.json.JSONArray(p.payload == null ? "[]" : p.payload);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
               if (i > 0) sb.append("\n  ");
               org.json.JSONObject o = arr.getJSONObject(i);
               sb.append(o.optString("name")).append("：")
                       .append(o.optString("label")).append(" → ")
                       .append(this.proposalValueForDisplay(o.optString("field"), o.optString("value")));
            }
            return sb.length() == 0 ? "（空）" : sb.toString();
         }
      } catch (Exception e) {
         return "（解析失败：" + e.getMessage() + "）";
      }
   }

   private String proposalValueForDisplay(String field, String value) {
      if (value == null) return "";
      if (!"provider".equals(field)) return value;
      String[] parts = value.split("\\|");
      StringBuilder sb = new StringBuilder(parts.length == 0 ? "" : parts[0]);
      if (parts.length >= 2) {
         sb.append("｜密钥 ").append(AiClient.maskKey(parts[1]));
      }
      if (parts.length >= 3 && !parts[2].trim().isEmpty()) {
         sb.append("｜模型 ").append(parts[2].trim());
      }
      return sb.toString();
   }

   private static int kindPriority(String kind) {
      if ("add_member".equals(kind)) return 0;
      if ("order".equals(kind)) return 1;

      if ("remove_member".equals(kind)) return 3;
      return 2;
   }

   private static String kindNameOf(String kind) {
      if ("add_member".equals(kind)) return "\u2795 添加角色";
      if ("order".equals(kind)) return "\ud83d\udd22 发言顺序";
      if ("remove_member".equals(kind)) return "\ud83d\uddd1 移除角色";
      return "\u2699 专家配置";
   }

   private void confirmApplyAllProposals(List<AppDatabase.GmAdminProposal> pend) {
      final int total = pend.size();

      this.confirmDanger("确认应用全部 " + total + " 项？",
              "将按这个顺序依次生效：先添加角色 → 再排发言顺序 → 最后改配置。\n\n"
                      + "中途某一项失败会停下并告诉你停在哪一项，后面的不会继续。",
              "应用全部", () -> {
                 int done = 0;
                 for (AppDatabase.GmAdminProposal p : pend) {
                    String err = this.applyAdminProposal(p);
                    if (err != null) {
                       this.showToast("第 " + (done + 1) + " 项失败：" + err + "（后面的没有应用）");
                       this.showAdminPage();
                       this.refreshAdminButtonBadge();
                       return;
                    }
                    this.db.setGmAdminProposalStatus(p.id, "applied");
                    ++done;
                 }
                 this.showToast("已应用全部 " + done + " 项");
                 this.showAdminPage();
                 this.refreshAdminButtonBadge();
              });
   }

   private String applyAdminProposal(AppDatabase.GmAdminProposal p) {      try {
         if ("add_member".equals(p.kind)) {

            org.json.JSONArray arr = new org.json.JSONArray(p.payload == null ? "[]" : p.payload);
            if (arr.length() == 0) return "提案内容为空";

            java.util.Set<String> existingNames = new java.util.HashSet<>();
            for (AppDatabase.GmMember em : this.db.listGmMembers(p.roomId)) {
               if (em.name != null) existingNames.add(em.name);
            }
            int skippedDup = 0;
            int addedCount = 0;
            for (int ai = 0; ai < arr.length(); ai++) {
               org.json.JSONObject o = arr.optJSONObject(ai);
               if (o == null) continue;
               String nm = o.optString("name", "").trim();
               String desc = o.optString("roleDesc", "");
               String prompt = o.optString("prompt", "");
               if (nm.isEmpty()) continue;
               if (existingNames.contains(nm)) { ++skippedDup; continue; }
               existingNames.add(nm);
               if (prompt != null && prompt.startsWith("@@ASSET:")) {
                  String file = prompt.substring("@@ASSET:".length()).trim();
                  prompt = this.readAssetPrompt(file, desc);
               }

               if (prompt == null || prompt.trim().isEmpty()) {
                  prompt = desc == null ? "" : desc.trim();
                  android.util.Log.w("VC_GM", "角色「" + nm + "」提示词为空（assets 读不到），已回落身份定位");
               }

               String avatar = o.optString("avatar", "").trim();
               if (avatar.isEmpty()) avatar = "\ud83e\udd16";
               int thinkLv = o.has("thinkLevel") ? o.optInt("thinkLevel", 2) : 2;
               thinkLv = ReasoningNegotiator.clampLevel(thinkLv);
               boolean canSearch = o.has("canSearch") ? o.optBoolean("canSearch", true) : true;
               String model = o.optString("model", "").trim();
               String baseUrl = o.optString("baseUrl", "").trim();
               String apiKey = o.optString("apiKey", "").trim();

               String providerName = o.optString("provider", "").trim();
               if (!providerName.isEmpty()) {
                  for (AppDatabase.GmProvider gp : this.db.listGmProviders()) {
                     if (gp != null && providerName.equals(gp.name)) {
                        baseUrl = gp.baseUrl == null ? "" : gp.baseUrl;
                        apiKey = gp.apiKey == null ? "" : gp.apiKey;
                        break;
                     }
                  }
               }
               if (model.isEmpty()) model = this.settings.chatModel;
               if (baseUrl.isEmpty()) baseUrl = this.settings.chatBaseUrl;
               if (apiKey.isEmpty()) apiKey = this.settings.chatApiKey;
               long id = this.db.insertGmMember(p.roomId, nm, avatar, desc, prompt,
                       model, baseUrl, apiKey, canSearch, 0, false, 0);
               if (id <= 0) continue;

               this.db.setGmMemberActiveBySystem(id, !isCelebrityRole(nm));

               if (thinkLv != 2) {
                  AppDatabase.GmMember tmp = this.db.getGmMember(id);
                  if (tmp != null) { tmp.thinkLevel = thinkLv; this.db.updateGmMember(tmp); }
               }

               int max = 0;
               for (AppDatabase.GmMember m : this.db.listGmMembers(p.roomId)) max = Math.max(max, m.sortOrder);
               AppDatabase.GmMember nm2 = this.db.getGmMember(id);
               if (nm2 != null) { nm2.sortOrder = max + 1; this.db.updateGmMember(nm2); }
               ++addedCount;
               }
            if (addedCount == 0) {
               return skippedDup > 0
                       ? ("这些角色已经在本群里了（跳过 " + skippedDup + " 位），没有新增。")
                       : "没有可添加的角色（提案里的名字都是空的）";
            }

            this.ui.post(this::renderChat);
            return null;
         }
         if ("remove_member".equals(p.kind)) {

            org.json.JSONArray arr = new org.json.JSONArray(p.payload == null ? "[]" : p.payload);
            int delCount = 0;
            for (int i = 0; i < arr.length(); i++) {
               org.json.JSONObject o = arr.optJSONObject(i);
               long mid = o == null ? 0L : o.optLong("memberId", 0L);
               if (mid <= 0L) continue;
               AppDatabase.GmMember m = this.db.getGmMember(mid);
               if (m == null || m.isDefault) continue;
               this.db.deleteGmMember(mid);
               ++delCount;
            }
            if (delCount == 0) return "没有可移除的角色（内置角色不可删除）";

            this.ui.post(this::renderChat);
            return null;
         }
         if ("order".equals(p.kind)) {
            org.json.JSONArray arr = new org.json.JSONArray(p.payload == null ? "[]" : p.payload);
            List<AppDatabase.GmMember> members = this.db.listGmMembers(p.roomId);
            List<Long> ordered = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
               String name = arr.getString(i);
               for (AppDatabase.GmMember m : members) {
                  if (m.name != null && m.name.equals(name)) { ordered.add(m.id); break; }
               }
            }
            if (ordered.isEmpty()) return "没有匹配到任何专家名字";
            this.db.setGmMemberSortOrder(p.roomId, ordered);

            this.ui.post(this::renderChat);
            return null;
         } else {
            org.json.JSONArray arr = new org.json.JSONArray(p.payload == null ? "[]" : p.payload);
            int applied = 0;
            for (int i = 0; i < arr.length(); i++) {
               org.json.JSONObject o = arr.getJSONObject(i);
               long mid = o.optLong("memberId", 0L);
               String field = o.optString("field", "");
               String value = o.optString("value", "");
               if (mid <= 0L || field.isEmpty()) continue;
               AppDatabase.GmMember m = this.db.getGmMember(mid);
               if (m == null) continue;
               switch (field) {
                  case "model":
                     m.model = value;
                     this.db.updateGmMember(m);
                     applied++;
                     break;
                  case "think":
                     try { m.thinkLevel = ReasoningNegotiator.clampLevel(Integer.parseInt(value.trim())); }
                     catch (Exception ignored) { continue; }
                     this.db.updateGmMember(m);
                     applied++;
                     break;
                  case "active":
                     boolean act = "1".equals(value.trim());

                     this.db.setGmMemberActiveBySystem(mid, act);
                     applied++;
                     break;
                  case "provider":

                     String[] parts = value.split("\\|");
                     if (parts.length >= 2) {
                        m.baseUrl = parts[0].trim();
                        m.apiKey = parts[1].trim();
                        if (parts.length >= 3 && !parts[2].trim().isEmpty()) m.model = parts[2].trim();
                        this.db.updateGmMember(m);
                        applied++;
                     }
                     break;
                  default:
                     break;
               }
            }
            if (applied == 0) return "没有可应用的改动";

            this.ui.post(this::renderChat);
            return null;
         }
      } catch (Exception e) {
         return e.getMessage();
      }
   }

   private static List<String> arrToList(org.json.JSONArray arr) {
      List<String> out = new ArrayList<>();
      for (int i = 0; i < arr.length(); i++) out.add(arr.optString(i, ""));
      return out;
   }

   private void showAdminModelPicker() {
      final long roomId = this.currentRoomId;
      final AppDatabase.GmAdminConfig[] cfg = new AppDatabase.GmAdminConfig[]{
              this.db.getGmAdminConfig(roomId)};

      android.content.SharedPreferences legacy = this.activity.getSharedPreferences("vc_admin", 0);
      String lb = legacy.getString("adminBase", "").trim();
      if (cfg[0].baseUrl.isEmpty() && !lb.isEmpty()) {
         cfg[0].baseUrl = lb;
         cfg[0].apiKey = legacy.getString("adminKey", "").trim();
         cfg[0].model = legacy.getString("adminModel", "").trim();
         this.db.setGmAdminConfig(cfg[0]);
         legacy.edit().remove("adminBase").remove("adminKey").remove("adminModel").apply();
      }

      LinearLayout card = new LinearLayout(this.activity);
      card.setOrientation(1);
      GradientDrawable bg = new GradientDrawable();
      bg.setColor(this.col(color.surface));
      bg.setCornerRadius((float) this.dp(20));
      bg.setStroke(this.dp(1), this.col(color.border));
      card.setBackground(bg);
      card.setPadding(this.dp(16), this.dp(14), this.dp(16), this.dp(12));

      ScrollView sv = new ScrollView(this.activity);
      LinearLayout box = new LinearLayout(this.activity);
      box.setOrientation(1);
      sv.addView(box);

      TextView h = new TextView(this.activity);
      h.setText("\u2699 管理员设置");
      h.setTextColor(this.col(color.text));
      h.setTextSize(16.0F);
      h.setTypeface((Typeface) null, 1);
      box.addView(h);

      TextView tip = new TextView(this.activity);
      tip.setVisibility(android.view.View.GONE);
      tip.setTextColor(this.col(color.muted));
      tip.setTextSize(11.0F);
      tip.setLineSpacing((float) this.dp(3), 1.0F);
      tip.setPadding(0, this.dp(6), 0, this.dp(2));
      box.addView(tip);

      this.settingsSectionLabel(box, "\ud83e\udd16 模型");
      String modelShown = cfg[0].model.isEmpty()
              ? ("跟随主对话：" + (this.settings.model == null ? "-" : this.settings.model))
              : cfg[0].model;
      final TextView modelVal = new TextView(this.activity);
      modelVal.setText(modelShown);
      modelVal.setTextColor(this.col(color.text));
      modelVal.setTextSize(13.0F);
      modelVal.setPadding(0, this.dp(2), 0, 0);
      box.addView(modelVal);
      final TextView modelSub = new TextView(this.activity);
      modelSub.setText(cfg[0].baseUrl.isEmpty() ? "服务商：跟随主对话配置"
              : ("服务商：" + this.providerNameOf(cfg[0].baseUrl) + "\n" + cfg[0].baseUrl));
      modelSub.setTextColor(this.col(color.muted));
      modelSub.setTextSize(10.0F);
      modelSub.setLineSpacing((float) this.dp(2), 1.0F);
      modelSub.setPadding(0, this.dp(2), 0, 0);
      box.addView(modelSub);

      LinearLayout modelRow = new LinearLayout(this.activity);
      modelRow.setOrientation(0);
      modelRow.setPadding(0, this.dp(8), 0, 0);
      Button pickProv = this.primaryButton("\ud83c\udfe2 选择服务商 + 模型", this.col(color.onAccent));
      pickProv.setTextSize(12.0F);
      modelRow.addView(pickProv);
      Button follow = this.ghostButton("跟随主对话");
      follow.setTextSize(12.0F);
      LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-2, -2);
      flp.setMarginStart(this.dp(8));
      modelRow.addView(follow, flp);
      box.addView(modelRow);

      this.settingsSectionLabel(box, "\ud83e\udde0 思考档位");
      final int[] think = new int[]{cfg[0].thinkLevel};
      final Button thinkBtn = this.ghostButton("");
      thinkBtn.setTextSize(12.0F);
      thinkBtn.setTextColor(this.col(color.accent));
      Runnable syncThink = () -> {
         String nm = think[0] < 0 ? "跟随主对话（当前 " + this.thinkNameOf(this.settings.thinkLevel, this.settings) + "）"
                 : ReasoningNegotiator.levelName(think[0]);
         thinkBtn.setText("\ud83e\udde0 " + nm + "  ▾");
      };
      syncThink.run();
      LinearLayout thinkRow = new LinearLayout(this.activity);
      thinkRow.setOrientation(0);
      thinkRow.setPadding(0, this.dp(6), 0, 0);
      thinkRow.addView(thinkBtn);
      Button thinkFollow = this.ghostButton("跟随主对话");
      thinkFollow.setTextSize(12.0F);
      LinearLayout.LayoutParams tflp = new LinearLayout.LayoutParams(-2, -2);
      tflp.setMarginStart(this.dp(8));
      thinkRow.addView(thinkFollow, tflp);
      box.addView(thinkRow);
      TextView thinkHint = new TextView(this.activity);
      thinkHint.setVisibility(android.view.View.GONE);
      thinkHint.setTextColor(this.col(color.muted));
      thinkHint.setTextSize(10.0F);
      thinkHint.setLineSpacing((float) this.dp(2), 1.0F);
      thinkHint.setPadding(0, this.dp(4), 0, 0);
      box.addView(thinkHint);

      thinkBtn.setOnClickListener((v) -> {
         String[] names = ReasoningNegotiator.levelNames();
         String[] icons = new String[]{"\u26a1", "\ud83d\udca4", "\ud83e\udde0", "\ud83d\udd2c",
                 "\ud83d\udd2c", "\ud83d\ude80"};
         String[] descs = new String[]{"直接回答，最快", "思考少一点，快一些", "先思考再回答",
                 "思考更深入（深度思考）", "思考很深、慢一些", "思考最深入，最慢"};
         String[] items = new String[names.length];
         for (int i = 0; i < names.length; ++i) items[i] = icons[i] + " " + names[i];
         this.showStyledListPicker((View) null, "管理员的思考档位",
                 items, descs, think[0] < 0 ? -1 : think[0], (chosen) -> {
                    think[0] = chosen;
                    syncThink.run();
                 });
      });
      thinkFollow.setOnClickListener((v) -> {
         think[0] = -1;
         syncThink.run();
      });

      pickProv.setOnClickListener((v) -> {
         List<AppDatabase.GmProvider> providers = this.db.listGmProviders();
         if (providers.isEmpty()) {
            this.showToast("还没有服务商，先到「设置 → \ud83c\udfe2 服务商（全局）」添加");
            return;
         }
         String[] names = new String[providers.size()];
         String[] subs = new String[providers.size()];
         for (int i = 0; i < providers.size(); ++i) {
            AppDatabase.GmProvider p = providers.get(i);
            names[i] = "\ud83c\udfe2 " + p.name;
            subs[i] = p.baseUrl + " · " + p.modelList().size() + " 个模型";
         }
         this.showStyledListPicker((View) null, "选择服务商", names, subs, -1, (which) -> {
            AppDatabase.GmProvider p = providers.get(which);
            List<String> models = p.modelList();
            if (models.isEmpty()) {

               cfg[0].baseUrl = p.baseUrl;
               cfg[0].apiKey = p.apiKey == null ? "" : p.apiKey;
               this.db.setGmAdminConfig(cfg[0]);
               modelVal.setText(cfg[0].model.isEmpty() ? "跟随主对话" : cfg[0].model);
               modelSub.setText("服务商：" + p.name + "\n" + p.baseUrl);
               this.showToast("该服务商还没有模型，可到「服务商（全局）」一键获取，或先只定服务商");
               return;
            }
            String[] arr = models.toArray(new String[0]);
            String[] msubs = new String[arr.length];
            for (int i = 0; i < msubs.length; ++i) msubs[i] = "";
            this.showStyledListPicker((View) null, "选择模型（" + p.name + "）", arr, msubs, -1, (mi) -> {
               cfg[0].baseUrl = p.baseUrl;
               cfg[0].apiKey = p.apiKey == null ? "" : p.apiKey;
               cfg[0].model = arr[mi];
               this.db.setGmAdminConfig(cfg[0]);
               modelVal.setText(arr[mi]);
               modelSub.setText("服务商：" + p.name + "\n" + p.baseUrl);
            });
         });
      });
      follow.setOnClickListener((v) -> {
         cfg[0].baseUrl = "";
         cfg[0].apiKey = "";
         cfg[0].model = "";
         this.db.setGmAdminConfig(cfg[0]);
         modelVal.setText("跟随主对话：" + (this.settings.model == null ? "-" : this.settings.model));
         modelSub.setText("服务商：跟随主对话配置");
      });

      card.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1.0F));

      LinearLayout row = new LinearLayout(this.activity);
      row.setOrientation(0);
      row.setGravity(8388613);
      LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
      rlp.setMargins(0, this.dp(8), 0, 0);
      row.setLayoutParams(rlp);
      Button ok = this.primaryButton("保存", this.col(color.onAccent));
      Button cancel = this.ghostButton("取消");
      LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-2, -2);
      LinearLayout.LayoutParams clp3 = new LinearLayout.LayoutParams(-2, -2);
      clp3.setMarginStart(this.dp(8));
      row.addView(ok, olp);
      row.addView(cancel, clp3);
      card.addView(row);

      Dialog dlg = this.showDialogWide(card);
      cancel.setOnClickListener((v) -> dlg.dismiss());
      ok.setOnClickListener((v) -> {

         cfg[0].contextLimit = ADMIN_CONTEXT_FIXED;
         cfg[0].thinkLevel = think[0];
         this.db.setGmAdminConfig(cfg[0]);
         dlg.dismiss();
         this.showToast("已保存");
      });
   }

   private static final int ADMIN_CONTEXT_FIXED = 1000000;

   private static final int ADMIN_MAX_OUTPUT_FIXED = 110000;

   private void settingsSectionLabel(LinearLayout parent, String text) {
      TextView t = new TextView(this.activity);
      t.setText(text);
      t.setTextColor(this.col(color.accent));
      t.setTextSize(12.0F);
      t.setTypeface((Typeface) null, 1);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
      lp.setMargins(0, this.dp(14), 0, 0);
      parent.addView(t, lp);
   }

   private String providerNameOf(String baseUrl) {
      if (baseUrl == null || baseUrl.isEmpty()) return "-";
      try {
         for (AppDatabase.GmProvider p : this.db.listGmProviders()) {
            if (baseUrl.equals(p.baseUrl)) return p.name;
         }
      } catch (Exception ignored) { }
      return baseUrl;
   }

   private String thinkNameOf(int level, AppSettings st) {
      int v;
      if (level >= 0) {
         v = ReasoningNegotiator.clampLevel(level);
      } else if (st != null && !st.enableThinking) {
         v = ReasoningNegotiator.LEVEL_OFF;
      } else {
         v = (st != null && st.enableDeepThinking)
                 ? ReasoningNegotiator.LEVEL_HIGH : ReasoningNegotiator.LEVEL_MEDIUM;
      }
      String[] names = ReasoningNegotiator.levelNames();
      return names[Math.max(0, Math.min(names.length - 1, v))];
   }

   private void sendToAdmin(final String text) {
      final long roomId = this.currentRoomId;
      if (roomId <= 0L || text == null || text.trim().isEmpty()) return;
      final String userText = text.trim();

      this.pendingAdminUserText = userText;

      this.adminReadingHistory = false;
      this.adminUserTouching = false;
      this.adminForceFollowOnce = true;

      final java.util.concurrent.atomic.AtomicBoolean stopFlag = new java.util.concurrent.atomic.AtomicBoolean(false);
      this.adminGeneratingStop = stopFlag;
      this.adminGeneratingRoomId = roomId;
      this.showAdminPage();
      this.beginLiveAdminCard(roomId);

      this.bgExecute(this.executor, () -> {
         final StringBuilder thinkBuf = new StringBuilder();
         final StringBuilder toolBuf = new StringBuilder();
         final String[] streamed = new String[]{""};

         this.beginBgWork("admin");
         try {
            AppSettings st = this.adminSettings(roomId);
            int ctxLimit = this.adminContextLimit(roomId);

            List<AppDatabase.GmMember> members = this.db.listGmMembers(roomId);
            int active = 0;
            for (AppDatabase.GmMember m : members) if (m.active) active++;
            AppDatabase.GmRoom room = this.db.getGmRoom(roomId);
            String sys = AiClient.buildAdminSystemPrompt(
                    room == null ? "群聊" : room.title, members.size(), active)
                    + "\n\n【你能装载的上下文上限】约 " + ctxLimit + " token。超出时更早的内容会被裁掉。";

            List<AppDatabase.MessageItem> history = new ArrayList<>();
            for (AppDatabase.GmAdminMessage am : this.db.listGmAdminMessages(roomId, 1000)) {
               if (am.content == null || am.content.trim().isEmpty()) continue;
               AppDatabase.MessageItem mi = new AppDatabase.MessageItem(
                       0L, "user".equals(am.role) ? "user" : "ai", am.content, null, am.createdAt);
               history.add(mi);
            }

            if (!history.isEmpty()) {
               AppDatabase.MessageItem last = history.get(history.size() - 1);
               if ("user".equals(last.role) && userText.equals(last.content == null ? "" : last.content.trim())) {
                  history.remove(history.size() - 1);
               }
            }

            final AiClient.StreamListener listen = new AiClient.StreamListener() {
                       public void onReasoningDelta(String delta) {
                          thinkBuf.append(delta);
                          GroupChatManager.this.appendLiveAdminText(roomId, delta, true);
                       }

                       public void onContentDelta(String delta) {
                          streamed[0] += delta;
                          GroupChatManager.this.appendLiveAdminText(roomId, delta, false);
                       }

                       public void onToolStart(String name, String argSummary) {
                          String line = "调用 " + name
                                  + (argSummary != null && !argSummary.isEmpty() ? "（" + argSummary + "）" : "");
                          if (toolBuf.length() > 0) toolBuf.append("\n");
                          toolBuf.append(line);
                          GroupChatManager.this.appendLiveAdminTool(roomId, line, true);
                       }

                       public void onToolEnd(String name, String resultSummary) {
                          String line = "✅ " + name + " 返回"
                                  + (resultSummary != null && !resultSummary.isEmpty() ? "：" + resultSummary : "");
                          if (toolBuf.length() > 0) toolBuf.append("\n");
                          toolBuf.append(line);
                          GroupChatManager.this.appendLiveAdminTool(roomId, line, false);
                       }
                    };
            AiClient.ChatResult res = AiClient.chatWithToolsStream(
                    st, sys, history, userText,
                    AiClient.buildAdminTools(), (name, args) -> this.handleAdminTool(roomId, name, args),
                    adminIters(st), listen, stopFlag);

            res = this.recheckPhantomClaim(roomId, res, st, sys, history, userText, listen, stopFlag);

            final boolean paused = stopFlag.get();
            String answer = res != null && res.content != null && !res.content.trim().isEmpty()
                    ? res.content.trim()
                    : streamed[0].trim();
            if (answer.isEmpty()) {

               answer = paused
                       ? "（已暂停：本轮只生成了思考，还没有正文）"
                       : "（管理员没有返回内容）";
            }
            if (paused && !answer.startsWith("（已暂停")) answer = answer + "\n\n⏸ （已暂停，内容未生成完）";

            String toolLog = res != null && res.toolLog != null && !res.toolLog.trim().isEmpty()
                    ? res.toolLog.trim() : toolBuf.toString().trim();

            final boolean stillCurrent = (this.adminGeneratingStop == stopFlag);
            if (stillCurrent) {
               this.clearLiveAdminCard(roomId);
               this.adminGeneratingStop = null;
               this.adminGeneratingRoomId = 0L;
            }

            this.db.insertGmAdminMessage(roomId, "user", userText, "");
            this.pendingAdminUserText = null;
            this.db.insertGmAdminMessage(roomId, "assistant", answer,
                    res != null && res.reasoning != null && !res.reasoning.isEmpty()
                            ? res.reasoning : thinkBuf.toString(),
                    false, toolLog);
            this.ui.post(() -> {

               if (this.currentRoomId == roomId && this.currentPage == 7) this.showAdminPage();
               this.refreshAdminButtonBadge();
            });
            this.endBgWork("discuss");
         } catch (Exception e) {
            final String err = e.getMessage() == null ? e.toString() : e.getMessage();
            if (this.adminGeneratingStop == stopFlag) {
               this.clearLiveAdminCard(roomId);
               this.adminGeneratingStop = null;
               this.adminGeneratingRoomId = 0L;
            }
            this.db.insertGmAdminMessage(roomId, "user", userText, "");
            this.pendingAdminUserText = null;
            this.db.insertGmAdminMessage(roomId, "assistant", "❌ 出错了：" + err, "",
                    false, toolBuf.toString().trim());
            this.ui.post(() -> {
               if (this.currentRoomId == roomId && this.currentPage == 7) this.showAdminPage();
            });
            this.endBgWork("discuss");
         }
      });
   }

   private static final String[] DONE_WORDS = {"已提交", "提交了", "已经提交", "已生成", "已经生成",
           "已提交给你", "已为你提交", "已提案", "已经提案", "已排好", "排好了", "已调整", "已经调整",
           "已改好", "已切换", "已经切换", "已修改", "已经帮你改"};

   private static boolean containsAnyWord(String text, String[] words) {
      if (text == null || text.isEmpty()) return false;
      for (String w : words) if (text.contains(w)) return true;
      return false;
   }

   private AiClient.ChatResult recheckPhantomClaim(long roomId, AiClient.ChatResult res,
           AppSettings st, String sys, List<AppDatabase.MessageItem> history, String userText,
           AiClient.StreamListener listen, java.util.concurrent.atomic.AtomicBoolean stopFlag) {
      try {
         if (res == null || res.content == null || res.content.trim().isEmpty()) return res;
         if (stopFlag != null && stopFlag.get()) return res;
         String answer = res.content.trim();
         String what = this.phantomClaim(answer, res.toolLog, roomId);
         if (what == null) return res;
         android.util.Log.i("VC_GM", "假完成复检命中（" + what + "）：正文说做了，库里没有对应记录");
         List<AppDatabase.MessageItem> h2 = new ArrayList<>(history);
         h2.add(new AppDatabase.MessageItem(0L, "ai", answer, null, System.currentTimeMillis()));
         String fix = "【系统核对】你刚才的回答里说已经完成了「" + what + "」，但系统里**查不到对应记录**——"
                 + "这类改动只有**调用工具**才会真的生成提案，写在正文里不算。\n"
                 + "请立刻调用相应工具把它提交。如果你指的是**之前**已经提交过、或者用户已经确认过的那一条，"
                 + "就直接说明清楚，不要重复提交。";
         AiClient.ChatResult res2 = AiClient.chatWithToolsStream(st, sys, h2, fix,
                 AiClient.buildAdminTools(), (name, args) -> this.handleAdminTool(roomId, name, args),
                 adminIters(st), listen, stopFlag);
         if (res2 != null && res2.content != null && !res2.content.trim().isEmpty()) {
            res2.content = answer + "\n\n" + res2.content.trim();
            res2.toolLog = (res.toolLog == null ? "" : res.toolLog)
                    + (res2.toolLog == null ? "" : res2.toolLog);
            return res2;
         }
      } catch (Exception e) {
         android.util.Log.w("VC_GM", "假完成复检失败（保留原回答）: " + e.getMessage());
      }
      return res;
   }

   private String phantomClaim(String answer, String toolLog, long roomId) {
      if (answer == null || answer.isEmpty()) return null;
      if (!containsAnyWord(answer, DONE_WORDS)) return null;

      if (toolLog != null && toolLog.contains("propose_")) return null;
      boolean hasPending = false, hasOrder = false, hasConfig = false;
      try {
         List<AppDatabase.GmAdminProposal> pend = this.db.listPendingGmAdminProposals(roomId);
         if (pend != null) {
            for (AppDatabase.GmAdminProposal pr : pend) {
               if (pr == null) continue;
               hasPending = true;
               if ("order".equals(pr.kind)) hasOrder = true;
               if ("member_config".equals(pr.kind)) hasConfig = true;
            }
         }
      } catch (Exception ignored) { }
      if (hasPending && (hasOrder || hasConfig)) return null;
      if (answer.contains("提案") && !hasPending) return "提案";
      if (containsAnyWord(answer, new String[]{"顺序", "发言次序", "排序"}) && !hasOrder) return "发言顺序";
      if (containsAnyWord(answer, new String[]{"服务商", "供应商", "接口", "模型配置"}) && !hasConfig) return "服务商/配置";
      return null;
   }

   private LinearLayout liveAdminCard;

   private LinearLayout liveAdminContainer;
   private ScrollView liveAdminScroll;

   private TextView liveAdminThinkTag;
   private TextView liveAdminToolTag;

   private View liveAdminThinkWrap;
   private View liveAdminToolWrap;
   private TextView liveAdminThinkBody;

   private TextView liveAdminToolBody;

   private final StringBuilder liveAdminToolBuf = new StringBuilder();
   private TextView liveAdminContentBody;
   private long liveAdminRoomId;

   private volatile boolean adminUserTouching = false;
   private volatile boolean adminReadingHistory = false;

   private volatile boolean rebuildingAdminPage = false;

   private final java.util.Set<Long> adminApprovedRetry =
           java.util.Collections.synchronizedSet(new java.util.HashSet<Long>());

   private volatile boolean adminForceFollowOnce = false;

   private java.util.concurrent.atomic.AtomicBoolean adminGeneratingStop;

   private long adminGeneratingRoomId;

   private String pendingAdminUserText;

   private void beginLiveAdminCard(long roomId) {
      this.ui.post(() -> {

         if (this.liveAdminCard != null) {
            android.view.ViewParent op = this.liveAdminCard.getParent();
            if (op instanceof android.view.ViewGroup) {
               ((android.view.ViewGroup) op).removeView(this.liveAdminCard);
            }
            this.liveAdminCard = null;
         }
         this.liveAdminRoomId = roomId;
         if (this.liveAdminContainer == null) return;
         LinearLayout card = new LinearLayout(this.activity);
         card.setOrientation(1);
         GradientDrawable bg = new GradientDrawable();
         bg.setColor(this.col(color.surface));
         bg.setCornerRadius((float) this.dp(12));
         bg.setStroke(this.dp(1), this.col(color.border));
         card.setBackground(bg);
         card.setPadding(this.dp(12), this.dp(9), this.dp(12), this.dp(9));

         TextView who = new TextView(this.activity);
         who.setText("\ud83e\uddd1\u200d\u2708\ufe0f 管理员 思考中…");
         who.setTextColor(this.col(color.accent));
         who.setTextSize(11.0F);
         who.setTypeface((Typeface) null, 1);
         card.addView(who);

         this.liveAdminThinkTag = this.sectionTag("\ud83d\udcad 思考过程", this.col(color.accentBright));
         this.liveAdminThinkTag.setVisibility(8);
         card.addView(this.liveAdminThinkTag);
         this.liveAdminThinkBody = new TextView(this.activity);
         this.liveAdminThinkBody.setTextColor(this.col(color.muted));
         this.liveAdminThinkBody.setTextSize(10.0F);
         this.liveAdminThinkBody.setLineSpacing((float) this.dp(2), 1.0F);
         this.liveAdminThinkWrap = this.wrapMemberScroll(this.liveAdminThinkBody, 200);
         this.liveAdminThinkWrap.setVisibility(8);
         card.addView(this.liveAdminThinkWrap);

         this.liveAdminToolTag = this.sectionTag("\ud83d\udd27 工具调用", this.col(color.searchAccent));
         this.liveAdminToolTag.setVisibility(8);
         card.addView(this.liveAdminToolTag);
         TextView toolBody = new TextView(this.activity);
         toolBody.setTextColor(this.col(color.searchAccent));
         toolBody.setTextSize(10.0F);
         toolBody.setLineSpacing((float) this.dp(2), 1.15F);
         this.liveAdminToolBody = toolBody;
         this.liveAdminToolWrap = this.wrapMemberScroll(toolBody, 120);
         this.liveAdminToolWrap.setVisibility(8);
         card.addView(this.liveAdminToolWrap);
         this.liveAdminToolBuf.setLength(0);

         this.liveAdminContentBody = new TextView(this.activity);
         this.liveAdminContentBody.setTextColor(this.col(color.text));
         this.liveAdminContentBody.setTextSize(13.0F);
         this.liveAdminContentBody.setLineSpacing((float) this.dp(3), 1.0F);
         LinearLayout.LayoutParams cblp = new LinearLayout.LayoutParams(-1, -2);
         cblp.setMargins(0, this.dp(6), 0, 0);
         card.addView(this.liveAdminContentBody, cblp);

         this.liveAdminCard = card;
         LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
         lp.setMargins(0, this.dp(6), this.dp(30), 0);
         this.liveAdminContainer.addView(card, lp);
         this.scrollLiveAdminToBottom();
      });
   }

   private void appendLiveAdminText(long roomId, String delta, boolean isThink) {
      if (delta == null || delta.isEmpty() || roomId != this.liveAdminRoomId) return;
      this.ui.post(() -> {
         if (this.liveAdminCard == null) return;
         if (isThink) {

            this.liveAdminThinkTag.setVisibility(0);
            this.liveAdminThinkWrap.setVisibility(0);
            this.liveAdminThinkBody.append(delta);
         } else {
            this.liveAdminContentBody.append(delta);
         }
         this.requestAdminScrollFollow();
      });
   }

   private long adminLastScrollAt = 0L;
   private boolean adminScrollPosted = false;

   private void requestAdminScrollFollow() {
      long now = System.currentTimeMillis();
      long since = now - this.adminLastScrollAt;
      if (since >= LIVE_REPAINT_MS) {
         this.adminLastScrollAt = now;
         this.scrollLiveAdminToBottom();
         return;
      }
      if (this.adminScrollPosted) return;
      this.adminScrollPosted = true;
      this.ui.postDelayed(() -> {
         this.adminScrollPosted = false;
         this.adminLastScrollAt = System.currentTimeMillis();
         this.scrollLiveAdminToBottom();
      }, LIVE_REPAINT_MS - since);
   }

   private void appendLiveAdminTool(long roomId, String line, boolean running) {
      if (line == null || line.isEmpty() || roomId != this.liveAdminRoomId) return;
      this.ui.post(() -> {
         if (this.liveAdminToolBody == null) return;
         if (this.liveAdminToolBuf.length() > 0) this.liveAdminToolBuf.append("\n");
         this.liveAdminToolBuf.append(line);
         this.renderLiveAdminTool();
      });
   }

   private void renderLiveAdminTool() {
      if (this.liveAdminToolBody == null || this.liveAdminToolTag == null) return;
      String tool = this.liveAdminToolBuf.toString();
      boolean has = !tool.trim().isEmpty();
      this.liveAdminToolTag.setVisibility(has ? 0 : 8);
      if (this.liveAdminToolWrap != null) this.liveAdminToolWrap.setVisibility(has ? 0 : 8);
      if (!has) {
         this.liveAdminToolBody.setText("");
         return;
      }
      StringBuilder sb = new StringBuilder();
      int[] starts = new int[64];
      boolean[] isFb = new boolean[64];
      int n = 0;
      int fb = 0;
      for (String ln : tool.split("\n")) {
         if (ln.trim().isEmpty()) continue;
         String show = ln.replaceFirst("^\\[FB\\]\\s*", "").replaceFirst("^\\[TQ\\]\\s*", "")
                 .replaceFirst("^\\[API\\]\\s*", "");
         boolean isFallback = AiClient.isFallbackLine(ln);
         if (isFallback) { show = "⚠ 降级 · " + show; ++fb; }
         if (sb.length() > 0) sb.append("\n");
         if (n < starts.length) { starts[n] = sb.length(); isFb[n] = isFallback; ++n; }
         sb.append(show);
      }

      this.liveAdminToolTag.setText("\ud83d\udd27 工具调用"
              + (fb > 0 ? "（" + fb + "/" + n + " 条降级）" : ""));
      this.liveAdminToolTag.setTextColor(this.col(fb > 0 ? color.accentBright : color.searchAccent));
      String full = sb.toString();
      android.text.SpannableString sp = new android.text.SpannableString(full);
      for (int i = 0; i < n; ++i) {
         int s = starts[i];
         int e = (i + 1 < n) ? starts[i + 1] - 1 : full.length();
         if (e > s && isFb[i]) {
            sp.setSpan(new android.text.style.ForegroundColorSpan(this.col(color.accentBright)),
                    s, e, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
         }
      }
      this.liveAdminToolBody.setText(sp);
      this.scrollLiveAdminToBottom();
   }

   private void clearLiveAdminCard(long roomId) {
      this.ui.post(() -> {
         if (this.liveAdminCard != null && this.liveAdminContainer != null) {
            this.liveAdminContainer.removeView(this.liveAdminCard);
         }
         this.liveAdminCard = null;
         this.liveAdminThinkBody = null;
         this.liveAdminToolBody = null;
         this.liveAdminThinkTag = null;
         this.liveAdminToolTag = null;
         this.liveAdminThinkWrap = null;
         this.liveAdminToolWrap = null;
         this.liveAdminToolBuf.setLength(0);
         this.liveAdminContentBody = null;
      });
   }

   private void scrollLiveAdminToBottom() {
      if (this.liveAdminScroll == null) return;

      if (this.adminUserTouching) return;
      if (this.adminReadingHistory) return;
      this.liveAdminScroll.post(() -> {
         if (this.liveAdminScroll == null) return;
         if (this.adminUserTouching || this.adminReadingHistory) return;
         this.liveAdminScroll.fullScroll(130);
      });
   }

   private void updateAdminReadingHistoryFlag() {

      if (this.rebuildingAdminPage) return;
      if (this.liveAdminScroll == null) return;

      if (this.liveAdminScroll.getHeight() <= 0) return;
      View child = this.liveAdminScroll.getChildAt(0);
      if (child == null || child.getHeight() <= 0) return;
      try {
         boolean nearBottom = child.getHeight()
                 - (this.liveAdminScroll.getScrollY() + this.liveAdminScroll.getHeight()) < this.dp(80);
         this.adminReadingHistory = !nearBottom;
      } catch (Exception ignored) { }
   }

   private String handleAdminTool(long roomId, String name, org.json.JSONObject args) throws Exception {

      if ("web_search".equals(name) || "fetch_url".equals(name)) {
         return this.webToolHandler == null
                 ? "{\"success\":false,\"error\":\"联网工具未配置\"}"
                 : this.webToolHandler.handle(name, args);
      }
      if ("list_experts".equals(name)) {
         List<AppDatabase.GmMember> members = this.db.listGmMembers(roomId);
         org.json.JSONArray arr = new org.json.JSONArray();
         int order = 0;
         for (AppDatabase.GmMember m : members) {
            order++;
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("name", m.name == null ? "" : m.name);
            o.put("roleDesc", m.roleDesc == null ? "" : m.roleDesc);
            o.put("currentOrder", m.sortOrder > 0 ? m.sortOrder : "未排（按内置职能规则）");
            o.put("model", m.model == null ? "" : m.model);
            o.put("baseUrl", m.baseUrl == null ? "" : m.baseUrl);
            o.put("thinkLevel", m.thinkLevel + "（0关1轻度2中3高4极高5Ultra）");

            o.put("active", m.active ? "参与讨论" : "未参与");
            o.put("usedMsgs", m.usedMsgs);
            o.put("usedTokens", m.usedTokens);
            o.put("canSearch", m.canSearch);
            arr.put(o);
         }

         org.json.JSONArray provs = new org.json.JSONArray();
         for (AppDatabase.GmProvider p : this.db.listGmProviders()) {
            org.json.JSONObject po = new org.json.JSONObject();
            po.put("name", p.name);
            po.put("baseUrl", p.baseUrl);
            po.put("models", p.models);
            provs.put(po);
         }
         org.json.JSONObject out = new org.json.JSONObject();
         out.put("experts", arr);
         out.put("providers", provs);
         out.put("total", members.size());
         return out.toString();
      }
      if ("read_discussion".equals(name)) {
         int limit = args.optInt("limit", 60);
         if (limit <= 0 || limit > 300) limit = 60;

         boolean full = args.optBoolean("full", true);
         int maxChars = args.optInt("maxChars", 0);
         if (maxChars > 0 && maxChars < 200) maxChars = 200;
         if (maxChars > 100000) maxChars = 100000;
         long onlySeq = args.optLong("seq", 0L);
         List<AppDatabase.GmMessage> msgs = this.db.listGmMessages(roomId, limit);
         StringBuilder sb = new StringBuilder();
         int shown = 0;
         for (AppDatabase.GmMessage m : msgs) {
            if ("system".equals(m.kind)) continue;
            if (onlySeq > 0L && m.seq != onlySeq) continue;
            String body = AiClient.stripMarkersPublic(m.content).trim();
            if (body.isEmpty()) continue;
            if (!full && maxChars > 0 && body.length() > maxChars) {
               body = body.substring(0, maxChars) + "…（原文共 " + body.length()
                       + " 字，要看全用 read_discussion(full=true, seq=" + m.seq + ")）";
            }
            sb.append("#").append(m.seq).append(" [").append(m.speaker).append("] ").append(body).append("\n\n");
            ++shown;
         }
         if (shown == 0) {
            return onlySeq > 0L ? ("没有找到编号 #" + onlySeq + " 的发言。") : "（群里还没有发言）";
         }
         return sb.toString();
      }
      if ("read_plans".equals(name)) {
         List<AppDatabase.GmDoc> docs = this.db.listGmDocs(roomId);
         if (docs == null || docs.isEmpty()) return "（还没有导出过方案）";
         StringBuilder sb = new StringBuilder();
         for (AppDatabase.GmDoc d : docs) {
            sb.append("【").append(d.title == null ? "方案" : d.title).append("】");
            if (d.modelName != null && !d.modelName.isEmpty()) sb.append("（模型：").append(d.modelName).append("）");
            sb.append("\n");

            String c = this.db.getGmDocContent(d.id);
            sb.append(c).append("\n\n");
         }
         return sb.toString();
      }
      if ("read_usage".equals(name)) {
         StringBuilder sb = new StringBuilder();
         long total = 0;
         for (AppDatabase.GmMember m : this.db.listGmMembers(roomId)) {
            total += m.usedTokens;
         }

         int usedNow = 0;
         try { usedNow = this.estimateRoomTokens(roomId); } catch (Exception ignored) { }
         sb.append("【当前上下文占用】约 ").append(usedNow).append(" token");
         AppDatabase.GmRoom room0 = this.db.getGmRoom(roomId);
         if (room0 != null && room0.contextLimit > 0) {
            int pct = (int) ((long) usedNow * 100L / (long) room0.contextLimit);
            sb.append("（占上限 ").append(room0.contextLimit).append(" 的 ").append(pct).append("%）");
         }
         sb.append("\n【历史累计消耗】").append(total).append(" token"
                 + "（这是**账单总额**，从讨论开始到现在一共花掉的，"
                 + "与「还能装多少」无关；不要拿它去和上限比）\n\n各专家明细（按累计消耗降序）：\n");
         List<AppDatabase.GmMember> ms = this.db.listGmMembers(roomId);
         Collections.sort(ms, (a, b) -> Long.compare(b.usedTokens, a.usedTokens));
         for (AppDatabase.GmMember m : ms) {
            sb.append("· ").append(m.name)
                    .append("　发言 ").append(m.usedMsgs).append(" 次")
                    .append("　消耗 ").append(m.usedTokens).append(" tok")
                    .append("　模型 ").append(m.model == null ? "-" : m.model)
                    .append("　").append(m.active ? "参与讨论" : "未参与").append("\n");
         }
         if (room0 != null) {
            sb.append("\n上下文上限 ").append(room0.contextLimit).append(" tok");
         }
         sb.append("\n\n（判断「上下文是不是快满了」只看上面的**当前上下文占用**；"
                 + "累计消耗是历史账单。想看某位专家是否在浪费 token，用 read_expert_detail 看逐条明细。）");
         return sb.toString();
      }
      if ("read_feedback".equals(name)) {
         List<AppDatabase.GmFeedback> fbs = this.db.listGmFeedback(roomId);
         if (fbs.isEmpty()) return "（专家还没有提交反馈）";
         StringBuilder sb = new StringBuilder();
         for (AppDatabase.GmFeedback f : fbs) {
            sb.append("[").append(f.category).append("] ").append(f.author)
                    .append(f.context == null || f.context.isEmpty() ? "" : " " + f.context)
                    .append("\n  ").append(f.title);
            if (f.detail != null && !f.detail.isEmpty()) sb.append("\n  ").append(f.detail);
            sb.append("\n\n");
         }
         return sb.toString();
      }
      if ("propose_remove_member".equals(name)) {

         String raw = args.optString("names", "");
         if (raw.trim().isEmpty()) raw = args.optString("name", "");
         List<String> names = new ArrayList<>();
         for (String p : raw.split("[\\n,，、|;；]+")) {
            String s = p.trim();
            if (!s.isEmpty()) names.add(s);
         }
         if (names.isEmpty()) return "{\"success\":false,\"error\":\"names 不能为空（要移除的角色名，用 list_experts 的准确名字）\"}";
         List<AppDatabase.GmMember> members = this.db.listGmMembers(roomId);
         org.json.JSONArray arr = new org.json.JSONArray();
         List<String> rejected = new ArrayList<>();
         List<String> missing = new ArrayList<>();
         for (String n : names) {
            AppDatabase.GmMember hit = null;
            for (AppDatabase.GmMember m : members) {
               if (m.name != null && m.name.equals(n)) { hit = m; break; }
            }
            if (hit == null) { missing.add(n); continue; }
            if (hit.isDefault) { rejected.add(n); continue; }
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("memberId", hit.id);
            o.put("name", hit.name == null ? "" : hit.name);
            arr.put(o);
         }
         if (arr.length() == 0) {
            StringBuilder err = new StringBuilder("{\"success\":false,\"error\":\"没有可移除的角色");
            if (!rejected.isEmpty()) err.append("；").append(joinNames(rejected, 20)).append(" 是内置角色，不能删除（角色库是固定的）");
            if (!missing.isEmpty()) err.append("；").append(joinNames(missing, 20)).append(" 不在本群里");
            err.append("。\"}");
            return err.toString();
         }
         String summary = args.optString("summary", "").trim();
         StringBuilder nmList = new StringBuilder();
         for (int i = 0; i < arr.length(); i++) {
            if (i > 0) nmList.append("、");
            nmList.append(arr.optJSONObject(i).optString("name"));
         }
         this.db.insertGmAdminProposal(roomId, "remove_member", arr.toString(),
                 summary.isEmpty() ? ("移除角色：" + nmList) : summary);
         StringBuilder ok = new StringBuilder("已提交提案（等用户点「✔ 允许」后生效）：移除 "
                 + arr.length() + " 位角色 —— " + nmList
                 + "。历史发言会保留，角色库不受影响。");
         if (!rejected.isEmpty()) ok.append(" 注意：").append(joinNames(rejected, 20)).append(" 是内置角色，不能删除。");
         if (!missing.isEmpty()) ok.append(" 另有 ").append(joinNames(missing, 20)).append(" 不在本群里。");
         return ok.toString();
      }
      if ("propose_order".equals(name)) {
         String raw = args.optString("order", "");
         String summary = args.optString("summary", "");
         List<String> names = new ArrayList<>();
         for (String p : raw.split("[\\n,，、|;；]+")) {
            String s = p.trim();
            if (!s.isEmpty()) names.add(s);
         }
         if (names.isEmpty()) return "{\"success\":false,\"error\":\"order 不能为空\"}";
         List<AppDatabase.GmMember> members = this.db.listGmMembers(roomId);
         List<String> matched = new ArrayList<>();
         List<String> unmatched = new ArrayList<>();
         for (String n : names) {
            boolean ok = false;
            for (AppDatabase.GmMember m : members) {
               if (m.name != null && m.name.equals(n)) { matched.add(n); ok = true; break; }
            }
            if (!ok) unmatched.add(n);
         }
         if (matched.isEmpty()) {
            return "{\"success\":false,\"error\":\"名字一个都没匹配上，请用 list_experts 返回的准确名字。"
                    + "你给的：" + raw.replace("\"", "") + "\"}";
         }
         org.json.JSONArray arr = new org.json.JSONArray(matched);
         this.db.insertGmAdminProposal(roomId, "order", arr.toString(),
                 summary.isEmpty() ? "调整 " + matched.size() + " 位专家的发言顺序" : summary);
         StringBuilder msg = new StringBuilder("已提交提案（等你确认）：把 ")
                 .append(matched.size()).append(" 位专家改为这个顺序：")
                 .append(this.joinNames(matched, 40));
         if (!unmatched.isEmpty()) {
            msg.append("\n⚠️ 这些名字没匹配上、已忽略：").append(this.joinNames(unmatched, 20));
         }
         msg.append("\n请在上面点「✔ 允许」后生效。");
         return msg.toString();
      }
      if ("propose_member_config".equals(name)) {
         String raw = args.optString("changes", "");
         String summary = args.optString("summary", "");
         List<AppDatabase.GmMember> members = this.db.listGmMembers(roomId);
         org.json.JSONArray arr = new org.json.JSONArray();
         List<String> human = new ArrayList<>();
         List<String> bad = new ArrayList<>();
         for (String line : raw.split("\\r?\\n")) {
            String s = line.trim();
            if (s.isEmpty()) continue;
            String[] parts = s.split("\\|");
            if (parts.length < 3) { bad.add(s); continue; }
            String nm = parts[0].trim(), field = parts[1].trim().toLowerCase(java.util.Locale.ROOT), val = parts[2].trim();
            AppDatabase.GmMember hit = null;
            for (AppDatabase.GmMember m : members) {
               if (m.name != null && m.name.equals(nm)) { hit = m; break; }
            }
            if (hit == null) { bad.add(nm); continue; }
            String label, value = val;
            if ("provider".equals(field)) {

               String pname = val;
               String found = null;
               for (AppDatabase.GmProvider p : this.db.listGmProviders()) {
                  if (p.name != null && p.name.equals(pname)) {
                     found = p.baseUrl + "|" + (p.apiKey == null ? "" : p.apiKey);
                     break;
                  }
               }
               if (found == null) { bad.add(pname + "（服务商不存在）"); continue; }
               value = found;
               label = "服务商";
            } else if ("model".equals(field)) {
               label = "模型";
            } else if ("think".equals(field)) {
               label = "思考档位";
            } else if ("active".equals(field)) {
               label = "参与讨论";
               value = ("1".equals(val) || "参与讨论".equals(val)) ? "1" : "0";
            } else {
               bad.add(s + "（字段不认识）");
               continue;
            }
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("memberId", hit.id);
            o.put("name", hit.name);
            o.put("field", field);
            o.put("value", value);
            o.put("label", label);
            arr.put(o);
            human.add(hit.name + "的" + label + "→" + val);
         }
         if (arr.length() == 0) {
            return "{\"success\":false,\"error\":\"没有一条能应用，请检查格式（专家名|字段|新值）。问题行："
                    + this.joinNames(bad, 10) + "\"}";
         }
         this.db.insertGmAdminProposal(roomId, "member_config", arr.toString(),
                 summary.isEmpty() ? "调整 " + arr.length() + " 项专家配置" : summary);
         StringBuilder msg = new StringBuilder("已提交提案（等你确认）：").append(this.joinNames(human, 20));
         if (!bad.isEmpty()) msg.append("\n⚠️ 这些没处理：").append(this.joinNames(bad, 10));
         msg.append("\n请在上面点「✔ 允许」后生效。");
         return msg.toString();
      }
      if ("read_role_library".equals(name)) {

         String kw = args.optString("keyword", "").trim();
         String one = args.optString("name", "").trim();
         boolean withPrompt = args.optBoolean("withPrompt", false) || !one.isEmpty() || !kw.isEmpty();

         if (!one.isEmpty()) {
            String[] lib = lookupRoleInLibraries(one);
            if (lib == null) {

               for (AppDatabase.GmMember m : this.db.listGmMembers(roomId)) {
                  if (m.name != null && m.name.equals(one)) {
                     return "【" + one + "】（本群成员，非内置库角色）\n"
                             + "定位：" + (m.roleDesc == null ? "" : m.roleDesc) + "\n"
                             + "提示词：\n" + (m.prompt == null ? "（空）" : m.prompt);
                  }
               }
               return "（角色库里没有叫「" + one + "」的角色。用 keyword 模糊找，或 list_experts 看本群成员。）";
            }
            String prompt = resolveLibraryPrompt(one, lib[2], lib[0]);
            return "【" + one + "】头像 " + lib[1] + "\n定位：" + lib[0] + "\n完整提示词：\n" + prompt;
         }

         StringBuilder sb = new StringBuilder();
         int total = 0;
         sb.append("=== 内置开发角色（").append(DevRoleLibrary.DEV_ROLES.length).append(" 个）===\n");
         for (String[] r : DevRoleLibrary.DEV_ROLES) {
            if (r == null || r.length < 3) continue;
            String nm = r[0] == null ? "" : r[0];
            String desc = r[2] == null ? "" : r[2];
            if (!kw.isEmpty() && !nm.contains(kw) && !desc.contains(kw)) continue;
            sb.append("· ").append(nm).append("：").append(desc).append("\n");
            if (withPrompt) sb.append("  提示词：").append(resolveLibraryPrompt(nm, "@@ASSET:" + (r.length > 3 ? r[3] : ""), desc)).append("\n");
            ++total;
         }
         sb.append("\n=== 内置专家（").append(ExpertLibrary.EXPERTS.length).append(" 个）===\n");
         for (String[] e : ExpertLibrary.EXPERTS) {
            if (e == null || e.length < 3) continue;
            String nm = e[0] == null ? "" : e[0];
            String desc = e[2] == null ? "" : e[2];
            if (!kw.isEmpty() && !nm.contains(kw) && !desc.contains(kw)) continue;
            sb.append("· ").append(nm).append("：").append(desc).append("\n");
            if (withPrompt) sb.append("  提示词：").append(e.length > 3 ? e[3] : "").append("\n");
            ++total;
         }
         sb.append("\n=== 内置人格（").append(PersonaLibrary.CELEBRITY_PERSONAS.length).append(" 个）===\n");
         for (String[] p : PersonaLibrary.CELEBRITY_PERSONAS) {
            if (p == null || p.length < 3) continue;
            String nm = p[0] == null ? "" : p[0];
            String desc = p[2] == null ? "" : p[2];
            if (!kw.isEmpty() && !nm.contains(kw) && !desc.contains(kw)) continue;
            sb.append("· ").append(nm).append("：").append(desc).append("\n");
            if (withPrompt) sb.append("  提示词：").append(resolveLibraryPrompt(nm, "@@ASSET:" + (p.length > 3 ? p[3] : ""), desc)).append("\n");
            ++total;
         }
         if (total == 0) {
            return "（角色库里没有匹配「" + kw + "」的角色。可以换关键词，或直接用 name+roleDesc+prompt 自定义一个。）";
         }
         if (!withPrompt) {

            sb.append("\n共 ").append(total).append(" 个角色 —— **这就是全量清单**，没有省略任何一位。\n")
              .append("下一步：① 从上面挑出你打算加的人（名字必须一字不差）；")
              .append("② 想看某位的**完整提示词**、判断他会不会干这个活，"
                      + "再调 read_role_library 并传 name=那位角色的名字；")
              .append("③ 看完用 propose_add_member 提交提案。");
         }
         return sb.toString();
      }
      if ("propose_add_member".equals(name)) {

         org.json.JSONArray arr = new org.json.JSONArray();
         org.json.JSONArray many = args.optJSONArray("members");
         if (many != null && many.length() > 0) {
            for (int i = 0; i < many.length(); i++) {
               org.json.JSONObject src = many.optJSONObject(i);
               if (src == null) continue;
               org.json.JSONObject o = buildAddMemberPayload(src);
               if (o != null) arr.put(o);
            }
         } else {
            org.json.JSONObject o = buildAddMemberPayload(args);
            if (o != null) arr.put(o);
         }
         if (arr.length() == 0) {
            return "{\"success\":false,\"error\":\"没有可添加的角色。用 name（单人）或 members（多人数组，每项 {name}）参数。\"}";
         }
         StringBuilder names = new StringBuilder();
         for (int i = 0; i < arr.length(); i++) {
            if (i > 0) names.append("、");
            names.append(arr.optJSONObject(i).optString("name", ""));
         }
         String summary = args.optString("summary", "").trim();
         String nm = names.toString();
         this.db.insertGmAdminProposal(roomId, "add_member", arr.toString(),
                 summary.isEmpty()
                         ? (arr.length() > 1 ? ("添加 " + arr.length() + " 位角色：" + nm) : ("添加角色「" + nm + "」"))
                         : summary);

         int celebCnt = 0;
         for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject io2 = arr.optJSONObject(i);
            if (io2 != null && isCelebrityRole(io2.optString("name", ""))) ++celebCnt;
         }
         return "已提交提案（等用户点「✔ 允许」后一次性生效）：添加 " + arr.length() + " 位角色 —— " + nm + "。"
                 + (arr.length() > 1 ? "无需逐个确认，用户点一次即可全部加入。" : "请用户在上方点「✔ 允许」后生效。")
                 + (celebCnt > 0 ? "（其中 " + celebCnt + " 位是名人思维，进群后默认待命，需要用户在成员页打开才参与讨论。）" : "");
      }

      if ("read_questions".equals(name)) {
         StringBuilder sb = new StringBuilder();
         sb.append("本群提问账本（**权威数据**，与专家自述不符时一律以此为准）：\n\n");

         List<AppDatabase.GmInquiryItem> unanswered = new ArrayList<>();
         try { unanswered = this.db.listGmUnansweredQuestions(roomId); } catch (Exception ignored) { }
         sb.append("【还没被用户回答的：").append(unanswered.size()).append(" 条】\n");
         if (unanswered.isEmpty()) {
            sb.append("（无）\n");
         } else {
            for (int i = 0; i < unanswered.size(); i++) {
               AppDatabase.GmInquiryItem q = unanswered.get(i);
               sb.append(i + 1).append(". [").append(q.asker).append("] ")
                 .append(q.question == null ? "" : q.question.trim()).append("\n");
            }

         }

         sb.append("\n【已经得到用户回答的】\n");
         int answered = 0;
         try {
            for (AppDatabase.GmInquiry inq : this.db.listGmInquiries(roomId)) {
               if (inq == null || inq.items == null) continue;
               for (AppDatabase.GmInquiryItem it : inq.items) {
                  if (it == null || it.answer == null || it.answer.trim().isEmpty()) continue;
                  ++answered;
                  sb.append(answered).append(". [").append(it.asker).append("] ")
                    .append(it.question == null ? "" : it.question.trim()).append("\n")
                    .append("   用户回答：").append(it.answer.trim()).append("\n");
               }
            }
         } catch (Exception ignored) { }
         if (answered == 0) sb.append("（无）\n");
         sb.append("\n合计：待答 ").append(unanswered.size()).append(" 条，已回答 ").append(answered).append(" 条。");
         return sb.toString();
      }

      if ("read_progress".equals(name)) {
         Convergence c = this.computeConvergence(roomId, this.roomRuns.get(roomId));
         StringBuilder sb = new StringBuilder();
         sb.append("本群讨论进度（**权威数据**，与用户界面上看到的数字同源）：\n\n");
         sb.append("【汇总】参战 ").append(c.active).append(" 人 · 已确认 ")
           .append(c.confirmed).append(" 人（**全员确认才是讨论结束**）\n");
         if (!c.notConfirmed.isEmpty()) {
            sb.append("还没确认（").append(c.notConfirmed.size()).append(" 人）：")
              .append(this.joinNames(c.notConfirmed, 20)).append("\n");
         } else {
            sb.append("还没确认：（无，全员已确认）\n");
         }
         sb.append("\n【逐人明细】\n");
         try {
            for (AppDatabase.GmMember m : this.db.listGmMembers(roomId)) {
               if (!m.active) continue;

               sb.append("· ").append(m.name).append("：")
                 .append(m.confirmedRound > 0
                         ? ("已确认（第 " + m.confirmedRound + " 轮）")
                         : "未确认").append("\n");
            }
         } catch (Exception ignored) { }
         sb.append("\n⚠️ 判断依据：确认状态取自**系统登记**（由 confirm_convergence 工具写入，用户界面底部的「✅ 确认 N/M」就是它）。专家发言里说「我认可了 / 我已确认」只代表他**自己以为**确认了 —— 若与上面的登记不符，一律以本工具为准。");
         sb.append("\n⚠️ 用户发的群消息**不会**再自动重置任何人的确认状态（旧规则已废除）。"
                 + "如果用户发了一条重要消息、希望某些专家重新过一遍，"
                 + "用 request_reenroll 把名单写上去（名单以用户说的为准）。");
         return sb.toString();
      }

      if ("read_expert_detail".equals(name)) {
         String wantName = args.optString("name", "").trim();
         List<AppDatabase.GmMember> ms = this.db.listGmMembers(roomId);
         StringBuilder sb = new StringBuilder();
         if (wantName.isEmpty()) {

            sb.append("全群用量摘要（按累计 token 降序）：\n");
            List<AppDatabase.GmMember> sorted = new ArrayList<>(ms);
            java.util.Collections.sort(sorted, (a, b) ->
                    Long.compare(b.usedTokens, a.usedTokens));
            for (AppDatabase.GmMember m : sorted) {
               if (m.usedTokens <= 0) continue;
               sb.append("· ").append(m.name).append("：发言 ").append(m.usedMsgs)
                 .append(" 条，累计 ").append(m.usedTokens).append(" token")
                 .append(m.active ? "" : "（未参与）").append("\n");
            }
            sb.append("\n提示：用 read_expert_detail 传名字可看某人的逐条明细。");
            return sb.toString();
         }
         AppDatabase.GmMember target = null;
         for (AppDatabase.GmMember m : ms) {
            if (m.name != null && m.name.equals(wantName)) { target = m; break; }
         }
         if (target == null) {
            return "{\"success\":false,\"error\":\"没有叫「" + wantName + "」的专家。用 list_experts 查准确名字。\"}";
         }
         sb.append("专家「").append(target.name).append("」的发言明细：\n")
           .append("配置：模型=").append(target.model)
           .append("，思考档位=").append(target.thinkLevel)
           .append("，可联网=").append(target.canSearch ? "是" : "否")
           .append("，状态=").append(target.active ? "参与讨论" : "未参与").append("\n\n");
         List<AppDatabase.GmMessage> msgs = this.db.listGmMessages(roomId, 1500);
         int idx = 0;
         for (AppDatabase.GmMessage m : msgs) {
            if (m.memberId != target.id) continue;
            ++idx;
            String body = AiClient.stripMarkersPublic(m.content);
            boolean suspicious = isLikelyDegenerate(body);
            sb.append("#").append(m.seq)
              .append(m.isThought ? " [仅思考]" : " [发言]")
              .append(" 长度=").append(body.length()).append(" 字")
              .append("，消耗=").append(m.tokens).append(" token");
            if (m.durationMs > 0) sb.append("，耗时=").append(m.durationMs / 1000).append("s");
            if (suspicious) sb.append("  ⚠️ **疑似生成异常（内容有大段自我重复）**");
            sb.append("\n");
         }
         if (idx == 0) sb.append("（这位专家还没有任何发言记录）\n");
         return sb.toString();
      }

      if ("read_anomalies".equals(name)) {
         int limit = args.optInt("limit", 30);
         if (limit <= 0 || limit > 200) limit = 30;
         List<AppDatabase.GmAnomaly> list = this.db.listGmAnomalies(roomId, limit);
         if (list.isEmpty()) return "本群暂无异常记录（说明至今没发生过生成循环 / 工具失败 / 调用失败）。";
         StringBuilder sb = new StringBuilder();
         sb.append("本群异常日志（最新在前，共 ").append(this.db.countGmAnomalies(roomId))
           .append(" 条，显示最近 ").append(list.size()).append(" 条）：\n\n");
         java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US);
         for (AppDatabase.GmAnomaly a : list) {
            String kindName;
            if ("repeat".equals(a.kind)) kindName = "生成循环";
            else if ("repeat_retry".equals(a.kind)) kindName = "循环重跑";
            else if ("tool_fail".equals(a.kind)) kindName = "工具失败";
            else if ("api_fail".equals(a.kind)) kindName = "调用失败";
            else kindName = a.kind;
            sb.append("· ").append(fmt.format(new java.util.Date(a.createdAt)))
              .append("　第").append(a.round).append("轮　").append(a.memberName)
              .append("　【").append(kindName).append("】").append(a.detail).append("\n");
            if (a.sample != null && !a.sample.trim().isEmpty()) {
               String s = a.sample.replace("\n", " ");
               sb.append("      重复片段开头：").append(s.length() > 120 ? s.substring(0, 120) + "…" : s).append("\n");
            }
         }
         sb.append("\n（这些只是历史留痕，不代表当前状态。要看某条发言的完整内容用 read_discussion。）");
         return sb.toString();
      }

      if ("read_basic_info".equals(name)) {
         return this.adminReadBasicInfo(roomId);
      }
      if ("apply_basic_info".equals(name)) {
         return this.adminApplyBasicInfo(roomId, args);
      }

      if ("search_user_archive".equals(name) || "fetch_user_archive".equals(name)) {
         return this.handleGroupTool(roomId, name, args, "管理员");
      }

      if ("retry_failed_role".equals(name)) {
         String failed = this.pendingFailureRoleName(roomId);
         if (failed == null) {
            return "{\"success\":false,\"error\":\"当前没有待处置的失败——讨论要么在正常跑，"
                    + "要么已经跑完了。不需要重试。（如果你看到的是历史记录里的旧失败，忽略即可。）\"}";
         }
         try {

            this.adminApprovedRetry.add(roomId);
            this.db.setGmRoomBlockedUntil(roomId, 0L);
            final boolean wasRunning = this.isRoomRunning(roomId);
            if (wasRunning) {
               this.ui.post(() -> this.refreshStartButton());
            } else {
               this.adminApprovedRetry.remove(roomId);
               this.ui.post(this::startDiscussion);
            }
            android.util.Log.i("VC_GM", "管理员触发重试：角色=" + failed + " 线程活着=" + wasRunning);
            this.db.insertGmAnomaly(roomId, 0, 0L, "管理员", "admin_retry",
                    "管理员判断可以再试，已触发重试「" + failed + "」（线程活着=" + wasRunning + "）", null);
            return "{\"success\":true,\"result\":\"已触发重试：接下来会从「" + failed
                    + "」接着跑。你现在应该给用户写一条消息说明：你为什么要重试、"
                    + "你判断这次失败更像是偶发问题还是配置问题。\"}";
         } catch (Exception e) {
            this.adminApprovedRetry.remove(roomId);
            return "{\"success\":false,\"error\":\"触发重试时出错：" + e.getMessage() + "\"}";
         }
      }

      if ("request_reenroll".equals(name)) {
         String raw = args.optString("names", "");
         String reason = args.optString("reason", "").trim();
         List<AppDatabase.GmMember> pool = this.db.listGmMembers(roomId);
         List<String> done = new ArrayList<>();
         List<String> bad = new ArrayList<>();
         List<String> alreadyOpen = new ArrayList<>();
         for (String line : raw.split("[\\r\\n、,，;；]+")) {
            String nm = line.trim();
            if (nm.isEmpty()) continue;
            AppDatabase.GmMember hit = null;
            for (AppDatabase.GmMember m : pool) {
               if (m.name != null && m.name.equals(nm)) { hit = m; break; }
            }
            if (hit == null) { bad.add(nm); continue; }
            if (!hit.active) { bad.add(nm + "（未参与讨论）"); continue; }
            if (hit.confirmedRound <= 0) { alreadyOpen.add(nm); continue; }
            this.db.setGmMemberConfirmedRound(hit.id, 0);
            done.add(nm);
         }
         if (!done.isEmpty()) {

            StringBuilder msg = new StringBuilder();
            msg.append("用户通过管理员让 ").append(done.size()).append(" 位专家重新参与讨论：");
            msg.append(String.join("、", done));
            if (!reason.isEmpty()) msg.append("\n原因：").append(reason);
            msg.append("\n（他们的确认状态已重置为「未确认」；请对照当前方案与用户的最新消息，"
                    + "觉得需要调整就直接发言，确认没问题就再确认一次。）");
            this.db.insertGmMessage(roomId, 0L, "系统", "\ud83d\udd01", msg.toString(), false, "system", 0);
            this.ui.post(this::renderChat);
         }
         org.json.JSONObject out = new org.json.JSONObject();
         out.put("success", true);
         out.put("reenrolled", new org.json.JSONArray(done));
         if (!alreadyOpen.isEmpty()) out.put("alreadyUnconfirmed", new org.json.JSONArray(alreadyOpen));
         if (!bad.isEmpty()) out.put("notFound", new org.json.JSONArray(bad));
         out.put("note", done.isEmpty()
                 ? "没有人的状态被改变。若这些专家本来就没确认，直接打开讨论开关让他们继续就行。"
                 : "已重置为未确认。请告诉用户：打开「▶ 讨论」后他们会重新看到最新内容并自行判断。");
         return out.toString();
      }

      return "{\"success\":false,\"error\":\"未知工具 " + name + "\"}";
   }

   private String adminReadBasicInfo(long roomId) {
      StringBuilder sb = new StringBuilder();
      sb.append("{\"basic_info\":[");
      try {
         List<AppDatabase.GmBasicInfo> all = this.db.listGmBasicInfo();
         java.util.Set<Long> injected = this.db.getInjectedBasicInfoIds(roomId);
         for (int i = 0; i < all.size(); i++) {
            AppDatabase.GmBasicInfo b = all.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"id\":").append(b.id)
              .append(",\"title\":").append(org.json.JSONObject.quote(b.title == null ? "" : b.title))
              .append(",\"content\":").append(org.json.JSONObject.quote(b.content == null ? "" : b.content))
              .append(",\"category\":").append(org.json.JSONObject.quote(b.category == null ? "" : b.category))
              .append(",\"already_injected\":").append(injected.contains(b.id));
         }
      } catch (Exception e) {
         android.util.Log.w("VC_GM", "adminReadBasicInfo 读基础信息失败: " + e.getMessage());
      }
      sb.append("],\"user_archive_demands\":[");
      try {

         List<AppDatabase.GmQaRoomBrief> briefs = this.db.searchGmQaRooms("", 100);
         boolean first = true;
         for (AppDatabase.GmQaRoomBrief b : briefs) {
            if (b.roomId == roomId) continue;
            if (!first) sb.append(",");
            first = false;
            sb.append("{\"demand_id\":").append(b.roomId)
              .append(",\"demand\":").append(org.json.JSONObject.quote(
                      b.roomTitle == null ? ("需求#" + b.roomId) : b.roomTitle))
              .append(",\"pairs\":").append(b.pairCount)
              .append(",\"answered\":").append(b.answeredCount).append("}");
         }
      } catch (Exception e) {
         android.util.Log.w("VC_GM", "adminReadBasicInfo 读信息库失败: " + e.getMessage());
      }
      sb.append("],\"hint\":");
      sb.append(org.json.JSONObject.quote(
              "basic_info = 用户的基础事实（服务器/域名/营业执照…）。"
              + "你判断当前这个群需不需要哪几条，然后用 apply_basic_info 传 ids 注入；"
              + "已注入过的（already_injected=true）不用重复注入。\n"
              + "user_archive_demands = 用户在**其它群**提过的需求清单（demand_id + 该需求下有多少组问答）。"
              + "要看某个需求下具体「谁问了什么、用户怎么答的」，用 fetch_user_archive 传 demand_id 抓取"
              + "（可加 keyword 只看相关的，或加 offset/limit 分页）。\n"
              + "⚠️ 不要把所有需求都抓一遍——先看清单判断哪些与当前群相关，再抓那几个。"));
      sb.append("}");
      return sb.toString();
   }

   private String adminApplyBasicInfo(long roomId, org.json.JSONObject args) {
      try {

         if (args == null || !args.has("ids")) {
            return "{\"success\":false,\"error\":\"缺少 ids 参数。要注入哪几条基础信息，请用 read_basic_info "
                    + "拿到的 id 以逗号分隔传给 ids（例如 ids=\\\"3,7\\\"）。"
                    + "确实想清空本群公告时，请显式传 ids=\\\"\\\"（空字符串）。\"}";
         }
         String idsStr = args.optString("ids", "").trim();
         java.util.Set<Long> want = new java.util.HashSet<>();
         if (!idsStr.isEmpty()) {
            for (String p : idsStr.split(",")) {
               try { long v = Long.parseLong(p.trim()); if (v > 0) want.add(v); }
               catch (Exception ignored) { }
            }
         }
         java.util.Set<Long> before = this.db.getInjectedBasicInfoIds(roomId);
         this.db.setInjectedBasicInfoIds(roomId, want);

         StringBuilder names = new StringBuilder();
         int matched = 0;
         try {
            for (AppDatabase.GmBasicInfo b : this.db.listGmBasicInfo()) {
               if (!want.contains(b.id)) continue;
               ++matched;
               if (names.length() > 0) names.append("、");
               names.append(b.title == null ? "" : b.title.trim());
            }
         } catch (Exception ignored) { }
         int added = 0, removed = 0;
         for (Long id : want) if (!before.contains(id)) ++added;
         for (Long id : before) if (!want.contains(id)) ++removed;

         String unknownNote = want.size() > matched
                 ? "（其中 " + (want.size() - matched) + " 个 id 在基础信息库里不存在，已忽略，"
                   + "请用 read_basic_info 核对后重传）"
                 : "";
         String note;
         if (want.isEmpty()) {
            note = "管理员已清空本群的基础信息公告。";
         } else {
            note = "管理员把 " + matched + " 条基础信息设为本群公告（" + names + "）"
                    + (added > 0 || removed > 0 ? "；本次新增 " + added + " 条、移除 " + removed + " 条。" : "。")
                    + unknownNote;
         }
         this.db.insertGmMessage(roomId, 0L, "系统", "\ud83d\udccc", note, false, "system", 0);
         this.ui.post(this::renderChat);
         return new org.json.JSONObject()
                 .put("success", true)
                 .put("injected_count", matched)
                 .put("message", note + " 下一轮起，所有角色都会在上下文顶部看到这些事实，"
                         + "不会再为它们提问。你应该给用户写一条说明：你选了哪几条、为什么。")
                 .toString();
      } catch (Exception e) {
         return "{\"success\":false,\"error\":\"注入基础信息时出错：" + e.getMessage() + "\"}";
      }
   }

   private boolean isLikelyDegenerate(String body) {
      if (body == null || body.length() < 3000) return false;

      int mid = body.length() / 2;
      String sample = body.substring(Math.max(0, mid - 100), Math.min(body.length(), mid + 100));
      int c = 0, from = 0;
      while (true) {
         int i = body.indexOf(sample, from);
         if (i < 0) break;
         ++c;
         from = i + sample.length();
         if (c >= 5) return true;
      }
      return false;
   }

   private void processRoundEndOrderRequests(long roomId, int round) {
      List<AppDatabase.GmFeedback> all;
      try { all = this.db.listGmFeedback(roomId); } catch (Exception e) { return; }

      final List<AppDatabase.GmFeedback> orders = new ArrayList<>();
      for (AppDatabase.GmFeedback f : all) {
         if (!"order".equals(f.category)) continue;
         if (this.handledOrderFeedback.contains(f.id)) continue;
         orders.add(f);
      }
      if (orders.isEmpty()) return;

      for (AppDatabase.GmFeedback f : orders) this.handledOrderFeedback.add(f.id);

      android.util.Log.i("VC_GM", "轮末发现 " + orders.size() + " 条顺序类反馈，交给管理员分析 room=" + roomId);

      StringBuilder names = new StringBuilder();
      for (AppDatabase.GmFeedback f : orders) {
         if (names.length() > 0) names.append("、");
         names.append(f.author == null ? "成员" : f.author);
      }
      this.db.insertGmMessage(roomId, 0L, "系统", "\ud83d\udd22",
              "本轮有 " + orders.size() + " 位专家对发言顺序提出了建议（" + names + "），已交给管理员评估。",
              false, "system", 0);
      this.ui.post(this::renderChat);

      this.bgExecute(this.executor, () -> {
         this.beginBgWork("order");
         try {
            this.runAdminOrderReview(roomId, round, orders);
         } catch (Exception e) {
            android.util.Log.w("VC_GM", "管理员顺序评估失败: " + e.getMessage());
         } finally {
            this.endBgWork("discuss");
         }
      });
   }

   private final java.util.Set<Long> handledOrderFeedback =
           java.util.Collections.synchronizedSet(new java.util.HashSet<Long>());

   private void runAdminOrderReview(long roomId, int round, List<AppDatabase.GmFeedback> orders) throws Exception {
      AppDatabase.GmRoom room = this.db.getGmRoom(roomId);
      List<AppDatabase.GmMember> members = this.db.listGmMembers(roomId);
      int active = 0;
      for (AppDatabase.GmMember m : members) if (m.active) active++;
      String sys = AiClient.buildAdminSystemPrompt(
              room == null ? "群聊" : room.title, members.size(), active);

      StringBuilder task = new StringBuilder();
      task.append("【自动任务】第 ").append(round).append(" 轮结束，以下专家对发言顺序提出了建议。\n");
      task.append("请你：① 用 list_experts 看清当前顺序；② 判断这些建议是否合理（你是专业人士，不是有求必应——"
              + "不合理的要说明为什么不改）；③ 用 propose_order 提交提案（如果确实该调）；"
              + "④ 用一段话向用户汇报：谁提了什么、你的结论是什么。\n\n");
      task.append("专家们的原话：\n");
      for (AppDatabase.GmFeedback f : orders) {
         task.append("· ").append(f.author == null ? "成员" : f.author)
                 .append(f.context == null || f.context.isEmpty() ? "" : "（" + f.context + "）")
                 .append("：").append(f.title == null ? "" : f.title);
         if (f.detail != null && !f.detail.trim().isEmpty()) task.append(" —— ").append(f.detail.trim());
         task.append("\n");
      }

      AppSettings st = this.adminSettings(roomId);
      final StringBuilder thinkBuf = new StringBuilder();
      final StringBuilder toolBuf = new StringBuilder();
      final String[] streamed = new String[]{""};
      AiClient.ChatResult res = AiClient.chatWithToolsStream(
              st, sys, new ArrayList<AppDatabase.MessageItem>(), task.toString(),
              AiClient.buildAdminTools(), (name, args) -> this.handleAdminTool(roomId, name, args),
              adminIters(st), new AiClient.StreamListener() {
                 public void onReasoningDelta(String delta) { thinkBuf.append(delta); }
                 public void onContentDelta(String delta) { streamed[0] += delta; }
                 public void onToolStart(String name, String argSummary) {
                    if (toolBuf.length() > 0) toolBuf.append("\n");
                    toolBuf.append("调用 ").append(name)
                            .append(argSummary != null && !argSummary.isEmpty() ? "（" + argSummary + "）" : "");
                 }
                 public void onToolEnd(String name, String resultSummary) {
                    if (toolBuf.length() > 0) toolBuf.append("\n");
                    toolBuf.append("✅ ").append(name).append(" 返回")
                            .append(resultSummary != null && !resultSummary.isEmpty() ? "：" + resultSummary : "");
                 }
              });

      String answer = res != null && res.content != null && !res.content.trim().isEmpty()
              ? res.content.trim() : streamed[0].trim();
      if (answer.isEmpty()) answer = "（管理员分析了顺序建议，但没有给出结论）";
      String toolLog = res != null && res.toolLog != null && !res.toolLog.trim().isEmpty()
              ? res.toolLog.trim() : toolBuf.toString().trim();

      this.db.insertGmAdminMessage(roomId, "assistant",
              "【第 " + round + " 轮顺序评估】\n" + answer,
              res != null && res.reasoning != null ? res.reasoning : thinkBuf.toString(),
              true, toolLog);

      final String firstLine = answer.length() > 80 ? answer.substring(0, 80) + "…" : answer;
      try {
         com.linyuan.app.ChatNotifier.notifyGroupFailure(
                 this.activity.getApplicationContext(), roomId,
                 room == null ? "群聊" : room.title,
                 "🧑‍✈️ 管理员有话要说",
                 firstLine + "\n（点群聊卡片的「🧑‍✈️」查看）");
      } catch (Exception ignored) { }

      this.ui.post(() -> {
         this.refreshAdminButtonBadge();
         this.refreshAdminCardBadges();
         if (this.currentRoomId == roomId && this.currentPage == 7) this.showAdminPage();
      });
   }

   private AppSettings adminSettings() {
      return this.adminSettings(this.currentRoomId);
   }

   private AppSettings adminSettings(long roomId) {
      AppDatabase.GmAdminConfig cfg = this.db.getGmAdminConfig(roomId);
      AppSettings st = new AppSettings();
      st.baseUrl = this.settings.baseUrl;
      st.apiKey = this.settings.apiKey;
      st.model = this.settings.model;

      st.temperature = "1.0";

      st.maxTokens = String.valueOf(ADMIN_MAX_OUTPUT_FIXED);
      st.enableThinking = true;
      st.webSearchEnabled = this.settings.webSearchEnabled;
      if (cfg.baseUrl != null && !cfg.baseUrl.isEmpty()) st.baseUrl = cfg.baseUrl;
      if (cfg.apiKey != null && !cfg.apiKey.isEmpty()) st.apiKey = cfg.apiKey;
      if (cfg.model != null && !cfg.model.isEmpty()) st.model = cfg.model;

      st.thinkLevel = cfg.thinkLevel;
      if (cfg.thinkLevel < 0 && this.settings.thinkLevel >= 0) st.thinkLevel = this.settings.thinkLevel;
      return st;
   }

   /** 管理员那几轮的轮数：跟全局同一个值（2026-10-17：原来 5 处硬编码 500）。 */
   private int adminIters(AppSettings st) {
      if (st != null && st.maxToolIterations > 0) return st.maxToolIterations;
      return this.settings != null && this.settings.maxToolIterations > 0
              ? this.settings.maxToolIterations : 500;
   }

   private int adminContextLimit(long roomId) {
      return ADMIN_CONTEXT_FIXED;
   }

   private AiClient.SpeakLimits speakLimitsFor(long roomId) {
      AiClient.SpeakLimits lim = AiClient.SpeakLimits.defaults();
      try {
         AppDatabase.GmAdminConfig cfg = this.db.getGmAdminConfig(roomId);
         if (cfg != null) {
            lim.repeatMinChars = cfg.repeatMinChars;
            lim.repeatLen = cfg.repeatLen;
            lim.repeatWindow = cfg.repeatWindow;
            lim.repeatMax = cfg.repeatMax;
            lim.toolMaxIter = cfg.toolMaxIter;
            lim.repeatRetry = cfg.repeatRetry;
         }
      } catch (Exception ignored) { }

      lim.repeatMinChars = Math.max(500, Math.min(200000, lim.repeatMinChars));
      lim.repeatLen = Math.max(100, Math.min(20000, lim.repeatLen));
      lim.repeatWindow = Math.max(lim.repeatLen * 3, Math.min(200000, lim.repeatWindow));
      lim.repeatMax = Math.max(2, Math.min(100, lim.repeatMax));
      lim.toolMaxIter = Math.max(1, Math.min(100, lim.toolMaxIter));
      lim.repeatRetry = Math.max(0, Math.min(10, lim.repeatRetry));
      return lim;
   }

   private void askAdminForRepeatFailure(long roomId, AppDatabase.GmMember member, int round,
                                         String sample, int count) {
      final String who = member == null ? "某个专家" : member.name;

      try { this.db.setGmRoomBlockedUntil(roomId, System.currentTimeMillis() + 86400000L); }
      catch (Exception ignored) { }

      try {
         this.db.insertGmMessage(roomId, 0L, "系统", "\u26a0\ufe0f",
                 who + " 连续 " + (count > 0 ? count : 0) + " 次出现生成循环（重复片段："
                         + (sample == null ? "—" : sample.replace("\n", " ")) + "），已暂停等管理员核验。",
                 false, "system", 0);
      } catch (Exception ignored) { }
      this.ui.post(() -> {
         this.renderChat();
         this.refreshRetryButton();
      });

      try { WorkSound.play(this.activity, WorkSound.EV_ALERT, true); } catch (Exception ignored) { }
      try {
         AppDatabase.GmRoom room = this.db.getGmRoom(roomId);
         ChatNotifier.notifyGroupFailure(this.activity.getApplicationContext(), roomId,
                 room == null ? "群聊" : room.title,
                 "⚠️ " + who + " 生成循环，讨论已暂停等你判断",
                 "重复内容：" + (sample == null ? "—" : sample.replace("\n", " ")));
      } catch (Exception ignored) { }

      this.bgExecute(this.executor, () -> {
         this.beginBgWork("repeat");
         try {
            this.runAdminRepeatReview(roomId, round, who, sample, count);
         } finally {
            this.endBgWork("discuss");
         }
      });
   }

   private void runAdminRepeatReview(long roomId, int round, String who, String sample, int count) {
      try {
         AppSettings st = this.adminSettings(roomId);
         StringBuilder sys = new StringBuilder(AiClient.buildAdminSystemPrompt(
                 this.roomTitleOf(roomId), 0, 0));
         sys.append("\n\n【本次请你处理的突发事件】\n")
            .append("群里的「").append(who).append("」在第 ").append(round)
            .append(" 轮的生成里检测到**退化重复**（模型复读故障）：")
            .append("同一段内容重复了约 ").append(count).append(" 次，重复片段开头是「")
            .append(sample == null ? "—" : sample.replace("\n", " ")).append("」。\n")
            .append("系统已经丢弃了那次产出并自动重跑了若干次，仍然复现，所以暂停讨论交给你。\n")
            .append("请你：① 用 read_discussion / list_experts 看一眼这位专家最近的表现和配置；")
            .append("② 判断这更像是**模型/服务商的问题**（比如该模型不稳定）还是**任务本身太难**；")
            .append("③ 给用户写一段简短说明：你看到什么、建议怎么处理（换模型？换服务商？等用户判断？）。")
            .append("如果确实需要换模型或服务商，就提提案（propose_member_config），等用户点允许。\n");
         StringBuilder usr = new StringBuilder();
         usr.append("专家「").append(who).append("」连续生成循环，请核验并给我处理建议。");
         AiClient.ChatResult res = AiClient.chatWithToolsStream(
                 st, sys.toString(), new ArrayList<AppDatabase.MessageItem>(), usr.toString(),
                 AiClient.buildAdminTools(), (name, args) -> this.handleAdminTool(roomId, name, args),
                 adminIters(st), null, (java.util.concurrent.atomic.AtomicBoolean) null);
         String answer = res != null && res.content != null && !res.content.trim().isEmpty()
                 ? res.content.trim() : "（管理员没有返回内容）";
         this.db.insertGmAdminMessage(roomId, "assistant",
                 "⚠️ 「" + who + "」生成循环，我已核验：\n\n" + answer,
                 res != null ? res.reasoning : "", true, res != null ? res.toolLog : "");
      } catch (Exception e) {
         android.util.Log.w("VC_GM", "管理员核验生成循环失败: " + e.getMessage());
      }
      this.ui.post(() -> {
         this.refreshAdminButtonBadge();
         this.refreshAdminCardBadges();
      });
   }

   private String roomTitleOf(long roomId) {
      try {
         AppDatabase.GmRoom r = this.db.getGmRoom(roomId);
         return r == null || r.title == null ? "群聊" : r.title;
      } catch (Exception e) {
         return "群聊";
      }
   }

   private void askAdminToHandleFailure(long roomId, AppDatabase.GmMember member, int round,
                                        String errShown, boolean rateLimited, int cooldownSec, int attempts) {
      final String who = member == null ? "某个专家" : member.name;

      try {
         this.db.insertGmMessage(roomId, 0L, "系统", "\u26a0\ufe0f",
                 who + " 连续 " + attempts + " 次请求失败"
                         + (rateLimited ? "（上游限流）" : "") + "，已交给管理员判断下一步。",
                 false, "system", 0);
      } catch (Exception ignored) { }
      this.ui.post(() -> {
         this.renderChat();
         this.refreshRetryButton();
         this.refreshStartButton();
      });

      try { WorkSound.play(this.activity, WorkSound.EV_ALERT, true); } catch (Exception ignored) { }
      try {
         ChatNotifier.notifyGroupFailure(this.activity.getApplicationContext(), roomId,
                 this.roomTitleOf(roomId),
                 "⚠️ " + who + " 反复请求失败，已交管理员判断",
                 (errShown == null ? "" : errShown) + "\n（管理员会给你发消息说明情况）");
      } catch (Exception ignored) { }

      this.bgExecute(this.executor, () -> {
         this.beginBgWork("takeover");
         try {
            this.runAdminFailureTakeover(roomId, member, round, errShown, rateLimited, cooldownSec, attempts);
         } finally {
            this.endBgWork("discuss");
         }
      });
   }

   private void runAdminFailureTakeover(long roomId, AppDatabase.GmMember member, int round,
                                        String errShown, boolean rateLimited, int cooldownSec, int attempts) {
      try {
         AppSettings st = this.adminSettings(roomId);
         int ctxLimit = this.adminContextLimit(roomId);
         List<AppDatabase.GmMember> members = this.db.listGmMembers(roomId);
         int active = 0;
         for (AppDatabase.GmMember m : members) if (m.active) active++;

         String base = AiClient.buildAdminSystemPrompt(this.roomTitleOf(roomId), members.size(), active)
                 + "\n\n【你能装载的上下文上限】约 " + ctxLimit + " token。超出时更早的内容会被裁掉。";

         List<AppDatabase.MessageItem> history = new ArrayList<>();
         try {
            for (AppDatabase.GmAdminMessage am : this.db.listGmAdminMessages(roomId, 1000)) {
               if (am.content == null || am.content.trim().isEmpty()) continue;
               history.add(new AppDatabase.MessageItem(
                       0L, "user".equals(am.role) ? "user" : "ai", am.content, null, am.createdAt));
            }
         } catch (Exception ignored) { }

         String who = member == null ? "某个专家" : member.name;
         StringBuilder task = new StringBuilder();
         task.append("【自动任务】群里出事了，系统把处置权交给你，请你判断下一步。\n\n");
         task.append("事件：专家「").append(who).append("」在第 ").append(round)
             .append(" 轮连续 ").append(attempts).append(" 次请求失败")
             .append(rateLimited ? "（上游限流，最后一次冷却提示约 " + this.fmtDuration(cooldownSec) + "）"
                                 : "（不是限流，是普通失败：网络/服务端错误一类）")
             .append("。系统已按规则自动重试过，现在停了。\n")
             .append("最后一次的错误信息：").append(errShown == null ? "—" : errShown)
             .append("\n\n请你按顺序做：\n")
             .append("① **先调查清楚**：用 read_anomalies 看这个群最近的失败记录（谁、什么类型、什么时候），"
                     + "用 read_discussion 看这位专家最近说了什么、讨论进行到哪，"
                     + "用 list_experts 看它的配置（模型/服务商/是否联网）。\n")
             .append("② 判断性质：像是**偶发抖动**（网络、上游临时故障）→ 值得再试；"
                     + "像是**配置问题**（模型不存在/密钥失效/地址错）或**持续限流** → 不该硬试，"
                     + "该换模型或服务商，那就提提案（propose_member_config）等我点允许。\n")
             .append("③ 如果结论是「值得立刻再试」→ 调用 **retry_failed_role** 自己触发重试"
                     + "（一次只重试一次，别连续猛试）。\n")
             .append("④ **必须**给我写一条消息说明情况——不是只说「我做了 X」，"
                     + "而是讲清楚：出了什么事、你查到了什么、你为什么这么判断、接下来会怎样。\n");
         AiClient.ChatResult res = AiClient.chatWithToolsStream(
                 st, base, history, task.toString(),
                 AiClient.buildAdminTools(), (name, args) -> this.handleAdminTool(roomId, name, args),
                 adminIters(st), null, (java.util.concurrent.atomic.AtomicBoolean) null);

         String answer = res != null && res.content != null && !res.content.trim().isEmpty()
                 ? res.content.trim() : "";
         if (answer.isEmpty()) {

            answer = "我收到了「" + who + "」反复请求失败的通知，但这次我没能给出判断"
                    + "（我的模型请求可能也受到了同一问题影响）。\n"
                    + "错误信息：" + (errShown == null ? "—" : errShown);
         }
         this.db.insertGmAdminMessage(roomId, "assistant",
                 "⚠️ 「" + who + "」反复请求失败（已试 " + attempts + " 次），我的判断：\n\n" + answer,
                 res != null ? res.reasoning : "", true, res != null ? res.toolLog : "");

         final String firstLine = answer.length() > 80 ? answer.substring(0, 80) + "…" : answer;
         try {
            ChatNotifier.notifyGroupFailure(this.activity.getApplicationContext(), roomId,
                    this.roomTitleOf(roomId), "🧑‍✈️ 管理员有话要说", firstLine);
         } catch (Exception ignored) { }

         this.ui.post(() -> {
            this.refreshAdminButtonBadge();
            this.refreshAdminCardBadges();
            if (this.currentRoomId == roomId && this.currentPage == 7) this.showAdminPage();
         });
      } catch (Exception e) {
         android.util.Log.w("VC_GM", "管理员接管失败事件出错: " + e.getMessage());

         try {
            this.db.insertGmAdminMessage(roomId, "assistant",
                    "⚠️ 有角色反复请求失败，但我在尝试分析时也出错了："
                            + (e.getMessage() == null ? "未知错误" : e.getMessage()),
                    "", true, "");
            this.ui.post(() -> this.refreshAdminButtonBadge());
         } catch (Exception ignored) { }
      }
   }

   private void refreshAdminCardBadges() {
      try {
         java.util.HashMap<Long, Integer> unread = this.db.mapUnreadGmAdminMessages();
         for (java.util.Map.Entry<Long, Integer> e : unread.entrySet()) {
            android.view.View badge = this.cardAdminBadges.get(e.getKey());
            if (badge == null) continue;
            final int n = e.getValue();
            this.ui.post(() -> {
               badge.setVisibility(n > 0 ? 0 : 8);
               if (badge instanceof TextView) ((TextView) badge).setText(String.valueOf(n));
            });
         }
      } catch (Exception ignored) { }
   }

   private final java.util.Map<Long, android.view.View> cardAdminBadges = new java.util.HashMap<>();

   private void resumeAfterInquiry(long roomId) {
      if (roomId <= 0) return;
      RoomRun r = this.roomRuns.get(roomId);      if (r != null) r.awaitingInquiry = false;

      AppDatabase.GmRoom room = this.db.getGmRoom(roomId);
      boolean started = room != null && room.started;
      this.db.insertGmMessage(roomId, 0L, "系统", "\u25b6\ufe0f",
              started ? "已收到你的确认，继续讨论。"
                      : "已保存你的回答。重新打开「▶ 讨论」开关后继续讨论。",
              false, "system", 0);
      this.renderChat();
      if (started && this.currentRoomId == roomId && !this.isRoomRunning(roomId)) {
         this.startDiscussion();
      }
   }

   private void maybeShowPendingInquiry(long roomId) {
      AppDatabase.GmInquiry q = this.db.latestGmInquiry(roomId);
      if (q != null && "pending".equals(q.status)) {
         this.ui.post(() -> this.showInquiryDraft(q.id));
      }
   }

   private void maybeResumeInterrupted(long roomId) {
      if (roomId <= 0L) return;
      if (this.currentRoomId != roomId) return;
      if (this.isRoomRunning(roomId)) return;
      if (this.roomRuns.size() >= MAX_PARALLEL_ROOMS) return;
      AppDatabase.GmRoom r = this.db.getGmRoom(roomId);
      if (r == null || !r.started) return;
      if (r.lastRound <= 0) return;

      AppDatabase.GmInquiry q = this.db.latestGmInquiry(roomId);
      if (q != null && "pending".equals(q.status)) return;
      android.util.Log.i("VC_GM", "自动续跑被中断的讨论 room=" + roomId + " round=" + r.lastRound);
      this.startDiscussion();
   }

   private static boolean shouldSkipQuestion(List<AppDatabase.GmInquiryItem> existing, String q) {
      String key = normalizeQuestion(q);
      if (key.length() < 4) return true;
      for (AppDatabase.GmInquiryItem it : existing) {
         String k2 = normalizeQuestion(it.question);
         if (k2.length() < 4) continue;
         if (k2.equals(key)) return true;

         String shorter = k2.length() <= key.length() ? k2 : key;
         String longer = k2.length() <= key.length() ? key : k2;
         if (shorter.length() >= 6 && longer.contains(shorter)) return true;
      }
      return false;
   }

   private static String normalizeQuestion(String q) {
      if (q == null) return "";
      String s = q.replaceAll("[\\s\\p{Punct}，。、；：？！「」『』（）《》…—·~【】]", "");
      s = s.replace("*", "").replace("#", "").replace("`", "");
      s = s.replaceAll("[的了呢吗是你您请]", "");
      return s.toLowerCase(java.util.Locale.ROOT);
   }

   private void showInquiryDraft(long inquiryId) {
      final AppDatabase.GmInquiry q = this.db.getGmInquiry(inquiryId);
      if (q == null) return;

      LinearLayout root = new LinearLayout(this.activity);
      root.setOrientation(1);

      GradientDrawable rootBg = new GradientDrawable();
      rootBg.setColor(this.col(color.surface));
      rootBg.setCornerRadius((float)this.dp(20));
      rootBg.setStroke(this.dp(1), this.col(color.border));
      root.setBackground(rootBg);

      root.setClipToPadding(false);
      root.setPadding(this.dp(16), this.dp(18), this.dp(16), this.dp(14));

      TextView title = new TextView(this.activity);
      title.setText("\u2753 " + (q.title == null ? "需要你确认的问题" : q.title));
      title.setTextColor(this.col(color.text));
      title.setTextSize(16.0F);
      title.setTypeface((Typeface)null, 1);
      root.addView(title);
      TextView hint = new TextView(this.activity);
      hint.setText("请在每条问题下方填写你的回答，填完点右下角「\u2713 保存并继续」。");
      hint.setTextColor(this.col(color.muted));
      hint.setTextSize(11.0F);
      hint.setPadding(0, this.dp(6), 0, this.dp(10));
      root.addView(hint);

      final java.util.List<android.widget.EditText> answerInputs = new java.util.ArrayList<>();
      final java.util.List<AppDatabase.GmInquiryItem> items = q.items;
      android.widget.ScrollView sv = new android.widget.ScrollView(this.activity);
      sv.setVerticalScrollBarEnabled(true);
      LinearLayout list = new LinearLayout(this.activity);
      list.setOrientation(1);
      for (int i = 0; i < items.size(); i++) {
         AppDatabase.GmInquiryItem it = items.get(i);
         LinearLayout box = new LinearLayout(this.activity);
         box.setOrientation(1);
         GradientDrawable bg = new GradientDrawable();
         bg.setColor(this.col(color.surfaceInput));
         bg.setCornerRadius((float)this.dp(12));
         bg.setStroke(this.dp(1), this.col(color.border));
         box.setBackground(bg);
         box.setPadding(this.dp(12), this.dp(10), this.dp(12), this.dp(10));
         LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
         blp.setMargins(0, this.dp(8), 0, 0);
         box.setLayoutParams(blp);

         TextView who = new TextView(this.activity);

         String prevAsker = i > 0 ? items.get(i - 1).asker : null;
         boolean sameAsker = prevAsker != null && prevAsker.equals(it.asker);
         who.setText(sameAsker
                 ? ("Q" + (i + 1))
                 : ("Q" + (i + 1) + " · " + (it.asker == null ? "成员" : it.asker)));
         who.setTextColor(this.col(color.accent));
         who.setTextSize(12.0F);
         who.setTypeface((Typeface)null, 1);
         box.addView(who);
         TextView qt = new TextView(this.activity);
         qt.setText(it.question == null ? "" : it.question);
         qt.setTextColor(this.col(color.text));
         qt.setTextSize(13.0F);
         qt.setLineSpacing((float)this.dp(3), 1.2F);
         qt.setPadding(0, this.dp(4), 0, this.dp(8));
         box.addView(qt);

         android.widget.EditText ans = new android.widget.EditText(this.activity);

         if (it.hasOptions()) {
            TextView optHint = new TextView(this.activity);
               optHint.setText(it.multi
                       ? "可多选"
                       : "单选");
            optHint.setTextColor(this.col(color.muted));
            optHint.setTextSize(10.0F);
            optHint.setPadding(0, 0, 0, this.dp(4));
            box.addView(optHint);
            final boolean multi = it.multi;
            final java.util.LinkedHashSet<String> picked = new java.util.LinkedHashSet<>();
            final java.util.List<TextView> chips = new java.util.ArrayList<>();

            if (it.answer != null && !it.answer.trim().isEmpty()) {
               for (String seg : it.answer.split("；|;")) {
                  String s = seg == null ? "" : seg.trim();
                  if (!s.isEmpty() && it.options.contains(s)) picked.add(s);
               }
            }
            for (int oi = 0; oi < it.options.size(); oi++) {
               final String optText = it.options.get(oi);
               final TextView chip = new TextView(this.activity);
               chip.setText((char)('A' + oi) + "、" + optText);
               chip.setTextColor(this.col(color.text));
               chip.setTextSize(12.0F);
               chip.setClickable(true);
               chip.setPadding(this.dp(10), this.dp(8), this.dp(10), this.dp(8));
               GradientDrawable obg = new GradientDrawable();
               boolean preOn = picked.contains(optText);
               obg.setColor(this.col(preOn ? color.accent : color.surface));
               obg.setCornerRadius((float)this.dp(10));
               obg.setStroke(this.dp(1), this.col(preOn ? color.accent : color.border));
               chip.setBackground(obg);
               if (preOn) chip.setTextColor(this.col(color.onAccent));
               LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-1, -2);
               olp.setMargins(0, 0, 0, this.dp(5));
               chip.setOnClickListener((v) -> {

                  if (picked.contains(optText)) {
                     picked.remove(optText);
                  } else {
                     if (!multi) picked.clear();
                     picked.add(optText);
                  }

                  for (int k = 0; k < chips.size(); k++) {
                     TextView cv = chips.get(k);
                     boolean on = picked.contains(it.options.get(k));
                     GradientDrawable g = new GradientDrawable();
                     g.setColor(on ? this.col(color.accent) : this.col(color.surface));
                     g.setCornerRadius((float)this.dp(10));
                     g.setStroke(this.dp(1), on ? this.col(color.accent) : this.col(color.border));
                     cv.setBackground(g);
                     cv.setTextColor(on ? this.col(color.onAccent) : this.col(color.text));
                  }

                  ans.setText(String.join("；", picked));
                  ans.setSelection(ans.getText().length());
               });
               chips.add(chip);
               box.addView(chip, olp);
            }
         }

         ans.setHint(it.hasOptions()
                 ? "不选上面的也行，直接在这里写你的答案…"
                 : ("在这里回答 Q" + (i + 1) + " 的问题…"));
         ans.setText(it.answer == null ? "" : it.answer);
         ans.setTextColor(this.col(color.text));
         ans.setHintTextColor(this.col(color.muted));
         ans.setTextSize(13.0F);
         ans.setBackgroundResource(drawable.input_bar);
         ans.setPadding(this.dp(12), this.dp(10), this.dp(12), this.dp(10));
         ans.setMinLines(2);
         ans.setMaxLines(6);
         ans.setGravity(android.view.Gravity.START | android.view.Gravity.TOP);
         ans.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                 | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
         box.addView(ans);
         answerInputs.add(ans);

         final int ansIdx = i;
         ans.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {
               try {
                  items.get(ansIdx).answer = s == null ? "" : s.toString();
                  GroupChatManager.this.db.updateGmInquiryAnswers(q.id, items, "pending");
               } catch (Exception ignored) {}
            }
         });
         list.addView(box);

         final String askerName = it.asker;
         final String questionText = it.question;
         final AppDatabase.GmInquiryItem itemRef = it;

         final TextView historyView = new TextView(this.activity);
         historyView.setTextColor(this.col(color.muted));
         historyView.setTextSize(12.0F);
         historyView.setLineSpacing((float)this.dp(3), 1.15F);
         historyView.setPadding(this.dp(10), this.dp(8), this.dp(10), this.dp(8));
         GradientDrawable hbg = new GradientDrawable();
         hbg.setColor(this.col(color.surface));
         hbg.setCornerRadius((float)this.dp(10));
         historyView.setBackground(hbg);
         LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
         hlp.setMargins(0, this.dp(6), 0, 0);
         historyView.setVisibility(8);
         if (itemRef.clarifyLog != null && !itemRef.clarifyLog.trim().isEmpty()) {
            historyView.setText(this.formatClarifyLog(itemRef.clarifyLog, askerName));
            historyView.setVisibility(0);
         }
         box.addView(historyView, hlp);

         Button clarifyBtn = this.ghostButton("\u2753 \u770b\u4e0d\u61c2 / \u53cd\u95ee");
         clarifyBtn.setTextColor(this.col(color.accent));
         clarifyBtn.setTextSize(11.0F);
         LinearLayout.LayoutParams cblp2 = new LinearLayout.LayoutParams(-2, -2);
         cblp2.setMargins(0, this.dp(6), 0, 0);

         final LinearLayout askPanel = new LinearLayout(this.activity);
         askPanel.setOrientation(1);
         askPanel.setVisibility(8);
         final EditText askInput = new EditText(this.activity);
         askInput.setHint("写下你看不懂的地方 / 你的反问…");
         askInput.setTextColor(this.col(color.text));
         askInput.setHintTextColor(this.col(color.muted));
         askInput.setTextSize(13.0F);
         askInput.setMinLines(2);
         askInput.setGravity(android.view.Gravity.TOP);
         askInput.setBackgroundResource(drawable.input_bar);
         askInput.setPadding(this.dp(12), this.dp(10), this.dp(12), this.dp(10));
         askPanel.addView(askInput, new LinearLayout.LayoutParams(-1, -2));
         final TextView askStatus = new TextView(this.activity);
         askStatus.setTextColor(this.col(color.accent));
         askStatus.setTextSize(11.0F);
         askStatus.setPadding(0, this.dp(6), 0, 0);
         askStatus.setVisibility(8);
         askPanel.addView(askStatus, new LinearLayout.LayoutParams(-1, -2));
         final Button sendAsk = this.primaryButton("\u27a4 \u53d1\u9001\u53cd\u95ee", this.col(color.onAccent));
         sendAsk.setTextSize(12.0F);
         LinearLayout.LayoutParams salp = new LinearLayout.LayoutParams(-2, -2);
         salp.topMargin = this.dp(8);
         salp.gravity = 5;
         askPanel.addView(sendAsk, salp);
         LinearLayout.LayoutParams palp = new LinearLayout.LayoutParams(-1, -2);
         palp.setMargins(0, this.dp(6), 0, 0);
         box.addView(askPanel, palp);

         clarifyBtn.setOnClickListener((v) -> {
            boolean show = askPanel.getVisibility() != 0;
            askPanel.setVisibility(show ? 0 : 8);
            if (show) askInput.requestFocus();
         });
         box.addView(clarifyBtn, cblp2);

         sendAsk.setOnClickListener((v) -> {
            final String ask = askInput.getText().toString().trim();
            if (ask.isEmpty()) { this.showToast("先写点什么再发"); return; }
            sendAsk.setEnabled(false);
            askInput.setEnabled(false);
            askStatus.setText("\u23f3 \u6b63\u5728\u8bf7 " + (askerName == null ? "\u5bf9\u65b9" : askerName) + " \u89e3\u91ca\u2026");
            askStatus.setVisibility(0);
            final String prior = itemRef.clarifyLog == null ? "" : itemRef.clarifyLog;
            this.runClarification(q.roomId, askerName, questionText, prior, ask, (reply, err) -> {
               sendAsk.setEnabled(true);
               askInput.setEnabled(true);
               if (err != null) {
                  askStatus.setText("\u274c \u53cd\u95ee\u5931\u8d25\uff1a" + err);
                  return;
               }
               askStatus.setVisibility(8);
               askInput.setText("");

               itemRef.clarifyLog = (itemRef.clarifyLog == null ? "" : itemRef.clarifyLog)
                       + "[反问]" + ask + "\n[回应]" + reply + "\n\n";
               try {
                  AppDatabase.GmInquiry now = this.db.getGmInquiry(q.id);
                  String st = (now != null && now.status != null) ? now.status : "pending";
                  this.db.updateGmInquiryAnswers(q.id, items, st);
               } catch (Exception ignored) { }
               historyView.setText(this.formatClarifyLog(itemRef.clarifyLog, askerName));
               historyView.setVisibility(0);
            });
         });
      }
      sv.addView(list);
      LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(-1, 0, 1.0F);
      root.addView(sv, svlp);

      final android.app.Dialog dlg = new android.app.Dialog(this.activity);

      dlg.setOnDismissListener((d) -> {

         try { GroupChatManager.this.inquiryScrollY.put(q.id, sv.getScrollY()); } catch (Exception ignored) {}
         try {
            for (int i = 0; i < items.size() && i < answerInputs.size(); i++) {
               items.get(i).answer = answerInputs.get(i).getText().toString();
            }
            AppDatabase.GmInquiry now = this.db.getGmInquiry(q.id);
            String st = (now != null && now.status != null) ? now.status : "pending";
            this.db.updateGmInquiryAnswers(q.id, items, st);
         } catch (Exception ignored) {}
      });
      LinearLayout btnRow = new LinearLayout(this.activity);
      btnRow.setOrientation(0);
      btnRow.setGravity(android.view.Gravity.END);
      LinearLayout.LayoutParams brlp = new LinearLayout.LayoutParams(-1, -2);
      brlp.setMargins(0, this.dp(10), 0, 0);
      Button later = this.ghostButton("稍后再说");
      later.setOnClickListener((v) -> {

         List<AppDatabase.GmInquiryItem> save = new java.util.ArrayList<>();
         for (int i = 0; i < items.size(); i++) {
            AppDatabase.GmInquiryItem x = items.get(i);
            x.answer = answerInputs.get(i).getText().toString().trim();
            save.add(x);
         }
         this.db.updateGmInquiryAnswers(q.id, save, "pending");
         dlg.dismiss();
      });
      btnRow.addView(later);
      Button saveGo = this.primaryButton("\u2713 保存并继续", this.col(color.onAccent));
      LinearLayout.LayoutParams sglp = new LinearLayout.LayoutParams(-2, -2);
      sglp.setMarginStart(this.dp(8));
      saveGo.setOnClickListener((v) -> {
         List<AppDatabase.GmInquiryItem> save = new java.util.ArrayList<>();
         int answered = 0;
         for (int i = 0; i < items.size(); i++) {
            AppDatabase.GmInquiryItem x = items.get(i);
            x.answer = answerInputs.get(i).getText().toString().trim();
            if (!x.answer.isEmpty()) answered++;
            save.add(x);
         }
         if (answered == 0) {

            this.db.updateGmInquiryAnswers(q.id, save, "pending");
            this.showToast("还没有填写任何回答");
            return;
         }
         this.db.updateGmInquiryAnswers(q.id, save, "answered");

         this.db.markGmQuestionsAnswered(q.roomId);

         try {
            String roomTitle = null, roomGoal = null;
            AppDatabase.GmRoom r0 = this.db.getGmRoom(q.roomId);
            if (r0 != null) { roomTitle = r0.title; roomGoal = r0.goalText; }

            if (roomGoal == null || roomGoal.trim().isEmpty()) {
               roomGoal = this.db.demandTextOfRoom(q.roomId);
            }
            for (AppDatabase.GmInquiryItem x : save) {
               if (x == null || x.question == null || x.question.trim().isEmpty()) continue;
               if (x.answer == null || x.answer.trim().isEmpty()) continue;
               this.db.insertGmQaArchive(q.roomId, roomTitle, roomGoal, x.asker,
                       x.question.trim(), x.options, x.answer, System.currentTimeMillis());
            }
         } catch (Exception e) {
            android.util.Log.w("VC_GM", "归档用户信息库失败: " + e.getMessage());
         }
         dlg.dismiss();

         StringBuilder sb = new StringBuilder("【我对提问的回复】");
         int skippedCount = 0;
         for (int i = 0; i < save.size(); i++) {
            AppDatabase.GmInquiryItem x = save.get(i);
            if (x.answer == null || x.answer.isEmpty()) {
               ++skippedCount;
               sb.append("\n\nQ").append(i + 1).append("（").append(x.asker).append("提问：")
                 .append(x.question).append("）\n")
                 .append("（这题我暂时跳过，没有回答。如果它很关键，可以在后面的讨论里再提一次。）");
               continue;
            }
            sb.append("\n\nQ").append(i + 1).append("（").append(x.asker).append("提问：")
              .append(x.question).append("）\n我的回答：").append(x.answer);
         }
         this.db.insertGmMessage(q.roomId, 0L, "我", "\ud83d\ude4b", sb.toString(), false, "chat", 0);
         this.showToast(skippedCount > 0
                 ? ("已提交 " + answered + " 条回答，" + skippedCount + " 条跳过，讨论继续")
                 : ("已提交 " + answered + " 条回答，讨论继续"));
         this.resumeAfterInquiry(q.roomId);
      });
      btnRow.addView(saveGo, sglp);
      root.addView(btnRow, brlp);

      dlg.setContentView(root);
      android.view.Window w = dlg.getWindow();
      if (w != null) {

         w.setBackgroundDrawable(new ColorDrawable(0));
         android.util.DisplayMetrics dm = this.activity.getResources().getDisplayMetrics();

         w.setLayout((int)((float)dm.widthPixels * 0.94F), (int)((float)dm.heightPixels * 0.82F));
      }
      dlg.show();

      final Integer savedY = this.inquiryScrollY.get(q.id);
      if (savedY != null && savedY > 0) {
         sv.post(() -> sv.scrollTo(0, savedY));
      }
   }

   private final java.util.HashMap<Long, Integer> inquiryScrollY = new java.util.HashMap<>();

   private String formatClarifyLog(String log, String asker) {
      if (log == null || log.trim().isEmpty()) return "";
      String t = log.trim();
      t = t.replace("[反问]", "\ud83d\ude4b 你的反问：");
      t = t.replace("[回应]", "\n\ud83d\udcac " + (asker == null ? "成员" : asker) + " 的回应：");
      return t;
   }

   private void setHeaderButtons(boolean inRoom) {

      if (this.confirmButton != null) {
         this.confirmButton.setVisibility(inRoom ? this.confirmButton.getVisibility() : 8);
         if (inRoom) this.refreshConfirmButton();
      }

      if (this.membersButton != null) {

         this.membersButton.setVisibility(8);
      }

      if (this.adminButton != null) {

         this.adminButton.setVisibility(inRoom ? 0 : 8);
         if (inRoom && this.currentRoomId > 0L) this.refreshAdminButtonBadge();
      }

      if (this.settingsButton != null) {
         this.settingsButton.setVisibility(inRoom ? 0 : 8);
      }
   }

   private void refreshAdminButtonBadge() {
      if (this.adminButton == null || this.currentRoomId <= 0L) return;

      int n = 0;
      try { n = this.db.countUnreadGmAdminMessages(this.currentRoomId); } catch (Exception ignored) { }
      this.adminButton.setText(n > 0 ? "🧑‍✈️ 管理员 " + n : "🧑‍✈️ 管理员");
      this.adminButton.setTextColor(this.col(n > 0 ? color.danger : color.accent));
   }

   private Button startButton;

   private long startButtonRoomId = 0L;

   private void refreshStartButton() {
      if (this.startButton == null || this.startButtonRoomId <= 0L) return;
      AppDatabase.GmRoom r = this.db.getGmRoom(this.startButtonRoomId);
      if (r == null) return;

      boolean on = r.started;
      if (on) {
         this.startButton.setText("\u23f9 停止");
         this.startButton.setBackgroundResource(drawable.btn_secondary);
         this.startButton.setTextColor(this.col(color.danger));
      } else {
         this.startButton.setText("\u25b6 讨论");
         this.startButton.setBackgroundResource(drawable.btn_primary);
         this.startButton.setTextColor(this.col(color.onAccent));
      }
   }

   private void refreshRetryButton() {

      this.renderChat();
   }

   private void onStartButtonClick(long roomId) {
      AppDatabase.GmRoom rr = this.db.getGmRoom(roomId);
      if (rr == null) return;

      if (rr.started) {

         this.db.setGmRoomStarted(roomId, false);
         this.db.setGmRoomBlockedUntil(roomId, 0L);
         this.stopDiscussion(roomId);

         this.resetRoomBuf(roomId);
         this.detachLiveMemberCard();
         this.db.insertGmMessage(roomId, 0L, "系统", "⚙️",
                 "已暂停讨论（重新打开「▶ 讨论」后才能继续）。", false, "system", 0);
         this.renderChat();
      } else {

         List<AppDatabase.GmMember> members = this.db.listGmMembers(roomId);
         if (members.isEmpty()) {
            this.showToast("先添加成员（点右上角「\ud83d\udc65 成员」）");
            return;
         }
         this.db.setGmRoomStarted(roomId, true);

         this.db.setGmRoomBlockedUntil(roomId, 0L);

         boolean hasAsk = this.db.countGmUserMessages(roomId) > 0;
         boolean alreadyDiscussed = this.db.countGmMemberMessages(roomId) > 0;
         if (hasAsk) {
            this.db.insertGmMessage(roomId, 0L, "系统", "⚙️",
                    alreadyDiscussed ? "已开启讨论：接着之前的内容继续。"
                                     : "已开启讨论：开始讨论你说的需求。", false, "system", 0);
            this.renderChat();
            this.startDiscussion();
         } else {
            this.db.insertGmMessage(roomId, 0L, "系统", "⚙️",
                    "已开启讨论：说出你的需求，成员们就会开始讨论。", false, "system", 0);
            this.showToast("已开启讨论：说出你的需求，他们就会开始");
            this.renderChat();
         }
      }
      this.refreshStartButton();
   }

   private void onRetryButtonClick(long roomId) {
      AppDatabase.GmRoom rr = this.db.getGmRoom(roomId);
      if (rr == null) return;
      if (!rr.started) {
         this.showToast("先打开「▶ 讨论」开关，再点重试");
         return;
      }

      String failed = this.pendingFailureRoleName(roomId);
      long left = rr.blockedUntil - System.currentTimeMillis();
      this.db.setGmRoomBlockedUntil(roomId, 0L);

      if (this.isRoomRunning(roomId)) {

         this.showToast("已重试：" + (failed == null ? "继续讨论" : "接着 " + failed + " 继续")
                 + (left > 5000L ? "（原冷却还剩 " + this.fmtDuration((int) (left / 1000L)) + "）" : ""));
      } else {

         this.showToast("重新开始讨论（从断掉的角色继续）");
         this.startDiscussion();
      }
      this.refreshRetryButton();
   }

   private String pendingFailureRoleName(long roomId) {
      try {
         List<AppDatabase.GmMessage> msgs = this.db.listGmMessages(roomId, 200);
         if (msgs.isEmpty()) return null;

         for (int i = msgs.size() - 1; i >= 0; --i) {
            AppDatabase.GmMessage m = msgs.get(i);
            String kind = m.kind == null ? "" : m.kind;
            if (!"system".equals(kind)) break;
            String c = m.content == null ? "" : m.content;
            if (!c.contains("请求失败")) continue;

            String name = this.roleNameFromFailureNotice(c);
            if (name == null) continue;

            AppDatabase.GmMember failed = this.findMemberByName(roomId, name);
            if (failed == null) continue;

            boolean recovered = false;
            for (AppDatabase.GmMessage mm : msgs) {
               if (mm.id > m.id && mm.memberId == failed.id && !mm.isThought) {
                  recovered = true;
                  break;
               }
            }
            return recovered ? null : failed.name;
         }
         return null;
      } catch (Exception e) {
         android.util.Log.w("VC_GM", "判定失败角色异常: " + e.getMessage());
         return null;
      }
   }

   private String fmtDuration(int seconds) {
      if (seconds <= 0) return "0秒";
      if (seconds < 60) return seconds + "秒";

      if (seconds >= 3600) {
         int h = seconds / 3600;
         int m = (seconds % 3600) / 60;
         return m == 0 ? h + "小时" : h + "小时" + m + "分";
      }
      int m = seconds / 60;
      int s = seconds % 60;
      return s == 0 ? m + "分" : m + "分" + s + "秒";
   }

   private Integer parsePositiveInt(EditText et, String fieldName, int min) {
      String raw = et.getText().toString().trim();
      try {
         int v = Integer.parseInt(raw);
         if (v < min) {
            this.showToast("「" + fieldName + "」不能小于 " + min + "（当前填的是 " + v + "），请改后重试");
            return null;
         }
         return v;
      } catch (Exception e) {
         this.showToast("「" + fieldName + "」填的不是数字（" + (raw.isEmpty() ? "空" : raw) + "），请改后重试");
         return null;
      }
   }

   private Integer parseNonNegativeInt(EditText et, String fieldName) {
      String raw = et.getText().toString().trim();
      try {
         int v = Integer.parseInt(raw);
         if (v < 0) {
            this.showToast("「" + fieldName + "」不能填负数（0 = 不限），请改后重试");
            return null;
         }
         return v;
      } catch (Exception e) {
         this.showToast("「" + fieldName + "」填的不是数字（" + (raw.isEmpty() ? "空" : raw) + "），请改后重试");
         return null;
      }
   }

   private int parseCooldownFromError(String err) {
      if (err == null) return 0;
      try {
         java.util.regex.Matcher m = java.util.regex.Pattern.compile("@@CD:(\\d{1,5})").matcher(err);
         if (m.find()) return Integer.parseInt(m.group(1));
      } catch (Exception ignored) { }
      return AiClient.parseCooldownSeconds(err);
   }

   private String stripCooldownMarker(String err) {
      if (err == null) return "";
      return err.replaceAll("@@CD:\\d{1,5}\\s*", "").trim();
   }

   private void renderChat() {
      if (Looper.myLooper() != Looper.getMainLooper()) {
         this.ui.post(this::renderChat);
      } else if (this.chatList != null) {

         long viewRoom = this.viewRoomId;
         if (viewRoom <= 0 || !this.isVisible()) return;
         boolean wasNearBottom = true;

         try {
            View child = this.chatScroll == null ? null : this.chatScroll.getChildAt(0);
            if (child != null) {
               wasNearBottom = child.getHeight() - (this.chatScroll.getScrollY() + this.chatScroll.getHeight()) < this.dp(160);
            }
         } catch (Exception var6) {
         }

         this.rebuildingChat = true;
         this.chatList.removeAllViews();

         this.detachLiveMemberCard();

         List<AppDatabase.GmMessage> shownMsgs = this.db.listGmMessages(viewRoom, this.renderMsgLimit);
         int totalMsgs = this.db.countGmMessages(viewRoom);

         if (totalMsgs > shownMsgs.size()) {
            TextView loadMore = new TextView(this.activity);
            loadMore.setText("⬆ 加载更早的消息（还有 " + (totalMsgs - shownMsgs.size()) + " 条）");
            loadMore.setTextColor(this.col(color.accent));
            loadMore.setTextSize(12.0F);
            loadMore.setGravity(17);
            loadMore.setPadding(0, this.dp(10), 0, this.dp(10));
            loadMore.setOnClickListener((v) -> {
               int prevHeight = this.chatList.getHeight();
               int prevScroll = this.chatScroll == null ? 0 : this.chatScroll.getScrollY();
               this.userReadingHistory = true;
               this.renderMsgLimit += 60;
               this.renderChat();
               if (this.chatScroll != null) {

                  this.chatScroll.post(() -> {
                     if (this.chatScroll == null || this.chatList == null) return;
                     int delta = this.chatList.getHeight() - prevHeight;
                     this.chatScroll.scrollTo(0, Math.max(0, prevScroll + delta));
                  });
               }
            });
            this.chatList.addView(loadMore);
         }

         for(AppDatabase.GmMessage m : shownMsgs) {
            if ("system".equals(m.kind)) {
               TextView sys = new TextView(this.activity);
               sys.setText(m.content);
               sys.setTextColor(this.col(color.muted));
               sys.setTextSize(11.0F);
               sys.setGravity(17);
               sys.setPadding(0, this.dp(8), 0, this.dp(8));
               this.chatList.addView(sys);
            } else {
               this.chatList.addView(this.buildMessageCard(m));
            }
         }

         this.appendVisionRetryCard(viewRoom);

         this.rebuildingChat = false;

         this.appendFailureCard(viewRoom);

         this.appendInquiryCard(viewRoom);

         AppDatabase.GmRoom cardRoom = this.db.getGmRoom(viewRoom);
         String exportState = cardRoom == null || cardRoom.exportState == null ? "idle" : cardRoom.exportState;

         final boolean runningStale = "running".equals(exportState)
                 && !(this.exportRunning && this.exportLiveRoomId == viewRoom);
         List<AppDatabase.GmDoc> docs = this.db.listGmDocs(viewRoom);
         AppDatabase.GmDoc latest = docs.isEmpty() ? null : (AppDatabase.GmDoc) docs.get(0);
         if ("running".equals(exportState) || "failed".equals(exportState) || latest != null) {
            LinearLayout finCard = new LinearLayout(this.activity);
            finCard.setOrientation(0);
            finCard.setGravity(16);
            GradientDrawable fbg = new GradientDrawable();
            fbg.setColor(0x1A7A4BD0);
            fbg.setCornerRadius((float) this.dp(14));
            fbg.setStroke(this.dp(1), 0xFF7A4BD0);
            finCard.setBackground(fbg);
            finCard.setPadding(this.dp(14), this.dp(12), this.dp(14), this.dp(12));
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
            flp.setMargins(0, this.dp(10), 0, this.dp(4));
            finCard.setLayoutParams(flp);
            TextView ficon = new TextView(this.activity);
            ficon.setText(("running".equals(exportState) && !runningStale) ? "\ud83d\udd04" : ("failed".equals(exportState) ? "\u26a0\ufe0f" : "\ud83d\udccc"));
            ficon.setTextSize(20.0F);
            finCard.addView(ficon, new LinearLayout.LayoutParams(-2, -2));
            LinearLayout ftextCol = new LinearLayout(this.activity);
            ftextCol.setOrientation(1);
            LinearLayout.LayoutParams ftlp = new LinearLayout.LayoutParams(0, -2, 1.0F);
            ftlp.setMarginStart(this.dp(10));
            ftextCol.setLayoutParams(ftlp);
            TextView ftitle = new TextView(this.activity);
            TextView fsub = new TextView(this.activity);
            if ("running".equals(exportState) && !runningStale) {
               ftitle.setText("\u6b63\u5728\u63d0\u70bc\u65b9\u6848\u2026");
               fsub.setText((cardRoom.exportNote == null ? "" : cardRoom.exportNote + " \u00b7 ") + "\u70b9\u53f3\u4fa7\u300c\u67e5\u770b\u300d\u770b\u5b9e\u65f6\u8fc7\u7a0b / \u53d6\u6d88 / \u91cd\u8bd5");
            } else if (runningStale) {
               ftitle.setText("\u63d0\u70bc\u5df2\u4e2d\u65ad\uff08\u53ef\u80fd\u5361\u4f4f\uff09");
               fsub.setText("\u4e0a\u6b21\u63d0\u70bc\u6ca1\u8dd1\u5b8c\uff08\u91cd\u88c5/\u6740\u8fdb\u7a0b\u4f1a\u4e2d\u65ad\uff09\u3002\u70b9\u53f3\u4fa7\u91cd\u8bd5\u6216\u6e05\u9664");
            } else if ("failed".equals(exportState)) {
               ftitle.setText("\u63d0\u70bc\u5931\u8d25");
               String note = cardRoom.exportNote == null ? "" : cardRoom.exportNote;
               fsub.setText((note.length() > 60 ? note.substring(0, 60) + "\u2026" : note) + " \u00b7 \u70b9\u53f3\u4fa7\u91cd\u8bd5");
            } else {

               int[] tagStyle = this.exportKindTagStyle(latest.modelName);
               TextView fkind = new TextView(this.activity);
               fkind.setText(this.exportKindTagText(latest.modelName));
               fkind.setTextColor(tagStyle[1]);
               fkind.setTextSize(11.0F);
               fkind.setTypeface((Typeface) null, 1);
               GradientDrawable ftbg = new GradientDrawable();
               ftbg.setColor(tagStyle[0]);
               ftbg.setCornerRadius((float) this.dp(7));
               fkind.setBackground(ftbg);
               fkind.setPadding(this.dp(8), this.dp(3), this.dp(8), this.dp(3));
               LinearLayout.LayoutParams fklp = new LinearLayout.LayoutParams(-2, -2);
               fklp.setMargins(0, 0, 0, this.dp(4));
               ftextCol.addView(fkind, fklp);
               ftitle.setText("\u7ec8\u7a3f \u00b7 " + (latest.title == null ? "实施方案" : latest.title));

               fsub.setText(latest.contentLength + " \u5b57 \u00b7 \u70b9\u53f3\u4fa7\u4fdd\u5b58\u4e3a TXT \u6216\u53d1\u9001\u6587\u4ef6");
            }
            ftitle.setTextColor(0xFF7A4BD0);
            ftitle.setTextSize(14.0F);
            ftitle.setTypeface((Typeface) null, 1);
            fsub.setTextColor(this.col(color.muted));
            fsub.setTextSize(11.0F);
            fsub.setPadding(0, this.dp(2), 0, 0);
            ftextCol.addView(ftitle);
            ftextCol.addView(fsub);
            finCard.addView(ftextCol);
            Button fbtn = this.ghostButton(("running".equals(exportState) && !runningStale) ? "\u67e5\u770b" : ("failed".equals(exportState) || runningStale ? "\u5904\u7406" : "\u6253\u5f00"));
            fbtn.setTextColor(0xFF7A4BD0);
            finCard.addView(fbtn, new LinearLayout.LayoutParams(-2, -2));
            final AppDatabase.GmDoc fd = latest;
            final long fRoomLive = viewRoom;
            final boolean fStale = runningStale;
            final String fState = exportState;
            fbtn.setOnClickListener((v) -> {
               if ("running".equals(fState) && !fStale) {

                  String[] opts = new String[]{"\ud83d\udc40 \u67e5\u770b\u5b9e\u65f6\u63d0\u70bc\u8fc7\u7a0b", "\u23f9 \u53d6\u6d88\u672c\u6b21\u63d0\u70bc", "\ud83d\udd01 \u653e\u5f03\u5e76\u91cd\u65b0\u63d0\u70bc"};
                  this.showActionSheet("\u6b63\u5728\u63d0\u70bc\u65b9\u6848", opts, (w) -> {
                     if (w == 0) { this.showRefineLiveView(fRoomLive); }
                     else if (w == 1) {
                        this.exportCancel.set(true);
                        this.db.setGmRoomExportState(fRoomLive, "idle", null);
                        this.showToast("\u5df2\u53d6\u6d88\u63d0\u70bc");
                        this.renderChat();
                     } else {
                        this.exportCancel.set(true);
                        this.db.setGmRoomExportState(fRoomLive, "idle", null);
                        this.pickModelThenExport((Long) null);
                     }
                  });
               } else if (fStale || "failed".equals(fState)) {

                  String[] opts = new String[]{"\ud83d\udd01 \u91cd\u65b0\u63d0\u70bc", "\ud83d\uddd1 \u6e05\u9664\u8be5\u72b6\u6001"};
                  this.showActionSheet(fStale ? "\u63d0\u70bc\u5df2\u4e2d\u65ad" : "\u63d0\u70bc\u5931\u8d25", opts, (w) -> {
                     if (w == 0) { this.db.setGmRoomExportState(fRoomLive, "idle", null); this.pickModelThenExport((Long) null); }
                     else { this.db.setGmRoomExportState(fRoomLive, "idle", null); this.renderChat(); }
                  });
               } else if (fd != null) {
                  this.showPlanDetail(fd);
               } else {
                  this.showPlanList();
               }
            });
            finCard.setOnClickListener((v) -> fbtn.performClick());
            this.chatList.addView(finCard);
         }

         if (wasNearBottom && !this.userReadingHistory && !this.chatUserTouching) {
            this.scrollChatToBottom();
         }

      }
   }

   private String exportKindTagText(String modelName) {
      String mn = modelName == null ? "" : modelName.trim();
      if ("全量对话原文".equals(mn)) return "\ud83d\udde8 全部对话原文";
      return "\ud83e\udd16 大模型提炼的实施方案";
   }

   private int[] exportKindTagStyle(String modelName) {
      String mn = modelName == null ? "" : modelName.trim();
      if ("全量对话原文".equals(mn)) return new int[]{0xFF5A6472, 0xFFFFFFFF};
      return new int[]{this.col(color.accent), this.col(color.onAccent)};
   }

   private int roleColor(String name) {
      int h = (name == null ? 0 : name.hashCode());
      float hue = Math.floorMod(h, 360);
      return android.graphics.Color.HSVToColor(new float[]{hue, 0.5f, 0.62f});
   }

   private void jumpToMessageSeq(final int seq) {
      if (this.chatList == null) return;
      final String tag = "gmseq_" + seq;
      View target = this.chatList.findViewWithTag(tag);
      if (target == null) {
         if (this.renderMsgLimit < this.db.countGmMessages(this.viewRoomId)) {
            this.userReadingHistory = true;
            this.renderMsgLimit = Math.max(this.renderMsgLimit + 120, seq + 60);
            this.renderChat();
            this.chatList.post(() -> this.jumpToMessageSeq(seq));
         } else {
            this.showToast("找不到 #" + seq);
         }
         return;
      }
      this.userReadingHistory = true;
      if (this.chatScroll != null) {
         int y = target.getTop();
         View p = (View) target.getParent();
         while (p != null && p != this.chatList) { y += p.getTop(); p = (View) p.getParent(); }
         this.chatScroll.smoothScrollTo(0, Math.max(0, y - this.dp(80)));
      }
      android.graphics.drawable.Drawable old = target.getBackground();
      target.setBackgroundColor(0x33E8791A);
      target.postDelayed(() -> target.setBackground(old), 1600L);
   }

   private void showActionSheet(String title, String[] opts, java.util.function.IntConsumer onPick) {
      LinearLayout box = new LinearLayout(this.activity);
      box.setOrientation(1);
      GradientDrawable bg = new GradientDrawable();
      bg.setColor(this.col(color.surface));
      bg.setCornerRadius((float) this.dp(20));
      bg.setStroke(this.dp(1), this.col(color.border));
      box.setBackground(bg);
      box.setPadding(this.dp(18), this.dp(16), this.dp(18), this.dp(12));
      if (title != null && !title.isEmpty()) {
         TextView h = new TextView(this.activity);
         h.setText(title);
         h.setTextColor(this.col(color.text));
         h.setTextSize(16.0F);
         h.setTypeface((Typeface) null, 1);
         box.addView(h);
      }
      final Dialog dlg = new Dialog(this.activity);

      ScrollView sv = new ScrollView(this.activity);
      LinearLayout rows = new LinearLayout(this.activity);
      rows.setOrientation(1);
      sv.addView(rows);
      for (int i = 0; i < opts.length; i++) {
         final int idx = i;
         TextView row = new TextView(this.activity);
         row.setText(opts[i]);
         row.setTextColor(this.col(color.text));
         row.setTextSize(14.0F);
         row.setPadding(this.dp(14), this.dp(13), this.dp(14), this.dp(13));
         GradientDrawable rbg = new GradientDrawable();
         rbg.setColor(this.col(color.surfaceInput));
         rbg.setCornerRadius((float) this.dp(12));
         rbg.setStroke(this.dp(1), this.col(color.border));
         row.setBackground(rbg);
         LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
         rlp.setMargins(0, this.dp(8), 0, 0);
         row.setOnClickListener((v) -> { dlg.dismiss(); if (onPick != null) onPick.accept(idx); });
         rows.addView(row, rlp);
      }

      LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(-1, 0, 1.0F);
      svlp.setMargins(0, this.dp(4), 0, 0);
      box.addView(sv, svlp);
      Button cancel = this.ghostButton("\u53d6\u6d88");
      LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
      clp.setMargins(0, this.dp(12), 0, 0);
      cancel.setOnClickListener((v) -> dlg.dismiss());
      box.addView(cancel, clp);
      dlg.setContentView(box);
      if (dlg.getWindow() != null) {
         dlg.getWindow().setBackgroundDrawable(new ColorDrawable(0));
         WindowManager.LayoutParams lp = dlg.getWindow().getAttributes();
         lp.width = (int) (this.activity.getResources().getDisplayMetrics().widthPixels * 0.86F);

         int estDp = 130 + opts.length * 76;
         int maxPx = (int) (this.activity.getResources().getDisplayMetrics().heightPixels * 0.85F);
         lp.height = Math.min(this.dp(estDp), maxPx);
         dlg.getWindow().setAttributes(lp);
      }
      dlg.show();
   }

   private View buildMessageCard(AppDatabase.GmMessage m) {
      LinearLayout card = new LinearLayout(this.activity);
      if (m.seq > 0) card.setTag("gmseq_" + m.seq);
      final boolean isUserMsg = "\u6211".equals(m.speaker);
      card.setOrientation(1);
      GradientDrawable bg = new GradientDrawable();
      if (isUserMsg) {
         bg.setColor(this.col(color.userBubble));
         bg.setCornerRadius((float)this.dp(14));
         bg.setStroke(this.dp(1), this.col(color.userBubble));
      } else {
         bg.setColor(this.col(color.surface));
         bg.setCornerRadius((float)this.dp(14));

         bg.setStroke(this.dp(m.memberId > 0L ? 2 : 1), m.memberId > 0L ? this.roleColor(m.speaker) : this.col(color.border));
      }
      card.setBackground(bg);
      card.setPadding(this.dp(12), this.dp(10), this.dp(12), this.dp(10));
      LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
      clp.setMargins(0, this.dp(5), 0, 0);
      card.setLayoutParams(clp);
      LinearLayout head = new LinearLayout(this.activity);
      head.setOrientation(0);
      head.setGravity(16);
      head.setClickable(true);

      if (m.seq > 0) {
         TextView seqTag = new TextView(this.activity);
         seqTag.setText("#" + m.seq);
         seqTag.setTextColor(this.col(color.muted));
         seqTag.setTextSize(10.0F);
         seqTag.setTypeface((Typeface)null, 1);
         LinearLayout.LayoutParams seqLp = new LinearLayout.LayoutParams(-2, -2);
         seqLp.setMarginEnd(this.dp(6));
         head.addView(seqTag, seqLp);
      }
      TextView name = new TextView(this.activity);
      name.setText((m.avatar == null ? "\ud83e\udd16" : m.avatar) + " " + (m.speaker == null ? "成员" : m.speaker));
      name.setTextColor(isUserMsg ? this.col(color.userBubbleText) : this.col(color.text));
      name.setTextSize(13.0F);
      name.setTypeface((Typeface)null, 1);
      head.addView(name);

      if (m.isThought) {
         TextView tag = new TextView(this.activity);
         tag.setText("  \ud83d\udcad 思考");
         tag.setTextColor(this.col(color.muted));
         tag.setTextSize(11.0F);
         head.addView(tag);
      }

      head.addView(new View(this.activity), new LinearLayout.LayoutParams(0, 1, 1.0F));
      TextView meta = new TextView(this.activity);
      meta.setText(this.hhmm(m.createdAt) + (m.tokens > 0 ? " · " + m.tokens + " tok" : "") + "  ▾");
      meta.setTextColor(isUserMsg ? this.col(color.userBubbleText) : this.col(color.muted));
      meta.setTextSize(10.0F);
      meta.setTag("gmMeta_" + m.id);
      head.addView(meta);
      card.addView(head);
      String raw = m.content == null ? "" : m.content;

      raw = AiClient.stripMarkersPublicKeepSections(raw);
      String toolLog = "";
      int toolsIdx = raw.indexOf("@@TOOLS:");
      if (toolsIdx >= 0) {
         toolLog = raw.substring(toolsIdx + "@@TOOLS:".length()).trim();
         raw = raw.substring(0, toolsIdx);
      }

      String thinking = "";
      int thinkIdx = raw.indexOf("@@THINKING:");
      if (thinkIdx >= 0) {
         thinking = raw.substring(thinkIdx + "@@THINKING:".length()).trim();
         raw = raw.substring(0, thinkIdx);
      }

      String speechText = raw.trim();
      List<LinearLayout> bodies = new ArrayList();
      if (!thinking.isEmpty()) {
         bodies.add(this.addGmSection(card, "\ud83d\udcad 思考过程", thinking, this.col(color.accentBright), 254766054, true, false, m.id, "thought"));
      }

      if (!toolLog.isEmpty()) {

         bodies.add(this.addToolSection(card, toolLog, m.id));
      }

      LinearLayout replyBody = this.addGmSection(card, "\ud83d\udcac 回复", speechText.isEmpty() ? "（本轮没有发言）" : speechText, this.col(color.accent), isUserMsg ? this.col(color.surface) : 0, true, false, m.id, "reply");
      bodies.add(replyBody);

      if (isUserMsg) {
         List<AppDatabase.GmBasicInfo> injectedList = null;
         try { injectedList = this.db.listInjectedBasicInfo(m.roomId); } catch (Exception ignored) { }
         if (injectedList != null && !injectedList.isEmpty()) {
            StringBuilder bi = new StringBuilder();
            for (AppDatabase.GmBasicInfo b : injectedList) {
               bi.append("· ").append(b.title == null ? "" : b.title.trim());
               String bc = b.content == null ? "" : b.content.trim();
               if (!bc.isEmpty()) bi.append("：").append(bc);
               bi.append("\n");
            }
            bi.append("\n（这些是本群管理员从「基础信息」库里挑出来注入的，"
                    + "已作为系统公告常驻在每位专家的上下文里，他们不会再为这些提问。）");

            LinearLayout biBody = this.addGmSection(card, "\ud83d\udccc 基础信息（"
                    + injectedList.size() + " 条）", bi.toString().trim(),
                    this.col(color.accent), this.col(color.surface), true, false, m.id, "basicinfo");
            bodies.add(biBody);
         }
      }

      java.util.regex.Matcher refM = java.util.regex.Pattern.compile("(?:\u56de\u770b|\u53c2\u89c1|\u8be6\u89c1|\u91cd\u70b9\u770b|\u52a1\u5fc5\u770b|\u5fc5\u770b|\u5efa\u8bae\u770b|\u53c2\u8003)\\s*#?(\\d{1,4})").matcher(speechText);
      final java.util.LinkedHashSet<Integer> refs = new java.util.LinkedHashSet<>();
      while (refM.find() && refs.size() < 4) {
         try {
            int n = Integer.parseInt(refM.group(1));
            if (n > 0 && n != m.seq) refs.add(n);
         } catch (Exception ignored) {}
      }
      if (!refs.isEmpty()) {

         LinearLayout refWrap = new LinearLayout(this.activity);
         refWrap.setOrientation(1);
         refWrap.setPadding(0, this.dp(6), 0, 0);
         int perRow = Math.max(2, (this.activity.getResources().getDisplayMetrics().widthPixels - this.dp(48)) / this.dp(96));
         LinearLayout refRow = null;
         int inRow = 0;
         for (final Integer rn : refs) {
            if (refRow == null || inRow >= perRow) {
               refRow = new LinearLayout(this.activity);
               refRow.setOrientation(0);
               LinearLayout.LayoutParams rrlp = new LinearLayout.LayoutParams(-1, -2);
               rrlp.setMargins(0, 0, 0, this.dp(6));
               refRow.setLayoutParams(rrlp);
               refWrap.addView(refRow);
               inRow = 0;
            }
            TextView chip = new TextView(this.activity);
            chip.setText("\ud83d\udd17 \u5b9a\u4f4d #" + rn);
            chip.setTextColor(this.col(color.accent));
            chip.setTextSize(11.0F);
            chip.setClickable(true);
            chip.setPadding(this.dp(8), this.dp(3), this.dp(8), this.dp(3));
            GradientDrawable cbg = new GradientDrawable();
            cbg.setColor(this.col(color.surfaceInput));
            cbg.setCornerRadius((float) this.dp(8));
            cbg.setStroke(this.dp(1), this.col(color.border));
            chip.setBackground(cbg);
            LinearLayout.LayoutParams chlp = new LinearLayout.LayoutParams(-2, -2);
            chlp.setMarginEnd(this.dp(6));
            chip.setOnClickListener((v) -> this.jumpToMessageSeq(rn));
            refRow.addView(chip, chlp);
            inRow++;
         }
         card.addView(refWrap);
      }

      final long msgId = m.id;
      boolean expanded = this.expandedMsgIds.contains(msgId);
      if (expanded) {
         for(LinearLayout b : bodies) b.setVisibility(0);
         meta.setText(this.hhmm(m.createdAt) + (m.tokens > 0 ? " · " + m.tokens + " tok" : "") + "  ▴");
      }
      head.setOnClickListener((v) -> {
         boolean anyHidden = false;

         for(LinearLayout b : bodies) {
            if (b.getVisibility() != 0) {
               anyHidden = true;
               break;
            }
         }

         boolean show = anyHidden;

         for(LinearLayout b : bodies) {
            b.setVisibility(show ? 0 : 8);
         }

         if (show) {
            this.expandedMsgIds.add(msgId);
         } else {
            this.expandedMsgIds.remove(msgId);
         }

         String base = this.hhmm(m.createdAt) + (m.tokens > 0 ? " · " + m.tokens + " tok" : "");
         meta.setText(base + (show ? "  ▴" : "  ▾"));
      });

      if (m.memberId > 0L && !isUserMsg) {
         String modelName = null;
         try {
            AppDatabase.GmMember mem = this.db.getGmMember(m.memberId);
            if (mem == null || mem.model == null || mem.model.trim().isEmpty()) {
               for (AppDatabase.GmMember x : this.db.listGmMembers(this.viewRoomId)) {
                  if (x.name != null && x.name.equals(m.speaker)) { mem = x; break; }
               }
            }
            if (mem != null && mem.model != null && !mem.model.trim().isEmpty()) modelName = mem.model.trim();
         } catch (Exception ignored) { }
         if (modelName == null) modelName = "未知模型";

         boolean usedSearch = toolLog.contains("web_search");
         boolean usedFetch = toolLog.contains("fetch_url");
         boolean usedWeb = usedSearch || usedFetch;
         boolean usedTool = !toolLog.isEmpty();

         int searchCnt = AiClient.countToolCalls(toolLog, "web_search");
         int fetchCnt = AiClient.countToolCalls(toolLog, "fetch_url");

         LinearLayout footer = new LinearLayout(this.activity);
         footer.setOrientation(1);
         LinearLayout.LayoutParams ftLp = new LinearLayout.LayoutParams(-1, -2);
         ftLp.setMargins(0, this.dp(6), 0, 0);
         footer.setLayoutParams(ftLp);

         boolean hasTps = m.completionTokens > 0 && m.genMs > 0L;
         boolean showToolTag = usedTool && !usedWeb;
         if (usedWeb || hasTps || showToolTag) {
            LinearLayout rowA = new LinearLayout(this.activity);
            rowA.setOrientation(0);
            rowA.setGravity(16);
            if (usedWeb) {

               TextView webTag = new TextView(this.activity);
               webTag.setText("\ud83c\udf10 联网查证");
               webTag.setTextColor(this.col(color.searchAccent));
               webTag.setTextSize(10.0F);
               webTag.setTypeface((Typeface)null, 1);
               GradientDrawable wbg = new GradientDrawable();
               wbg.setColor(0x18E8791A);
               wbg.setCornerRadius((float)this.dp(6));
               webTag.setBackground(wbg);
               webTag.setPadding(this.dp(6), this.dp(2), this.dp(6), this.dp(2));
               rowA.addView(webTag);
            }
            if (hasTps) {

               double secs = m.genMs / 1000.0;
               int tps = (m.completionTokens > 0 && secs > 0.05)
                       ? (int) Math.round(m.completionTokens / secs) : 0;
               if (tps > 0) {
                  TextView tp = new TextView(this.activity);
                  tp.setText("\u26a1 " + tps + " tok/s");
                  tp.setTextColor(this.col(color.muted));
                  tp.setTextSize(10.0F);
                  tp.setPadding(this.dp(6), this.dp(2), this.dp(6), this.dp(2));
                  GradientDrawable tpb = new GradientDrawable();
                  tpb.setColor(this.col(color.surfaceInput));
                  tpb.setCornerRadius((float)this.dp(6));
                  tpb.setStroke(this.dp(1), this.col(color.border));
                  tp.setBackground(tpb);
                  LinearLayout.LayoutParams tlp2 = new LinearLayout.LayoutParams(-2, -2);
                  tlp2.setMarginStart(this.dp(5));
                  rowA.addView(tp, tlp2);
               }
            }
            rowA.addView(new View(this.activity), new LinearLayout.LayoutParams(0, 1, 1.0F));
            if (showToolTag) {
               TextView toolTag = new TextView(this.activity);
               toolTag.setText("\ud83d\udd27 调用工具");
               toolTag.setTextColor(this.col(color.searchAccent));
               toolTag.setTextSize(10.0F);
               toolTag.setTypeface((Typeface)null, 1);
               GradientDrawable ttbg = new GradientDrawable();
               ttbg.setColor(0x18E8791A);
               ttbg.setCornerRadius((float)this.dp(6));
               toolTag.setBackground(ttbg);
               toolTag.setPadding(this.dp(6), this.dp(2), this.dp(6), this.dp(2));
               rowA.addView(toolTag);
            }
            footer.addView(rowA, new LinearLayout.LayoutParams(-1, -2));
         }

         LinearLayout rowB = new LinearLayout(this.activity);
         rowB.setOrientation(0);
         rowB.setGravity(8388613);

         if (m.isThought) {
            LinearLayout rowSilent = new LinearLayout(this.activity);
            rowSilent.setOrientation(0);
            rowSilent.setGravity(8388611);
            TextView silentTag = new TextView(this.activity);
            silentTag.setText("（未发言）");
            silentTag.setTextColor(this.col(color.muted));
            silentTag.setTextSize(10.0F);
            GradientDrawable stbg = new GradientDrawable();
            stbg.setColor(this.col(color.surfaceInput));
            stbg.setCornerRadius((float)this.dp(6));
            stbg.setStroke(this.dp(1), this.col(color.border));
            silentTag.setBackground(stbg);
            silentTag.setPadding(this.dp(6), this.dp(2), this.dp(6), this.dp(2));
            rowSilent.addView(silentTag);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
            slp.setMargins(0, this.dp(4), 0, 0);

            slp.setMarginStart(this.dp(5));
            footer.addView(rowSilent, slp);
         }
         TextView modelChip = new TextView(this.activity);
         modelChip.setText(modelName);
         modelChip.setTextColor(this.col(color.muted));
         modelChip.setTextSize(10.0F);
         modelChip.setBackgroundResource(drawable.btn_ghost);
         modelChip.setPadding(this.dp(10), this.dp(4), this.dp(10), this.dp(4));
         rowB.addView(modelChip);
         LinearLayout.LayoutParams rbLp = new LinearLayout.LayoutParams(-1, -2);
         rbLp.setMargins(0, this.dp(4), 0, 0);
         footer.addView(rowB, rbLp);

         card.addView(footer, ftLp);
      }
      return card;
   }

   private LinearLayout addGmSection(LinearLayout parent, String title, String bodyText, int accentColor, int bgColor, boolean startCollapsed, boolean mono) {
      return this.addGmSection(parent, title, bodyText, accentColor, bgColor, startCollapsed, mono, 0L, null);
   }

   private LinearLayout addGmSection(LinearLayout parent, String title, String bodyText, int accentColor, int bgColor, boolean startCollapsed, boolean mono, long msgId, String sectionTag) {
      LinearLayout wrap = new LinearLayout(this.activity);
      wrap.setOrientation(1);
      LinearLayout box = new LinearLayout(this.activity);
      box.setOrientation(1);
      GradientDrawable bg = new GradientDrawable();
      bg.setColor(bgColor);
      bg.setCornerRadius((float)this.dp(10));
      box.setBackground(bg);
      box.setPadding(this.dp(10), this.dp(6), this.dp(10), this.dp(6));
      wrap.addView(box);
      LinearLayout head = new LinearLayout(this.activity);
      head.setOrientation(0);
      head.setGravity(16);
      View bar = new View(this.activity);
      bar.setBackgroundColor(accentColor);
      LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(this.dp(3), this.dp(14));
      barLp.setMarginEnd(this.dp(8));
      head.addView(bar, barLp);
      TextView titleView = new TextView(this.activity);
      titleView.setText(title + "  ▾");
      titleView.setTextColor(accentColor);
      titleView.setTextSize(12.0F);
      titleView.setTypeface((Typeface)null, 1);
      head.addView(titleView);
      box.addView(head);
      TextView body = new TextView(this.activity);

      body.setText(MarkdownRenderer.render(bodyText, this.col(color.text2),
              this.col(color.muted), this.col(color.accent), mono));
      body.setTextColor(this.col(color.text2));
      body.setTextSize(mono ? 11.0F : 12.0F);
      body.setLineSpacing((float)this.dp(2), 1.15F);
      body.setPadding(this.dp(11), this.dp(6), 0, this.dp(2));
      NestedLimitScrollView sv = new NestedLimitScrollView(this.activity);
      sv.setMaxHeightPx(this.dp(mono ? 120 : 200));
      sv.addView(body, new FrameLayout.LayoutParams(-1, -2));
      body.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
         if (b - t != ob - ot) {
            sv.post(() -> {
               if (!sv.isUserTouching()) {
                  sv.fullScroll(130);
               }

            });
         }

      });
      box.addView(sv);

      final String skey = (msgId > 0L && sectionTag != null) ? sectionKey(msgId, sectionTag) : null;
      boolean openNow = (skey != null && this.expandedSections.contains(skey)) ? true : !startCollapsed;
      final boolean[] open = new boolean[]{openNow};
      sv.setVisibility(open[0] ? 0 : 8);
      titleView.setText(title + (open[0] ? "  ▴" : "  ▾"));
      head.setOnClickListener((v) -> {
         open[0] = !open[0];
         sv.setVisibility(open[0] ? 0 : 8);
         titleView.setText(title + (open[0] ? "  ▴" : "  ▾"));

         if (skey != null) {
            if (open[0]) this.expandedSections.add(skey);
            else this.expandedSections.remove(skey);
         }
      });
      LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-1, -2);
      wlp.setMargins(0, this.dp(6), 0, 0);
      parent.addView(wrap, wlp);
      return wrap;
   }

   private LinearLayout addToolSection(LinearLayout parent, String toolLog, long msgId) {
      int fallbackCount = 0;
      int totalCount = 0;
      for (String ln : toolLog.split("\n")) {
         if (ln.trim().isEmpty()) continue;
         ++totalCount;
         if (AiClient.isFallbackLine(ln)) ++fallbackCount;
      }

      int searchCount = AiClient.countToolCalls(toolLog, "web_search");
      int fetchCount = AiClient.countToolCalls(toolLog, "fetch_url");

      StringBuilder titleSb = new StringBuilder("\ud83d\udd27 工具调用");
      if (searchCount > 0) titleSb.append(" · \ud83d\udd0d 搜索 ").append(searchCount).append(" 次");
      if (fetchCount > 0) titleSb.append(" · \ud83d\udcc4 抓取 ").append(fetchCount).append(" 次");
      if (fallbackCount > 0) titleSb.append("（").append(fallbackCount).append("/").append(totalCount).append(" 条降级）");
      final String toolTitle = titleSb.toString();

      LinearLayout wrap = new LinearLayout(this.activity);
      wrap.setOrientation(1);
      LinearLayout box = new LinearLayout(this.activity);
      box.setOrientation(1);
      GradientDrawable bg = new GradientDrawable();
      bg.setColor(350779674);
      bg.setCornerRadius((float) this.dp(10));
      box.setBackground(bg);
      box.setPadding(this.dp(10), this.dp(6), this.dp(10), this.dp(6));
      wrap.addView(box);

      LinearLayout head = new LinearLayout(this.activity);
      head.setOrientation(0);
      head.setGravity(16);
      View bar = new View(this.activity);

      bar.setBackgroundColor(this.col(fallbackCount > 0 ? color.accentBright : color.searchAccent));
      LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(this.dp(3), this.dp(14));
      barLp.setMarginEnd(this.dp(8));
      head.addView(bar, barLp);
      TextView titleView = new TextView(this.activity);
      titleView.setText(toolTitle + "  ▾");
      titleView.setTextColor(this.col(fallbackCount > 0 ? color.accentBright : color.searchAccent));
      titleView.setTextSize(12.0F);
      titleView.setTypeface((Typeface) null, 1);
      head.addView(titleView);
      box.addView(head);

      LinearLayout lines = new LinearLayout(this.activity);
      lines.setOrientation(1);
      lines.setPadding(this.dp(11), this.dp(6), 0, this.dp(2));
      for (String ln : toolLog.split("\n")) {
         if (ln.trim().isEmpty()) continue;
         boolean isFallback = AiClient.isFallbackLine(ln);
         TextView tv = new TextView(this.activity);

         String show = ln.replaceFirst("^\\[FB\\]\\s*", "").replaceFirst("^\\[TQ\\]\\s*", "")
                 .replaceFirst("^\\[API\\]\\s*", "");
         if (isFallback) show = "⚠ 降级 · " + show;
         tv.setText(show);
         tv.setTextColor(this.col(isFallback ? color.accentBright : color.text2));
         tv.setTextSize(11.0F);
         tv.setLineSpacing((float) this.dp(2), 1.15F);
         tv.setPadding(0, this.dp(2), 0, this.dp(2));
         lines.addView(tv);
      }
      NestedLimitScrollView sv = new NestedLimitScrollView(this.activity);
      sv.setMaxHeightPx(this.dp(120));
      sv.addView(lines, new FrameLayout.LayoutParams(-1, -2));
      box.addView(sv);

      final String skey = msgId > 0L ? sectionKey(msgId, "tool") : null;
      boolean openNow = skey != null && this.expandedSections.contains(skey);
      final boolean[] open = new boolean[]{openNow};
      sv.setVisibility(open[0] ? 0 : 8);
      titleView.setText(toolTitle + (open[0] ? "  ▴" : "  ▾"));
      head.setOnClickListener((v) -> {
         open[0] = !open[0];
         sv.setVisibility(open[0] ? 0 : 8);
         titleView.setText(toolTitle + (open[0] ? "  ▴" : "  ▾"));
         if (skey != null) {
            if (open[0]) this.expandedSections.add(skey);
            else this.expandedSections.remove(skey);
         }
      });

      LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-1, -2);
      wlp.setMargins(0, this.dp(6), 0, 0);
      parent.addView(wrap, wlp);
      return wrap;
   }

   private long inferResumeAnchor(long roomId) {
      try {
         List<AppDatabase.GmMessage> msgs = this.db.listGmMessages(roomId, 200);
         if (msgs.isEmpty()) return -1L;

         for (int i = msgs.size() - 1; i >= 0; --i) {
            AppDatabase.GmMessage m = msgs.get(i);
            String kind = m.kind == null ? "" : m.kind;
            if (!"system".equals(kind)) break;
            String c = m.content == null ? "" : m.content;
            if (!c.contains("请求失败")) continue;

            String name = this.roleNameFromFailureNotice(c);
            if (name == null) continue;
            AppDatabase.GmMember failed = this.findMemberByName(roomId, name);
            if (failed == null) continue;

            boolean recovered = false;
            for (AppDatabase.GmMessage mm : msgs) {
               if (mm.id > m.id && mm.memberId == failed.id) {
                  recovered = true;
                  break;
               }
            }
            if (recovered) continue;

            long anchor = 0L;
            for (AppDatabase.GmMember mem : this.orderMembersForRound(this.db.listGmMembers(roomId))) {
               if (mem.id == failed.id) break;
               anchor = mem.id;
            }
            android.util.Log.i("VC_GM", "续跑锚点=重试失败角色 room=" + roomId
                    + " failed=" + failed.name + "(id=" + failed.id + ") anchor=" + anchor);
            return anchor;
         }

         for (int i = msgs.size() - 1; i >= 0; --i) {
            AppDatabase.GmMessage m = msgs.get(i);
            String kind = m.kind == null ? "" : m.kind;
            if (m.memberId > 0L && !"system".equals(kind)) {
               return m.memberId;
            }
         }
         return -1L;
      } catch (Exception e) {
         android.util.Log.w("VC_GM", "推断续跑点失败: " + e.getMessage());
         return -1L;
      }
   }

   private String roleNameFromFailureNotice(String content) {
      if (content == null) return null;
      int idx = content.indexOf("请求失败");
      if (idx <= 0) return null;
      String head = content.substring(0, idx).trim();

      head = head.replace("\u26a0\ufe0f", "").replace("\u26a0", "").trim();
      return head.isEmpty() ? null : head;
   }

   private AppDatabase.GmMember findMemberByName(long roomId, String name) {
      if (name == null) return null;
      for (AppDatabase.GmMember m : this.db.listGmMembers(roomId)) {
         if (name.equals(m.name)) return m;
      }
      return null;
   }

   private void appendInquiryCard(long roomId) {
      try {
         final AppDatabase.GmInquiry q = this.db.latestGmInquiry(roomId);
         if (q == null || !"pending".equals(q.status)) return;

         int total = 0, filled = 0;
         if (q.items != null) {
            for (AppDatabase.GmInquiryItem it : q.items) {
               ++total;
               if (it.answer != null && !it.answer.trim().isEmpty()) ++filled;
            }
         }

         LinearLayout card = new LinearLayout(this.activity);
         card.setOrientation(1);
         GradientDrawable bg = new GradientDrawable();
         bg.setColor(0x1A2F6BE6);
         bg.setCornerRadius((float) this.dp(14));
         bg.setStroke(this.dp(1), this.col(color.accent));
         card.setBackground(bg);
         card.setPadding(this.dp(14), this.dp(12), this.dp(14), this.dp(8));

         TextView title = new TextView(this.activity);
         title.setText("\ud83d\udcdd 等你回复 · " + (q.title == null ? "需要你确认的问题" : q.title));
         title.setTextColor(this.col(color.accent));
         title.setTextSize(13.0F);
         title.setTypeface((Typeface) null, 1);
         card.addView(title);

         TextView body = new TextView(this.activity);
         String who = "";
         if (q.items != null && !q.items.isEmpty() && q.items.get(0).asker != null) {
            java.util.LinkedHashSet<String> askers = new java.util.LinkedHashSet<>();
            for (AppDatabase.GmInquiryItem it : q.items) {
               if (it.asker != null && !it.asker.trim().isEmpty()) askers.add(it.asker.trim());
            }
            who = String.join("、", askers);
         }
         body.setText("共 " + total + " 个问题"
                     + (filled > 0 ? "（已填 " + filled + " 个）" : "")
                     + (who.isEmpty() ? "" : "\n提问者：" + who));
         body.setTextColor(this.col(color.text2));
         body.setTextSize(11.5F);
         body.setLineSpacing((float) this.dp(2), 1.0F);
         body.setPadding(0, this.dp(6), 0, 0);
         card.addView(body);

         Button ask = this.primaryButton("\ud83d\udcdd 问答", this.col(color.onAccent));
         ask.setTextSize(12.0F);
         ask.setPadding(this.dp(14), 0, this.dp(14), 0);
         ask.setMinWidth(0);
         ask.setMinimumWidth(0);
         ask.setMinHeight(0);
         ask.setMinimumHeight(0);
         LinearLayout row = new LinearLayout(this.activity);
         row.setOrientation(0);
         row.setGravity(8388613);
         LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, this.dp(32));
         rlp.setMargins(0, this.dp(10), 0, 0);
         row.addView(ask, rlp);
         card.addView(row);

         ask.setOnClickListener((v) -> this.showInquiryDraft(q.id));

         LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
         clp.setMargins(0, this.dp(10), 0, 0);
         card.setLayoutParams(clp);
         this.chatList.addView(card);
      } catch (Exception e) {
         android.util.Log.w("VC_GM", "问答卡片渲染失败: " + e.getMessage());
      }
   }

   private void scrollChatToBottom() {
      if (this.chatScroll != null) {
         this.chatScroll.post(() -> this.chatScroll.fullScroll(130));
      }
   }

   private void followLiveScrollIfWatching() {
      if (this.chatScroll == null) return;

      if (this.chatUserTouching) return;
      if (this.userReadingHistory) return;
      this.chatScroll.post(() -> {
         if (this.chatUserTouching || this.userReadingHistory) return;
         this.chatScroll.fullScroll(130);
      });
   }

   private void jumpToLatest() {
      this.userReadingHistory = false;
      this.scrollChatToBottom();
   }

   private void updateReadingHistoryFlag() {

      if (this.rebuildingChat) return;
      if (this.chatScroll != null) {
         View child = this.chatScroll.getChildAt(0);
         if (child != null) {
            boolean nearBottom;
            try {
               nearBottom = child.getHeight() - (this.chatScroll.getScrollY() + this.chatScroll.getHeight()) < this.dp(80);
            } catch (Exception var4) {
               return;
            }

            this.userReadingHistory = !nearBottom;
         }
      }
   }

   private void updateChatArrow() {
      if (this.chatJumpArrow != null && this.chatScroll != null) {
         View child = this.chatScroll.getChildAt(0);
         if (child != null) {
            boolean nearBottom = child.getHeight() - (this.chatScroll.getScrollY() + this.chatScroll.getHeight()) < this.dp(120);
            this.chatJumpArrow.setVisibility(nearBottom ? 8 : 0);
         }
      }
   }

   private String describeGroupImages(String text) {
      if (text == null || !text.contains("@@IMG:")) return text;
      StringBuilder out = new StringBuilder();
      String[] lines = text.split("\n");
      int idx = 0;
      int failed = 0;
      for (String line : lines) {
         String t = line.trim();
         if (!t.startsWith("@@IMG:")) {
            out.append(line).append("\n");
            continue;
         }
         String path = t.substring("@@IMG:".length()).trim();
         ++idx;
         String desc = null;
         try {
            String b64 = this.readImageAsBase64(path);
            if (b64 != null) {
               desc = AiClient.describeImage(this.settings,
                       b64, "请详细描述这张图片的内容，用中文回答。");
            }
         } catch (Exception e) {
            android.util.Log.w("VC_IMG", "群聊图片识别失败: " + e.getMessage());
         }
         if (desc != null && !desc.trim().isEmpty()) {

            out.append("\n【用户发来的图片 ").append(idx).append("，图片识别模型识别出的内容：】\n")
               .append(desc.trim()).append("\n");
         } else {
            ++failed;

            out.append(line).append("\n");
            out.append("【这张图片这次没识别成功（识别模型请求失败），可以点下面的「重新识别」再试。】\n");
         }
      }
      String result = out.toString().trim();
      if (failed > 0) {

         this.lastImageVisionFailed = failed;
      } else {
         this.lastImageVisionFailed = 0;
      }
      return result;
   }

   private String readImageAsBase64(String path) {
      if (path == null || path.trim().isEmpty()) return null;
      try {
         java.io.File f = new java.io.File(path.trim());
         if (!f.exists() || !f.isFile()) return null;
         byte[] bytes = new byte[(int) f.length()];
         java.io.FileInputStream in = new java.io.FileInputStream(f);
         try {
            int off = 0;
            while (off < bytes.length) {
               int n = in.read(bytes, off, bytes.length - off);
               if (n <= 0) break;
               off += n;
            }
         } finally {
            try { in.close(); } catch (Exception ignored) { }
         }
         return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
      } catch (Exception e) {
         return null;
      }
   }

   private int lastImageVisionFailed = 0;

   private boolean onUserSays(String text) {
      if (this.currentRoomId <= 0L) {
         return false;
      } else if (this.isRoomRunning(this.currentRoomId)) {
         this.showToast("这个群正在讨论中…可先暂停");
         return false;
      } else {
         AppDatabase.GmRoom room = this.db.getGmRoom(this.currentRoomId);
         if (room == null) {
            return false;
         } else {
            List<AppDatabase.GmMember> members = this.db.listGmMembers(this.currentRoomId);
            if (members.isEmpty()) {
               this.showToast("先添加成员（点右上角「\ud83d\udc65 成员」）");
               return false;
            }

            final String textToStore = text.contains("@@IMG:") ? this.describeGroupImages(text) : text;
            final int visionFailed = this.lastImageVisionFailed;
            if (visionFailed > 0) {

               this.showToast("有 " + visionFailed + " 张图片没识别成功，可以点聊天区的「重新识别」再试");
            }

            if (!room.started) {

               this.db.insertGmMessage(this.currentRoomId, 0L, "我", "\ud83d\ude4b", textToStore, false, "chat", 0);
               this.renderChat();
               this.showToast("已记下你的需求。点下面「▶ 讨论」开启，他们就会开始讨论");
               return true;
            } else {
               this.db.insertGmMessage(this.currentRoomId, 0L, "我", "\ud83d\ude4b", textToStore, false, "chat", 0);
               this.renderChat();
               this.startDiscussion();
               return true;
            }
         }
      }
   }

   public void showFullScreenEditor(EditText target) {
      Dialog dlg = new Dialog(this.activity);
      LinearLayout root = new LinearLayout(this.activity);
      root.setOrientation(1);

      GradientDrawable editorBg = new GradientDrawable();
      editorBg.setColor(this.col(color.surface));
      editorBg.setCornerRadius((float) this.dp(20));
      editorBg.setStroke(this.dp(1), this.col(color.border));
      root.setBackground(editorBg);
      root.setClipToOutline(true);
      root.setPadding(this.dp(16), this.dp(14), this.dp(16), this.dp(14));

      EditText big = new EditText(this.activity);
      big.setText(target.getText().toString());
      big.setSelection(big.getText().length());

      CharSequence srcHint = target.getHint();
      big.setHint(srcHint == null ? "" : srcHint);
      big.setTextColor(this.col(color.text));
      big.setHintTextColor(this.col(color.muted));
      big.setTextSize(15.0F);
      big.setGravity(8388659);
      big.setInputType(131073);

      big.setBackgroundResource(drawable.bg_edit_soft);
      big.setPadding(this.dp(14), this.dp(14), this.dp(14), this.dp(14));
      big.setVerticalScrollBarEnabled(true);
      root.addView(big, new LinearLayout.LayoutParams(-1, 0, 1.0F));

      LinearLayout footRow = new LinearLayout(this.activity);
      footRow.setOrientation(0);
      footRow.setGravity(16);
      LinearLayout.LayoutParams frlp = new LinearLayout.LayoutParams(-1, -2);
      frlp.setMargins(0, this.dp(10), 0, 0);
      TextView title = new TextView(this.activity);
      title.setText("编辑消息");
      title.setTextColor(this.col(color.text));
      title.setTextSize(15.0F);
      title.setTypeface((Typeface) null, 1);
      footRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1.0F));
      Button ok = this.ghostButton("完成");
      ok.setTextColor(this.col(color.accent));
      ok.setTextSize(13.0F);
      ok.setMinWidth(0);
      ok.setMinimumWidth(0);
      ok.setMinHeight(0);
      ok.setMinimumHeight(0);
      ok.setPadding(this.dp(16), 0, this.dp(16), 0);
      footRow.addView(ok, new LinearLayout.LayoutParams(-2, this.dp(36)));
      Button cancel = this.ghostButton("取消");
      cancel.setTextSize(13.0F);
      cancel.setMinWidth(0);
      cancel.setMinimumWidth(0);
      cancel.setMinHeight(0);
      cancel.setMinimumHeight(0);
      cancel.setPadding(this.dp(16), 0, this.dp(16), 0);
      LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, this.dp(36));
      clp.setMarginStart(this.dp(8));
      footRow.addView(cancel, clp);
      root.addView(footRow, frlp);

      final Runnable saveAndClose = () -> {
         target.setText(big.getText().toString());
         target.setSelection(target.getText().length());
         dlg.dismiss();
      };
      ok.setOnClickListener((v) -> saveAndClose.run());
      cancel.setOnClickListener((v) -> dlg.dismiss());
      dlg.setContentView(root);
      dlg.setCanceledOnTouchOutside(false);

      dlg.setOnKeyListener((d, keyCode, ev) -> {
         if (keyCode == android.view.KeyEvent.KEYCODE_BACK && ev.getAction() == android.view.KeyEvent.ACTION_UP) {
            saveAndClose.run();
            return true;
         }
         return false;
      });
      Window w = dlg.getWindow();
      if (w != null) {
         w.setBackgroundDrawableResource(17170445);

         w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                 | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
         w.setGravity(android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
         DisplayMetrics dm = this.activity.getResources().getDisplayMetrics();
         w.setLayout((int)((float)dm.widthPixels * 0.96F),
                 (int)((float)dm.heightPixels * 0.56F));
      }

      dlg.show();
      big.requestFocus();
   }

   private void startDiscussion() {
      long roomId = this.currentRoomId;
      if (roomId <= 0L) return;

      RoomRun priorRun = this.roomRuns.get(roomId);
      if (priorRun != null && !priorRun.stop.get()) {
         this.showToast("这个群正在讨论中");
         return;
      }
      if (priorRun != null) {
         int tries = this.pendingStartTries.merge(roomId, 1, Integer::sum);
         if (tries <= 20) {
            this.ui.postDelayed(this::startDiscussion, 300L);
            return;
         }

         android.util.Log.w("VC_GM", "room=" + roomId + " 上一场讨论迟迟未收尾，强制接管重启");
         this.roomRuns.remove(roomId);
      }
      this.pendingStartTries.remove(roomId);

      if (this.roomRuns.size() >= MAX_PARALLEL_ROOMS) {
         this.showToast("已有 " + this.roomRuns.size() + " 个群在同时讨论（上限 " + MAX_PARALLEL_ROOMS + "），请先停一个再开");
         return;
      }
      final RoomRun run = this.runOf(roomId);
      run.stop.set(false);
      run.awaitingInquiry = false;

      this.resetRoomBuf(roomId);
      this.detachLiveMemberCard();

      this.beginBgWork("discuss");
      this.running = true;
      this.runningRoomId = roomId;
      this.refreshStartButton();
      this.db.insertGmMessage(roomId, 0L, "系统", "⚙️", "\ud83e\udd16 讨论开始（自由发言，可沉默；一直讨论到全员确认最终方案为止）", false, "system", 0);
      this.renderChat();

      this.bgExecute(this.discussExecutor, () -> {

         android.util.Log.i("VC_GM", "讨论线程启动 room=" + roomId + " | " + this.discussPoolStat());
         try {
            this.runDiscussionLoop(roomId, run);
         } catch (Throwable t) {
            android.util.Log.w("VC_GM", "讨论线程异常退出 room=" + roomId + " : " + t);

            try { this.roomRuns.remove(roomId); } catch (Throwable ignored) { }
            if (this.roomRuns.isEmpty()) {
               try { this.endBgWork("discuss"); } catch (Throwable ignored) { }
            }
            if (this.runningRoomId == roomId) {
               this.running = false;
               this.runningRoomId = 0L;
            }
            try { this.db.setGmRoomBlockedUntil(roomId, 0L); } catch (Throwable ignored) { }
            this.ui.post(() -> this.resetRoomBuf(roomId));
            try {
               this.db.insertGmMessage(roomId, 0L, "系统", "⚠️",
                       "讨论异常结束：" + t, false, "system", 0);
            } catch (Throwable ignored) { }
         }
      });
   }

   private void runDiscussionLoop(final long roomId, final RoomRun run) {

         AppDatabase.GmRoom roomCfg = this.db.getGmRoom(roomId);
         final int MAX_ROUNDS = 9999;

         final int ctxBudget = roomCfg == null || roomCfg.contextLimit <= 0 ? 1000000 : roomCfg.contextLimit;
         final int ctxHardStop = (int) (ctxBudget * 0.95);

         int resumeRound = roomCfg != null ? roomCfg.lastRound : 0;
         long resumeAfterMemberId = roomCfg != null ? roomCfg.lastMemberId : 0L;

         if (resumeRound <= 0) {
            long inferredAnchor = this.inferResumeAnchor(roomId);
            if (inferredAnchor >= 0L) {
               resumeRound = 1;
               resumeAfterMemberId = inferredAnchor;
               android.util.Log.i("VC_GM", "断点由消息历史推断 room=" + roomId
                       + " anchor=" + inferredAnchor + "（本次之前跑的讨论没有断点字段）");
            }
         }

         if (resumeRound > 0) {
            if (resumeRound > MAX_ROUNDS) resumeRound = MAX_ROUNDS;
            final int rr0 = resumeRound;
            this.db.insertGmMessage(roomId, 0L, "系统", "\u23e9",
                    "从上次中断处继续：第 " + rr0 + " 轮（本轮已发言的角色不重复）。", false, "system", 0);
            this.ui.post(this::renderChat);
            android.util.Log.i("VC_GM", "断点续跑 room=" + roomId + " round=" + rr0
                    + " afterMember=" + resumeAfterMemberId);
         }
         final int startRound = resumeRound > 0 ? resumeRound : 1;

         this.restoreMinorityIfNeeded(roomId, run, startRound);

         boolean naturalEnd = false;

         try {

            AppDatabase.GmInquiry openInq0 = this.db.latestGmInquiry(roomId);
            if (openInq0 != null && "pending".equals(openInq0.status)) {
               final long iq0 = openInq0.id;
               this.ui.post(() -> {
                  this.renderChat();
                  if (this.isVisible() && this.viewRoomId == roomId
                          && !this.activity.isFinishing() && !this.activity.isDestroyed()) {
                     this.showInquiryDraft(iq0);
                  }
               });
               run.awaitingInquiry = true;
               return;
            }
            for(int round = startRound; round <= MAX_ROUNDS && !run.stop.get(); ++round) {

               if (this.estimateRoomTokens(roomId) >= ctxHardStop) {
                  this.db.insertGmMessage(roomId, 0L, "系统", "\ud83d\udccc",
                          "上下文已接近上限（约 " + (ctxBudget / 10000) + " 万 token 预算），为安全起见结束讨论。"
                                  + "若还需继续，请导出方案后另开新群继续。",
                          false, "system", 0);
                  naturalEnd = true;
                  break;
               }

               this.announcePhaseIfNeeded(roomId, round);

               List<AppDatabase.GmMember> members = this.orderMembersForRound(this.db.listGmMembers(roomId));

               if (run.minorityPhase) {
                  Convergence leaveConv = this.computeConvergence(roomId, run);
                  List<AppDatabase.GmMember> narrowed = new ArrayList<>();
                  for (AppDatabase.GmMember m : members) {
                     if (!m.active) { narrowed.add(m); continue; }
                     if (leaveConv.notConfirmed.indexOf(m.name) < 0
                             && !run.pulledMemberIds.contains(m.id)) {
                        continue;
                     }
                     narrowed.add(m);
                  }
                  members = narrowed;
               }
               boolean anySpokeThisRound = false;

               final String pullableNamesThisRound = run.minorityPhase ? this.buildPullableNames(roomId, run) : null;
               boolean[] urgentQuestion = new boolean[]{false};

               int resumeSkipped = 0;

               boolean brokeForQuestion = false;

               int resumeSkipUntil = -1;
               if (round == startRound && resumeAfterMemberId > 0L) {
                  for (int i = 0; i < members.size(); i++) {
                     if (members.get(i).id == resumeAfterMemberId) { resumeSkipUntil = i; break; }
                  }
               }
               for (int mi = 0; mi < members.size(); mi++) {
                  final AppDatabase.GmMember mem = members.get(mi);
                  AiClient.GmSpeakResult res = null;
                  long _t0 = 0L;
                  long speakDurMs = 0L;
                     if (run.stop.get()) {
                        break;
                     }

                     if (round == startRound && resumeAfterMemberId > 0L) {
                        boolean skip = resumeSkipUntil >= 0 ? mi <= resumeSkipUntil : mem.id <= resumeAfterMemberId;
                        if (skip) {
                           ++resumeSkipped;
                           continue;
                        }
                     }

                     AppDatabase.GmMember memNow = this.db.getGmMember(mem.id);
                     if (memNow == null) memNow = mem;

                     int spokenNow = memNow.maxMsgs > 0 ? this.db.countGmMemberSpeeches(roomId, memNow.id) : 0;
                     if (memNow.active && (memNow.maxMsgs <= 0 || spokenNow < memNow.maxMsgs)) {
                        AppDatabase.GmRoom roomNow = this.db.getGmRoom(roomId);
                        if (roomNow != null && roomNow.maxTotalMsgs > 0 && this.db.countGmMessages(roomId) >= roomNow.maxTotalMsgs) {
                           this.db.insertGmMessage(roomId, 0L, "系统", "⚙️", "已达全群消息上限，讨论结束。", false, "system", 0);
                           naturalEnd = true;
                           run.stop.set(true);
                           break;
                        }

                        if (!this.waitWhilePaused(roomId)) {
                           break;
                        }

                        boolean noIntervalNow = roomNow != null && roomNow.noInterval;
                        int minSec = noIntervalNow ? 0
                                : (roomNow == null ? 30 : Math.max(1, roomNow.speakIntervalSec));
                        int maxSec = noIntervalNow ? 0 : (roomNow == null ? 0 : roomNow.speakIntervalMaxSec);
                        int waitTargetSec;
                        if (maxSec > minSec) {
                           waitTargetSec = minSec + RANDOM.nextInt(maxSec - minSec + 1);
                        } else {
                           waitTargetSec = minSec;
                        }
                        long needMs = (long) waitTargetSec * 1000L;
                        long since = System.currentTimeMillis() - run.lastTurnEndAt;
                        if (run.lastTurnEndAt > 0L && since < needMs) {
                           long waitMs = needMs - since;
                           this.ui.post(() -> {
                              if (this.titleText != null && this.currentRoomId == roomId) {
                                 this.titleText.setText("群聊（" + mem.name + " " + waitMs / 1000L + " 秒后发言）");
                              }

                           });
                           long end = System.currentTimeMillis() + waitMs;

                           boolean waitOk = true;
                           while(System.currentTimeMillis() < end && !run.stop.get()) {
                              if (!this.waitWhilePaused(roomId)) { waitOk = false; break; }
                              Thread.sleep(300L);
                           }

                           if (run.stop.get() || !waitOk) {
                              break;
                           }
                        }

                        try { WorkSound.resetTurn(); } catch (Exception ignored) {}

                        int retryCount = 0;
                        boolean skipped = false;

                        int repeatRetryCount = 0;

                        String lastRepeatSample = null;
                        int lastRepeatCount = 0;

                        int takeoverCount = 0;
                        while (true) {
                           AppDatabase.GmMember fetched = this.db.getGmMember(mem.id);
                           if (fetched == null) fetched = mem;
                           if (fetched == null) { skipped = true; break; }

                           final AppDatabase.GmMember cur = fetched;
                           AppDatabase.GmRoom snapRoom = this.db.getGmRoom(roomId);
                           String ctx = this.buildRoomContext(roomId, snapRoom == null ? 100000 : snapRoom.contextLimit, cur.id, round);

                           final long toolMemTok = this.toolDetailTokOf.getOrDefault(cur.id, 0L);

                           final long sharedTok = this.estimateRoomTokens(roomId);
                           final long ownCtxTok = sharedTok
                                   + AiClient.estimateTokensText(cur.prompt)
                                   + toolMemTok;

                           final int sharedTokInt = (int) Math.min((long) Integer.MAX_VALUE, sharedTok);
                           String mName = cur.name;
                           final boolean retrying = retryCount > 0;

                           final AiClient.SpeakLimits speakLimits = this.speakLimitsFor(roomId);

                           if (cur.name != null && cur.name.contains("阈值")) {
                              speakLimits.toolMaxIter = 100;
                           }                           this.ui.post(() -> {
                              if (this.titleText != null && this.currentRoomId == roomId) {
                                 this.titleText.setText("群聊（" + mName + (retrying ? " 重试中…" : " 思考中…"));
                              }
                           });
                           try {
                              _t0 = System.currentTimeMillis();

                              this.roomSpeakMemberId.put(roomId, cur.id);
                              res = AiClient.groupChatSpeak(cur.baseUrl, cur.apiKey, cur.model, cur.name, cur.roleDesc, cur.prompt, ctx, cur.canSearch, snapRoom == null ? 110000 : snapRoom.maxOutputTokens, cur.thinkLevel, run.stop, (toolName, toolArgs) -> this.handleGroupTool(roomId, toolName, toolArgs), new AiClient.StreamListener() {
                                 public void onReasoningDelta(String delta) {
                                    GroupChatManager.this.appendLiveMemberText(roomId, cur.name, cur.avatar, delta, true);
                                 }

                                 public void onContentDelta(String delta) {
                                    GroupChatManager.this.appendLiveMemberContent(roomId, cur.name, cur.avatar, delta);
                                 }

                                 public void onToolStart(String name, String argSummary) {
                                    GroupChatManager.this.appendLiveMemberTool(roomId, cur.name, cur.avatar, "调用 " + name + (argSummary != null && !argSummary.isEmpty() ? "（" + argSummary + "）" : ""), true);
                                 }

                                 public void onToolEnd(String name, String resultSummary) {
                                    GroupChatManager.this.appendLiveMemberTool(roomId, cur.name, cur.avatar, "✅ " + name + " 返回" + (resultSummary != null && !resultSummary.isEmpty() ? "：" + resultSummary : ""), false);
                                 }

                                 public void onRoundDiscarded(String reason) {
                                    GroupChatManager.this.discardLiveMemberRound(roomId, reason);
                                 }
                              }, ctxBudget, sharedTokInt, snapRoom == null ? 0 : snapRoom.langMode, speakLimits, toolMemTok, ownCtxTok,

                                      round,
                                      pullableNamesThisRound, run.minorityPhase,

                                      this.memStoreTokOf.getOrDefault(cur.id, 0L));
                              speakDurMs = System.currentTimeMillis() - _t0;

                              if (res != null && res.repeatAborted) {
                                 lastRepeatSample = res.repeatSample;
                                 lastRepeatCount = res.repeatCount;
                                 this.clearLiveMemberCard(roomId);

                                 this.db.insertGmAnomaly(roomId, round, cur.id, cur.name, "repeat",
                                         "生成退化重复：同一片段重复约 " + res.repeatCount + " 次",
                                         res.repeatSample);
                                 if (repeatRetryCount < speakLimits.repeatRetry) {
                                    ++repeatRetryCount;
                                    android.util.Log.w("VC_GM", "「" + cur.name + "」本轮生成循环（"
                                            + res.repeatCount + " 次 × " + res.repeatSample + "），"
                                            + "丢弃并原参数重跑第 " + repeatRetryCount + "/" + speakLimits.repeatRetry + " 次");
                                    this.db.insertGmAnomaly(roomId, round, cur.id, cur.name, "repeat_retry",
                                            "第 " + repeatRetryCount + "/" + speakLimits.repeatRetry
                                                    + " 次重跑（参数不变）", null);

                                    continue;
                                 }

                                 android.util.Log.w("VC_GM", "「" + cur.name + "」连续 "
                                         + (speakLimits.repeatRetry + 1) + " 次生成循环，转管理员校验");
                                 this.askAdminForRepeatFailure(roomId, cur, round, lastRepeatSample, lastRepeatCount);
                                 skipped = true;
                                 break;
                              }

                              if (res != null && res.toolFailCount > 0) {
                                 this.db.insertGmAnomaly(roomId, round, cur.id, cur.name, "tool_fail",
                                         "本轮有 " + res.toolFailCount + " 次工具调用失败", null);
                                 if (repeatRetryCount < speakLimits.repeatRetry) {
                                    ++repeatRetryCount;
                                    android.util.Log.w("VC_GM", "「" + cur.name + "」本轮有 "
                                            + res.toolFailCount + " 次工具调用失败，丢弃产出重跑第 "
                                            + repeatRetryCount + "/" + speakLimits.repeatRetry + " 次");
                                    this.clearLiveMemberCard(roomId);
                                    continue;
                                 }
                                 android.util.Log.w("VC_GM", "「" + cur.name + "」工具反复失败且重跑用尽，"
                                         + "照常采信本轮产出（不阻塞讨论）");

                              }

                              break;
                           } catch (Exception e) {
                              String err = e.getMessage();
                              this.clearLiveMemberCard(roomId);

                              this.db.insertGmAnomaly(roomId, round, cur.id, cur.name, "api_fail",
                                      "角色调用失败：" + (err == null ? "未知" : err), null);
                              if (run.stop.get()) {
                                 this.db.insertGmMessage(roomId, 0L, "系统", "⚠️", cur.name + " 调用失败，已按「停止」跳过：" + err, false, "system", 0);
                                 this.ui.post(this::renderChat);
                                 skipped = true;
                                 break;
                              }

                              final int cooldownSec = parseCooldownFromError(err);
                              final boolean rateLimited = cooldownSec > 0;
                              final boolean canAutoRetry = rateLimited
                                      ? retryCount < MAX_AUTO_RETRY
                                      : retryCount < MAX_PLAIN_RETRY;
                              final long waitMs = canAutoRetry
                                      ? (rateLimited ? (long) cooldownSec * 1000L + 5000L
                                                     : PLAIN_RETRY_WAIT_MS)
                                      : 0L;

                              if (canAutoRetry) {
                                 this.db.setGmRoomBlockedUntil(roomId, System.currentTimeMillis() + waitMs);
                              }
                              String errShown = stripCooldownMarker(err);

                              String tip;
                              if (rateLimited && canAutoRetry) {
                                 tip = "上游限流：约 " + this.fmtDuration(cooldownSec) + " 后可再请求，"
                                         + "会自动重试（第 " + (retryCount + 1) + "/" + MAX_AUTO_RETRY + " 次）。"
                                         + "想更快可先换服务商（「👥 成员」或批量面板「🔁 统一设置模型」）。";
                              } else if (rateLimited) {
                                 tip = "上游限流：约 " + this.fmtDuration(cooldownSec) + " 后可再请求，自动重试已用完。"
                                         + "已交给管理员判断下一步（他会给你发消息）。";
                              } else if (canAutoRetry) {
                                 tip = "请求没成功（非限流）。约 " + this.fmtDuration((int) (PLAIN_RETRY_WAIT_MS / 1000L))
                                         + "后自动重试（第 " + (retryCount + 1) + "/" + MAX_PLAIN_RETRY + " 次）。"
                                         + "若一直失败，可到「👥 成员」给 " + cur.name + " 换模型/服务商。";
                              } else {
                                 tip = "已自动重试 " + MAX_PLAIN_RETRY + " 次仍未成功，已交给管理员判断下一步"
                                         + "（他会给你发消息）。也可到「👥 成员」给 " + cur.name + " 换模型/服务商。";
                              }
                              final String cardTip = tip;

                              this.db.insertGmMessage(roomId, 0L, "系统", "⚠️",
                                      "⚠️ " + cur.name + " 请求失败：" + err, false, "system", 0);
                              android.util.Log.w("VC_GM", "角色请求失败 room=" + roomId + " role=" + cur.name
                                      + " cooldown=" + cooldownSec + "s rateLimited=" + rateLimited
                                      + " canAutoRetry=" + canAutoRetry + " retry=" + retryCount
                                      + " : " + errShown);

                              this.db.insertGmAnomaly(roomId, round, cur.id, cur.name, "api_fail",
                                      "请求失败" + (rateLimited ? "（限流，冷却 " + cooldownSec + "s）" : "（非限流）")
                                              + "，第 " + (retryCount + 1) + " 次失败"
                                              + (canAutoRetry ? "，将自动重试" : "，自动重试已用完，转管理员"),
                                      errShown);

                              final String failRole = cur.name;
                              final String failWhy = errShown;
                              try {
                                 AppDatabase.GmRoom roomForMsg = this.db.getGmRoom(roomId);
                                 com.linyuan.app.ChatNotifier.notifyGroupFailure(
                                         this.activity.getApplicationContext(), roomId,
                                         roomForMsg == null ? "群聊" : roomForMsg.title,
                                         failRole, failWhy + "\n" + cardTip);
                              } catch (Exception ne) {
                                 android.util.Log.w("VC_GM", "失败通知发送异常: " + ne.getMessage());
                              }

                              this.ui.post(() -> { this.renderChat(); this.refreshStartButton(); });
                              if (!canAutoRetry) {

                                 this.askAdminToHandleFailure(roomId, cur, round, errShown,
                                         rateLimited, cooldownSec, retryCount + 1);

                                 boolean approved = false;
                                 if (takeoverCount < MAX_ADMIN_TAKEOVER) {
                                    ++takeoverCount;

                                    this.db.setGmRoomBlockedUntil(roomId,
                                            System.currentTimeMillis() + ADMIN_TAKEOVER_WAIT_MS);
                                    while (true) {
                                       if (this.isRoomStopped(roomId)) { skipped = true; break; }

                                       if (this.adminApprovedRetry.contains(roomId)) {
                                          this.adminApprovedRetry.remove(roomId);
                                          approved = true;
                                          break;
                                       }
                                       AppDatabase.GmRoom parked = this.db.getGmRoom(roomId);
                                       if (parked == null) { skipped = true; break; }
                                       long left = parked.blockedUntil - System.currentTimeMillis();
                                       if (left <= 0L) {

                                          approved = parked.blockedUntil == 0L;
                                          break;
                                       }
                                       try { Thread.sleep(500L); } catch (InterruptedException ie) {
                                          skipped = true; break;
                                       }
                                    }
                                    this.adminApprovedRetry.remove(roomId);
                                    if (!approved) {
                                       try { this.db.setGmRoomBlockedUntil(roomId, 0L); } catch (Exception ignored) { }
                                    }
                                 } else {
                                    android.util.Log.w("VC_GM", "「" + cur.name + "」本轮已让管理员接管 "
                                            + takeoverCount + " 次，不再重复，跳过该角色");
                                 }

                                 if (skipped) break;
                                 if (approved) {
                                    ++retryCount;
                                    android.util.Log.i("VC_GM", "管理员/用户已批准重试「" + cur.name + "」，重新发起请求");
                                    continue;
                                 }
                                 android.util.Log.w("VC_GM", "「" + cur.name + "」反复失败且管理员未批准重试，跳过该角色");
                                 skipped = true;
                                 break;
                              }
                              if (!this.waitWhilePaused(roomId)) { skipped = true; break; }
                              ++retryCount;

                           }
                        }
                        if (skipped) continue;
                     }

                  if (run.stop.get()) {
                     break;
                  }

                  if (res == null) continue;

                  boolean spoke = res.speech != null && !res.speech.trim().isEmpty();
                  StringBuilder stored = new StringBuilder();

                  stored.append(spoke ? res.speech.trim() : "（本轮不发言）");
                  if (res.thought != null && !res.thought.trim().isEmpty()) {
                     stored.append("\n@@THINKING:").append(res.thought.trim());
                  }

                  if (res.toolLog != null && !res.toolLog.trim().isEmpty()) {
                     stored.append("\n@@TOOLS:").append(res.toolLog.trim());
                  }

                  if (res.finalConfirmed) {
                     stored.append("\n@@CONFIRM:1");
                  }

                  boolean askedNewQuestion = false;
                  if (res.question != null && !res.question.trim().isEmpty()) {
                     List<AppDatabase.GmInquiryItem> existing = this.db.listGmUnansweredQuestions(roomId);
                     if (res.questions != null && !res.questions.isEmpty()) {
                        for (AiClient.GmQuestion gq : res.questions) {
                           if (gq == null || gq.text == null || gq.text.trim().isEmpty()) continue;
                           if (shouldSkipQuestion(existing, gq.text)) continue;
                           existing.add(new AppDatabase.GmInquiryItem(mem.name, gq.text.trim(), gq.options, gq.multi));
                           this.db.addGmPendingQuestion(roomId, mem.id, mem.name, gq.text.trim(), gq.options, gq.multi);
                           askedNewQuestion = true;
                        }
                     } else {

                        if (!shouldSkipQuestion(existing, res.question)) {
                           this.db.addGmPendingQuestion(roomId, mem.id, mem.name, res.question.trim(), res.questionOptions, res.questionMulti);
                           askedNewQuestion = true;
                        }
                     }
                     urgentQuestion[0] = urgentQuestion[0] || res.questionUrgent;
                  }

                  if (res.feedback != null && !res.feedback.isEmpty()) {
                     for (AiClient.GmFeedbackItem fb : res.feedback) {
                        if (fb == null || fb.title == null) continue;
                        this.db.insertGmFeedback(roomId, mem.id, mem.name, mem.avatar,
                                fb.category, fb.title, fb.detail, fb.context);
                     }

                     final int fbCount = res.feedback.size();
                     final String fbFirst = res.feedback.get(0).title;
                     try {
                        AppDatabase.GmRoom roomForFb = this.db.getGmRoom(roomId);
                        com.linyuan.app.ChatNotifier.notifyGroupFailure(
                                this.activity.getApplicationContext(), roomId,
                                roomForFb == null ? "群聊" : roomForFb.title,
                                mem.name + (fbCount > 1 ? " 等 " + fbCount + " 条反馈" : " 反馈"),
                                fbFirst + "\n（在群聊列表点「💬 反馈」查看）");
                     } catch (Exception ne) {
                        android.util.Log.w("VC_GM", "反馈通知发送异常: " + ne.getMessage());
                     }
                  }

                  if (run.minorityPhase && res.pulledExperts != null && !res.pulledExperts.isEmpty()) {
                     List<AppDatabase.GmMember> pool = this.db.listGmMembers(roomId);

                     Convergence pullConv = this.computeConvergence(roomId, run);
                     for (String pn : res.pulledExperts) {
                        for (AppDatabase.GmMember cand : pool) {
                           if (!cand.active || cand.name == null || !cand.name.equals(pn)) continue;
                           if (pullConv.notConfirmed.indexOf(cand.name) >= 0) continue;
                           if (!run.pulledMemberIds.add(cand.id)) continue;
                           String why = res.pulledReasons == null ? null : res.pulledReasons.get(pn);
                           this.db.insertGmMessage(roomId, 0L, "系统", "\ud83d\udd17",
                                   mem.name + " 把「" + cand.name + "」拉回了讨论"
                                           + (why == null || why.trim().isEmpty() ? "。" : "：" + why.trim()),
                                   false, "system", 0);
                        }
                     }
                     this.ui.post(this::renderChat);
                  }

                  if (spoke) {
                     long newId = this.db.insertGmMessage(roomId, mem.id, mem.name, mem.avatar, stored.toString(), false, "chat", res.tokens, speakDurMs, res.contextTokens, res.completionTokens, res.genMs);
                     this.db.addGmMemberUsage(mem.id, 1, (long)res.tokens);
                     anySpokeThisRound = true;

                     if (res.finalConfirmed) {
                        this.db.setGmMemberConfirmedRound(mem.id, round);
                     } else if (mem.confirmedRound > 0) {
                        this.db.setGmMemberConfirmedRound(mem.id, 0);
                     }
                  } else if (res.declaredSilent) {

                     this.db.insertGmMessage(roomId, mem.id, mem.name, mem.avatar, stored.toString(), true, "chat", res.tokens, speakDurMs, res.contextTokens, res.completionTokens, res.genMs);

                     this.db.addGmMemberUsage(mem.id, 1, (long)res.tokens);
                  }

                  this.clearLiveMemberCard(roomId);

                  run.lastTurnEndAt = System.currentTimeMillis();

                  this.db.setGmRoomProgress(roomId, round, mem.id);

                  if (round >= INSTANT_QUESTION_FROM_ROUND && askedNewQuestion) {
                     brokeForQuestion = true;
                     break;
                  }

                  this.ui.post(() -> {
                     this.renderChat();
                     this.refreshRetryButton();
                     this.refreshConfirmButton();
                  });
               }

               if (run.stop.get()) {
                  break;
               }

               boolean onlyResumeSkipped = !anySpokeThisRound && resumeSkipped > 0 && !brokeForQuestion;
               final int effectiveResumeSkipped = resumeSkipped;
               resumeSkipped = 0;
               if (onlyResumeSkipped) {

                  this.db.setGmRoomProgress(roomId, round + 1, 0L);
                  this.db.insertGmMessage(roomId, 0L, "系统", "⚙️",
                          "本轮的 " + effectiveResumeSkipped + " 位角色上次已发言，直接进入第 " + (round + 1) + " 轮。",
                          false, "system", 0);
                  this.ui.post(this::renderChat);
                  continue;
               }

               List<AppDatabase.GmInquiryItem> pend = this.db.listGmPendingQuestions(roomId);
               AppDatabase.GmInquiry openInq = this.db.latestGmInquiry(roomId);
               boolean hasOpenInquiry = openInq != null && "pending".equals(openInq.status);
               if (!pend.isEmpty() || hasOpenInquiry) {
                  final long inqId;
                  final int qCount;
                  if (!pend.isEmpty() && !hasOpenInquiry) {

                     this.db.insertGmMessage(roomId, 0L, "系统", "❓",
                             "有 " + pend.size() + " 个问题需要向你确认，已汇总成询问稿。"
                                     + "（你方便时回答即可；他们不会因为你还没答就停下讨论。）"
                                     + (urgentQuestion[0] ? "（含紧急提问）" : ""),
                             false, "system", 0);
                     inqId = this.db.insertGmInquiry(roomId, "第 " + round + " 轮 · 需要你确认的问题", pend);
                     this.db.markGmPendingQuestionsAsked(roomId);
                     qCount = pend.size();
                  } else {

                     inqId = openInq.id;
                     qCount = openInq.items == null ? 0 : openInq.items.size();
                  }

                  this.ui.post(() -> {
                     this.renderChat();

                     if (this.isVisible() && this.viewRoomId == roomId
                             && !this.activity.isFinishing() && !this.activity.isDestroyed()) {
                        this.showInquiryDraft(inqId);
                     }
                  });
                  this.notifyInquiry(roomId, qCount);

                  run.awaitingInquiry = true;
                  return;
               }

               this.processRoundEndOrderRequests(roomId, round);

               if (!run.minorityPhase && round >= MINORITY_SPLIT_FROM_ROUND) {
                  List<AppDatabase.GmMember> orderedAll = this.orderMembersForRound(this.db.listGmMembers(roomId));
                  List<AppDatabase.GmMember> minority = this.computeMinority(roomId, orderedAll);
                  if (!minority.isEmpty()) {
                     run.minorityPhase = true;
                     run.minorityStartRound = round;
                     run.allSilentRounds = 0;
                     List<String> names = new ArrayList<>();

                     Convergence actConv = this.computeConvergence(roomId, run);
                     int activeTotal = actConv.active;
                     int confirmedCnt = actConv.confirmed;
                     this.db.insertGmMessage(roomId, 0L, "系统", "\ud83e\udd1d",
                             "已有过半角色（" + confirmedCnt + "/" + activeTotal
                                     + "）确认了当前方案，剩下的 " + minority.size() + " 位（"
                                     + this.joinNames(names, 8) + "）按原顺序继续讨论。"
                                     + "已确认的角色先不参与（上下文与结论都还留着）；"
                                     + "他们若觉得需要，可以用 pull_expert 把已确认的人拉回来一起讨论。",
                             false, "system", 0);
                     android.util.Log.i("VC_GM", "少数派续议激活 room=" + roomId + " round=" + round
                             + " minority=" + minority.size() + "/" + orderedAll.size());
                     this.ui.post(this::renderChat);
                  }
               } else if (run.minorityPhase) {

                  List<AppDatabase.GmMember> orderedAll = this.orderMembersForRound(this.db.listGmMembers(roomId));
                  List<AppDatabase.GmMember> minority = this.computeMinority(roomId, orderedAll);
                  if (minority.isEmpty()) {

                     if (this.computeConvergence(roomId, run).done()) {
                        this.db.insertGmMessage(roomId, 0L, "系统", "\u2705",
                                "续议的 " + Math.max(0, round - run.minorityStartRound) + " 轮内，剩余角色也全部确认，讨论完成。"
                                        + "导出用底部「\ud83d\udcc4 导出」。",
                                false, "system", 0);
                        naturalEnd = true;
                        break;
                     }
                  }

               }

               Convergence conv = this.computeConvergence(roomId, run);
               if (conv.done()) {
                  this.db.insertGmMessage(roomId, 0L, "系统", "\u2705",
                          "全员（" + conv.active + " 位）都确认了最终方案，讨论完成。"
                                  + "需要文档就在底部「\ud83d\udcc4 导出」里选一种。",
                          false, "system", 0);
                  naturalEnd = true;
                  break;
               }

               if (conv.doneByDefault()) {
                  this.db.insertGmMessage(roomId, 0L, "系统", "\ud83d\udccc",
                          conv.confirmed + "/" + conv.active + " 位已确认，其余 "
                                  + conv.notConfirmed.size() + " 位（" + this.joinNames(conv.notConfirmed, 6)
                                  + "）连续两轮无新意见，视为默认同意，讨论完成。",
                          false, "system", 0);
                  naturalEnd = true;
                  break;
               }

               if (!anySpokeThisRound) {

                  run.allSilentRounds++;
                  if (run.allSilentRounds >= 2) {
                     this.db.insertGmMessage(roomId, 0L, "系统", "⚙️",
                             "连续两轮所有角色都选择了不发言，讨论结束。"
                                     + (conv.notConfirmed.isEmpty() ? "" : " 未确认：" + this.joinNames(conv.notConfirmed, 8) + "。"),
                             false, "system", 0);
                     naturalEnd = true;
                     break;
                  }
               } else {
                  run.allSilentRounds = 0;
               }

               this.db.insertGmMessage(roomId, 0L, "系统", "⚙️",
                       "第 " + round + " 轮结束 · 已确认 " + conv.confirmed + "/" + conv.active + "。",
                       false, "system", 0);
               this.ui.post(this::renderChat);
            }

            if (!run.stop.get()) {
               naturalEnd = true;
            }
         } catch (Throwable t) {
            String msg = String.valueOf(t.getMessage());
            try {
               this.db.insertGmMessage(roomId, 0L, "系统", "⚠️", "讨论异常结束：" + msg, false, "system", 0);
            } catch (Throwable ignored) {

            }
         } finally {

            if (naturalEnd) {
               try { this.db.clearGmRoomProgress(roomId); } catch (Throwable ignored) { }
            }

            this.roomRuns.remove(roomId);

            if (this.roomRuns.isEmpty()) {
               this.endBgWork("discuss");
            }

            this.ui.post(() -> this.resetRoomBuf(roomId));
            if (this.runningRoomId == roomId) {
               this.running = false;
               this.runningRoomId = 0L;
            }
            try { this.db.setGmRoomBlockedUntil(roomId, 0L); } catch (Throwable ignored) { }

            final boolean wasWatching = this.isVisible() && this.viewRoomId == roomId;
            if (!wasWatching && !run.awaitingInquiry) {
               try {
                  AppDatabase.GmRoom room = this.db.getGmRoom(roomId);
                  if (room != null) {
                     String t = room.title == null ? "群聊" : room.title;

                     try { WorkSound.play(this.activity, WorkSound.EV_DONE, true); } catch (Exception ignored) {}

                     com.linyuan.app.ChatNotifier.playAlertInApp(
                             this.activity.getApplicationContext(), true);
                     com.linyuan.app.ChatNotifier.notifyGroupDone(
                             this.activity.getApplicationContext(), roomId, t, "讨论已结束，回来看结论");
                  }
               } catch (Exception e) {
                  android.util.Log.w("VC_GM", "讨论结束通知失败: " + e.getMessage());
               }
            }
            this.ui.post(() -> {
               try {
                  if (this.activity.isFinishing() || this.activity.isDestroyed()) return;
                  this.renderChat();
                  this.refreshStartButton();
                  AppDatabase.GmRoom r = this.db.getGmRoom(roomId);
                  if (this.titleText != null && r != null && this.currentRoomId == roomId) {
                     this.titleText.setText(r.title == null ? "群聊" : r.title);
                  }
               } catch (Throwable ignored) {

               }
            });
         }
   }

   private boolean waitWhilePaused(long roomId) {
      while (true) {
         if (this.isRoomStopped(roomId)) return false;

         AppDatabase.GmRoom r = this.db.getGmRoom(roomId);
         if (r == null) return false;

         long now = System.currentTimeMillis();
         if (r.blockedUntil <= now) return true;

         try {
            Thread.sleep(400L);
         } catch (InterruptedException e) {
            return false;
         }
      }
   }

   private String buildRoomContext(long roomId, int contextLimit, long forMemberId) {
      return this.buildRoomContext(roomId, contextLimit, forMemberId, 0);
   }

   private String buildRoomContext(long roomId, int contextLimit, long forMemberId, int round) {
      AppDatabase.GmRoom room = this.db.getGmRoom(roomId);
      StringBuilder sb = new StringBuilder();
      if (room != null && room.globalPrompt != null && !room.globalPrompt.trim().isEmpty()) {
         sb.append(room.globalPrompt.trim()).append("\n\n");
      }

      List<AppDatabase.GmMessage> msgs = this.db.listGmMessages(roomId, 1500);

      AppDatabase.GmMember self = forMemberId > 0L ? this.db.getGmMember(forMemberId) : null;
      if (self != null) {

         String anchor = room != null && room.goalText != null && !room.goalText.trim().isEmpty()
                 ? room.goalText.trim() : this.firstUserAskFull(roomId, msgs);
         if (anchor != null && !anchor.isEmpty()) {

            sb.append("【本群讨论目标（始终围绕它，不要跑题）】").append(anchor).append("\n");
         }

         List<AppDatabase.GmMessage> userMsgs = null;
         try { userMsgs = this.db.listGmUserMessages(roomId); } catch (Exception ignored) { }
         if (userMsgs != null && !userMsgs.isEmpty()) {
            sb.append("【用户的需求与回答（全部，按时间；这是最高优先级的事实依据）】\n");
            for (AppDatabase.GmMessage um : userMsgs) {
               String s = AiClient.stripMarkersPublic(um.content).trim();
               if (s.isEmpty()) continue;
               sb.append("- (#").append(um.seq).append(") ").append(s).append("\n");
            }
         }

         List<AppDatabase.GmBasicInfo> injected = null;
         try { injected = this.db.listInjectedBasicInfo(roomId); } catch (Exception ignored) { }
         if (injected != null && !injected.isEmpty()) {

            sb.append("【用户基础信息（系统公告 · 事实依据）】\n");
            for (AppDatabase.GmBasicInfo b : injected) {

               String bc = b.content == null ? "" : b.content.trim();
               if (bc.isEmpty()) continue;
               sb.append("- ").append(bc).append("\n");
            }
         }

         try {
            int totalPairs = this.db.countGmQaArchive();
            if (totalPairs > 0) {
               int totalRooms = this.db.countGmQaArchiveRooms();
               sb.append("【用户信息库】现有 ").append(totalRooms).append(" 个需求、")
                 .append(totalPairs).append(" 组「用户回答过的」问答，"
                         + "来自他以往各群聊的历史提问。\n")

                 .append("（那些回答是**过去某个时间点**的记录，只能作参考、不是当前事实；"
                         + "情况会变。用 search_user_archive 列需求、"
                         + "fetch_user_archive 取某个需求下的问答。）\n");
            }
         } catch (Exception ignored) { }

         try {
            long[] st = this.db.poolStats(roomId);
            if (st[0] > 0) {
               sb.append("【共享资料池】全群已有 ").append(st[0]).append(" 份资料被移出各自上下文"
                       + "（换下场/压缩掉的都在这里，合计约 ").append(st[1]).append(" token）。\n")
                 .append("（用 search_material_pool 搜、fetch_material_pool 按需分段取回。）\n");
            }
         } catch (Exception ignored) { }

         sb.append(this.buildQuestionBoard(roomId, false));

         List<AppDatabase.GmMember> orderedForBoard = this.orderMembersForRound(this.db.listGmMembers(roomId));
         long boardRoundStartId = 0L;
         java.util.Set<Long> boardSpoke = java.util.Collections.emptySet();
         java.util.Set<Long> boardPassed = java.util.Collections.emptySet();

         RoomRun boardRun = this.roomRuns.get(roomId);
         boolean boardMinority = boardRun != null && boardRun.minorityPhase;
         java.util.Set<Long> boardLeft = new java.util.HashSet<>();
         try {
            boardRoundStartId = this.db.lastGmRoundBoundaryId(roomId);
            boardSpoke = this.db.gmRoundMemberIds(roomId, boardRoundStartId, false);
            boardPassed = this.db.gmRoundMemberIds(roomId, boardRoundStartId, true);
            if (boardMinority) {
               Convergence bc = this.computeConvergence(roomId, boardRun);
               for (AppDatabase.GmMember mm : orderedForBoard) {
                  if (mm.active && bc.notConfirmed.indexOf(mm.name) < 0
                          && !boardRun.pulledMemberIds.contains(mm.id)) {
                     boardLeft.add(mm.id);
                  }
               }
            }
         } catch (Exception ignored) { }
         if (!orderedForBoard.isEmpty()) {

            int selfPos = -1;
            int counted = 0;
            for (AppDatabase.GmMember mm : orderedForBoard) {
               if (!mm.active) continue;
               if (boardLeft.contains(mm.id)) continue;
               ++counted;
               if (mm.id == self.id) { selfPos = counted; break; }
            }
            int boardPos = 0;
            List<String> boardSilent = new ArrayList<>();
            StringBuilder board = new StringBuilder();
            for (AppDatabase.GmMember mm : orderedForBoard) {
               if (!mm.active) continue;

               if (boardLeft.contains(mm.id)) continue;
               ++boardPos;
               board.append(boardPos).append(". ").append(mm.avatar == null ? "" : mm.avatar).append(mm.name);
               if (mm.roleDesc != null && !mm.roleDesc.trim().isEmpty()) {
                  board.append("(").append(mm.roleDesc.trim()).append(")");
               }
               if (mm.id == self.id) board.append(" ←就是你");
               String boardState;
               if (boardPassed.contains(mm.id)) {
                  boardState = "本轮不发言";
                  boardSilent.add(mm.name);
               } else if (boardSpoke.contains(mm.id)) {
                  boardState = "已发言";
               } else if (mm.id == self.id) {

                  boardState = "现在轮到你";
               } else if (selfPos > 0 && boardPos < selfPos) {
                  boardState = "本轮未发言";
               } else {
                  boardState = "还没轮到";
               }
               board.append(" —— ").append(boardState).append("\n");
            }
            sb.append("【在场角色 · 本轮发言顺序】\n").append(board);
            if (selfPos > 0 && selfPos < boardPos) {
               sb.append("（你排第 ").append(selfPos).append(" 位；你后面还有 ")
                       .append(boardPos - selfPos).append(" 位**还没轮到**——"
                               + "他们不是没发言，只是次序在你之后。）\n");
            }
            if (!boardSilent.isEmpty()) {
               sb.append("（本轮已选择不发言：").append(this.joinNames(boardSilent, 12)).append("）\n");
            }
            sb.append("（发言前先看别人负责什么，只补充你专业范围内、别人还没说到的点；不要重复、不要越俎代庖。"
                    + "序号就是本轮的发言次序——排在你后面的人**还没轮到**，不是没发言；"
                    + "要请谁补充，在你的正式发言里直接说，他下一轮会看到。）\n");
            if (boardMinority && !boardLeft.isEmpty()) {
               sb.append("（另有 ").append(boardLeft.size())
                       .append(" 位已确认的角色本轮不参与——他们的结论在下面的讨论记录里，"
                               + "需要谁回来补充可以用 pull_expert。）\n");
            }
         }

         if (round > 0) {
            int budgetAll = room == null || room.contextLimit <= 0 ? 1000000 : room.contextLimit;
            int usedPct = (int) Math.min(100L, this.estimateRoomTokens(roomId) * 100L / Math.max(1, budgetAll));
            Convergence conv = this.computeConvergence(roomId, this.roomRuns.get(roomId));
            int ph = conv.phase(usedPct);

            sb.append("【当前进度】第 ").append(round).append(" 轮 · 阶段：").append(Convergence.phaseName(ph))
                    .append(" · 已确认 ").append(conv.confirmed).append(" 人\n");
            sb.append("  ").append(Convergence.phaseHint(ph)).append("\n");
            if (!conv.notConfirmed.isEmpty()) {
               sb.append("  还没确认：").append(this.joinNames(conv.notConfirmed, 12)).append("\n");
            }
            boolean selfConfirmed = conv.notConfirmed.indexOf(self.name) < 0;

            sb.append("  你自己：").append(selfConfirmed ? "已确认" : "还没确认")
                    .append(selfConfirmed ? ("（第 " + self.confirmedRound + " 轮确认的）") : "")
                    .append("\n");
            sb.append("  （验收状态由系统登记：你调 confirm_convergence 就记为已确认；"
                    + "之后若再发实质发言，会自动变回未确认——那表示你还有要改的地方，"
                    + "改完了再确认一次即可。只沉默、不发言不会改变这个状态。）\n");

            if (selfConfirmed) {
               RoomRun selfRun = this.roomRuns.get(roomId);
               boolean pulledBack = selfRun != null && selfRun.minorityPhase
                       && selfRun.pulledMemberIds.contains(self.id);
               sb.append("  （已确认是**当前状态**、不是封稿：");
               if (pulledBack) {
                  sb.append("你是被同伴用 pull_expert 拉回讨论的——");
               }
               sb.append("后面的讨论如果动到你负责的部分，你照样可以发言修改或补充；"
                       + "发言会被视为收回确认，之后再确认一次即可。）\n");
            }

         }

         boolean memDropRaw = false;
         String memContent = "";
         long memStoreTok = 0L;
         try {
            AppDatabase.GmAgentMemory m = this.db.getGmAgentMemory(roomId, self.id);
            if (m != null) {
               memContent = m.content;
               memDropRaw = m.dropRaw;
               memStoreTok = AiClient.estimateTokensText(m.content);
            }
         } catch (Exception ignored) { }

         this.memStoreTokOf.put(self.id, memStoreTok);

         String selfTools = this.selfToolsOnly(roomId, self.name, self.id, memDropRaw, memStoreTok);
         if (!selfTools.isEmpty()) {

            sb.append(memDropRaw
                    ? "【你之前调用过的工具（记录；资料正文已按你的压缩省掉，"
                      + "需要时用 search_material_pool / fetch_material_pool 从共享池取回）】\n"
                    : "【你之前调用的工具与查到的资料（从早到晚；别人看不到这些，需要的事实请写进发言）】\n")
              .append(selfTools);
         }

         if (memContent != null && !memContent.trim().isEmpty()) {
            sb.append("【你自己整理的资料结论（你压缩后存下来的）】\n")
              .append(memContent.trim()).append("\n");
            if (memDropRaw) {

               sb.append("（原始长文已按你的压缩省掉；需要细节时重新搜索/抓取即可取回。）\n");
            }
         }
         sb.append("\n───── 以下是本群全过程记录（所有人的发言，含你自己的）─────\n");
      }

      int budget = Math.max(2000, contextLimit);

      int headerCost = AiClient.estimateTokensText(sb.toString());

      int discussBudget = Math.max(2000, budget - headerCost);
      int used = 0;
      List<String> lines = new ArrayList();

      boolean anySpeech = false;

      for(int i = msgs.size() - 1; i >= 0; --i) {
         AppDatabase.GmMessage m = (AppDatabase.GmMessage)msgs.get(i);
         if (!"system".equals(m.kind)) {
            String shown = AiClient.stripMarkersPublic(m.content).trim();
            if (!shown.isEmpty() && !"（本轮不发言）".equals(shown)) {
               anySpeech = true;

            String line = "#" + m.seq + " [" + (m.speaker == null ? "成员" : m.speaker) + "] " + shown;

               int cost = AiClient.estimateTokensText(line);
               if (used + cost > discussBudget) {

                  if (cost >= discussBudget / 4) {
                     continue;
                  }
                  break;
               }

               used += cost;
               lines.add(0, line);
            }
         }
      }

      for(String l : lines) {
         sb.append(l).append("\n");
      }

      if (lines.isEmpty()) {

         sb.append(anySpeech
                 ? "（本群上下文预算已被占满，更早的发言本轮无法载入。如需引用早期结论，"
                   + "请基于你能看到的最新记录讨论，或请用户查看完整讨论记录。）"
                 : "（群里还没有人发言，你是第一个。）");
      }

      return sb.toString();
   }

   private String buildQuestionBoard(long roomId, boolean forTool) {
      List<AppDatabase.GmInquiryItem> unanswered = null;
      try { unanswered = this.db.listGmUnansweredQuestions(roomId); } catch (Exception ignored) { }
      List<AppDatabase.GmInquiryItem> answeredList = new ArrayList<>();
      try {
         for (AppDatabase.GmInquiry inq : this.db.listGmInquiries(roomId)) {
            if (inq == null || inq.items == null) continue;
            for (AppDatabase.GmInquiryItem it : inq.items) {
               if (it == null || it.question == null || it.question.trim().isEmpty()) continue;
               if (it.answer == null || it.answer.trim().isEmpty()) continue;
               answeredList.add(it);
            }
         }
      } catch (Exception ignored) { }
      int pendingCount = unanswered == null ? 0 : unanswered.size();
      StringBuilder bb = new StringBuilder();
      bb.append("【提问公告栏（官方，唯一权威）｜待答 ").append(pendingCount)
        .append(" 条，已回答 ").append(answeredList.size()).append(" 条】\n");

      bb.append("（**只有本栏列出的问题才是真实存在的提问**——它们是各角色调用 ask_user 工具"
              + "正式提交、系统登记在案的，这就是'官方认证'。\n"
              + "  某个角色在发言正文里说「我要提一个问题」「我提了一题」「这个要用户拍板」"
              + "但没真的调工具，**那不算提问**，不会出现在本栏——"
              + "不要为它等待、不要引它当理由推迟你的结论与确认。\n"
              + "  拿不准就用 read_questions 工具随时核对，本栏与它一致。）\n");
      if (pendingCount > 0) {
         bb.append("待答：\n");
         for (AppDatabase.GmInquiryItem q : unanswered) {
            bb.append("  - ").append(q.asker == null ? "成员" : q.asker).append(" 问：")
              .append(q.question == null ? "" : q.question.trim()).append("\n");
         }
         bb.append("（这些已正式提交、等用户作答；用户答完答案会进入你们上下文。**别人问过的不要重复问**。）\n");
      } else {
         bb.append("待答：（无）——所有已正式提交的提问都已得到用户回答，没有悬空的问题。\n");
      }
      if (!answeredList.isEmpty()) {
         bb.append("已回答（这些题已经拿到用户答复，不要再重复问）：\n");
         for (AppDatabase.GmInquiryItem it : answeredList) {
            bb.append("  - ✔ ").append(it.asker == null ? "成员" : it.asker).append(" 问：")
              .append(it.question == null ? "" : it.question.trim()).append("\n");
         }
         bb.append("（上列各题的**用户回答原文**见上方【用户的需求与回答】；判断方案走向时以那些回答为准。）\n");
      }
      return bb.toString();
   }

   private String selfToolsOnly(long roomId, String name, long stashMemberId) {
      return this.selfToolsOnly(roomId, name, stashMemberId, false);
   }

   private String selfToolsOnly(long roomId, String name, long stashMemberId, boolean onlyCalls) {
      return this.selfToolsOnly(roomId, name, stashMemberId, onlyCalls, 0L);
   }

   private String selfToolsOnly(long roomId, String name, long stashMemberId, boolean onlyCalls,
                                long digestTok) {
      if (name == null) return "";
      List<AppDatabase.GmMessage> own = null;
      try { own = this.db.listGmMessagesBySpeaker(roomId, name); } catch (Exception ignored) { }
      if (own == null) {
         if (stashMemberId > 0L) this.toolDetailTokOf.put(stashMemberId, 0L);
         return "";
      }
      StringBuilder calls = new StringBuilder();
      StringBuilder details = new StringBuilder();
      for (AppDatabase.GmMessage m : own) {
         if ("system".equals(m.kind) || m.content == null) continue;
         int oi = m.content.indexOf("@@TOOLS:");
         if (oi < 0) continue;
         String tools = m.content.substring(oi + "@@TOOLS:".length());

         tools = AiClient.stripFinalMarkers(tools).trim();
         int lateTi = tools.indexOf("@@THINKING:");
         if (lateTi >= 0) tools = tools.substring(0, lateTi).trim();
         if (tools.isEmpty()) continue;
         for (String raw : tools.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("└") || line.startsWith("   └")) {

               details.append("- (#").append(m.seq).append(") ").append(line).append("\n");
            } else {

               calls.append("- (#").append(m.seq).append(") ").append(line).append("\n");
            }
         }
      }
      if (calls.length() == 0 && details.length() == 0) {
         if (stashMemberId > 0L) this.toolDetailTokOf.put(stashMemberId, 0L);
         return "";
      }
      StringBuilder out = new StringBuilder(calls);

      long retainedTok = 0L;
      if (details.length() > 0 && onlyCalls) {

         if (stashMemberId > 0L) this.toolDetailTokOf.put(stashMemberId, 0L);
         return out.toString();
      }
      if (details.length() > 0) {

         long CAP = AiClient.TOOLS_DETAIL_TOKEN_CAP - Math.max(0L, digestTok);

         if (CAP <= 0L) {
            if (stashMemberId > 0L) this.toolDetailTokOf.put(stashMemberId, 0L);
            return out.toString();
         }
         StringBuilder kept = new StringBuilder();
         int keptLines = 0;
         int droppedLines = 0;
         boolean full = false;
         for (String ln : details.toString().split("\n")) {
            if (ln.isEmpty()) continue;
            int cost = AiClient.estimateTokensText(ln);
            if (!full && kept.length() == 0 && cost > CAP) {

               int keepChars = (int) (CAP / 2);
               String head = ln.length() > keepChars ? ln.substring(0, keepChars) : ln;
               kept.append(head).append("…（这条资料单条超预算，已截断）\n");
               retainedTok = AiClient.estimateTokensText(kept.toString());
               ++keptLines;
               full = true;
               ++droppedLines;
               continue;
            }
            if (!full && retainedTok + cost <= CAP) {
               kept.append(ln).append("\n");
               retainedTok += cost;
               ++keptLines;
            } else {
               full = true;
               ++droppedLines;

               try {
                  this.db.insertGmPoolItem(roomId, name, "预算超限移出", ln);
               } catch (Exception ignored) { }
            }
         }
         out.append(kept);
         if (droppedLines > 0) {
            out.append("…（你自己查到的资料正文累计超过 ").append(CAP / 10000)
               .append(" 万 token 预算，此处保留最早的 ").append(keptLines)
               .append(" 条 ≈ ").append(retainedTok).append(" token；后面的 ").append(droppedLines)
               .append(" 条已**移入共享资料池**（不是丢弃）——"
                       + "需要时用 search_material_pool 搜、fetch_material_pool 按需取回。"
                       + "上面每条调用记录都完整保留，也可按记录里的链接重新抓取。）\n");
         }
      }
      if (stashMemberId > 0L) this.toolDetailTokOf.put(stashMemberId, retainedTok);
      return out.toString();
   }

   private final java.util.concurrent.ConcurrentHashMap<Long, Long> toolDetailTokOf =
           new java.util.concurrent.ConcurrentHashMap<>();

   private final java.util.concurrent.ConcurrentHashMap<Long, Long> memStoreTokOf =
           new java.util.concurrent.ConcurrentHashMap<>();

   private String firstUserAsk(List<AppDatabase.GmMessage> msgs) {
      if (msgs == null) return "";
      for (AppDatabase.GmMessage m : msgs) {
         if ("我".equals(m.speaker)) {
            String s = AiClient.stripMarkersPublic(m.content).trim();
            if (!s.isEmpty()) return s;
         }
      }
      return "";
   }

   private String firstUserAskFull(long roomId, List<AppDatabase.GmMessage> msgs) {
      try {
         AppDatabase.GmMessage first = this.db.getFirstGmUserMessage(roomId);
         if (first != null) {
            String s = AiClient.stripMarkersPublic(first.content).trim();
            if (!s.isEmpty()) return s;
         }
      } catch (Exception ignored) { }
      return this.firstUserAsk(msgs);
   }

   private void appendVisionRetryCard(long roomId) {
      try {
         final AppDatabase.GmMessage target = this.findPendingVisionMessage(roomId);
         if (target == null) return;

         int imgCount = 0;
         for (String line : (target.content == null ? "" : target.content).split("\n")) {
            if (line.trim().startsWith("@@IMG:")) ++imgCount;
         }
         if (imgCount == 0) return;

         LinearLayout card = new LinearLayout(this.activity);
         card.setOrientation(1);
         GradientDrawable bg = new GradientDrawable();
         bg.setColor(0x14FF9800);
         bg.setCornerRadius((float) this.dp(14));
         bg.setStroke(this.dp(1), 0xFFFF9800);
         card.setBackground(bg);
         card.setPadding(this.dp(14), this.dp(12), this.dp(14), this.dp(8));

         TextView title = new TextView(this.activity);
         title.setText("\ud83d\udcf7 有 " + imgCount + " 张图片没识别成功");
         title.setTextColor(0xFFFF9800);
         title.setTextSize(13.0F);
         title.setTypeface((Typeface) null, 1);
         card.addView(title);

         TextView body = new TextView(this.activity);
          body.setText("图片内容还没能交给群里的专家（识别模型这次请求失败了）。");
         body.setTextColor(this.col(color.text2));
         body.setTextSize(11.5F);
         body.setLineSpacing((float) this.dp(2), 1.0F);
         body.setPadding(0, this.dp(6), 0, 0);
         card.addView(body);

         Button retry = this.primaryButton("\ud83d\udd04 重新识别", this.col(color.onAccent));
         retry.setTextSize(12.0F);
         retry.setPadding(this.dp(14), 0, this.dp(14), 0);
         retry.setMinWidth(0);
         retry.setMinimumWidth(0);
         retry.setMinHeight(0);
         retry.setMinimumHeight(0);
         LinearLayout row = new LinearLayout(this.activity);
         row.setOrientation(0);
         row.setGravity(8388613);
         LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, this.dp(32));
         rlp.setMargins(0, this.dp(10), 0, 0);
         row.addView(retry, rlp);
         card.addView(row);

         retry.setOnClickListener((v) -> this.retryVisionForMessage(roomId, target.id));
         this.chatList.addView(card);
      } catch (Exception ignored) { }
   }

   private AppDatabase.GmMessage findPendingVisionMessage(long roomId) {
      List<AppDatabase.GmMessage> msgs = this.db.listGmMessages(roomId, 100);
      for (int i = msgs.size() - 1; i >= 0; --i) {
         AppDatabase.GmMessage m = msgs.get(i);
         if (!"我".equals(m.speaker)) continue;
         String c = m.content == null ? "" : m.content;
         if (c.contains("@@IMG:") && !c.contains("图片识别模型识别出的内容")) return m;
      }
      return null;
   }

   private void retryVisionForMessage(long roomId, long msgId) {
      this.showToast("正在重新识别图片…");
      this.bgExecute(this.executor, () -> {

         this.beginBgWork("vision");
         try {
            AppDatabase.GmMessage m = null;
            for (AppDatabase.GmMessage x : this.db.listGmMessages(roomId, 200)) {
               if (x.id == msgId) { m = x; break; }
            }
            if (m == null) return;

            String updated = this.describeGroupImages(m.content == null ? "" : m.content);
            final int stillFailed = this.lastImageVisionFailed;
            if (stillFailed == 0) {
               this.db.updateGmMessageContent(msgId, updated);
            }
            this.ui.post(() -> {
               this.renderChat();
               this.showToast(stillFailed == 0 ? "图片识别完成，内容已交给群里讨论" : "识别还是失败，可以过会儿再试");
            });
         } catch (Exception e) {
            this.ui.post(() -> this.showToast("重新识别失败：" + e.getMessage()));
         } finally {
            this.endBgWork("discuss");
         }
      });
   }

   private void appendFailureCard(long roomId) {
      try {
         final String failedName = this.pendingFailureRoleName(roomId);
         if (failedName == null) return;

         String reason = "";
         try {
            List<AppDatabase.GmMessage> msgs = this.db.listGmMessages(roomId, 200);
            for (int i = msgs.size() - 1; i >= 0; --i) {
               AppDatabase.GmMessage m = msgs.get(i);
               if (!"system".equals(m.kind == null ? "" : m.kind)) break;
               String c = m.content == null ? "" : m.content;
               if (c.contains("请求失败")) { reason = c; break; }
            }
         } catch (Exception ignored) { }

         int cdSec = AiClient.parseCooldownSeconds(reason);

         reason = this.stripCooldownMarker(reason);
         reason = reason.replaceFirst("^\\s*(?:\u26a0\ufe0f|\u26a0|\\s)+", "").trim();
         String prefix = failedName + " 请求失败：";
         if (reason.startsWith(prefix)) reason = reason.substring(prefix.length()).trim();
         String prefix2 = failedName + " 请求失败:";
         if (reason.startsWith(prefix2)) reason = reason.substring(prefix2.length()).trim();
         if (reason.length() > 300) reason = reason.substring(0, 300) + "…";

         LinearLayout card = new LinearLayout(this.activity);
         card.setOrientation(1);
         GradientDrawable bg = new GradientDrawable();
         bg.setColor(0x14FF5252);
         bg.setCornerRadius((float) this.dp(14));
         bg.setStroke(this.dp(1), this.col(color.danger));
         card.setBackground(bg);
         card.setPadding(this.dp(14), this.dp(12), this.dp(14), this.dp(8));

         TextView title = new TextView(this.activity);
         title.setText("\u26a0\ufe0f " + failedName + " 请求失败");
         title.setTextColor(this.col(color.danger));
         title.setTextSize(13.0F);
         title.setTypeface((Typeface) null, 1);
         card.addView(title);

         TextView body = new TextView(this.activity);
         body.setText(reason.isEmpty() ? "模型请求没有成功，讨论停在这里。" : reason);
         body.setTextColor(this.col(color.text2));
         body.setTextSize(11.5F);
         body.setLineSpacing((float) this.dp(2), 1.0F);
         body.setPadding(0, this.dp(6), 0, 0);
         card.addView(body);

         TextView hint = new TextView(this.activity);

         String hintText = "点右下角「重试」从 " + failedName + " 接着继续；成功前这张卡片会一直留着。";
         if (cdSec > 0) {
            hintText = "上游限流，约 " + this.fmtDuration(cdSec)
                    + " 后可再试。点右下角「重试」从 " + failedName + " 接着继续；成功前这张卡片会一直留着。";
         }
         hint.setText(hintText);
         hint.setTextColor(this.col(color.muted));
         hint.setTextSize(10.5F);
         hint.setPadding(0, this.dp(6), 0, 0);
         card.addView(hint);

         Button retry = this.primaryButton("\ud83d\udd04 重试", this.col(color.onAccent));
         retry.setTextSize(12.0F);
         retry.setPadding(this.dp(14), 0, this.dp(14), 0);
         retry.setMinWidth(0);
         retry.setMinimumWidth(0);
         retry.setMinHeight(0);
         retry.setMinimumHeight(0);
         LinearLayout row = new LinearLayout(this.activity);
         row.setOrientation(0);
         row.setGravity(8388613);
         LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, this.dp(32));
         rlp.setMargins(0, this.dp(10), 0, 0);
         row.addView(retry, rlp);
         card.addView(row);

         retry.setOnClickListener((v) -> this.onRetryButtonClick(roomId));

         LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
         clp.setMargins(0, this.dp(10), 0, this.dp(6));
         card.setLayoutParams(clp);
         this.chatList.addView(card);
      } catch (Exception e) {
         android.util.Log.w("VC_GM", "失败卡片渲染失败: " + e.getMessage());
      }
   }

   private void stopDiscussionIfRunning() {
      this.stopDiscussion(this.currentRoomId);
   }

   private void stopDiscussion(long roomId) {
      RoomRun r = this.roomRuns.get(roomId);
      if (r != null) r.stop.set(true);
      if (this.runningRoomId == roomId) this.running = false;
   }

   public void shutdown() {
      this.shutDown = true;
      try {
         for (Long roomId : this.roomRuns.keySet()) {
            RoomRun r = this.roomRuns.get(roomId);
            if (r != null) r.stop.set(true);
         }
         this.roomRuns.clear();
      } catch (Exception ignored) { }
      this.pendingStartTries.clear();
      this.running = false;
      this.runningRoomId = 0L;
      try { this.releaseDiscussWakeLock(); } catch (Exception ignored) { }

      try { this.ui.removeCallbacksAndMessages(null); } catch (Exception ignored) { }
      try { this.executor.shutdownNow(); } catch (Exception ignored) { }
      try { this.discussExecutor.shutdownNow(); } catch (Exception ignored) { }
   }

   private String discussPoolStat() {
      try {
         if (this.discussExecutor instanceof java.util.concurrent.ThreadPoolExecutor) {
            java.util.concurrent.ThreadPoolExecutor t = (java.util.concurrent.ThreadPoolExecutor) this.discussExecutor;
            return "讨论池 active=" + t.getActiveCount() + "/" + t.getMaximumPoolSize()
                    + " queued=" + t.getQueue().size()
                    + " | 后台池 active=" + ((java.util.concurrent.ThreadPoolExecutor) this.executor).getActiveCount()
                    + "/" + ((java.util.concurrent.ThreadPoolExecutor) this.executor).getMaximumPoolSize()
                    + " | 在跑房间=" + this.roomRuns.size();
         }
      } catch (Exception ignored) { }
      return "讨论池状态不可用";
   }

   private void showRoleLibraryPicker() {
      List<AppDatabase.GmMember> existing = this.db.listGmMembers(this.currentRoomId);
      Set<String> inRoom = new HashSet();

      for(AppDatabase.GmMember m : existing) {
         inRoom.add(m.name);
      }

      List<String[]> candidates = new ArrayList();

      for(String[] e : DEFAULT_EXPERTS) {
         candidates.add(new String[]{e[0], e[1], e[2], e[3], e[4], "专家"});
      }

      for(String[] p : PersonaLibrary.CELEBRITY_PERSONAS) {
         candidates.add(new String[]{p[0], p[1], p[2], this.buildCelebrityPrompt(p), p[4], "名人思维"});
      }

      for(String[] r : DevRoleLibrary.DEV_ROLES) {
         if (r == null || r.length < 5) continue;
         candidates.add(new String[]{r[0], r[1], r[2], this.readAssetPrompt(r[3], r[2]), r[4], "开发角色"});
      }

      Set<Integer> checked = new HashSet();
      final String[] keyword = new String[]{""};
      LinearLayout box = new LinearLayout(this.activity);
      box.setOrientation(1);
      GradientDrawable bg = new GradientDrawable();
      bg.setColor(this.col(color.surface));
      bg.setCornerRadius((float)this.dp(20));
      bg.setStroke(this.dp(1), this.col(color.border));
      box.setBackground(bg);
      box.setPadding(this.dp(16), this.dp(14), this.dp(16), this.dp(12));
      TextView h = new TextView(this.activity);
      h.setText("从角色库添加");
      h.setTextColor(this.col(color.text));
      h.setTextSize(16.0F);
      h.setTypeface((Typeface)null, 1);
      box.addView(h);
      EditText searchEt = new EditText(this.activity);
      searchEt.setHint("\ud83d\udd0d 搜索角色名（如 小红书 / 马斯克）");
      searchEt.setBackgroundResource(drawable.bg_edit);
      searchEt.setPadding(this.dp(12), this.dp(10), this.dp(12), this.dp(10));
      searchEt.setTextColor(this.col(color.text));
      searchEt.setHintTextColor(this.col(color.muted));
      searchEt.setTextSize(13.0F);
      LinearLayout.LayoutParams selp = new LinearLayout.LayoutParams(-1, -2);
      selp.setMargins(0, this.dp(10), 0, 0);
      box.addView(searchEt, selp);
      LinearLayout tools = new LinearLayout(this.activity);
      tools.setGravity(8388613);
      LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
      tlp.setMargins(0, this.dp(8), 0, 0);
      tools.setLayoutParams(tlp);
      Button clearSearch = this.ghostButton("清搜索");
      Button selectAll = this.ghostButton("全选可添加");
      Button clearAll = this.ghostButton("清空选择");
      tools.addView(clearSearch);
      LinearLayout.LayoutParams csLp = new LinearLayout.LayoutParams(-2, -2);
      csLp.setMarginStart(this.dp(8));
      tools.addView(selectAll, csLp);
      LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(-2, -2);
      clp2.setMarginStart(this.dp(8));
      tools.addView(clearAll, clp2);
      box.addView(tools);
      ScrollView sv = new ScrollView(this.activity);
      LinearLayout list = new LinearLayout(this.activity);
      list.setOrientation(1);
      sv.addView(list);
      LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 0, 1.0F);
      slp.setMargins(0, this.dp(8), 0, 0);
      box.addView(sv, slp);
      Dialog dlg = new Dialog(this.activity);
      final Runnable[] render = new Runnable[1];
      render[0] = () -> {
         list.removeAllViews();
         String kw = keyword[0].trim();
         int shown = 0;

         for(int i = 0; i < candidates.size(); ++i) {
            String[] c = (String[])candidates.get(i);
            boolean already = inRoom.contains(c[0]);
            if (kw.isEmpty() || c[0].contains(kw)) {
               ++shown;
               LinearLayout row = new LinearLayout(this.activity);
               row.setOrientation(1);
               boolean isChecked = checked.contains(i);
               GradientDrawable rowBg = new GradientDrawable();
               rowBg.setColor(already ? this.col(color.surfaceInput) : (isChecked ? this.col(color.accentSoft) : 0));
               rowBg.setCornerRadius((float)this.dp(12));
               rowBg.setStroke(this.dp(1), isChecked ? this.col(color.accent) : this.col(color.border));
               row.setBackground(rowBg);
               row.setPadding(this.dp(12), this.dp(10), this.dp(12), this.dp(10));
               TextView t = new TextView(this.activity);
               t.setText(c[1] + " " + c[0] + "　" + c[5]);
               t.setTextColor(already ? this.col(color.muted) : this.col(color.text));
               t.setTextSize(14.0F);
               t.setTypeface((Typeface)null, 1);
               row.addView(t);
               TextView sub = new TextView(this.activity);

               sub.setText(c[2] + "　\ud83d\udd0d可联网");
               sub.setTextColor(this.col(color.muted));
               sub.setTextSize(11.0F);
               sub.setLineSpacing((float)this.dp(2), 1.0F);
               sub.setPadding(0, this.dp(3), 0, 0);
               row.addView(sub);
               if (already) {
                  TextView tag = new TextView(this.activity);
                  tag.setText("✓ 已在本群");
                  tag.setTextColor(this.col(color.accent));
                  tag.setTextSize(11.0F);
                  tag.setPadding(0, this.dp(5), 0, 0);
                  row.addView(tag);
               } else {
                  final int idx = i;
                  row.setOnClickListener((v) -> {
                     if (checked.contains(idx)) {
                        checked.remove(idx);
                     } else {
                        checked.add(idx);
                     }

                     render[0].run();
                  });
               }

               LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
               rlp.setMargins(0, 0, 0, this.dp(8));
               list.addView(row, rlp);
            }
         }

         if (shown == 0) {
            TextView empty = new TextView(this.activity);
            empty.setText("没有匹配的角色");
            empty.setTextColor(this.col(color.muted));
            empty.setTextSize(12.0F);
            list.addView(empty);
         }

      };
      render[0].run();
      searchEt.addTextChangedListener(new TextWatcher() {
         public void beforeTextChanged(CharSequence s, int a, int b, int c) {
         }

         public void onTextChanged(CharSequence s, int a, int b, int c) {
         }

         public void afterTextChanged(Editable e) {
            keyword[0] = e.toString();
            render[0].run();
         }
      });
      selectAll.setOnClickListener((v) -> {
         String kw = keyword[0].trim();

         for(int i = 0; i < candidates.size(); ++i) {
            String[] c = (String[])candidates.get(i);
            if (!inRoom.contains(c[0]) && (kw.isEmpty() || c[0].contains(kw))) {
               checked.add(i);
            }
         }

         render[0].run();
      });
      clearAll.setOnClickListener((v) -> {
         checked.clear();
         render[0].run();
      });
      clearSearch.setOnClickListener((v) -> {
         searchEt.setText("");
         keyword[0] = "";
         render[0].run();
      });
      LinearLayout row = new LinearLayout(this.activity);
      row.setGravity(8388613);
      LinearLayout.LayoutParams rlp2 = new LinearLayout.LayoutParams(-1, -2);
      rlp2.setMargins(0, this.dp(12), 0, 0);
      row.setLayoutParams(rlp2);
      Button cancel = this.ghostButton("取消");
      Button ok = this.primaryButton("加入群聊", this.col(color.onAccent));
      row.addView(cancel);
      LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-2, -2);
      olp.setMarginStart(this.dp(8));
      row.addView(ok, olp);
      box.addView(row);
      dlg.setContentView(box);
      if (dlg.getWindow() != null) {
         dlg.getWindow().setBackgroundDrawable(new ColorDrawable(0));
         WindowManager.LayoutParams lp = dlg.getWindow().getAttributes();
         lp.width = (int)((float)this.activity.getResources().getDisplayMetrics().widthPixels * 0.92F);
         lp.height = (int)((float)this.activity.getResources().getDisplayMetrics().heightPixels * 0.82F);
         dlg.getWindow().setAttributes(lp);
      }

      cancel.setOnClickListener((v) -> dlg.dismiss());
      ok.setOnClickListener((v) -> {
         if (checked.isEmpty()) {
            this.showToast("还没有勾选角色");
         } else {
            int added = 0;
            int skippedDup = 0;

            java.util.Set<String> haveNames = new java.util.HashSet<>();
            for (AppDatabase.GmMember em : this.db.listGmMembers(this.currentRoomId)) {
               if (em.name != null) haveNames.add(em.name);
            }
            List<Integer> ids = new ArrayList(checked);
            Collections.sort(ids);

            for(int idx : ids) {
               String[] c = (String[])candidates.get(idx);
               if (haveNames.contains(c[0])) { ++skippedDup; continue; }
               haveNames.add(c[0]);
               boolean isCelebrity = "名人思维".equals(c[5]);

               long mid = this.db.insertGmMember(this.currentRoomId, c[0], c[1], c[2], c[3], this.settings.chatModel, this.settings.chatBaseUrl, this.settings.chatApiKey, true, 0, false);

               this.db.setGmMemberActiveBySystem(mid, !isCelebrity);
               ++added;
            }

            dlg.dismiss();
            this.showMemberManager();
            this.showToast("已加入 " + added + " 位角色（名人思维默认待命，其余进群即参战，可随时调整）"
                    + (skippedDup > 0 ? "；跳过 " + skippedDup + " 位已在本群的" : ""));
         }
      });
      dlg.show();
   }

   private void pickProviderThenModelForChecked(Set<Long> checked, List<AppDatabase.GmMember> members,
                                                Map<Long, String[]> modelChanges, Runnable[] render, TextView status) {
      List<AppDatabase.GmProvider> providers = this.db.listGmProviders();
      if (providers.isEmpty()) {
         this.showToast("还没有服务商，先到「设置 → \ud83c\udfe2 服务商（全局）」添加");
         return;
      }
      if (providers.size() == 1) {

         this.pickModelThenAssign(providers, true, checked, members, modelChanges, render, status);
         return;
      }

      final Set<Integer> pickedProviders = new java.util.LinkedHashSet<>();
      LinearLayout box = new LinearLayout(this.activity);
      box.setOrientation(1);
      GradientDrawable bg = new GradientDrawable();
      bg.setColor(this.col(color.surface));
      bg.setCornerRadius((float) this.dp(20));
      bg.setStroke(this.dp(1), this.col(color.border));
      box.setBackground(bg);
      box.setPadding(this.dp(16), this.dp(14), this.dp(16), this.dp(12));

      TextView h = new TextView(this.activity);
      h.setText("选择服务商（可多选）");
      h.setTextColor(this.col(color.text));
      h.setTextSize(16.0F);
      h.setTypeface((Typeface) null, 1);
      box.addView(h);

      TextView tip = new TextView(this.activity);
      tip.setVisibility(android.view.View.GONE);
      tip.setTextColor(this.col(color.muted));
      tip.setTextSize(11.0F);
      tip.setLineSpacing((float) this.dp(3), 1.0F);
      tip.setPadding(0, this.dp(6), 0, this.dp(4));
      box.addView(tip);

      ScrollView sv = new ScrollView(this.activity);
      LinearLayout listCol = new LinearLayout(this.activity);
      listCol.setOrientation(1);
      sv.addView(listCol);
      LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 0, 1.0F);
      slp.setMargins(0, this.dp(8), 0, 0);

      for (int i = 0; i < providers.size(); ++i) {
         final int idx = i;
         AppDatabase.GmProvider p = providers.get(i);

         LinearLayout card = new LinearLayout(this.activity);
         card.setOrientation(0);
         card.setGravity(16);
         card.setPadding(this.dp(10), this.dp(9), this.dp(12), this.dp(9));

         CheckBox cb = new CheckBox(this.activity);
         cb.setClickable(false);
         cb.setFocusable(false);

         LinearLayout infoCol = new LinearLayout(this.activity);
         infoCol.setOrientation(1);
         LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(0, -2, 1.0F);
         icLp.setMarginStart(this.dp(8));

         TextView nameTv = new TextView(this.activity);
         nameTv.setText("\ud83c\udfe2 " + p.name);
         nameTv.setTextColor(this.col(color.text));
         nameTv.setTextSize(13.5F);
         nameTv.setTypeface((Typeface) null, 1);
         infoCol.addView(nameTv);

         TextView metaTv = new TextView(this.activity);
         metaTv.setText(p.modelList().size() + " 个模型  ·  " + p.baseUrl);
         metaTv.setTextColor(this.col(color.muted));
         metaTv.setTextSize(10.5F);
         metaTv.setPadding(0, this.dp(2), 0, 0);
         infoCol.addView(metaTv);

         card.addView(cb, new LinearLayout.LayoutParams(-2, -2));
         card.addView(infoCol, icLp);

         final boolean[] on = {false};
         styleProviderPickCard(card, on[0]);
         card.setOnClickListener((v) -> {
            on[0] = !on[0];
            cb.setChecked(on[0]);
            if (on[0]) pickedProviders.add(idx);
            else pickedProviders.remove(idx);
            styleProviderPickCard(card, on[0]);
         });

         LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
         clp.setMargins(0, 0, 0, this.dp(7));
         listCol.addView(card, clp);
      }
      box.addView(sv, slp);

      LinearLayout row = new LinearLayout(this.activity);
      row.setOrientation(0);
      row.setGravity(8388613);
      Button ok = this.primaryButton("下一步：选模型", this.col(color.onAccent));
      Button cancel = this.ghostButton("取消");
      LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-2, -2);
      LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(-2, -2);
      clp2.setMarginStart(this.dp(8));
      row.addView(ok, olp);
      row.addView(cancel, clp2);
      LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
      rlp.setMargins(0, this.dp(10), 0, 0);
      box.addView(row, rlp);

      Dialog dlg = this.showDialogWide(box);
      cancel.setOnClickListener((v) -> dlg.dismiss());
      ok.setOnClickListener((v) -> {
         if (pickedProviders.isEmpty()) {
            this.showToast("请至少勾选一个服务商");
            return;
         }
         List<AppDatabase.GmProvider> picked = new ArrayList<>();
         for (int i : pickedProviders) picked.add(providers.get(i));
         dlg.dismiss();
         this.pickModelThenAssign(picked, picked.size() > 1, checked, members, modelChanges, render, status);
      });
   }

   private void styleProviderPickCard(LinearLayout card, boolean selected) {
      GradientDrawable bg = new GradientDrawable();
      bg.setColor(this.col(selected ? color.accentSoft : color.surfaceInput));
      bg.setCornerRadius((float) this.dp(12));
      bg.setStroke(this.dp(1), this.col(selected ? color.accent : color.border));
      card.setBackground(bg);
   }

   private void pickModelThenAssign(List<AppDatabase.GmProvider> picked, boolean multiMode,
                                    Set<Long> checked, List<AppDatabase.GmMember> members,
                                    Map<Long, String[]> modelChanges, Runnable[] render, TextView status) {
      List<String> models;
      if (multiMode) {

         models = new ArrayList<>(picked.get(0).modelList());
         for (int i = 1; i < picked.size(); ++i) {
            models.retainAll(picked.get(i).modelList());
         }
         if (models.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (AppDatabase.GmProvider p : picked) {
               if (sb.length() > 0) sb.append("、");
               sb.append(p.name);
            }
            this.showToast("选中的服务商（" + sb + "）没有共同的模型，无法分摊。请换一组，或分批设置");
            return;
         }
      } else {
         models = picked.get(0).modelList();
         if (models.isEmpty()) {
            this.showToast("该服务商还没有模型，请到「服务商（全局）」一键获取");
            return;
         }
      }
      java.util.Collections.sort(models);

      String title = multiMode
              ? "选一个共同模型（" + picked.size() + " 个服务商都有）"
              : ("统一使用哪个模型？（" + picked.get(0).name + "）");
      String[] arr = models.toArray(new String[0]);
      String[] subs = new String[arr.length];
      for (int i = 0; i < arr.length; ++i) subs[i] = "";

      this.showStyledListPicker((View) null, title, arr, subs, -1, (m) -> {
         String model = arr[m];
         long[] ids = new long[checked.size()];
         int k = 0;
         for (AppDatabase.GmMember mem : members) {
            if (checked.contains(mem.id)) ids[k++] = mem.id;
         }

         int n = 0;
         for (long id : ids) {
            AppDatabase.GmProvider p = picked.get(n % picked.size());
            modelChanges.put(id, new String[]{p.baseUrl, p.apiKey == null ? "" : p.apiKey, model});
            ++n;
         }

         String extra = picked.size() > 1
                 ? "，将在 " + picked.size() + " 个服务商之间分摊"
                 : "";
         this.showToast("已为 " + n + " 位角色选择「" + model + "」" + extra + "，点「\ud83d\udcbe 保存模型改动」生效");
         render[0].run();
         status.setText(this.hintForBatch(checked.size(), modelChanges.size()));
      });
   }

   private void showBatchPanel() {
      if (this.currentRoomId > 0L) {

         List<AppDatabase.GmMember> members = this.orderMembersForRound(this.db.listGmMembers(this.currentRoomId));
         if (members.isEmpty()) {
            this.showToast("还没有成员");
         } else {
            Set<Long> checked = new HashSet();
            Map<Long, String[]> modelChanges = new HashMap();
            LinearLayout box = new LinearLayout(this.activity);
            box.setOrientation(1);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(this.col(color.surface));
            bg.setCornerRadius((float)this.dp(20));
            bg.setStroke(this.dp(1), this.col(color.border));
            box.setBackground(bg);
            box.setPadding(this.dp(16), this.dp(14), this.dp(16), this.dp(12));
            TextView h = new TextView(this.activity);
            h.setText("☑ 批量参与讨论 / 选模型");
            h.setTextColor(this.col(color.text));
            h.setTextSize(16.0F);
            h.setTypeface((Typeface)null, 1);
            box.addView(h);

            LinearLayout tools = new LinearLayout(this.activity);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
            tlp.setMargins(0, this.dp(10), 0, 0);
            tools.setLayoutParams(tlp);
            Button selAll = this.ghostButton("全部");
            Button selActive = this.ghostButton("选已参与");
            Button selInactive = this.ghostButton("选未参与");
            tools.addView(selAll, new LinearLayout.LayoutParams(0, -2, 1.0F));
            this.addWithMarginWeight(tools, selActive, 8);
            this.addWithMarginWeight(tools, selInactive, 8);
            box.addView(tools);
            ScrollView sv = new ScrollView(this.activity);
            LinearLayout list = new LinearLayout(this.activity);
            list.setOrientation(1);
            sv.addView(list);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 0, 1.0F);
            slp.setMargins(0, this.dp(10), 0, 0);
            box.addView(sv, slp);
            Dialog dlg = new Dialog(this.activity);

            dlg.setOnDismissListener((d) -> { if (this.inHub || this.currentPage == 2) { this.showMemberManager(); } });
            TextView status = new TextView(this.activity);
            status.setTextColor(this.col(color.muted));
            status.setTextSize(11.0F);
            status.setPadding(0, this.dp(6), 0, 0);
            Runnable[] render = new Runnable[1];
            render[0] = () -> {
               list.removeAllViews();

               for(AppDatabase.GmMember m : members) {
                  LinearLayout card = new LinearLayout(this.activity);
                  card.setOrientation(1);
                  boolean isChecked = checked.contains(m.id);
                  GradientDrawable cb = new GradientDrawable();
                  cb.setColor(isChecked ? this.col(color.accentSoft) : this.col(color.surfaceInput));
                  cb.setCornerRadius((float)this.dp(10));
                  cb.setStroke(this.dp(1), isChecked ? this.col(color.accent) : this.col(color.border));
                  card.setBackground(cb);

                  card.setPadding(this.dp(10), this.dp(7), this.dp(10), this.dp(7));
                  LinearLayout row1 = new LinearLayout(this.activity);
                  row1.setOrientation(0);
                  row1.setGravity(16);
                  CheckBox cb2 = new CheckBox(this.activity);
                  cb2.setChecked(isChecked);
                  cb2.setOnCheckedChangeListener((b, v) -> {
                     if (v) {
                        checked.add(m.id);
                     } else {
                        checked.remove(m.id);
                     }

                     render[0].run();
                  });
                  row1.addView(cb2);
                  TextView name = new TextView(this.activity);
                  name.setText((m.avatar == null ? "" : m.avatar) + " " + m.name + (m.active ? "　✅参与讨论中" : "　⏸待命"));
                  name.setTextColor(this.col(color.text));
                  name.setTextSize(13.0F);
                  name.setTypeface((Typeface)null, 1);
                  row1.addView(name);
                  card.addView(row1);
                  String[] pending = (String[])modelChanges.get(m.id);
                  String curModel = pending != null ? pending[2] : m.model;
                  if (pending != null) {
                     String var10000 = pending[0];
                  } else {
                     String var25 = m.baseUrl;
                  }

                  TextView cur = new TextView(this.activity);
                  cur.setText("当前：" + (curModel != null && !curModel.isEmpty() ? curModel : "未设置") + (pending != null ? "　（已改，未保存）" : ""));
                  cur.setTextColor(pending != null ? this.col(color.searchAccent) : this.col(color.muted));
                  cur.setTextSize(10.5F);
                  cur.setPadding(this.dp(30), this.dp(2), 0, 0);
                  card.addView(cur);
                  LinearLayout row2 = new LinearLayout(this.activity);
                  row2.setOrientation(0);
                  LinearLayout.LayoutParams r2lp = new LinearLayout.LayoutParams(-1, -2);
                  r2lp.setMargins(this.dp(30), this.dp(4), 0, 0);
                  row2.setLayoutParams(r2lp);
                  Button provBtn = this.primaryButton("\ud83c\udfe2 服务商 ▾", this.col(color.accent));
                  provBtn.setBackgroundResource(drawable.btn_secondary);
                  provBtn.setTextSize(11.0F);
                  provBtn.setPadding(this.dp(8), this.dp(6), this.dp(8), this.dp(6));
                  provBtn.setOnClickListener((v) -> {
                     List<AppDatabase.GmProvider> providers = this.db.listGmProviders();
                     if (providers.isEmpty()) {
                        this.showToast("还没有服务商，先去「\ud83c\udfe2 服务商」添加");
                     } else {
                        String[] pn = new String[providers.size()];
                        String[] ps = new String[providers.size()];

                        for(int i = 0; i < providers.size(); ++i) {
                           pn[i] = ((AppDatabase.GmProvider)providers.get(i)).name;
                           ps[i] = ((AppDatabase.GmProvider)providers.get(i)).baseUrl + " · " + ((AppDatabase.GmProvider)providers.get(i)).modelList().size() + " 个模型";
                        }

                        this.showStyledListPicker((View)null, "给「" + m.name + "」选服务商", pn, ps, -1, (which) -> {
                           AppDatabase.GmProvider p = (AppDatabase.GmProvider)providers.get(which);
                           String[] prev = (String[])modelChanges.get(m.id);
                           String keepModel = prev != null ? prev[2] : m.model;
                           modelChanges.put(m.id, new String[]{p.baseUrl, p.apiKey == null ? "" : p.apiKey, keepModel});
                           render[0].run();
                           this.showToast("已选服务商「" + p.name + "」，接着点「\ud83e\udd16 模型」选具体模型");
                        });
                     }
                  });
                  row2.addView(provBtn, new LinearLayout.LayoutParams(0, -2, 1.0F));
                  Button modelBtn = this.primaryButton("\ud83e\udd16 模型 ▾", this.col(color.accent));
                  modelBtn.setBackgroundResource(drawable.btn_secondary);
                  modelBtn.setTextSize(11.0F);
                  modelBtn.setPadding(this.dp(8), this.dp(6), this.dp(8), this.dp(6));
                  modelBtn.setOnClickListener((v) -> {
                     String[] pend = (String[])modelChanges.get(m.id);
                     String useBase = pend != null ? pend[0] : m.baseUrl;
                     AppDatabase.GmProvider matched = null;

                     for(AppDatabase.GmProvider gp : this.db.listGmProviders()) {
                        if (gp.baseUrl != null && gp.baseUrl.trim().equals(useBase == null ? "" : useBase.trim())) {
                           matched = gp;
                           break;
                        }
                     }

                     final AppDatabase.GmProvider matchedRef = matched;
                     if (matchedRef == null) {
                        this.showToast("请先选服务商");
                     } else {
                        List<String> models = matchedRef.modelList();
                        if (models.isEmpty()) {
                           this.showToast("该服务商没保存模型，先去服务商页添加");
                        } else {
                           String[] arr = (String[])models.toArray(new String[0]);
                           String[] subs = new String[arr.length];

                           for(int i = 0; i < arr.length; ++i) {
                              subs[i] = "";
                           }

                           String curM = pend != null ? pend[2] : m.model;
                           int curIdx = -1;

                           for(int i = 0; i < arr.length; ++i) {
                              if (arr[i].equals(curM)) {
                                 curIdx = i;
                                 break;
                              }
                           }

                           final AppDatabase.GmMember memRef = m;
                           this.showStyledListPicker((View)null, "给「" + memRef.name + "」选模型（" + matchedRef.name + "）", arr, subs, curIdx, (which) -> {
                              String[] p2 = (String[])modelChanges.get(memRef.id);
                              if (p2 == null) {
                                 p2 = new String[]{matchedRef.baseUrl, matchedRef.apiKey == null ? "" : matchedRef.apiKey, arr[which]};
                              } else {
                                 p2 = new String[]{p2[0], p2[1], arr[which]};
                              }

                              modelChanges.put(memRef.id, p2);
                              render[0].run();
                           });
                        }
                     }
                  });
                  this.addWithMargin(row2, modelBtn, 12);
                  LinearLayout.LayoutParams r2wrap = new LinearLayout.LayoutParams(-1, -2);
                  r2wrap.setMargins(this.dp(30), this.dp(4), 0, this.dp(1));
                  row2.setLayoutParams(r2wrap);
                  card.addView(row2);
                  LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
                  clp.setMargins(0, this.dp(2), 0, this.dp(6));
                  list.addView(card, clp);
               }

               status.setText(this.hintForBatch(checked.size(), modelChanges.size()));
            };
            render[0].run();

            selAll.setOnClickListener((v) -> {
               checked.clear();
               for(AppDatabase.GmMember m : members) {
                  checked.add(m.id);
               }

               render[0].run();
            });
            selActive.setOnClickListener((v) -> {
               checked.clear();

               for(AppDatabase.GmMember m : members) {
                  if (m.active) {
                     checked.add(m.id);
                  }
               }

               render[0].run();
            });
            selInactive.setOnClickListener((v) -> {
               checked.clear();

               for(AppDatabase.GmMember m : members) {
                  if (!m.active) {
                     checked.add(m.id);
                  }
               }

               render[0].run();
            });

            box.addView(status);

            Button saveModels = this.primaryButton("\ud83d\udcbe 保存模型改动", this.col(color.onAccent));
            saveModels.setTextSize(11.0F);
            saveModels.setPadding(this.dp(12), this.dp(7), this.dp(12), this.dp(7));
            LinearLayout.LayoutParams smLp = new LinearLayout.LayoutParams(-1, -2);
            smLp.setMargins(0, this.dp(6), 0, 0);
            box.addView(saveModels, smLp);
            LinearLayout rowActive = new LinearLayout(this.activity);
            rowActive.setOrientation(0);
            LinearLayout.LayoutParams raLp = new LinearLayout.LayoutParams(-1, -2);
            raLp.setMargins(0, this.dp(5), 0, 0);
            Button setActive = this.primaryButton("✅ 勾选的设为参与讨论", this.col(color.onAccent));
            setActive.setTextSize(11.0F);
            setActive.setPadding(this.dp(10), this.dp(7), this.dp(10), this.dp(7));
            Button setInactive = this.ghostButton("⏸ 勾选的设为暂停讨论");
            setInactive.setTextSize(11.0F);
            setInactive.setPadding(this.dp(10), this.dp(7), this.dp(10), this.dp(7));
            rowActive.addView(setActive, new LinearLayout.LayoutParams(0, -2, 1.0F));
            LinearLayout.LayoutParams siLp = new LinearLayout.LayoutParams(0, -2, 1.0F);
            siLp.setMarginStart(this.dp(6));
            rowActive.addView(setInactive, siLp);
            box.addView(rowActive, raLp);
            Button batchModel = this.ghostButton("\ud83d\udd01 给勾选的角色统一设置模型（可选多个服务商分摊）");
            batchModel.setTextColor(this.col(color.accent));
            batchModel.setTextSize(11.0F);
            batchModel.setPadding(this.dp(12), this.dp(7), this.dp(12), this.dp(7));
            LinearLayout.LayoutParams bmLp = new LinearLayout.LayoutParams(-1, -2);
            bmLp.setMargins(0, this.dp(5), 0, 0);
            batchModel.setOnClickListener((v) -> {
               if (checked.isEmpty()) {
                  this.showToast("请先勾选角色");
               } else {
                  this.pickProviderThenModelForChecked(checked, members, modelChanges, render, status);
               }
            });
            box.addView(batchModel, bmLp);

            Button batchThink = this.ghostButton("\ud83e\udde0 给勾选的角色统一设置思考档位");
            batchThink.setTextColor(this.col(color.accent));
            batchThink.setTextSize(11.0F);
            batchThink.setPadding(this.dp(12), this.dp(7), this.dp(12), this.dp(7));
            LinearLayout.LayoutParams btLp = new LinearLayout.LayoutParams(-1, -2);
            btLp.setMargins(0, this.dp(5), 0, 0);
            batchThink.setOnClickListener((v) -> {
               if (checked.isEmpty()) {
                  this.showToast("请先勾选角色");
               } else {
                  this.showStyledThinkPicker(batchThink, 1, (chosen) -> {
                     int n = 0;

                     for(AppDatabase.GmMember m : members) {
                        if (checked.contains(m.id)) {
                           m.thinkLevel = chosen;
                           this.db.updateGmMember(m);
                           ++n;
                        }
                     }

                     String[] tn = ReasoningNegotiator.levelNames();
                     this.showToast("已把 " + n + " 位角色设为「思考："
                             + tn[ReasoningNegotiator.clampLevel(chosen)] + "」");

                     render[0].run();
                  });
               }
            });
            box.addView(batchThink, btLp);
            LinearLayout rowClose = new LinearLayout(this.activity);
            rowClose.setGravity(8388613);
            LinearLayout.LayoutParams rcLp = new LinearLayout.LayoutParams(-1, -2);
            rcLp.setMargins(0, this.dp(5), 0, 0);
            Button close = this.ghostButton("关闭");
            close.setTextSize(11.0F);
            close.setPadding(this.dp(10), this.dp(6), this.dp(10), this.dp(6));
            rowClose.addView(close);
            box.addView(rowClose, rcLp);
            dlg.setContentView(box);
            if (dlg.getWindow() != null) {
               dlg.getWindow().setBackgroundDrawable(new ColorDrawable(0));
               WindowManager.LayoutParams lp = dlg.getWindow().getAttributes();
               lp.width = (int)((float)this.activity.getResources().getDisplayMetrics().widthPixels * 0.94F);

               lp.height = (int)((float)this.activity.getResources().getDisplayMetrics().heightPixels * 0.92F);
               dlg.getWindow().setAttributes(lp);
            }

            close.setOnClickListener((v) -> dlg.dismiss());
            setActive.setOnClickListener((v) -> {
               if (checked.isEmpty()) {
                  this.showToast("请先勾选角色");
               } else {
                  int n = 0;

                  for(AppDatabase.GmMember m : members) {
                     if (checked.contains(m.id)) {
                        this.db.setGmMemberActive(m.id, true);
                        m.active = true;

                        ++n;
                     }
                  }

                  this.showToast("已把 " + n + " 位角色设为参与讨论");

                  render[0].run();
               }
            });
            setInactive.setOnClickListener((v) -> {
               if (checked.isEmpty()) {
                  this.showToast("请先勾选角色");
               } else {
                  int n = 0;

                  for(AppDatabase.GmMember m : members) {
                     if (checked.contains(m.id)) {
                        this.db.setGmMemberActive(m.id, false);
                        m.active = false;
                        ++n;
                     }
                  }

                  this.showToast("已把 " + n + " 位角色设为暂停讨论");
                  render[0].run();
               }
            });
            saveModels.setOnClickListener((v) -> {
               if (modelChanges.isEmpty()) {
                  this.showToast("还没有改过任何模型");
               } else {
                  int n = 0;

                  for(AppDatabase.GmMember m : members) {
                     String[] ch = (String[])modelChanges.get(m.id);
                     if (ch != null) {
                        m.baseUrl = ch[0];
                        m.apiKey = ch[1];
                        m.model = ch[2];
                        this.db.updateGmMember(m);
                        ++n;
                     }
                  }

                  modelChanges.clear();
                  this.showToast("已保存 " + n + " 个角色的模型设置");

                  render[0].run();
               }
            });
            dlg.show();
         }
      }
   }

   private String hintForBatch(int checkedCount, int changedCount) {
      return "已勾选 " + checkedCount + " 位" + (changedCount > 0 ? "　·　模型改动待保存 " + changedCount + " 位" : "");
   }

   private void addWithMargin(LinearLayout parent, View child, int marginDp) {
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
      lp.setMarginStart(this.dp(marginDp));
      parent.addView(child, lp);
   }

   private void addWithMarginWeight(LinearLayout parent, View child, int marginDp) {
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1.0F);
      lp.setMarginStart(this.dp(marginDp));
      parent.addView(child, lp);
   }

   private void showMemberManager() {
      this.stopUsageTicker();
      if (this.currentRoomId > 0L) {
         if (!this.inHub) this.currentPage = 2;
         this.setHeaderButtons(false);
         this.renderTarget.removeAllViews();
         LinearLayout root = new LinearLayout(this.activity);
         root.setOrientation(1);
         this.renderTarget.addView(root, new FrameLayout.LayoutParams(-1, -1));

         LinearLayout titleRow = new LinearLayout(this.activity);
         titleRow.setOrientation(0);
         titleRow.setGravity(16);
         LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(-1, -2);
         trLp.setMargins(0, 0, 0, this.dp(8));
         titleRow.setLayoutParams(trLp);
         TextView mTitle = new TextView(this.activity);
         mTitle.setText("\ud83d\udc65 群成员");
         mTitle.setTextColor(this.col(color.text));
         mTitle.setTextSize(16.0F);
         mTitle.setTypeface((Typeface)null, 1);
         titleRow.addView(mTitle, new LinearLayout.LayoutParams(0, -2, 1.0F));
         Button saveTpl = this.primaryButton("\ud83d\udcbe 存为模板", this.col(color.onAccent));
         saveTpl.setOnClickListener((v) -> this.saveRoomAsTemplate());
         titleRow.addView(saveTpl);
         root.addView(titleRow);

         LinearLayout barRow = new LinearLayout(this.activity);
         barRow.setOrientation(0);
         barRow.setGravity(16);
         Button lib = this.primaryButton("添加角色", this.col(color.onAccent));
         lib.setOnClickListener((v) -> this.showRoleLibraryPicker());
         barRow.addView(lib, new LinearLayout.LayoutParams(0, -2, 1.0F));
         Button batch = this.ghostButton("批量勾选");
         batch.setTextColor(this.col(color.accent));
         LinearLayout.LayoutParams blp2 = new LinearLayout.LayoutParams(0, -2, 1.0F);
         blp2.setMarginStart(this.dp(8));
         batch.setOnClickListener((v) -> this.showBatchPanel());
         barRow.addView(batch, blp2);
         Button add = this.ghostButton("自定义");
         LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0, -2, 1.0F);
         alp.setMarginStart(this.dp(8));
         add.setOnClickListener((v) -> this.showMemberEditor((AppDatabase.GmMember)null));
         barRow.addView(add, alp);
         root.addView(barRow);
         List<AppDatabase.GmMember> members = this.db.listGmMembers(this.currentRoomId);

         members = this.orderMembersForRound(members);
         ScrollView sv = new ScrollView(this.activity);
         LinearLayout list = new LinearLayout(this.activity);
         list.setOrientation(1);
         sv.addView(list);
         LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 0, 1.0F);
         slp.setMargins(0, this.dp(8), 0, 0);
         root.addView(sv, slp);
         AppDatabase.GmRoom room = this.db.getGmRoom(this.currentRoomId);
         int ctxLimit = room == null ? 1000000 : room.contextLimit;

         java.util.HashMap<Long, Integer> ctxOf = this.db.mapGmMemberLastPromptTokens(this.currentRoomId);
         java.util.HashMap<Long, Long> ownTokOf = this.db.mapGmMemberOwnTokens(this.currentRoomId);

         for(AppDatabase.GmMember m : members) {
            LinearLayout card = new LinearLayout(this.activity);
            card.setOrientation(1);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(this.col(color.surface));
            bg.setCornerRadius((float)this.dp(14));
            bg.setStroke(this.dp(1), this.col(color.border));
            card.setBackground(bg);
            card.setPadding(this.dp(14), this.dp(12), this.dp(14), this.dp(12));
            TextView n = new TextView(this.activity);
            n.setText((m.avatar == null ? "\ud83e\udd16" : m.avatar) + " " + m.name);
            n.setTextColor(this.col(color.text));
            n.setTextSize(15.0F);
            n.setTypeface((Typeface)null, 1);
            card.addView(n);
            TextView d = new TextView(this.activity);
            String phTag = m.phase == 1 ? " · \ud83d\udccc中期进场" : (m.phase == 2 ? " · \ud83d\udccc收尾进场" : "");

            String[] tnShow = new String[]{"思考关", "思考轻度", "思考中", "思考高", "思考极高", "思考Ultra"};
            d.setText((m.roleDesc == null ? "" : m.roleDesc) + phTag + "\n模型：" + (m.model == null ? "-" : m.model)
                    + (m.canSearch ? " · \ud83d\udd0d可联网" : "")
                    + " · \ud83e\udde0" + tnShow[ReasoningNegotiator.clampLevel(m.thinkLevel)]);
            d.setTextColor(this.col(color.muted));
            d.setTextSize(11.0F);
            d.setLineSpacing((float)this.dp(2), 1.0F);
            d.setPadding(0, this.dp(4), 0, 0);
            card.addView(d);

            int spokenCnt = this.db.countGmMemberSpeeches(this.currentRoomId, m.id);
            String quota = m.maxMsgs > 0
                    ? spokenCnt + "/" + m.maxMsgs + " 条（正式发言；共激活 " + m.usedMsgs + " 次）"
                    : spokenCnt + " 条（不限；共激活 " + m.usedMsgs + " 次）";

            long ownTok = ownTokOf.getOrDefault(m.id, 0L);

            Integer ctxNow = ctxOf.get(m.id);
            TextView u = new TextView(this.activity);
            if (ctxNow != null && ctxNow > 0) {
               u.setText("\ud83d\udcca 已发言 " + quota + " · 本人累计 " + this.fmtTokens(ownTok) + " tok\n"
                       + "\ud83e\udde0 上次读入上下文 " + this.fmtTokens(ctxNow) + " / " + this.fmtTokens(ctxLimit) + " tok"
                       + "（剩余 " + this.fmtTokens(Math.max(0L, (long) ctxLimit - (long) ctxNow)) + "）");
            } else {

               u.setText("\ud83d\udcca 已发言 " + quota + " · 本人累计 " + this.fmtTokens(ownTok) + " tok\n"
                       + "\ud83e\udde0 本轮尚未调用（还没有读到任何上下文）");
            }
            u.setTextColor(this.col(color.muted));
            u.setTextSize(11.0F);
            u.setLineSpacing((float)this.dp(2), 1.0F);
            u.setPadding(0, this.dp(6), 0, 0);
            card.addView(u);
            LinearLayout row = new LinearLayout(this.activity);
            row.setGravity(8388613);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
            rlp.setMargins(0, this.dp(10), 0, 0);
            row.setLayoutParams(rlp);
            boolean[] isActive = new boolean[]{m.active};
            Button toggle = this.ghostButton(isActive[0] ? "暂停讨论" : "参与讨论");

            final Runnable[] paintToggle = new Runnable[1];
            paintToggle[0] = () -> {
               if (isActive[0]) {
                  toggle.setBackgroundResource(drawable.btn_primary);
                  toggle.setTextColor(this.col(color.onAccent));
               } else {
                  toggle.setBackgroundResource(drawable.btn_ghost);
                  toggle.setTextColor(this.col(color.muted));
               }
            };
            paintToggle[0].run();
            toggle.setOnClickListener((v) -> {
               isActive[0] = !isActive[0];
               this.db.setGmMemberActive(m.id, isActive[0]);
               m.active = isActive[0];
               toggle.setText(isActive[0] ? "暂停讨论" : "参与讨论");
               paintToggle[0].run();
            });
            row.addView(toggle);

            Button thinkBtn = this.ghostButton("\ud83e\udde0 " + ReasoningNegotiator.levelName(m.thinkLevel));
            thinkBtn.setTextColor(this.col(color.accent));
            thinkBtn.setOnClickListener((v) -> {
               this.showStyledThinkPicker(thinkBtn, m.thinkLevel, (chosen) -> {
                  m.thinkLevel = chosen;
                  this.db.updateGmMember(m);
                  thinkBtn.setText("\ud83e\udde0 " + ReasoningNegotiator.levelName(chosen));
               });
            });
            LinearLayout.LayoutParams tklp = new LinearLayout.LayoutParams(-2, -2);
            tklp.setMarginStart(this.dp(8));
            row.addView(thinkBtn, tklp);
            if (m.isDefault) {
               TextView builtin = new TextView(this.activity);
               builtin.setText("内置");
               builtin.setTextColor(this.col(color.muted));
               builtin.setTextSize(11.0F);
               builtin.setPadding(this.dp(10), this.dp(6), this.dp(6), this.dp(6));
               row.addView(builtin);
            } else {
               Button del = this.ghostButton("删除");
               del.setTextColor(this.col(color.danger));
               del.setOnClickListener((v) -> this.confirmDeleteMember(m));
               row.addView(del);
            }

            Button edit = this.primaryButton("编辑", this.col(color.onAccent));
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-2, -2);
            elp.setMarginStart(this.dp(8));
            edit.setOnClickListener((v) -> this.showMemberEditor(m));
            row.addView(edit, elp);
            card.addView(row);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
            clp.setMargins(0, this.dp(8), 0, 0);
            list.addView(card, clp);
         }

         if (members.isEmpty()) {
            TextView empty = new TextView(this.activity);
            empty.setText("还没有成员。");
            empty.setTextColor(this.col(color.muted));
            empty.setTextSize(13.0F);
            list.addView(empty);
         }

      }
   }

   private int estimateRoomTokens(long roomId) {
      long tok = this.db.sumGmPublicTokens(roomId);
      return (int) Math.min((long) Integer.MAX_VALUE, tok);
   }

   private void showMemberEditor(AppDatabase.GmMember existing) {
      boolean isNew = existing == null;
      LinearLayout box = new LinearLayout(this.activity);
      box.setOrientation(1);
      GradientDrawable bg = new GradientDrawable();
      bg.setColor(this.col(color.surface));
      bg.setCornerRadius((float)this.dp(20));
      bg.setStroke(this.dp(1), this.col(color.border));
      box.setBackground(bg);
      box.setPadding(this.dp(18), this.dp(18), this.dp(18), this.dp(14));
      TextView h = new TextView(this.activity);
      h.setText(isNew ? "添加角色" : "编辑角色");
      h.setTextColor(this.col(color.text));
      h.setTextSize(16.0F);
      h.setTypeface((Typeface)null, 1);
      box.addView(h);
      ScrollView sv = new ScrollView(this.activity);
      LinearLayout form = new LinearLayout(this.activity);
      form.setOrientation(1);
      sv.addView(form);
      EditText nameEt = this.field(form, "角色名", isNew ? "" : existing.name, "如：资料员、战略专家");
      EditText avatarEt = this.field(form, "头像 emoji", isNew ? "\ud83e\udd16" : existing.avatar, "如：\ud83d\udd0d \ud83e\udde0 \ud83d\udd34 ✍️");
      EditText descEt = this.field(form, "身份定位", isNew ? "" : existing.roleDesc, "一句话说明这个角色负责什么");
      EditText promptEt = this.field(form, "角色提示词", isNew ? "" : existing.prompt, "详细说明它的思考方式和发言风格");
      EditText baseEt = this.field(form, "请求地址（点下面「切换服务商」自动填）", isNew ? this.settings.chatBaseUrl : existing.baseUrl, "OpenAI 兼容地址");
      EditText keyEt = this.field(form, "API Key（点下面「切换服务商」自动填）", isNew ? this.settings.chatApiKey : existing.apiKey, "留空用默认");

      this.attachPasswordToggleGM(keyEt);
      EditText modelEt = this.field(form, "模型（可点下拉选择）", isNew ? this.settings.chatModel : existing.model, "如 deepseek-v4-flash-0731");
      EditText quotaEt = this.field(form, "发言上限（条，按正式发言计；沉默轮不吃配额）", isNew ? "0" : String.valueOf(existing.maxMsgs), "0 = 不限");
      LinearLayout pickRow = new LinearLayout(this.activity);
      pickRow.setOrientation(0);
      pickRow.setGravity(16);
      LinearLayout.LayoutParams prlp = new LinearLayout.LayoutParams(-1, -2);
      prlp.setMargins(0, this.dp(12), 0, 0);
      pickRow.setLayoutParams(prlp);
      Button provDrop = this.primaryButton("\ud83c\udfe2 切换服务商  ▾", this.col(color.onAccent));
      provDrop.setBackgroundResource(drawable.btn_secondary);
      provDrop.setTextColor(this.col(color.accent));
      provDrop.setOnClickListener((v) -> this.pickProviderForRole(baseEt, keyEt, modelEt));
      pickRow.addView(provDrop, new LinearLayout.LayoutParams(0, -2, 1.0F));
      Button modelDrop = this.primaryButton("\ud83e\udd16 选择模型  ▾", this.col(color.onAccent));
      modelDrop.setBackgroundResource(drawable.btn_secondary);
      modelDrop.setTextColor(this.col(color.accent));
      LinearLayout.LayoutParams mdlp = new LinearLayout.LayoutParams(0, -2, 1.0F);
      mdlp.setMarginStart(this.dp(8));
      modelDrop.setOnClickListener((v) -> {
         String cur = baseEt.getText().toString().trim();
         AppDatabase.GmProvider matched = null;

         for(AppDatabase.GmProvider gp : this.db.listGmProviders()) {
            if (gp.baseUrl != null && gp.baseUrl.trim().equals(cur)) {
               matched = gp;
               break;
            }
         }

         if (matched == null) {
            List<AppDatabase.GmProvider> all = this.db.listGmProviders();
            if (all.isEmpty()) {
               this.showToast("还没有服务商，先去「\ud83c\udfe2 服务商」添加");
               return;
            }

            matched = (AppDatabase.GmProvider)all.get(0);
            baseEt.setText(matched.baseUrl);
            keyEt.setText(matched.apiKey == null ? "" : matched.apiKey);
         }

         this.pickModelForRole(matched, modelEt);
      });
      pickRow.addView(modelDrop, mdlp);
      form.addView(pickRow);

      int[] thinkLevel = new int[]{isNew ? ReasoningNegotiator.LEVEL_LOW : existing.thinkLevel};
      TextView thinkBtn = new TextView(this.activity);
      Runnable updateThinkLabel = () -> {
         String[] names = ReasoningNegotiator.levelNames();
         thinkBtn.setText("\ud83e\udde0 思考档位：" + names[ReasoningNegotiator.clampLevel(thinkLevel[0])] + "  ▾");
      };
      updateThinkLabel.run();
      thinkBtn.setTextColor(this.col(color.text));
      thinkBtn.setTextSize(13.0F);
      thinkBtn.setBackgroundResource(drawable.bg_edit);
      thinkBtn.setPadding(this.dp(12), this.dp(12), this.dp(12), this.dp(12));
      LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
      tlp.setMargins(0, this.dp(12), 0, 0);
      thinkBtn.setOnClickListener((v) -> this.showStyledThinkPicker(thinkBtn, thinkLevel[0], (chosen) -> {
            thinkLevel[0] = chosen;
            updateThinkLabel.run();
         }));
      form.addView(thinkBtn, tlp);

      Switch searchSw = new Switch(this.activity);
      searchSw.setText("允许联网搜索");
      searchSw.setTextColor(this.col(color.text));
      searchSw.setTextSize(13.0F);
      searchSw.setChecked(!isNew && existing.canSearch);
      LinearLayout.LayoutParams swlp = new LinearLayout.LayoutParams(-1, -2);
      swlp.setMargins(0, this.dp(10), 0, 0);
      form.addView(searchSw, swlp);
      LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 0, 1.0F);
      box.addView(sv, slp);
      LinearLayout row = new LinearLayout(this.activity);
      row.setGravity(8388613);
      LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
      rlp.setMargins(0, this.dp(14), 0, 0);
      row.setLayoutParams(rlp);
      Button cancel = this.ghostButton("取消");
      row.addView(cancel);
      Button ok = this.primaryButton("保存", this.col(color.onAccent));
      LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-2, -2);
      olp.setMarginStart(this.dp(8));
      row.addView(ok, olp);
      box.addView(row);
      Dialog dlg = this.showDialogWide(box);
      cancel.setOnClickListener((v) -> dlg.dismiss());
      ok.setOnClickListener((v) -> {
         String nm = nameEt.getText().toString().trim();
         if (nm.isEmpty()) {
            this.showToast("请填角色名");
         } else {
            int quota;
            try {
               quota = Integer.parseInt(quotaEt.getText().toString().trim());
            } catch (Exception var20) {
               quota = 0;
            }

            if (isNew) {
               long newId = this.db.insertGmMember(this.currentRoomId, nm, avatarEt.getText().toString().trim(), descEt.getText().toString().trim(), promptEt.getText().toString().trim(), modelEt.getText().toString().trim(), baseEt.getText().toString().trim(), keyEt.getText().toString().trim(), searchSw.isChecked(), quota);
               AppDatabase.GmMember created = this.db.getGmMember(newId);
               if (created != null && thinkLevel[0] != 1) {
                  created.thinkLevel = thinkLevel[0];
                  this.db.updateGmMember(created);
               }

            } else {
               existing.name = nm;
               existing.avatar = avatarEt.getText().toString().trim();
               existing.roleDesc = descEt.getText().toString().trim();
               existing.prompt = promptEt.getText().toString().trim();
               existing.model = modelEt.getText().toString().trim();
               existing.baseUrl = baseEt.getText().toString().trim();
               existing.apiKey = keyEt.getText().toString().trim();
               existing.canSearch = searchSw.isChecked();
               existing.maxMsgs = quota;
               existing.thinkLevel = thinkLevel[0];

               this.db.updateGmMember(existing);
            }

            dlg.dismiss();
            this.showMemberManager();
         }
      });
      dlg.show();
   }

   private void saveExportToDownloads(final String fileBase, final String content, final String what) {
      final long roomId = this.currentRoomId;
      this.showToast("正在生成「" + what + "」文档…");
      this.bgExecute(this.executor, () -> {
         String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(new Date());
         String safe = (fileBase == null ? "导出" : fileBase).replaceAll("[\\\\/:*?\"<>|]", "_");
         String fileName = safe + "_" + stamp + ".txt";
         final File out = this.saveTextToDownloads(fileName, content == null ? "" : content);
         this.ui.post(() -> {
            if (this.activity.isFinishing() || this.activity.isDestroyed()) return;
            if (out == null) {
               this.showToast("保存失败（可到「设置 → 实施方案」里再试一次）");
            } else {
               this.showToast("已保存到 手机存储/Download/" + fileName + "（" + content.length() + " 字）");

               if (this.currentRoomId == roomId) this.renderChat();
            }
         });
      });
   }

   private File saveTextToDownloads(String fileName, String content) {
      byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
      if (VERSION.SDK_INT >= 29) {
         try {
            ContentValues v = new ContentValues();
            v.put("_display_name", fileName);
            v.put("mime_type", "text/plain");
            v.put("relative_path", Environment.DIRECTORY_DOWNLOADS);
            Uri uri = this.activity.getContentResolver().insert(Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri != null) {
               OutputStream os = this.activity.getContentResolver().openOutputStream(uri);
               if (os != null) {
                  os.write(bytes);
                  os.flush();
                  os.close();
                  return new File("/sdcard/Download/" + fileName);
               }
            }
         } catch (Exception e) {
            Log.w("VC_EXPORT", "MediaStore 写入失败，试直接写: " + e.getMessage());
         }
      }

      try {
         File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
         if (!dir.exists()) {
            dir.mkdirs();
         }

         File out = new File(dir, fileName);
         FileOutputStream fos = new FileOutputStream(out);
         fos.write(bytes);
         fos.flush();
         fos.close();
         return out;
      } catch (Exception e) {
         Log.w("VC_EXPORT", "直接写入也失败: " + e.getMessage());
         return null;
      }
   }

   private long estimateExportTokens() {
      try {
         long total = 0L;
         for (AppDatabase.GmMessage m : this.db.listGmMessages(this.currentRoomId, 1000000)) {
            if ("system".equals(m.kind)) continue;

            String c = AiClient.stripMarkersPublic(m.content).trim();
            if (c.isEmpty()) continue;
            total += AiClient.estimateTokensText(c) + 8L;
         }
         return total;
      } catch (Exception e) {
         return 0L;
      }
   }

   private void pickModelThenExport(Long reExportOf) {
      if (this.currentRoomId > 0L) {
         List<AppDatabase.GmProvider> providers = this.db.listGmProviders();
         if (providers.isEmpty()) {

            String gKey = this.settings.chatApiKey == null ? "" : this.settings.chatApiKey.trim();
            if (gKey.isEmpty()) gKey = this.apiKeyFallbackFor(this.settings.chatBaseUrl);
            if (gKey.isEmpty()) {
               this.showToast("没有可用的 API Key：请到「服务商」添加，或在角色上填一个");
               return;
            }
            this.confirmExport(reExportOf, this.settings.chatBaseUrl, gKey, this.isBlankSafe(this.settings.chatModel) ? "默认模型" : this.settings.chatModel);
         } else {
            List<String[]> items = new ArrayList();

            for(AppDatabase.GmProvider p : providers) {

               String provKey = p.apiKey == null ? "" : p.apiKey.trim();
               if (provKey.isEmpty()) {
                  provKey = this.apiKeyFallbackFor(p.baseUrl);
               }
               for(String m : p.modelList()) {
                  if (m != null && !m.trim().isEmpty()) {
                     items.add(new String[]{p.name + " / " + m, p.baseUrl, provKey, m});
                  }
               }
            }

            if (items.isEmpty()) {
               this.showToast("服务商里还没有模型，请先到「服务商」添加");
            } else {
               String[] names = new String[items.size()];
               String[] subs = new String[items.size()];

               for(int i = 0; i < items.size(); ++i) {
                  names[i] = "\ud83e\udd16 " + ((String[])items.get(i))[0];
                  subs[i] = "用这个模型对全部讨论做高质量提炼";
               }

               this.showStyledListPicker((View)null, "选择提炼用的模型", names, subs, -1, (which) -> {
                  String[] it = (String[])items.get(which);

                  if (it[2] == null || it[2].trim().isEmpty()) {
                     this.showToast("这个服务商没有可用的 API Key：请到「服务商」补上，"
                             + "或在任意角色上填一个同地址的 Key");
                     return;
                  }
                  this.confirmExport(reExportOf, it[1], it[2], it[3]);
               });
            }
         }
      }
   }

   private String apiKeyFallbackFor(String baseUrl) {
      String want = baseUrl == null ? "" : baseUrl.trim();
      if (want.isEmpty()) return "";
      while (want.endsWith("/")) want = want.substring(0, want.length() - 1);
      try {
         for (AppDatabase.GmMember m : this.db.listGmMembers(this.currentRoomId)) {
            String b = m.baseUrl == null ? "" : m.baseUrl.trim();
            while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
            if (!b.equals(want)) continue;
            String k = m.apiKey == null ? "" : m.apiKey.trim();
            if (!k.isEmpty()) return k;
         }
      } catch (Exception ignored) { }
      String gb = this.settings.chatBaseUrl == null ? "" : this.settings.chatBaseUrl.trim();
      while (gb.endsWith("/")) gb = gb.substring(0, gb.length() - 1);
      if (gb.equals(want)) {
         String gk = this.settings.chatApiKey == null ? "" : this.settings.chatApiKey.trim();
         if (!gk.isEmpty()) return gk;
      }
      return "";
   }

   private boolean isBlankSafe(String s) {
      return s == null || s.trim().isEmpty();
   }

   private void confirmExport(Long reExportOf, String baseUrl, String apiKey, String model) {
      boolean[] var10000 = new boolean[]{false};
      LinearLayout box = new LinearLayout(this.activity);
      box.setOrientation(1);
      GradientDrawable bg = new GradientDrawable();
      bg.setColor(this.col(color.surface));
      bg.setCornerRadius((float)this.dp(20));
      bg.setStroke(this.dp(1), this.col(color.border));
      box.setBackground(bg);
      box.setPadding(this.dp(18), this.dp(18), this.dp(18), this.dp(14));
      TextView h = new TextView(this.activity);
      h.setText(reExportOf == null ? "导出实施方案" : "重新导出实施方案");
      h.setTextColor(this.col(color.text));
      h.setTextSize(16.0F);
      h.setTypeface((Typeface)null, 1);
      box.addView(h);
      TextView tip = new TextView(this.activity);

      long approxTok = this.estimateExportTokens();
      String sizeWarn = "";
      if (approxTok > 200000) {
         sizeWarn = "\n\n⚠️ 本次讨论约 " + (approxTok / 10000) + " 万 token，只选上下文足够大的模型。"
                 + "若失败提示「prompt is too long / 输入超长」，就是这个模型装不下，换上下文更大的模型。";
      }

      String replaceWarn = reExportOf == null ? ""
              : "\n\n⚠️ 这是**重新导出**：提炼成功后会**替换掉原来那份方案**（旧的会被删除，无法恢复）。"
              + "想保留旧的请先取消，用「导出」另存一份新的。";
      tip.setText("用「" + model + "」把本群全部讨论提炼成一份实施方案（保留需求、技术方案、界面与交互明细、风险、验收标准等核心内容，去掉寒暄与重复）。\n\n方案会保存到本群设置里，可随时查看、删除或重新导出。" + replaceWarn + sizeWarn);
      tip.setTextColor(this.col(color.muted));
      tip.setTextSize(12.0F);
      tip.setLineSpacing((float)this.dp(3), 1.0F);
      tip.setPadding(0, this.dp(8), 0, 0);
      box.addView(tip);
      CheckBox keepRawCb = new CheckBox(this.activity);
      keepRawCb.setText("文档末尾附上完整讨论原文（默认不附，只留提炼结果）");
      keepRawCb.setTextColor(this.col(color.text));
      keepRawCb.setTextSize(12.0F);
      keepRawCb.setChecked(false);
      LinearLayout.LayoutParams cblp = new LinearLayout.LayoutParams(-1, -2);
      cblp.setMargins(0, this.dp(10), 0, 0);
      box.addView(keepRawCb, cblp);

      TextView thinkLbl = new TextView(this.activity);
      thinkLbl.setText("提炼思考档位");
      thinkLbl.setTextColor(this.col(color.muted));
      thinkLbl.setTextSize(11.0F);
      LinearLayout.LayoutParams tllp = new LinearLayout.LayoutParams(-1, -2);
      tllp.setMargins(0, this.dp(12), 0, 0);
      box.addView(thinkLbl, tllp);
      final int[] thinkSel = new int[]{this.exportThinkLevel};
      LinearLayout thinkRow = new LinearLayout(this.activity);
      thinkRow.setOrientation(0);
      LinearLayout.LayoutParams trlp = new LinearLayout.LayoutParams(-1, -2);
      trlp.setMargins(0, this.dp(4), 0, 0);
      thinkRow.setLayoutParams(trlp);

      final Button[] thinkBtns = new Button[ReasoningNegotiator.LEVEL_COUNT];
      for (int ti = 0; ti < thinkBtns.length; ti++) {
         final int idx = ti;
         Button b = this.ghostButton(ReasoningNegotiator.levelName(ti));
         thinkBtns[ti] = b;
         LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, this.dp(40), 1.0F);
         if (ti > 0) blp.setMarginStart(this.dp(4));
         b.setOnClickListener((v) -> {
            thinkSel[0] = idx;
            this.exportThinkLevel = idx;
            for (int k = 0; k < thinkBtns.length; k++) {
               thinkBtns[k].setBackgroundResource(k == idx ? drawable.btn_primary : drawable.btn_ghost);
               thinkBtns[k].setTextColor(k == idx ? this.col(color.onAccent) : this.col(color.text));
            }
         });
         thinkRow.addView(b, blp);
      }
      thinkBtns[thinkSel[0]].setBackgroundResource(drawable.btn_primary);
      thinkBtns[thinkSel[0]].setTextColor(this.col(color.onAccent));
      box.addView(thinkRow);
      LinearLayout row = new LinearLayout(this.activity);
      row.setGravity(8388613);
      LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
      rlp.setMargins(0, this.dp(14), 0, 0);
      row.setLayoutParams(rlp);
      Button cancel = this.ghostButton("取消");
      Button ok = this.primaryButton("开始提炼", this.col(color.onAccent));
      row.addView(cancel);
      LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-2, -2);
      olp.setMarginStart(this.dp(8));
      row.addView(ok, olp);
      box.addView(row);
      Dialog dlg = this.showDialog(box);
      cancel.setOnClickListener((v) -> dlg.dismiss());
      ok.setOnClickListener((v) -> {
         boolean keep = keepRawCb.isChecked();
         dlg.dismiss();
         this.runExport(reExportOf, baseUrl, apiKey, model, keep);
      });
   }

   private void runExport(Long reExportOf, String baseUrl, String apiKey, String model, boolean keepRawInDoc) {
      AppDatabase.GmRoom room = this.db.getGmRoom(this.currentRoomId);
      if (room != null) {

         List<AppDatabase.GmMessage> msgs = this.db.listGmMessages(this.currentRoomId, 1000000);
         if (msgs.isEmpty()) {
            this.showToast("群里还没有讨论内容");
         } else if (this.exportRunning) {

            AppDatabase.GmRoom busy = this.db.getGmRoom(this.exportLiveRoomId);
            this.showToast("「" + (busy == null || busy.title == null ? "其他群" : busy.title)
                    + "」正在提炼，等它完成后再导出");
         } else {
            long roomId = this.currentRoomId;
            String roomTitle = room.title == null ? "群聊" : room.title;
            this.showToast("正在提炼（模型：" + model + "）…可在本群底部看到进度");
            this.db.setGmRoomExportState(roomId, "running", model);
            synchronized (this.refineLiveBuf) { this.refineLiveBuf.setLength(0); }
            this.refineRoomId = roomId;
            this.exportCancel.set(false);
            this.exportRunning = true;
            this.exportLiveRoomId = roomId;

            this.ui.post(this::renderChat);
            this.bgExecute(this.executor, () -> {

               this.beginBgWork("export");
               try {
                  String docRoleSpeech = "";

                  for(int i = msgs.size() - 1; i >= 0; --i) {
                     AppDatabase.GmMessage m = (AppDatabase.GmMessage)msgs.get(i);
                     if (m.speaker != null && m.speaker.contains("技术文档师") && m.content != null && m.content.length() > 200) {

                        docRoleSpeech = AiClient.stripMarkersPublic(m.content);
                        break;
                     }
                  }

                  StringBuilder log = new StringBuilder();

                  for(AppDatabase.GmMessage m : msgs) {
                     if (!"system".equals(m.kind)) {

                        String body = AiClient.stripMarkersPublic(m.content).trim();
                        if (body.isEmpty()) continue;
                        log.append("[").append(m.speaker == null ? "成员" : m.speaker).append("] ")
                                .append(body).append("\n\n");
                     }
                  }

                  String discussion = log.toString();

                  String plan;
                  try {
                     plan = AiClient.buildImplementationDoc(model, baseUrl, apiKey, roomTitle, docRoleSpeech, discussion, this.exportCancel, this.exportThinkLevel, new AiClient.StreamListener() {
                        public void onReasoningDelta(String delta) {
                           synchronized (refineLiveBuf) { refineLiveBuf.append("💭 ").append(delta); }
                           refreshRefineLive();
                        }
                        public void onContentDelta(String delta) {
                           synchronized (refineLiveBuf) { refineLiveBuf.append(delta); }
                           refreshRefineLive();
                        }
                        public void onToolStart(String name, String argSummary) {
                           synchronized (refineLiveBuf) { refineLiveBuf.append("\n🔧 调用 ").append(name).append("\n"); }
                           refreshRefineLive();
                        }
                        public void onToolEnd(String name, String resultSummary) {
                           synchronized (refineLiveBuf) { refineLiveBuf.append("✅ ").append(name).append(" 返回\n"); }
                           refreshRefineLive();
                        }
                     });
                  } catch (Exception e) {
                     if (this.exportCancel.get()) { this.db.setGmRoomExportState(roomId, "idle", null); this.ui.post(this::renderChat); return; }
                     Log.w("VC_EXPORT", "提炼失败: " + e.getMessage());
                     String err = e.getMessage();
                     this.db.setGmRoomExportState(roomId, "failed", err);
                     this.ui.post(() -> { this.showToast("提炼失败：" + err); this.renderChat(); });
                     return;
                  }

                  if (this.exportCancel.get()) {
                     this.db.setGmRoomExportState(roomId, "idle", null);
                     this.ui.post(this::renderChat);
                     return;
                  }

                  String title = roomTitle + " 实施方案";

                  String body0 = keepRawInDoc ? plan.trim()
                          + "\n\n---\n\n## Appendix: Full Discussion Transcript\n\n" + discussion : plan.trim();
                  String full = body0;
                  if (reExportOf != null) {
                     this.db.deleteGmDoc(reExportOf);
                  }

                  long docId = this.db.insertGmDoc(roomId, title, full, model);
                  this.db.setGmRoomExportState(roomId, "done", model);

                  String outName = roomTitle.replaceAll("[\\\\/:*?\"<>|]", "_") + "_实施方案_"
                          + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(new Date()) + ".txt";
                  final File savedFile = this.saveTextToDownloads(outName, full);
                  final String savedName = savedFile == null ? null : outName;
                  this.ui.post(() -> {
                     this.db.insertGmMessage(roomId, 0L, "系统", "\ud83d\udcc4",
                             "已生成实施方案（" + model + "，约 " + full.length() + " 字）"
                                     + (savedName == null ? "，在「设置 → 实施方案」里查看/导出。"
                                             : "，已保存到 手机存储/Download/" + savedName),
                             false, "system", 0);
                     this.renderChat();
                     this.showPlanReadyDialog(docId);
                  });
               } catch (Exception e) {
                  if (this.exportCancel.get()) { this.db.setGmRoomExportState(roomId, "idle", null); this.ui.post(this::renderChat); return; }
                  String err = e.getMessage();
                  this.db.setGmRoomExportState(roomId, "failed", err);
                  this.ui.post(() -> { this.showToast("导出失败：" + err); this.renderChat(); });
               } finally {
                  this.exportRunning = false;
                  this.exportLiveRoomId = 0L;
                  this.endBgWork("discuss");
               }

            });
         }
      }
   }

   private void refreshRefineLive() {
      this.ui.post(() -> {
         if (this.refineLiveDialog != null && this.refineLiveDialog.isShowing() && this.refineLiveBody != null) {
            String txt;
            synchronized (this.refineLiveBuf) { txt = this.refineLiveBuf.toString(); }

            this.refineLiveBody.setText(txt.isEmpty()
                    ? "\u6b63\u5728\u8fde\u63a5\u6a21\u578b\u2026"
                    : MarkdownRenderer.render(txt, this.col(color.text2), this.col(color.muted),
                            this.col(color.accent), false));

            boolean follow = !this.refineUserTouching && !this.refineFollowPaused;
            this.setRefineJumpVisible(!follow);
            if (this.refineLiveScroll != null && follow) {
               this.refineLiveScroll.post(() -> {
                  if (this.refineLiveScroll != null
                          && !this.refineUserTouching && !this.refineFollowPaused) {
                     this.refineLiveScroll.fullScroll(130);
                  }
               });
            }
         }
      });
   }

   private void setRefineJumpVisible(boolean visible) {
      if (this.refineJumpBtn == null) return;
      int want = visible ? View.VISIBLE : View.GONE;
      if (this.refineJumpBtn.getVisibility() != want) this.refineJumpBtn.setVisibility(want);
   }

   private boolean isRefineNearBottom(ScrollView sv) {
      if (sv == null || sv.getChildCount() == 0) return true;
      View child = sv.getChildAt(0);
      if (child == null) return true;
      int diff = child.getBottom() - (sv.getScrollY() + sv.getHeight());
      return diff <= this.dp(120);
   }

   private void showRefineLiveView(long roomId) {
      LinearLayout box = new LinearLayout(this.activity);
      box.setOrientation(1);
      GradientDrawable bg = new GradientDrawable();
      bg.setColor(this.col(color.surface));
      bg.setCornerRadius((float) this.dp(18));
      bg.setStroke(this.dp(1), this.col(color.border));
      box.setBackground(bg);
      box.setPadding(this.dp(16), this.dp(16), this.dp(16), this.dp(12));
      TextView h = new TextView(this.activity);
      h.setText("\ud83d\udd04 \u63d0\u70bc\u8fc7\u7a0b\uff08\u5b9e\u65f6\uff09");
      h.setTextColor(this.col(color.text));
      h.setTextSize(15.0F);
      h.setTypeface((Typeface) null, 1);
      box.addView(h);
      TextView tip = new TextView(this.activity);
      tip.setText("\u6b63\u5728\u628a\u5168\u90e8\u8ba8\u8bba\u63d0\u70bc\u6210\u5b9e\u65bd\u65b9\u6848\uff0c\u4e0b\u9762\u662f\u6a21\u578b\u7684\u5b9e\u65f6\u8f93\u51fa\uff08\ud83d\udcad=\u601d\u8003\uff0c\u5176\u4f59=\u6b63\u6587\uff09\u3002\u5185\u5bb9\u5728\u6eda\u52a8=\u6ca1\u5361\u4f4f\uff1b\u5173\u95ed\u672c\u7a97\u4e0d\u5f71\u54cd\u63d0\u70bc\u3002\n"
              + "\ud83d\udc46 \u4f60\u53ef\u4ee5\u968f\u65f6\u4e0a\u4e0b\u6ed1\u7ffb\u770b\uff1a\u4e00\u78b0\u5c4f\u5c31\u505c\u6b62\u81ea\u52a8\u8ddf\u968f\uff0c\u4e0d\u4f1a\u518d\u628a\u4f60\u62c9\u56de\u5e95\u90e8\u3002");
      tip.setTextColor(this.col(color.muted));
      tip.setTextSize(11.0F);
      tip.setPadding(0, this.dp(4), 0, this.dp(8));
      box.addView(tip);
      ScrollView sv = new ScrollView(this.activity);
      TextView body = new TextView(this.activity);
      String init;
      synchronized (this.refineLiveBuf) { init = this.refineLiveBuf.toString(); }
      body.setText(init.isEmpty() ? "\u6b63\u5728\u8fde\u63a5\u6a21\u578b\u2026"
              : MarkdownRenderer.render(init, this.col(color.text2), this.col(color.muted),
                      this.col(color.accent), false));
      body.setTextColor(this.col(color.text2));
      body.setTextSize(12.0F);
      body.setTextIsSelectable(true);
      body.setLineSpacing((float) this.dp(2), 1.1F);
      sv.addView(body);
      LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 0, 1.0F);

      this.refineUserTouching = false;
      this.refineFollowPaused = false;
      sv.setOnTouchListener((v, ev) -> {
         int a = ev.getActionMasked();
         if (a == android.view.MotionEvent.ACTION_DOWN) {
            this.refineUserTouching = true;
         } else if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) {
            this.refineUserTouching = false;

            sv.post(() -> this.refineFollowPaused = !this.isRefineNearBottom(sv));
            this.setRefineJumpVisible(this.refineFollowPaused);
         }
         return false;
      });
      sv.setOnScrollChangeListener((v, sx, sy, osx, osy) -> {
         if (!this.refineUserTouching) return;
         this.refineFollowPaused = !this.isRefineNearBottom(sv);
      });

      FrameLayout holder = new FrameLayout(this.activity);
      holder.addView(sv, new FrameLayout.LayoutParams(-1, -1));
      TextView jump = this.ghostButton("\u2193 \u56de\u5230\u6700\u65b0");
      jump.setTextColor(this.col(color.accent));
      jump.setVisibility(View.GONE);
      jump.setOnClickListener((v2) -> {
         this.refineFollowPaused = false;
         this.setRefineJumpVisible(false);
         if (this.refineLiveScroll != null) this.refineLiveScroll.fullScroll(130);
      });
      FrameLayout.LayoutParams jlp = new FrameLayout.LayoutParams(-2, -2);
      jlp.gravity = 8388693;
      jlp.setMargins(0, 0, this.dp(4), this.dp(8));
      holder.addView(jump, jlp);
      this.refineJumpBtn = jump;
      box.addView(holder, slp);
      Button close = this.ghostButton("\u5173\u95ed");
      close.setOnClickListener((v) -> {
         if (this.refineLiveDialog != null) this.refineLiveDialog.dismiss();
      });
      LinearLayout row = new LinearLayout(this.activity);
      row.setGravity(8388613);
      row.addView(close);
      box.addView(row, new LinearLayout.LayoutParams(-1, -2));
      Dialog dlg = new Dialog(this.activity);
      dlg.setContentView(box);
      if (dlg.getWindow() != null) {
         dlg.getWindow().setBackgroundDrawable(new ColorDrawable(0));
         WindowManager.LayoutParams lp = dlg.getWindow().getAttributes();
         lp.width = (int) (this.activity.getResources().getDisplayMetrics().widthPixels * 0.94F);
         lp.height = (int) (this.activity.getResources().getDisplayMetrics().heightPixels * 0.8F);
         dlg.getWindow().setAttributes(lp);
      }
      this.refineLiveDialog = dlg;
      this.refineLiveBody = body;
      this.refineLiveScroll = sv;
      dlg.setOnDismissListener((d) -> {
         this.ui.removeCallbacksAndMessages(REFINE_LIVE_TOKEN);
         this.refineLiveDialog = null;
         this.refineLiveBody = null;
         this.refineLiveScroll = null;
         this.refineJumpBtn = null;
         this.refineUserTouching = false;
         this.refineFollowPaused = false;
      });
      dlg.show();
      this.ui.postAtTime(this.refineLiveTick, REFINE_LIVE_TOKEN, android.os.SystemClock.uptimeMillis() + 700L);
   }

   private static final Object REFINE_LIVE_TOKEN = new Object();
   private final Runnable refineLiveTick = new Runnable() {
      public void run() {
         if (GroupChatManager.this.refineLiveDialog != null && GroupChatManager.this.refineLiveDialog.isShowing()) {
            GroupChatManager.this.refreshRefineLive();
            GroupChatManager.this.ui.postAtTime(this, REFINE_LIVE_TOKEN, android.os.SystemClock.uptimeMillis() + 700L);
         }
      }
   };

   private void exportFullTranscript() {
      long roomId = this.currentRoomId;
      if (roomId <= 0L) return;
      String doc = this.buildFullTranscript(roomId);
      if (doc.isEmpty()) { this.showToast("\u8fd8\u6ca1\u6709\u5bf9\u8bdd\u5185\u5bb9"); return; }
      AppDatabase.GmRoom room = this.db.getGmRoom(roomId);
      String base = (room == null || room.title == null ? "\u7fa4\u804a" : room.title);
      String title = base + " \u5168\u90e8\u5bf9\u8bdd";
      long docId = this.db.insertGmDoc(roomId, title, doc, "\u5168\u91cf\u5bf9\u8bdd\u539f\u6587");

      this.saveExportToDownloads(base + "_全部对话", doc, "全部对话原文");
      this.renderChat();
   }

   private String buildFullTranscript(long roomId) {
      AppDatabase.GmRoom room = this.db.getGmRoom(roomId);
      String roomTitle = room == null || room.title == null ? "\u7fa4\u804a" : room.title;
      List<AppDatabase.GmMessage> msgs = this.db.listGmMessages(roomId, 100000);
      StringBuilder sb = new StringBuilder("# " + roomTitle + " \u00b7 \u5168\u90e8\u5bf9\u8bdd\u8bb0\u5f55\n\n");
      SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
      int n = 0;
      for (AppDatabase.GmMessage m : msgs) {
         String body = AiClient.stripMarkersPublic(m.content).trim();
         if (body.isEmpty()) continue;
         String who = m.speaker == null ? "\u6210\u5458" : m.speaker;
         sb.append("**#").append(m.seq).append(" ").append(who).append("** \u3014").append(fmt.format(new Date(m.createdAt))).append("\u3015\n\n");
         sb.append(body).append("\n\n---\n\n");
         n++;
      }
      return n == 0 ? "" : sb.toString();
   }

   private void startExportFlow(Long reExportOf) {
      long roomId = this.currentRoomId;
      if (roomId <= 0L) return;

      String[] opts = new String[]{
              "\ud83e\udd16 大模型提炼实施方案（选模型把全部讨论提炼成一份方案）",
              "\ud83d\udcac 全部对话原文（所有发言存成文档文件，不调模型）"};
      this.showActionSheet("\u5bfc\u51fa\u54ea\u79cd\uff1f", opts, (w) -> {
         if (w == 0) this.pickModelThenExport(reExportOf);
         else this.exportFullTranscript();
      });
   }

   private void runClarification(final long roomId, final String asker, final String question,
                                 final String priorLog, final String ask,
                                 final java.util.function.BiConsumer<String, String> onDone) {
      this.bgExecute(this.executor, () -> {

         this.beginBgWork("clarify");
         try {
            AppDatabase.GmMember gm = null;
            for (AppDatabase.GmMember mm : this.db.listGmMembers(roomId)) {
               if (mm.name != null && mm.name.equals(asker)) { gm = mm; break; }
            }
            String base = gm != null && gm.baseUrl != null && !gm.baseUrl.trim().isEmpty() ? gm.baseUrl.trim() : this.settings.chatBaseUrl;
            String key = gm != null && gm.apiKey != null && !gm.apiKey.trim().isEmpty() ? gm.apiKey : this.settings.chatApiKey;
            String mdl = gm != null && gm.model != null && !gm.model.trim().isEmpty() ? gm.model : this.settings.chatModel;
            if (base == null || base.trim().isEmpty()) base = "https://dashscope.aliyuncs.com/compatible-mode/v1";

            StringBuilder sysB = new StringBuilder();
            sysB.append("你是群聊里的「").append(asker == null ? "成员" : asker).append("」。\n");
            if (gm != null && gm.roleDesc != null && !gm.roleDesc.trim().isEmpty()) {
               sysB.append("你的身份定位：").append(gm.roleDesc.trim()).append("\n");
            }
            if (gm != null && gm.prompt != null && !gm.prompt.trim().isEmpty()) {

               sysB.append("【你的角色设定（必须遵守）】\n").append(gm.prompt.trim()).append("\n");
            }

            String goalLine = "";
            try {
               AppDatabase.GmRoom rr = this.db.getGmRoom(roomId);
               List<AppDatabase.GmMessage> all = this.db.listGmMessages(roomId, 500);
               String anchor = rr != null && rr.goalText != null && !rr.goalText.trim().isEmpty()
                       ? rr.goalText.trim() : this.firstUserAsk(all);
               if (anchor != null && !anchor.isEmpty()) {

                  goalLine = anchor;
               }
            } catch (Exception ignored) { }
            if (!goalLine.isEmpty()) {
               sysB.append("【本群讨论目标】").append(goalLine).append("\n");
            }

            try {
               String ctx = this.buildRoomContext(roomId, 1000000, gm != null ? gm.id : 0L, 0);
               if (ctx != null && !ctx.trim().isEmpty()) {
                  sysB.append("\n───── 本群完整讨论记录（你参加过，请据此回答）─────\n");
                  sysB.append(ctx).append("\n");
               }
            } catch (Exception ignored) { }

            if (priorLog != null && !priorLog.trim().isEmpty()) {
               sysB.append("\n───── 这道题此前你与用户的反问往来（你解释过的不要原样重复，接着往下答）─────\n")
                   .append(priorLog.trim()).append("\n");
            }
            sysB.append("\n───── 现在用户在看不懂你提的某个问题，向你反问 ─────\n")
                .append("要求：① 站在你原有的专业身份上回答，不要脱离讨论内容空泛解释；")
                .append("② 用大白话把那道问题重新讲清楚（可以举例子、可以说清为什么这么问）；")
                .append("③ 如果用户的反问暴露了你之前的假设有误，要指出来并修正；")
                .append("④ 简短、口语，不要长篇大论。\n");

            String sys = sysB.toString();
            String usr = "你的原问题：" + (question == null ? "" : question) + "\n用户的追问：" + ask;
            JSONObject b = new JSONObject();
            b.put("model", mdl);
            org.json.JSONArray ms = new org.json.JSONArray();
            ms.put(new JSONObject().put("role", "system").put("content", sys));
            ms.put(new JSONObject().put("role", "user").put("content", usr));
            b.put("messages", ms);
            b.put("stream", false);
            b.put("temperature", 0.5);
            b.put("max_tokens", 110000);
            b.put("enable_thinking", false);
            String reply = AiClient.simpleChat(base, key, b.toString());
            if (reply == null || reply.trim().isEmpty()) reply = "（没返回内容）";

            this.db.insertGmMessage(roomId, 0L, "我", "\ud83d\ude4b", "【我的反问】" + ask, false, "chat", 0);
            long gmId = gm != null ? gm.id : 0L;
            String av = gm != null && gm.avatar != null ? gm.avatar : "\ud83e\udd16";
            this.db.insertGmMessage(roomId, gmId, asker == null ? "成员" : asker, av,
                    "【回应反问】" + reply.trim(), false, "chat", 0);
            final String rp = reply.trim();
            this.ui.post(() -> {
               this.renderChat();
               if (onDone != null) onDone.accept(rp, null);
            });
         } catch (Exception e) {
            final String em = e.getMessage();
            this.ui.post(() -> { if (onDone != null) onDone.accept(null, em); });
         } finally {
            this.endBgWork("discuss");
         }
      });
   }

   private void showPlanReadyDialog(long docId) {

      AppDatabase.GmDoc doc = this.db.getGmDoc(docId);
      android.util.Log.i("VC_EXPORT", "文档已生成 id=" + docId
              + " title=" + (doc == null ? "?" : doc.title));
   }

   private void showUsagePanel() {
      if (!this.inHub) this.currentPage = 3;
      if (this.currentRoomId <= 0L) return;
      AppDatabase.GmRoom room = this.db.getGmRoom(this.currentRoomId);
      if (room == null) return;

      this.setHeaderButtons(false);
      this.renderTarget.removeAllViews();
      LinearLayout root = new LinearLayout(this.activity);
      root.setOrientation(1);
      this.renderTarget.addView(root, new FrameLayout.LayoutParams(-1, -1));

      LinearLayout bar = new LinearLayout(this.activity);
      bar.setOrientation(0);
      bar.setGravity(16);
      bar.setPadding(this.dp(14), this.dp(10), this.dp(14), this.dp(4));
      TextView title = new TextView(this.activity);
      title.setText("\ud83d\udcca 用量统计 · " + (room.title == null ? "群聊" : room.title));
      title.setTextColor(this.col(color.text));
      title.setTextSize(15.0F);
      title.setTypeface((Typeface) null, 1);
      bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1.0F));

      Button refresh = this.ghostButton("\ud83d\udd04 刷新");
      refresh.setTextColor(this.col(color.accent));
      refresh.setTextSize(12.0F);
      bar.addView(refresh);

      final android.widget.Switch liveSw = new android.widget.Switch(this.activity);
      liveSw.setText("实时");
      liveSw.setTextSize(12.0F);
      liveSw.setTextColor(this.col(color.text2));
      LinearLayout.LayoutParams lslp = new LinearLayout.LayoutParams(-2, -2);
      lslp.setMarginStart(this.dp(8));
      bar.addView(liveSw, lslp);
      root.addView(bar);

      ScrollView sv = new ScrollView(this.activity);
      LinearLayout list = new LinearLayout(this.activity);
      list.setOrientation(1);
      list.setPadding(this.dp(14), 0, this.dp(14), this.dp(14));
      sv.addView(list);
      root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1.0F));

      final Runnable render = new Runnable() {
         @Override
         public void run() {
            if (GroupChatManager.this.viewRoomId != GroupChatManager.this.currentRoomId) return;

            try { GroupChatManager.this.db.reconcileGmMemberUsage(GroupChatManager.this.currentRoomId); }
            catch (Exception ignored) { }
            list.removeAllViews();
            List<AppDatabase.GmMember> members = GroupChatManager.this.db.listGmMembers(GroupChatManager.this.currentRoomId);
            if (members.isEmpty()) {
               TextView empty = new TextView(GroupChatManager.this.activity);
               empty.setText("这个群还没有成员。");
               empty.setTextColor(GroupChatManager.this.col(color.muted));
               empty.setTextSize(12.0F);
               list.addView(empty);
               return;
            }

            java.util.LinkedHashMap<String, long[]> byModel = new java.util.LinkedHashMap<>();
            long totalTokens = 0L;
            int totalMsgs = 0;
            for (AppDatabase.GmMember m : members) {

               if (m.usedMsgs <= 0 && m.usedTokens <= 0) continue;
               String mdl = (m.model == null || m.model.trim().isEmpty()) ? "(未设模型)" : m.model.trim();
               long[] agg = byModel.get(mdl);
               if (agg == null) { agg = new long[]{0L, 0L, 0L}; byModel.put(mdl, agg); }
               agg[0] += m.usedTokens;
               agg[1] += m.usedMsgs;
               agg[2] += 1;
               totalTokens += m.usedTokens;
               totalMsgs += m.usedMsgs;
            }

            LinearLayout sumCard = new LinearLayout(GroupChatManager.this.activity);
            sumCard.setOrientation(1);
            GradientDrawable sbg = new GradientDrawable();
            sbg.setColor(0x1A2F6BE6);
            sbg.setCornerRadius((float) GroupChatManager.this.dp(14));
            sbg.setStroke(GroupChatManager.this.dp(1), GroupChatManager.this.col(color.accent));
            sumCard.setBackground(sbg);
            sumCard.setPadding(GroupChatManager.this.dp(14), GroupChatManager.this.dp(12),
                    GroupChatManager.this.dp(14), GroupChatManager.this.dp(12));
            TextView st = new TextView(GroupChatManager.this.activity);
            st.setText("本群累计消耗 " + GroupChatManager.this.fmtTokens(totalTokens)
                    + " · 发言 " + totalMsgs + " 次 · 涉及 " + byModel.size() + " 个模型");
            st.setTextColor(GroupChatManager.this.col(color.text));
            st.setTextSize(14.0F);
            st.setTypeface((Typeface) null, 1);
            sumCard.addView(st);

            try {
               long poolTok = GroupChatManager.this.db.sumGmPublicTokens(GroupChatManager.this.currentRoomId);
               TextView pool = new TextView(GroupChatManager.this.activity);
               pool.setText("\ud83d\udcda 共享讨论 " + GroupChatManager.this.fmtTokens(poolTok)
                       );
               pool.setTextColor(GroupChatManager.this.col(color.text2));
               pool.setTextSize(11.5F);
               pool.setPadding(0, GroupChatManager.this.dp(6), 0, 0);
               pool.setLineSpacing((float) GroupChatManager.this.dp(2), 1.05F);
               sumCard.addView(pool);
            } catch (Exception ignored) { }
            TextView ctxHint = new TextView(GroupChatManager.this.activity);

            ctxHint.setVisibility(android.view.View.GONE);
            ctxHint.setTextColor(GroupChatManager.this.col(color.muted));
            ctxHint.setTextSize(11.0F);
            ctxHint.setPadding(0, GroupChatManager.this.dp(4), 0, 0);
            sumCard.addView(ctxHint);
            LinearLayout.LayoutParams sclp = new LinearLayout.LayoutParams(-1, -2);
            sclp.setMargins(0, 0, 0, GroupChatManager.this.dp(10));
            list.addView(sumCard, sclp);

            java.util.List<java.util.Map.Entry<String, long[]>> sorted =
                    new java.util.ArrayList<>(byModel.entrySet());
            java.util.Collections.sort(sorted, (a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
            for (java.util.Map.Entry<String, long[]> e : sorted) {
               long[] v = e.getValue();
               LinearLayout card = new LinearLayout(GroupChatManager.this.activity);
               card.setOrientation(1);
               GradientDrawable cbg = new GradientDrawable();
               cbg.setColor(GroupChatManager.this.col(color.surface));
               cbg.setCornerRadius((float) GroupChatManager.this.dp(12));
               cbg.setStroke(GroupChatManager.this.dp(1), GroupChatManager.this.col(color.border));
               card.setBackground(cbg);
               card.setPadding(GroupChatManager.this.dp(12), GroupChatManager.this.dp(10),
                       GroupChatManager.this.dp(12), GroupChatManager.this.dp(10));
               TextView mt = new TextView(GroupChatManager.this.activity);
               mt.setText("\ud83e\udd16 " + e.getKey());
               mt.setTextColor(GroupChatManager.this.col(color.text));
               mt.setTextSize(13.0F);
               mt.setTypeface((Typeface) null, 1);
               card.addView(mt);
               TextView ms = new TextView(GroupChatManager.this.activity);
               ms.setText("消耗 " + GroupChatManager.this.fmtTokens(v[0]) + "  ·  发言 " + v[1] + " 次  ·  "
                       + v[2] + " 个角色在用   ·  占本群 "
                       + (totalTokens > 0 ? (v[0] * 100 / totalTokens) : 0) + "%");
               ms.setTextColor(GroupChatManager.this.col(color.text2));
               ms.setTextSize(11.5F);
               ms.setPadding(0, GroupChatManager.this.dp(4), 0, 0);
               card.addView(ms);
               LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
               clp.setMargins(0, GroupChatManager.this.dp(8), 0, 0);
               list.addView(card, clp);
            }

            if (!members.isEmpty()) {

               final java.util.HashMap<Long, Long> toolTokOf =
                       GroupChatManager.this.db.mapGmMemberToolTokens(GroupChatManager.this.currentRoomId);

               final long sharedCtxTok =
                       GroupChatManager.this.db.sumGmPublicTokens(GroupChatManager.this.currentRoomId);
               final long ctxLimit = room.contextLimit <= 0 ? 1000000L : room.contextLimit;
               final LinearLayout roleBox = new LinearLayout(GroupChatManager.this.activity);
               roleBox.setOrientation(1);
               roleBox.setVisibility(8);
               final TextView roleHead = new TextView(GroupChatManager.this.activity);
               roleHead.setText("👥 各角色明细  ▾");
               roleHead.setTextColor(GroupChatManager.this.col(color.accent));
               roleHead.setTextSize(12.0F);
               roleHead.setTypeface((Typeface) null, 1);
               roleHead.setPadding(0, GroupChatManager.this.dp(14), 0, GroupChatManager.this.dp(6));
               roleHead.setOnClickListener((vv) -> {
                  boolean show = roleBox.getVisibility() != 0;
                  if (show && roleBox.getChildCount() == 0) {
                     List<AppDatabase.GmMember> ms = GroupChatManager.this.db.listGmMembers(GroupChatManager.this.currentRoomId);

                     java.util.Collections.sort(ms, (a, b) -> Long.compare(b.usedTokens, a.usedTokens));
                     for (AppDatabase.GmMember m : ms) {

                        LinearLayout rCard = new LinearLayout(GroupChatManager.this.activity);
                        rCard.setOrientation(1);
                        GradientDrawable rbg = new GradientDrawable();
                        rbg.setColor(GroupChatManager.this.col(color.surface));
                        rbg.setCornerRadius((float) GroupChatManager.this.dp(12));
                        rbg.setStroke(GroupChatManager.this.dp(1), GroupChatManager.this.col(color.border));
                        rCard.setBackground(rbg);
                        rCard.setPadding(GroupChatManager.this.dp(12), GroupChatManager.this.dp(10),
                                GroupChatManager.this.dp(12), GroupChatManager.this.dp(10));
                        String mdl = (m.model == null || m.model.trim().isEmpty()) ? "(未设)" : m.model.trim();
                        TextView rn = new TextView(GroupChatManager.this.activity);
                        rn.setText((m.avatar == null ? "" : m.avatar) + " " + m.name + (m.active ? "" : "（待命）"));
                        rn.setTextColor(GroupChatManager.this.col(color.text));
                        rn.setTextSize(13.5F);
                        rn.setTypeface((Typeface) null, 1);
                        rCard.addView(rn);

                  long personaTok = AiClient.estimateTokensText(m.prompt);
                  long toolTok = toolTokOf.get(m.id) == null ? 0L : toolTokOf.get(m.id);
                  long memTok = 0L;
                  boolean memDrop = false;
                  try {
                     AppDatabase.GmAgentMemory mm = GroupChatManager.this.db.getGmAgentMemory(
                             GroupChatManager.this.currentRoomId, m.id);
                     if (mm != null) {
                        memTok = AiClient.estimateTokensText(mm.content);
                        memDrop = mm.dropRaw;
                     }
                  } catch (Exception ignored) { }
                  long ownTok = personaTok + toolTok + memTok;
                  long realtime = sharedCtxTok + ownTok;
                  int pct = (int) Math.min(100L, realtime * 100L / Math.max(1L, ctxLimit));

                  TextView rctx = new TextView(GroupChatManager.this.activity);
                  int cCtx = pct >= 80 ? 0xFFD32F2F : (pct >= 50 ? 0xFFE07B00 : GroupChatManager.this.col(color.accent));
                  rctx.setText("\ud83d\udcca 实时上下文占用 " + GroupChatManager.this.fmtTokens(realtime)
                          + "　/　上限 " + GroupChatManager.this.fmtTokens(ctxLimit)
                          + "（" + pct + "%）");
                  rctx.setTextColor(cCtx);
                  rctx.setTextSize(13.5F);
                  rctx.setTypeface((Typeface) null, 1);
                  rctx.setPadding(0, GroupChatManager.this.dp(6), 0, 0);
                  rCard.addView(rctx);

                  TextView rsub = new TextView(GroupChatManager.this.activity);

                  StringBuilder rsubSb = new StringBuilder();
                  rsubSb.append("　├ 共享讨论 ").append(GroupChatManager.this.fmtTokens(sharedCtxTok))
                        .append("（各角色相同）\n")
                        .append("　└ 它自己 ").append(GroupChatManager.this.fmtTokens(ownTok))
                        .append("（基础提示词 ").append(GroupChatManager.this.fmtTokens(personaTok))
                        .append(" + 资料 ").append(GroupChatManager.this.fmtTokens(toolTok + memTok))
                        .append("，其中原文 ").append(GroupChatManager.this.fmtTokens(toolTok))
                        .append(" + 结论 ").append(GroupChatManager.this.fmtTokens(memTok))
                        .append("；资料额度共 ").append(AiClient.TOOLS_DETAIL_TOKEN_CAP / 10000)
                        .append(" 万）");
                  if (memDrop) {

                     rsubSb.append("\n　　（该角色已把原始资料压成结论，原文不再注入）");
                  }
                  rsub.setText(rsubSb.toString());
                  rsub.setTextColor(GroupChatManager.this.col(color.text2));
                  rsub.setTextSize(11.5F);
                  rsub.setPadding(0, GroupChatManager.this.dp(3), 0, 0);
                  rsub.setLineSpacing((float) GroupChatManager.this.dp(2), 1.05F);
                  rCard.addView(rsub);

                        android.widget.ProgressBar pb = new android.widget.ProgressBar(
                                GroupChatManager.this.activity, null, android.R.attr.progressBarStyleHorizontal);
                        pb.setMax(100);
                        pb.setProgress(pct);
                        LinearLayout.LayoutParams pblp = new LinearLayout.LayoutParams(-1, GroupChatManager.this.dp(6));
                        pblp.setMargins(0, GroupChatManager.this.dp(6), 0, 0);
                        rCard.addView(pb, pblp);

                        LinearLayout row1 = GroupChatManager.this.statRow(
                                "token 消耗用量", GroupChatManager.this.fmtTokens(m.usedTokens),
                                "（累计 · 发言 " + m.usedMsgs + " 次）");
                        rCard.addView(row1);

                        TextView rmdl = new TextView(GroupChatManager.this.activity);
                        rmdl.setText("\ud83e\udd16 " + mdl);
                        rmdl.setTextColor(GroupChatManager.this.col(color.text2));
                        rmdl.setTextSize(11.5F);
                        rmdl.setPadding(0, GroupChatManager.this.dp(6), 0, 0);
                        rCard.addView(rmdl);

                        LinearLayout.LayoutParams rclp = new LinearLayout.LayoutParams(-1, -2);
                        rclp.setMargins(0, 0, 0, GroupChatManager.this.dp(8));
                        roleBox.addView(rCard, rclp);
                     }
                  }
                  roleBox.setVisibility(show ? 0 : 8);
                  roleHead.setText("👥 各角色明细  " + (show ? "▴" : "▾"));
               });
               list.addView(roleHead);
               list.addView(roleBox);
            }

            TextView foot = new TextView(GroupChatManager.this.activity);

            foot.setVisibility(android.view.View.GONE);
            foot.setTextColor(GroupChatManager.this.col(color.text2));
            foot.setTextSize(11.0F);
            foot.setPadding(0, GroupChatManager.this.dp(14), 0, 0);
            list.addView(foot);
         }
      };

      refresh.setOnClickListener((v) -> render.run());
      render.run();

      final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
      final Runnable[] tick = new Runnable[1];
      tick[0] = () -> {
         if (!liveSw.isChecked()) return;
         if (GroupChatManager.this.currentPage != 3) return;
         render.run();
         h.postDelayed(tick[0], 3000L);
      };
      liveSw.setOnCheckedChangeListener((b, checked) -> {
         if (checked) {
            GroupChatManager.this.showToast("实时跟随已开（每 3 秒刷新）");
            h.postDelayed(tick[0], 3000L);
         }
      });

      this.usageStopTicker = () -> h.removeCallbacks(tick[0]);
   }

   private Runnable usageStopTicker;

   private void stopUsageTicker() {
      Runnable r = this.usageStopTicker;
      if (r != null) {
         try { r.run(); } catch (Exception ignored) { }
         this.usageStopTicker = null;
      }
   }

   private String fmtTokens(long n) {
      if (n < 1000) return String.valueOf(n);
      if (n < 1_000_000L) return String.format(java.util.Locale.CHINA, "%.1fk", n / 1000.0);
      return String.format(java.util.Locale.CHINA, "%.2fM", n / 1_000_000.0);
   }

   private LinearLayout statRow(String label, String value, String note) {
      LinearLayout row = new LinearLayout(this.activity);
      row.setOrientation(0);
      row.setGravity(16);
      GradientDrawable bg = new GradientDrawable();
      bg.setColor(this.col(color.surfaceInput));
      bg.setCornerRadius((float) this.dp(8));
      bg.setStroke(this.dp(1), this.col(color.border));
      row.setBackground(bg);
      row.setPadding(this.dp(10), this.dp(7), this.dp(10), this.dp(7));

      TextView lb = new TextView(this.activity);
      lb.setText(label);

      lb.setTextColor(this.col(color.text));
      lb.setTextSize(12.0F);
      row.addView(lb);

      if (value != null && !value.isEmpty()) {
         TextView vl = new TextView(this.activity);
         vl.setText(value);
         vl.setTextColor(this.col(color.accent));
         vl.setTextSize(13.5F);
         vl.setTypeface((Typeface) null, 1);
         LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-2, -2);
         vlp.setMarginStart(this.dp(8));
         row.addView(vl, vlp);
      }

      if (note != null && !note.isEmpty()) {
         TextView nt = new TextView(this.activity);
         nt.setText(note);
         nt.setTextColor(this.col(color.text2));
         nt.setTextSize(11.0F);
         LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
         nlp.setMarginStart(this.dp(6));
         row.addView(nt, nlp);
      }

      LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
      rlp.setMargins(0, this.dp(6), 0, 0);
      row.setLayoutParams(rlp);
      return row;
   }

   private void showPlanList() {
      this.stopUsageTicker();
      if (this.currentRoomId > 0L) {
         if (!this.inHub) this.currentPage = 5;
         this.setHeaderButtons(false);
         this.renderTarget.removeAllViews();
         LinearLayout root = new LinearLayout(this.activity);
         root.setOrientation(1);
         this.renderTarget.addView(root, new FrameLayout.LayoutParams(-1, -1));
         LinearLayout barRow = new LinearLayout(this.activity);
         barRow.setOrientation(0);
         barRow.setGravity(16);
         barRow.addView(new View(this.activity), new LinearLayout.LayoutParams(0, 1, 1.0F));
         Button newBtn = this.primaryButton("＋ 导出新方案", this.col(color.onAccent));
         newBtn.setOnClickListener((v) -> this.startExportFlow((Long)null));
         barRow.addView(newBtn);
         root.addView(barRow);
         TextView hint = new TextView(this.activity);
         hint.setText("对本群讨论做全量提炼后保存的实施方案。");
         hint.setTextColor(this.col(color.muted));
         hint.setTextSize(11.0F);
         hint.setLineSpacing((float)this.dp(2), 1.0F);
         hint.setPadding(0, this.dp(8), 0, 0);
         root.addView(hint);
         List<AppDatabase.GmDoc> docs = this.db.listGmDocs(this.currentRoomId);
         if (docs.isEmpty()) {
            TextView empty = new TextView(this.activity);
            empty.setText("还没有导出过方案。");
            empty.setTextColor(this.col(color.muted));
            empty.setTextSize(13.0F);
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-1, -2);
            elp.setMargins(0, this.dp(12), 0, 0);
            root.addView(empty, elp);
         } else {
            ScrollView sv = new ScrollView(this.activity);
            LinearLayout list = new LinearLayout(this.activity);
            list.setOrientation(1);
            sv.addView(list);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 0, 1.0F);
            slp.setMargins(0, this.dp(8), 0, 0);
            root.addView(sv, slp);
            SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);

            for(AppDatabase.GmDoc d : docs) {
               LinearLayout card = new LinearLayout(this.activity);
               card.setOrientation(1);
               GradientDrawable bg = new GradientDrawable();
               bg.setColor(this.col(color.surface));
               bg.setCornerRadius((float)this.dp(14));
               bg.setStroke(this.dp(1), this.col(color.border));
               card.setBackground(bg);
               card.setPadding(this.dp(14), this.dp(12), this.dp(14), this.dp(12));

               String typeTag; int tagBg; int tagFg;
               String mn0 = d.modelName == null ? "" : d.modelName.trim();

               typeTag = this.exportKindTagText(mn0);
               int[] tagStyle = this.exportKindTagStyle(mn0);
               tagBg = tagStyle[0]; tagFg = tagStyle[1];
               TextView tag = new TextView(this.activity);
               tag.setText(typeTag);
               tag.setTextColor(tagFg);
               tag.setTextSize(11.5F);
               tag.setTypeface((Typeface)null, 1);
               GradientDrawable tbg = new GradientDrawable();
               tbg.setColor(tagBg);
               tbg.setCornerRadius((float)this.dp(7));
               tag.setBackground(tbg);

               tag.setPadding(this.dp(9), this.dp(4), this.dp(9), this.dp(4));
               LinearLayout.LayoutParams tglp = new LinearLayout.LayoutParams(-2, -2);
               tglp.setMargins(0, 0, 0, this.dp(7));
               card.addView(tag, tglp);

               TextView t = new TextView(this.activity);
               t.setText("\ud83d\udcc4 " + (d.title == null ? "实施方案" : d.title));
               t.setTextColor(this.col(color.text));
               t.setTextSize(15.0F);
               t.setTypeface((Typeface)null, 1);
               card.addView(t);
               TextView sub = new TextView(this.activity);

               boolean isRealModel = !mn0.isEmpty() && !"全量对话原文".equals(mn0);
               sub.setText(fmt.format(new Date(d.createdAt))
                       + (isRealModel ? " · 模型：" + mn0 : "")
                       + " · " + d.contentLength + " 字 · 完整内容见 Download 里的 TXT");
               sub.setTextColor(this.col(color.muted));
               sub.setTextSize(11.0F);
               sub.setPadding(0, this.dp(4), 0, 0);
               card.addView(sub);
               TextView preview = new TextView(this.activity);

               String pc = this.db.getGmDocPreview(d.id, 160);
               if (pc.length() > 160) {
                  pc = pc.substring(0, 160) + "…";
               }

               preview.setText(pc);
               preview.setTextColor(this.col(color.text2));
               preview.setTextSize(11.0F);
               preview.setLineSpacing((float)this.dp(2), 1.0F);
               preview.setPadding(0, this.dp(6), 0, 0);
               card.addView(preview);
               LinearLayout row = new LinearLayout(this.activity);
               row.setGravity(8388613);
               LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
               rlp.setMargins(0, this.dp(10), 0, 0);
               row.setLayoutParams(rlp);
               Button del = this.ghostButton("删除");
               del.setTextColor(this.col(color.danger));
               del.setOnClickListener((v) -> this.confirmDeleteDoc(d));
               row.addView(del);
               Button redo = this.ghostButton("重新导出");
               redo.setTextColor(this.col(color.accent));
               LinearLayout.LayoutParams r2 = new LinearLayout.LayoutParams(-2, -2);
               r2.setMarginStart(this.dp(8));
               redo.setOnClickListener((v) -> this.pickModelThenExport(d.id));
               row.addView(redo, r2);
               Button open = this.primaryButton("查看", this.col(color.onAccent));
               LinearLayout.LayoutParams r3 = new LinearLayout.LayoutParams(-2, -2);
               r3.setMarginStart(this.dp(8));
               open.setOnClickListener((v) -> this.showPlanDetail(d));
               row.addView(open, r3);
               card.addView(row);
               LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
               clp.setMargins(0, this.dp(8), 0, 0);
               list.addView(card, clp);
            }

         }
      }
   }

   private void confirmDeleteDoc(AppDatabase.GmDoc d) {
      LinearLayout box = new LinearLayout(this.activity);
      box.setOrientation(1);
      GradientDrawable bg = new GradientDrawable();
      bg.setColor(this.col(color.surface));
      bg.setCornerRadius((float)this.dp(20));
      bg.setStroke(this.dp(1), this.col(color.border));
      box.setBackground(bg);
      box.setPadding(this.dp(18), this.dp(18), this.dp(18), this.dp(14));
      TextView h = new TextView(this.activity);
      h.setText("删除这份方案？");
      h.setTextColor(this.col(color.text));
      h.setTextSize(16.0F);
      h.setTypeface((Typeface)null, 1);
      box.addView(h);
      TextView tip = new TextView(this.activity);
      tip.setText((d.title == null ? "实施方案" : d.title) + "\n删除后无法恢复（手机 Download 里已导出的 TXT 文件不受影响）。");
      tip.setTextColor(this.col(color.muted));
      tip.setTextSize(12.0F);
      tip.setLineSpacing((float)this.dp(3), 1.0F);
      tip.setPadding(0, this.dp(8), 0, 0);
      box.addView(tip);
      LinearLayout row = new LinearLayout(this.activity);
      row.setGravity(8388613);
      LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
      rlp.setMargins(0, this.dp(14), 0, 0);
      row.setLayoutParams(rlp);
      Button cancel = this.ghostButton("取消");
      Button ok = this.primaryButton("删除", this.col(color.onAccent));
      ok.setBackgroundResource(drawable.btn_ghost);
      ok.setTextColor(this.col(color.danger));
      row.addView(cancel);
      LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-2, -2);
      olp.setMarginStart(this.dp(8));
      row.addView(ok, olp);
      box.addView(row);
      Dialog dlg = this.showDialogWide(box);
      cancel.setOnClickListener((v) -> dlg.dismiss());
      ok.setOnClickListener((v) -> {
         this.db.deleteGmDoc(d.id);
         dlg.dismiss();
         this.showPlanList();
         this.showToast("已删除");
      });
   }

   private void showPlanDetail(final AppDatabase.GmDoc d) {

      final Dialog dlg = new Dialog(this.activity);
      final LinearLayout box = new LinearLayout(this.activity);
      box.setOrientation(1);
      GradientDrawable bg = new GradientDrawable();
      bg.setColor(this.col(color.surface));
      bg.setCornerRadius((float)this.dp(20));
      bg.setStroke(this.dp(1), this.col(color.border));
      box.setBackground(bg);
      box.setPadding(this.dp(16), this.dp(16), this.dp(16), this.dp(12));
      TextView h = new TextView(this.activity);
      h.setText("\ud83d\udcc4 " + (d.title == null ? "实施方案" : d.title));
      h.setTextColor(this.col(color.text));
      h.setTextSize(15.0F);
      h.setTypeface((Typeface)null, 1);
      box.addView(h);
      final TextView meta = new TextView(this.activity);

      String kind;
      String mn = d.modelName == null ? "" : d.modelName.trim();
      if ("全量对话原文".equals(mn)) kind = "全部对话原文（不调模型，逐条发言）";
      else if (mn.isEmpty()) kind = "文档";
      else kind = "大模型提炼的实施方案 · 模型：" + mn;
      meta.setText(kind + " · " + d.contentLength + " 字\n"
              + "已保存为 TXT（手机 Download）。");
      meta.setTextColor(this.col(color.muted));
      meta.setTextSize(11.5F);
      meta.setLineSpacing((float)this.dp(2), 1.0F);
      meta.setPadding(0, this.dp(6), 0, 0);
      box.addView(meta);

      LinearLayout row1 = new LinearLayout(this.activity);
      row1.setGravity(8388613);
      LinearLayout.LayoutParams r1lp = new LinearLayout.LayoutParams(-1, -2);
      r1lp.setMargins(0, this.dp(12), 0, 0);
      row1.setLayoutParams(r1lp);
      Button close = this.ghostButton("关闭");

      Button save = this.primaryButton("保存为 TXT", this.col(color.onAccent));

      Button shareFile = this.primaryButton("发送文件", this.col(color.onAccent));

      shareFile.setBackgroundResource(drawable.btn_secondary);
      shareFile.setTextColor(this.col(color.accent));
      row1.addView(close);
      LinearLayout.LayoutParams c2 = new LinearLayout.LayoutParams(-2, -2);
      c2.setMarginStart(this.dp(8));
      row1.addView(save, c2);
      LinearLayout.LayoutParams c3 = new LinearLayout.LayoutParams(-2, -2);
      c3.setMarginStart(this.dp(8));
      row1.addView(shareFile, c3);
      box.addView(row1);
      final Dialog fdlg = this.showDialogWide(box);
      close.setOnClickListener((v) -> fdlg.dismiss());

      save.setOnClickListener((v) -> this.savePlanToFile(d, fdlg));
      shareFile.setOnClickListener((v) -> this.sharePlanFile(d));
      fdlg.show();
   }

   private void savePlanToFile(final AppDatabase.GmDoc d, final Dialog dlg) {
      this.showToast("正在保存…");
      this.bgExecute(this.executor, () -> {
         String txt = (d.content != null && !d.content.isEmpty())
                 ? d.content
                 : this.db.getGmDocContent(d.id);
         String stamp = (new SimpleDateFormat("yyyyMMdd_HHmm", Locale.CHINA)).format(new Date(d.createdAt));
         String safe = (d.title == null ? "实施方案" : d.title).replaceAll("[\\\\/:*?\"<>|]", "_");
         final String fileName = safe + "_" + stamp + ".txt";
         final File out = this.saveTextToDownloads(fileName, txt == null ? "" : txt);
         final int len = txt == null ? 0 : txt.length();
         this.ui.post(() -> {
            if (this.activity.isFinishing() || this.activity.isDestroyed()) return;
            if (out == null) {
               this.showToast("保存失败");
            } else {
               this.showToast("已保存到 手机存储/Download/" + fileName + "（" + len + " 字）");
               if (dlg != null && dlg.isShowing()) dlg.dismiss();
            }
         });
      });
   }

   private void sharePlanFile(final AppDatabase.GmDoc d) {

      this.bgExecute(this.executor, () -> {
         try {
            String stamp = (new SimpleDateFormat("yyyyMMdd_HHmm", Locale.CHINA)).format(new Date(d.createdAt));
            String safe = (d.title == null ? "实施方案" : d.title).replaceAll("[\\\\/:*?\"<>|]", "_");
            final String fileName = safe + "_" + stamp + ".txt";
            File dir = new File(this.activity.getCacheDir(), "shared_plans");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, fileName);
            FileOutputStream fos = new FileOutputStream(f);

            String body = (d.content != null && !d.content.isEmpty()) ? d.content : this.db.getGmDocContent(d.id);
            fos.write(body.getBytes(StandardCharsets.UTF_8));
            fos.flush();
            fos.close();
            final Uri uri = FileProvider.getUriForFile(this.activity, FileProvider.AUTHORITY, f);
            this.ui.post(() -> {
               if (this.activity.isFinishing() || this.activity.isDestroyed()) return;
               Intent it = new Intent("android.intent.action.SEND");
               it.setType("text/plain");
               it.putExtra("android.intent.extra.STREAM", uri);
               it.putExtra("android.intent.extra.SUBJECT", fileName);
               it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
               try {
                  this.activity.startActivity(Intent.createChooser(it, "发送文件到（微信/QQ/邮箱等）"));
               } catch (Exception e2) {
                  this.showToast("分享文件失败：" + e2.getMessage());
               }
            });
         } catch (Exception e) {
            final String em = e.getMessage();
            this.ui.post(() -> this.showToast("分享文件失败：" + em));
         }
      });
   }

   private void showSettingsHub(int tab) {
      if (this.currentRoomId <= 0L) return;
      this.stopUsageTicker();
      this.inHub = true;
      this.currentPage = 3;
      this.pageBeforeSub = 1;
      this.setHeaderButtons(false);
      AppDatabase.GmRoom hr = this.db.getGmRoom(this.currentRoomId);
      String ht = hr != null && hr.title != null ? hr.title : "群聊设置";
      if (this.titleText != null) this.titleText.setText(ht);
      this.syncTopBarTitle(ht);

      this.renderTarget = this.content;
      this.content.removeAllViews();
      LinearLayout shell = new LinearLayout(this.activity);
      shell.setOrientation(1);
      this.content.addView(shell, new FrameLayout.LayoutParams(-1, -1));

      LinearLayout tabBar = new LinearLayout(this.activity);
      tabBar.setOrientation(0);
      tabBar.setGravity(16);
      tabBar.setPadding(this.dp(10), this.dp(8), this.dp(10), this.dp(2));
      String[] labels = {"\ud83d\udc65 成员", "\ud83d\udcc4 方案", "\ud83d\udcca 用量", "\u2699 参数"};
      this.hubTabButtons = new Button[labels.length];
      for (int i = 0; i < labels.length; i++) {
         final int idx = i;
         Button b = new Button(this.activity);
         b.setText(labels[i]);
         b.setAllCaps(false);
         b.setTextSize(11.5F);
         b.setMinHeight(0);
         b.setMinimumHeight(0);
         b.setPadding(this.dp(2), this.dp(9), this.dp(2), this.dp(9));
         b.setOnClickListener((v) -> this.switchHubTab(idx, true));
         this.hubTabButtons[i] = b;
         LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1.0F);
         if (i > 0) lp.setMarginStart(this.dp(6));
         tabBar.addView(b, lp);
      }
      shell.addView(tabBar);

      FrameLayout body = new FrameLayout(this.activity);
      this.hubBody = body;
      shell.addView(body, new LinearLayout.LayoutParams(-1, 0, 1.0F));

      this.switchHubTab(tab, false);
   }

   private void switchHubTab(int tab, boolean animate) {
      if (this.hubBody == null) return;
      this.hubTab = tab;
      this.inHub = true;
      this.updateHubTabStyles();
      this.stopUsageTicker();
      this.renderTarget = this.hubBody;
      switch (tab) {
         case 0: this.showMemberManager(); break;
         case 1: this.showPlanList(); break;
         case 2: this.showUsagePanel(); break;
         default: this.showRoomSettings(); break;
      }
      if (animate) {
         View child = this.hubBody.getChildAt(0);
         if (child != null) {
            float dx = this.hubBody.getWidth() > 0 ? this.hubBody.getWidth() * 0.12F : (float) this.dp(40);
            child.setAlpha(0.0F);
            child.setTranslationX(dx);
            child.animate().alpha(1.0F).translationX(0.0F).setDuration(200L).start();
         }
      }
   }

   private void updateHubTabStyles() {
      if (this.hubTabButtons == null) return;
      for (int i = 0; i < this.hubTabButtons.length; i++) {
         Button b = this.hubTabButtons[i];
         if (b == null) continue;
         if (i == this.hubTab) {
            b.setBackgroundResource(drawable.btn_primary);
            b.setTextColor(this.col(color.onAccent));
         } else {
            b.setBackgroundResource(drawable.btn_ghost);
            b.setTextColor(this.col(color.muted));
         }
      }
   }

   private void showRoomSettings() {
      try { PhoneAwareness.noteInAppStep(this.activity, "群聊 · 房间设置"); } catch (Throwable ignored) { }
      this.stopUsageTicker();
      if (!this.inHub) this.currentPage = 3;
      if (this.currentRoomId > 0L) {
         AppDatabase.GmRoom room = this.db.getGmRoom(this.currentRoomId);
         if (room != null) {
            this.setHeaderButtons(false);
            this.renderTarget.removeAllViews();
            LinearLayout root = new LinearLayout(this.activity);
            root.setOrientation(1);
            root.setPadding(0, 0, 0, 0);
            this.renderTarget.addView(root, new FrameLayout.LayoutParams(-1, -1));
            ScrollView sv = new ScrollView(this.activity);
            LinearLayout form = new LinearLayout(this.activity);
            form.setOrientation(1);
            sv.addView(form);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 0, 1.0F);
            slp.setMargins(0, this.dp(8), 0, 0);
            root.addView(sv, slp);

            EditText ctxEt = this.field(form, "总上下文上限（token）", String.valueOf(room.contextLimit), "1000000");
            this.fieldNote(form, "整个群聊可容纳的上下文总量，默认 100 万");
            EditText outEt = this.field(form, "单次输出上限（token）", String.valueOf(room.maxOutputTokens), "110000");
            this.fieldNote(form, "每个角色单次发言的最大长度，默认 11 万");
            EditText totalEt = this.field(form, "全群消息上限（条）", String.valueOf(room.maxTotalMsgs), "0 = 不限");
            this.fieldNote(form, "0 = 不限；达到后自动结束讨论");
            EditText intervalEt = this.field(form, "发言间隔 – 最短（秒）", String.valueOf(room.speakIntervalSec), "30");
            this.fieldNote(form, "两次发言之间的最短等待。所有角色在「最短~最长」之间随机取值，不再整齐地同时请求上游（更能避开并发限流）");
            EditText intervalMaxEt = this.field(form, "发言间隔 – 最长（秒）", String.valueOf(room.speakIntervalMaxSec), "60");
            this.fieldNote(form, "留 0 或填得比最短小 = 不随机，按最短值固定。例：最短 30、最长 60 → 30~60 秒随机触发");

            final android.widget.Switch noIntervalSw = new android.widget.Switch(this.activity);
            noIntervalSw.setText("不用间隔");
            noIntervalSw.setTextSize(12.5F);
            noIntervalSw.setTextColor(this.col(color.text2));
            noIntervalSw.setChecked(room.noInterval);
            LinearLayout.LayoutParams nislp = new LinearLayout.LayoutParams(-1, -2);
            nislp.setMargins(0, this.dp(14), 0, 0);
            form.addView(noIntervalSw, nislp);
            this.fieldNote(form, "打开后跳过等待，讨论节奏最快；适合不担心上游并发限流时使用");

            TextView langLabel = new TextView(this.activity);
            langLabel.setText("语言模式");
            langLabel.setTextColor(this.col(color.muted));
            langLabel.setTextSize(11.0F);
            LinearLayout.LayoutParams lllp = new LinearLayout.LayoutParams(-1, -2);
            lllp.setMargins(0, this.dp(14), 0, 0);
            form.addView(langLabel, lllp);
            final int[] langChoice = new int[]{room.langMode};

            final String[] langNames = new String[]{"全中文",
                    "英文思考 + 英文讨论 + 中文提问（默认）",
                    "英文思考 + 中文回复",
                    "全英文"};
            if (langChoice[0] < 0 || langChoice[0] > 3) langChoice[0] = 1;
            Button langBtn = this.primaryButton("当前：" + langNames[Math.max(0, Math.min(3, langChoice[0]))], this.col(color.accent));
            langBtn.setBackgroundResource(drawable.btn_secondary);
            LinearLayout.LayoutParams lblp = new LinearLayout.LayoutParams(-1, -2);
            lblp.setMargins(0, this.dp(6), 0, 0);
            langBtn.setOnClickListener((v) -> this.showStyledListPicker(null, "语言模式", langNames, null, langChoice[0], (which) -> {
               langChoice[0] = which;
               langBtn.setText("当前：" + langNames[which]);
               this.showToast("已选「" + langNames[which] + "」，点「保存设置」生效");
            }));
            form.addView(langBtn, lblp);
            TextView gpLabel = new TextView(this.activity);

            gpLabel.setText("全群共享规则（讨论纪律，注入每个角色）");
            gpLabel.setTextColor(this.col(color.muted));
            gpLabel.setTextSize(11.0F);
            LinearLayout.LayoutParams gplp = new LinearLayout.LayoutParams(-1, -2);
            gplp.setMargins(0, this.dp(14), 0, 0);
            form.addView(gpLabel, gplp);
            EditText gpEt = new EditText(this.activity);
            gpEt.setText(room.globalPrompt == null ? "" : room.globalPrompt);
            gpEt.setBackgroundResource(drawable.bg_edit);
            gpEt.setPadding(this.dp(12), this.dp(12), this.dp(12), this.dp(12));
            gpEt.setTextColor(this.col(color.text));
            gpEt.setHintTextColor(this.col(color.muted));
            gpEt.setTextSize(12.0F);
            gpEt.setMinLines(4);
            gpEt.setGravity(8388659);
            LinearLayout.LayoutParams gpelp = new LinearLayout.LayoutParams(-1, -2);
            gpelp.setMargins(0, this.dp(4), 0, 0);
            form.addView(gpEt, gpelp);
            Button resetRules = this.ghostButton("恢复默认规则");
            LinearLayout.LayoutParams rrlp = new LinearLayout.LayoutParams(-2, -2);
            rrlp.setMargins(0, this.dp(6), 0, 0);
            resetRules.setOnClickListener((v) -> {

               boolean devStyle = false;
               boolean hasCompliance = false;
               for (AppDatabase.GmMember mm : this.db.listGmMembers(room.id)) {
                  if ("技术文档师".equals(mm.name)) devStyle = true;
                  if ("资质代办参谋".equals(mm.name) || "合规审计员".equals(mm.name)) hasCompliance = true;
               }
               gpEt.setText(!devStyle ? SCENE_GLOBAL_RULES
                       : (hasCompliance ? DEV_GLOBAL_RULES : PERSONAL_GLOBAL_RULES));
            });
            form.addView(resetRules, rrlp);

            Button save = this.primaryButton("保存设置", this.col(color.onAccent));
            LinearLayout.LayoutParams savlp = new LinearLayout.LayoutParams(-1, -2);
            savlp.setMargins(0, this.dp(16), 0, this.dp(10));
            save.setOnClickListener((v) -> {

               Integer c = this.parsePositiveInt(ctxEt, "总上下文上限", 2000);
               if (c == null) return;
               Integer t = this.parseNonNegativeInt(totalEt, "全群消息上限");
               if (t == null) return;
               Integer iv = this.parsePositiveInt(intervalEt, "发言间隔（最短）", 1);
               if (iv == null) return;
               Integer ivMax = this.parseNonNegativeInt(intervalMaxEt, "发言间隔（最长）");
               if (ivMax == null) return;
               Integer mo = this.parsePositiveInt(outEt, "单次输出上限", 256);
               if (mo == null) return;
               if (ivMax > 0 && ivMax <= iv) {
                  this.showToast("「发言间隔最长」要大于最短（" + iv + " 秒）；想固定间隔就把它填 0");
                  return;
               }

               this.db.updateGmRoomSettings(this.currentRoomId,
                       Math.max(2000, c), Math.max(0, t), Math.max(1, iv), Math.max(256, mo));

               this.db.setGmRoomIntervalRange(this.currentRoomId, iv, ivMax);

               this.db.setGmRoomNoInterval(this.currentRoomId, noIntervalSw.isChecked());

               this.db.setGmRoomLangMode(this.currentRoomId, langChoice[0]);
               this.db.setGmRoomGlobalPrompt(this.currentRoomId, gpEt.getText().toString());

               this.showToast("已保存");
               android.util.Log.i("VC_GM", "房间设置已保存 room=" + this.currentRoomId
                       + " interval=" + iv);
               if (!this.inHub) this.openRoom(this.currentRoomId);
            });
            form.addView(save, savlp);
         }
      }
   }

   private void showProviderManager() {
      this.currentPage = 4;
      this.setHeaderButtons(false);
      this.content.removeAllViews();
      LinearLayout root = new LinearLayout(this.activity);
      root.setOrientation(1);
      this.content.addView(root, new FrameLayout.LayoutParams(-1, -1));

      try { MaskedFieldMenu.attachTree(root); } catch (Throwable ignored) { }
      LinearLayout barRow = new LinearLayout(this.activity);
      barRow.setOrientation(0);
      barRow.setGravity(16);
      barRow.addView(new View(this.activity), new LinearLayout.LayoutParams(0, 1, 1.0F));
      Button add = this.primaryButton("＋ 添加服务商", this.col(color.onAccent));
      add.setOnClickListener((v) -> this.showProviderEditor((AppDatabase.GmProvider)null));
      barRow.addView(add);
      root.addView(barRow);
      TextView hint = new TextView(this.activity);
      hint.setText("服务商 = 一个请求地址 + 一组模型名。保存后，创建/编辑角色时可直接「选服务商 → 选模型」，不用手填地址和密钥。模型支持一键获取或单个添加。");
      hint.setTextColor(this.col(color.muted));
      hint.setTextSize(11.0F);
      hint.setLineSpacing((float)this.dp(2), 1.0F);
      hint.setPadding(0, this.dp(8), 0, 0);
      root.addView(hint);
      ScrollView sv = new ScrollView(this.activity);
      LinearLayout list = new LinearLayout(this.activity);
      list.setOrientation(1);
      sv.addView(list);
      LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 0, 1.0F);
      slp.setMargins(0, this.dp(8), 0, 0);
      root.addView(sv, slp);
      List<AppDatabase.GmProvider> providers = this.db.listGmProviders();
      if (providers.isEmpty()) {
         TextView empty = new TextView(this.activity);
         empty.setText("还没有服务商。");
         empty.setTextColor(this.col(color.muted));
         empty.setTextSize(13.0F);
         list.addView(empty);
      }

      for(AppDatabase.GmProvider p : providers) {
         LinearLayout card = new LinearLayout(this.activity);
         card.setOrientation(1);
         GradientDrawable bg = new GradientDrawable();
         bg.setColor(this.col(color.surface));
         bg.setCornerRadius((float)this.dp(14));
         bg.setStroke(this.dp(1), this.col(color.border));
         card.setBackground(bg);
         card.setPadding(this.dp(14), this.dp(12), this.dp(14), this.dp(12));
         TextView n = new TextView(this.activity);
         n.setText("\ud83c\udfe2 " + p.name);
         n.setTextColor(this.col(color.text));
         n.setTextSize(15.0F);
         n.setTypeface((Typeface)null, 1);
         card.addView(n);
         TextView d = new TextView(this.activity);
         d.setText(p.baseUrl + "\n模型：" + String.join("、", p.modelList()));
         d.setTextColor(this.col(color.muted));
         d.setTextSize(11.0F);
         d.setLineSpacing((float)this.dp(2), 1.0F);
         d.setPadding(0, this.dp(4), 0, 0);
         card.addView(d);
         LinearLayout row = new LinearLayout(this.activity);
         row.setGravity(8388613);
         LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
         rlp.setMargins(0, this.dp(10), 0, 0);
         row.setLayoutParams(rlp);
         Button del = this.ghostButton("删除");
         del.setTextColor(this.col(color.danger));
         del.setOnClickListener((v) -> this.confirmDanger("删除服务商？", "\ud83c\udfe2 " + p.name + "\n" + p.baseUrl + "\n\n用该服务商的角色会失去可选的模型来源，删除后需重新添加。", () -> {
               this.db.deleteGmProvider(p.id);
               this.showProviderManager();
            }));
         row.addView(del);
         Button edit = this.primaryButton("编辑", this.col(color.onAccent));
         LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-2, -2);
         elp.setMarginStart(this.dp(8));
         edit.setOnClickListener((v) -> this.showProviderEditor(p));
         row.addView(edit, elp);
         card.addView(row);
         LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
         clp.setMargins(0, this.dp(8), 0, 0);
         list.addView(card, clp);
      }

   }

   public void showProviderManagerDialog() {
      Dialog dlg = new Dialog(this.activity);
      this.renderProviderDialog(dlg);
      dlg.show();
   }

   private void renderProviderDialog(Dialog dlg) {
      LinearLayout root = new LinearLayout(this.activity);
      root.setOrientation(1);
      GradientDrawable bg = new GradientDrawable();
      bg.setColor(this.col(color.surface));
      bg.setCornerRadius((float)this.dp(20));
      bg.setStroke(this.dp(1), this.col(color.border));
      root.setBackground(bg);
      root.setPadding(this.dp(16), this.dp(16), this.dp(16), this.dp(12));
      LinearLayout barRow = new LinearLayout(this.activity);
      barRow.setOrientation(0);
      barRow.setGravity(16);
      TextView title = new TextView(this.activity);
      title.setText("\ud83c\udfe2 服务商（全局）");
      title.setTextColor(this.col(color.text));
      title.setTextSize(16.0F);
      title.setTypeface((Typeface)null, 1);
      barRow.addView(title);
      barRow.addView(new View(this.activity), new LinearLayout.LayoutParams(0, 1, 1.0F));
      Button add = this.primaryButton("＋ 添加", this.col(color.onAccent));
      add.setOnClickListener((v) -> {
         dlg.dismiss();
         this.showProviderEditorFromSettings(() -> this.showProviderManagerDialog());
      });
      barRow.addView(add);
      root.addView(barRow);
      TextView hint = new TextView(this.activity);
      hint.setText("服务商 = 一个请求地址 + 一组模型。在这里添加好之后，角色、群聊、方案提炼等所有地方都能直接「选服务商 → 选模型」。");
      hint.setTextColor(this.col(color.muted));
      hint.setTextSize(11.0F);
      hint.setLineSpacing((float)this.dp(3), 1.0F);
      hint.setPadding(0, this.dp(8), 0, 0);
      root.addView(hint);
      ScrollView sv = new ScrollView(this.activity);
      LinearLayout list = new LinearLayout(this.activity);
      list.setOrientation(1);
      sv.addView(list);
      LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 0, 1.0F);
      slp.setMargins(0, this.dp(10), 0, 0);
      root.addView(sv, slp);
      List<AppDatabase.GmProvider> providers = this.db.listGmProviders();
      if (providers.isEmpty()) {
         TextView empty = new TextView(this.activity);
         empty.setText("还没有服务商。");
         empty.setTextColor(this.col(color.muted));
         empty.setTextSize(13.0F);
         empty.setLineSpacing((float)this.dp(3), 1.0F);
         list.addView(empty);
      }

      for(AppDatabase.GmProvider p : providers) {
         LinearLayout card = new LinearLayout(this.activity);
         card.setOrientation(1);
         GradientDrawable cbg = new GradientDrawable();
         cbg.setColor(this.col(color.surfaceInput));
         cbg.setCornerRadius((float)this.dp(14));
         cbg.setStroke(this.dp(1), this.col(color.border));
         card.setBackground(cbg);
         card.setPadding(this.dp(14), this.dp(12), this.dp(14), this.dp(10));
         TextView n = new TextView(this.activity);
         n.setText("\ud83c\udfe2 " + p.name);
         n.setTextColor(this.col(color.text));
         n.setTextSize(15.0F);
         n.setTypeface((Typeface)null, 1);
         card.addView(n);
         TextView d = new TextView(this.activity);
         List<String> mlist = p.modelList();
         d.setText(p.baseUrl + "\n模型（" + mlist.size() + "）：" + (mlist.isEmpty() ? "（暂无，点编辑一键获取）" : String.join("、", mlist.subList(0, Math.min(6, mlist.size()))) + (mlist.size() > 6 ? "…" : "")));
         d.setTextColor(this.col(color.muted));
         d.setTextSize(11.0F);
         d.setLineSpacing((float)this.dp(2), 1.0F);
         d.setPadding(0, this.dp(4), 0, 0);
         card.addView(d);
         LinearLayout row = new LinearLayout(this.activity);
         row.setGravity(8388613);
         LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
         rlp.setMargins(0, this.dp(10), 0, 0);
         row.setLayoutParams(rlp);
         Button del = this.ghostButton("删除");
         del.setTextColor(this.col(color.danger));
         del.setOnClickListener((v) -> this.confirmDanger("删除服务商？", "\ud83c\udfe2 " + p.name + "\n" + p.baseUrl + "\n\n删除后需重新添加。", () -> {
               this.db.deleteGmProvider(p.id);
               dlg.dismiss();
               this.showProviderManagerDialog();
            }));
         row.addView(del);
         Button edit = this.primaryButton("编辑", this.col(color.onAccent));
         LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-2, -2);
         elp.setMarginStart(this.dp(8));
         edit.setOnClickListener((v) -> {
            dlg.dismiss();
            this.showProviderEditorFromSettings(p, () -> this.showProviderManagerDialog());
         });
         row.addView(edit, elp);
         card.addView(row);
         LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
         clp.setMargins(0, 0, 0, this.dp(10));
         list.addView(card, clp);
      }

      Button close = this.ghostButton("关闭");
      LinearLayout cRow = new LinearLayout(this.activity);
      cRow.setGravity(8388613);
      LinearLayout.LayoutParams crLp = new LinearLayout.LayoutParams(-1, -2);
      crLp.setMargins(0, this.dp(6), 0, 0);
      cRow.setLayoutParams(crLp);
      cRow.addView(close);
      root.addView(cRow);
      dlg.setContentView(root);
      if (dlg.getWindow() != null) {
         dlg.getWindow().setBackgroundDrawable(new ColorDrawable(0));
         WindowManager.LayoutParams lp = dlg.getWindow().getAttributes();
         lp.width = (int)((float)this.activity.getResources().getDisplayMetrics().widthPixels * 0.94F);
         lp.height = (int)((float)this.activity.getResources().getDisplayMetrics().heightPixels * 0.84F);
         dlg.getWindow().setAttributes(lp);
      }

      close.setOnClickListener((v) -> dlg.dismiss());
   }

   private void showProviderEditorFromSettings(Runnable onDone) {
      this.showProviderEditorInternal((AppDatabase.GmProvider)null, onDone);
   }

   private void showProviderEditorFromSettings(AppDatabase.GmProvider existing, Runnable onDone) {
      this.showProviderEditorInternal(existing, onDone);
   }

   private void showProviderEditor(AppDatabase.GmProvider existing) {
      this.providerEditorDone = this::showProviderManager;
      this.showProviderEditorInternal(existing, (Runnable)null);
   }

   private void showProviderEditorInternal(AppDatabase.GmProvider existing, Runnable onDone) {
      if (onDone != null) {
         this.providerEditorDone = onDone;
      }

      boolean isNew = existing == null;
      List<String> models = new ArrayList();
      if (!isNew && existing.models != null) {
         for(String m : existing.models.split("\n")) {
            if (!m.trim().isEmpty()) {
               models.add(m.trim());
            }
         }
      }

      Map<String, String> testResults = new HashMap();

      final Set<String> checkedModels = new HashSet();
      LinearLayout box = new LinearLayout(this.activity);
      box.setOrientation(1);
      GradientDrawable bg = new GradientDrawable();
      bg.setColor(this.col(color.surface));
      bg.setCornerRadius((float)this.dp(20));
      bg.setStroke(this.dp(1), this.col(color.border));
      box.setBackground(bg);
      box.setPadding(this.dp(18), this.dp(18), this.dp(18), this.dp(14));
      TextView h = new TextView(this.activity);
      h.setText(isNew ? "添加服务商" : "编辑服务商");
      h.setTextColor(this.col(color.text));
      h.setTextSize(16.0F);
      h.setTypeface((Typeface)null, 1);
      box.addView(h);
      ScrollView sv = new ScrollView(this.activity);
      LinearLayout form = new LinearLayout(this.activity);
      form.setOrientation(1);
      sv.addView(form);
      EditText nameEt = this.field(form, "服务商名称", isNew ? "" : existing.name, "如：阿里百炼 / 硅基流动");
      EditText baseEt = this.field(form, "请求地址", isNew ? "" : existing.baseUrl, "OpenAI 兼容地址，如 https://xxx/v1");
      EditText keyEt = this.field(form, "API Key", isNew ? "" : existing.apiKey, "该服务商的密钥");
      this.attachPasswordToggleGM(keyEt);
      TextView modelLabel = new TextView(this.activity);
      modelLabel.setText("模型列表（可单个测试 / 一键测试全部）");
      modelLabel.setTextColor(this.col(color.muted));
      modelLabel.setTextSize(11.0F);
      LinearLayout.LayoutParams mllp = new LinearLayout.LayoutParams(-1, -2);
      mllp.setMargins(0, this.dp(14), 0, 0);
      form.addView(modelLabel, mllp);

      LinearLayout batchRow = new LinearLayout(this.activity);
      batchRow.setOrientation(0);
      batchRow.setGravity(8388613);
      LinearLayout.LayoutParams brlp = new LinearLayout.LayoutParams(-1, -2);
      brlp.setMargins(0, this.dp(4), 0, 0);
      batchRow.setLayoutParams(brlp);
      Button selectAll = this.ghostButton("全选");
      batchRow.addView(selectAll);
      Button delChecked = this.ghostButton("删除勾选");
      delChecked.setTextColor(this.col(color.danger));
      LinearLayout.LayoutParams dclp = new LinearLayout.LayoutParams(-2, -2);
      dclp.setMarginStart(this.dp(6));
      batchRow.addView(delChecked, dclp);
      form.addView(batchRow);
      LinearLayout modelListBox = new LinearLayout(this.activity);
      modelListBox.setOrientation(1);
      form.addView(modelListBox);
      Runnable[] renderModels = new Runnable[1];
      renderModels[0] = () -> {
         modelListBox.removeAllViews();
         if (models.isEmpty()) {
            TextView empty = new TextView(this.activity);
            empty.setText("（还没有模型）");
            empty.setTextColor(this.col(color.muted));
            empty.setTextSize(11.0F);
            empty.setPadding(0, this.dp(4), 0, this.dp(4));
            modelListBox.addView(empty);
         } else {
            for(int i = 0; i < models.size(); ++i) {
               String mName = (String)models.get(i);
               LinearLayout rowM = new LinearLayout(this.activity);
               rowM.setOrientation(1);
               rowM.setBackgroundResource(drawable.bg_edit);
               rowM.setPadding(this.dp(10), this.dp(8), this.dp(10), this.dp(8));
               LinearLayout.LayoutParams rmlp = new LinearLayout.LayoutParams(-1, -2);
               rmlp.setMargins(0, this.dp(4), 0, 0);
               rowM.setLayoutParams(rmlp);
               LinearLayout top = new LinearLayout(this.activity);
               top.setOrientation(0);
               top.setGravity(16);

               CheckBox cb = new CheckBox(this.activity);
               cb.setChecked(checkedModels.contains(mName));
               cb.setOnCheckedChangeListener((b, on) -> {
                  if (on) checkedModels.add(mName); else checkedModels.remove(mName);
               });
               top.addView(cb, new LinearLayout.LayoutParams(-2, -2));
               TextView name = new TextView(this.activity);
               name.setText("• " + mName);
               name.setTextColor(this.col(color.text));
               name.setTextSize(12.0F);
               top.addView(name, new LinearLayout.LayoutParams(0, -2, 1.0F));
               TextView resTv = new TextView(this.activity);
               String cached = (String)testResults.get(mName);
               resTv.setText(cached == null ? "" : cached);
               resTv.setTextSize(10.0F);
               resTv.setTextColor(cached != null && cached.startsWith("✅") ? this.col(color.accent) : this.col(color.danger));
               resTv.setPadding(0, 0, this.dp(6), 0);
               top.addView(resTv);
               Button testBtn = this.ghostButton("测试");
               testBtn.setOnClickListener((v) -> {
                  resTv.setText("…");
                  resTv.setTextColor(this.col(color.muted));
                  String bu = baseEt.getText().toString().trim();
                  String ak = keyEt.getText().toString().trim();
                  (new Thread(() -> {
                     String r = AiClient.testModel(bu, ak, mName);
                     this.ui.post(() -> {
                        testResults.put(mName, r);
                        resTv.setText(r);
                        resTv.setTextColor(r.startsWith("✅") ? this.col(color.accent) : this.col(color.danger));
                     });
                  })).start();
               });
               top.addView(testBtn);
               Button del = this.ghostButton("✕");
               del.setTextColor(this.col(color.danger));
               del.setOnClickListener((v) -> {

                  this.confirmDanger("删除模型？",
                          "• " + mName + "\n\n只是从编辑列表里移除；点「保存」后才真正生效，点「取消」不影响原数据。",
                          () -> {
                             models.remove(mName);
                             renderModels[0].run();
                          });
               });
               top.addView(del);
               rowM.addView(top);
               modelListBox.addView(rowM);
            }

         }
      };
      renderModels[0].run();

      selectAll.setOnClickListener((v) -> {
         boolean allChecked = !models.isEmpty() && checkedModels.containsAll(models);
         checkedModels.clear();
         if (!allChecked) checkedModels.addAll(models);
         renderModels[0].run();
         selectAll.setText(allChecked ? "全选" : "取消全选");
      });
      delChecked.setOnClickListener((v) -> {
         if (checkedModels.isEmpty()) {
            this.showToast("先勾选要删除的模型");
            return;
         }
         final int n = checkedModels.size();
         this.confirmDanger("删除勾选的 " + n + " 个模型？",
                 "共 " + n + " 个：" + String.join("、",
                         new ArrayList<String>(checkedModels))
                         + "\n\n只是从编辑列表里移除；点「保存」后才真正生效，点「取消」不影响原数据。",
                 () -> {
                    models.removeAll(checkedModels);
                    checkedModels.clear();
                    selectAll.setText("全选");
                    renderModels[0].run();
                 });
      });
      LinearLayout addRow = new LinearLayout(this.activity);
      addRow.setOrientation(0);
      addRow.setGravity(16);
      LinearLayout.LayoutParams arlp = new LinearLayout.LayoutParams(-1, -2);
      arlp.setMargins(0, this.dp(8), 0, 0);
      addRow.setLayoutParams(arlp);
      EditText oneModelEt = new EditText(this.activity);
      oneModelEt.setHint("输入模型名，如 deepseek-v4-flash-0731");
      oneModelEt.setBackgroundResource(drawable.bg_edit);
      oneModelEt.setPadding(this.dp(12), this.dp(10), this.dp(12), this.dp(10));
      oneModelEt.setTextColor(this.col(color.text));
      oneModelEt.setHintTextColor(this.col(color.muted));
      oneModelEt.setTextSize(12.0F);
      addRow.addView(oneModelEt, new LinearLayout.LayoutParams(0, -2, 1.0F));
      Button addOne = this.primaryButton("＋ 添加", this.col(color.onAccent));
      LinearLayout.LayoutParams aolp = new LinearLayout.LayoutParams(-2, -2);
      aolp.setMarginStart(this.dp(6));
      addOne.setOnClickListener((v) -> {
         String m = oneModelEt.getText().toString().trim();
         if (m.isEmpty()) {
            this.showToast("请输入模型名");
         } else if (models.contains(m)) {
            this.showToast("已存在该模型");
         } else {
            models.add(m);
            oneModelEt.setText("");
            renderModels[0].run();
         }
      });
      addRow.addView(addOne, aolp);
      form.addView(addRow);
      Button fetchBtn = this.primaryButton("⬇ 一键获取模型（可勾选保存）", this.col(color.accent));
      fetchBtn.setBackgroundResource(drawable.btn_secondary);
      LinearLayout.LayoutParams fblp = new LinearLayout.LayoutParams(-1, -2);
      fblp.setMargins(0, this.dp(8), 0, 0);
      fetchBtn.setOnClickListener((v) -> {
         String bu = baseEt.getText().toString().trim();
         String ak = keyEt.getText().toString().trim();
         if (bu.isEmpty()) {
            this.showToast("请先填请求地址");
         } else {
            this.showToast("正在获取模型列表…");
            (new Thread(() -> {
               try {
                  List<String> fetched = AiClient.fetchModelList(bu, ak);
                  this.ui.post(() -> this.showModelPicker(fetched, models, renderModels[0]));
               } catch (Exception e) {
                  String msg = e.getMessage() == null ? String.valueOf(e) : e.getMessage();

                  this.ui.post(() -> {
                     if (msg.contains("\n")) {
                        LinearLayout errBox = this.dialogBox("获取模型列表失败", msg);
                        Button okBtn = this.primaryButton("知道了", this.col(color.onAccent));
                        LinearLayout.LayoutParams oblp = new LinearLayout.LayoutParams(-2, -2);
                        oblp.gravity = 8388613;
                        oblp.setMargins(0, this.dp(14), 0, 0);
                        errBox.addView(okBtn, oblp);
                        Dialog errDlg = this.showDialog(errBox);
                        okBtn.setOnClickListener((x) -> errDlg.dismiss());
                     } else {
                        this.showToast("获取失败：" + msg);
                     }
                  });
               }

            })).start();
         }
      });
      form.addView(fetchBtn, fblp);
      Button testAllBtn = this.primaryButton("\ud83e\uddea 一键测试全部模型", this.col(color.accent));
      testAllBtn.setBackgroundResource(drawable.btn_secondary);
      LinearLayout.LayoutParams tblp = new LinearLayout.LayoutParams(-1, -2);
      tblp.setMargins(0, this.dp(8), 0, 0);
      testAllBtn.setOnClickListener((v) -> {
         if (models.isEmpty()) {
            this.showToast("还没有模型");
         } else {
            String bu = baseEt.getText().toString().trim();
            String ak = keyEt.getText().toString().trim();
            this.showToast("开始测试 " + models.size() + " 个模型…");
            (new Thread(() -> {
               for (String mName : new ArrayList<String>(models)) {
                  String r = AiClient.testModel(bu, ak, mName);
                  this.ui.post(() -> {
                     testResults.put(mName, r);
                     renderModels[0].run();
                  });
               }

               this.ui.post(() -> this.showToast("测试完成"));
            })).start();
         }
      });
      form.addView(testAllBtn, tblp);
      LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 0, 1.0F);
      box.addView(sv, slp);
      LinearLayout row = new LinearLayout(this.activity);
      row.setGravity(8388613);
      LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
      rlp.setMargins(0, this.dp(14), 0, 0);
      row.setLayoutParams(rlp);
      Button cancel = this.ghostButton("取消");
      row.addView(cancel);
      Button ok = this.primaryButton("保存", this.col(color.onAccent));
      LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-2, -2);
      olp.setMarginStart(this.dp(8));
      row.addView(ok, olp);
      box.addView(row);
      Dialog dlg = this.showDialogWide(box);
      cancel.setOnClickListener((v) -> {
         dlg.dismiss();
         if (this.providerEditorDone != null) {
            this.providerEditorDone.run();
         } else {
            this.showProviderManager();
         }

      });
      ok.setOnClickListener((v) -> {
         String nm = nameEt.getText().toString().trim();
         String bu = baseEt.getText().toString().trim();
         if (!nm.isEmpty() && !bu.isEmpty()) {
            StringBuilder msb = new StringBuilder();

            for(String m : models) {
               if (msb.length() > 0) {
                  msb.append("\n");
               }

               msb.append(m);
            }

            String modelsStr = msb.toString();
            if (isNew) {
               this.db.insertGmProvider(nm, bu, keyEt.getText().toString().trim(), modelsStr);
            } else {
               String oldBase = existing.baseUrl;
               String newKey = keyEt.getText().toString().trim();
               existing.name = nm;
               existing.baseUrl = bu;
               existing.apiKey = newKey;
               existing.models = modelsStr;
               this.db.updateGmProvider(existing);

               int touched = this.db.syncGmMembersByBaseUrl(oldBase, bu, newKey);
               if (touched > 0) {
                  this.showToast("服务商已保存，" + touched + " 位角色的地址/密钥已同步更新");
               }
            }

            dlg.dismiss();
            if (this.providerEditorDone != null) {
               this.providerEditorDone.run();
            } else {
               this.showProviderManager();
            }

         } else {
            this.showToast("名称和请求地址必填");
         }
      });
      dlg.show();
   }

   private void showModelPicker(List<String> all, List<String> target, Runnable onChanged) {
      Set<String> checked = new HashSet();

      for(String m : all) {
         if (target.contains(m)) {
            checked.add(m);
         }
      }

      LinearLayout box = new LinearLayout(this.activity);
      box.setOrientation(1);
      GradientDrawable bg = new GradientDrawable();
      bg.setColor(this.col(color.surface));
      bg.setCornerRadius((float)this.dp(20));
      bg.setStroke(this.dp(1), this.col(color.border));
      box.setBackground(bg);
      box.setPadding(this.dp(18), this.dp(18), this.dp(18), this.dp(14));
      TextView h = new TextView(this.activity);
      h.setText("选择要保存的模型（共 " + all.size() + " 个）");
      h.setTextColor(this.col(color.text));
      h.setTextSize(16.0F);
      h.setTypeface((Typeface)null, 1);
      box.addView(h);
      EditText searchEt = new EditText(this.activity);
      searchEt.setHint("\ud83d\udd0d 搜索模型名（如 deepseek / claude）");
      searchEt.setBackgroundResource(drawable.bg_edit);
      searchEt.setPadding(this.dp(12), this.dp(10), this.dp(12), this.dp(10));
      searchEt.setTextColor(this.col(color.text));
      searchEt.setHintTextColor(this.col(color.muted));
      searchEt.setTextSize(13.0F);
      LinearLayout.LayoutParams selp = new LinearLayout.LayoutParams(-1, -2);
      selp.setMargins(0, this.dp(10), 0, 0);
      box.addView(searchEt, selp);
      TextView countTv = new TextView(this.activity);
      countTv.setTextColor(this.col(color.muted));
      countTv.setTextSize(11.0F);
      countTv.setPadding(0, this.dp(6), 0, 0);
      box.addView(countTv);
      LinearLayout opRow = new LinearLayout(this.activity);
      opRow.setOrientation(0);
      opRow.setGravity(8388613);
      LinearLayout.LayoutParams orlp = new LinearLayout.LayoutParams(-1, -2);
      orlp.setMargins(0, this.dp(6), 0, 0);
      opRow.setLayoutParams(orlp);
      Button clearAll = this.ghostButton("清空选择");
      opRow.addView(clearAll);
      Button selectAllFiltered = this.primaryButton("全选当前筛选结果", this.col(color.onAccent));
      LinearLayout.LayoutParams salp = new LinearLayout.LayoutParams(-2, -2);
      salp.setMarginStart(this.dp(8));
      opRow.addView(selectAllFiltered, salp);
      box.addView(opRow);
      LinearLayout listBox = new LinearLayout(this.activity);
      listBox.setOrientation(1);
      ScrollView lsv = new ScrollView(this.activity);
      lsv.addView(listBox);
      LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, 0, 1.0F);
      llp.setMargins(0, this.dp(8), 0, 0);
      box.addView(lsv, llp);
      final String[] filter = new String[]{""};
      final Runnable[] renderList = new Runnable[1];
      renderList[0] = () -> {
         listBox.removeAllViews();
         String f = filter[0].toLowerCase();
         int shown = 0;

         for(String m : all) {
            if (f.isEmpty() || m.toLowerCase().contains(f)) {
               ++shown;
               boolean[] on = new boolean[]{checked.contains(m)};
               LinearLayout row = new LinearLayout(this.activity);
               row.setOrientation(0);
               row.setGravity(16);
               row.setPadding(this.dp(8), this.dp(10), this.dp(8), this.dp(10));
               TextView cb = new TextView(this.activity);
               cb.setText(on[0] ? "☑" : "☐");
               cb.setTextSize(17.0F);
               cb.setTextColor(on[0] ? this.col(color.accent) : this.col(color.muted));
               row.addView(cb);
               TextView nm = new TextView(this.activity);
               nm.setText(m + (target.contains(m) ? "  （已添加）" : ""));
               nm.setTextColor(this.col(color.text));
               nm.setTextSize(12.0F);
               nm.setPadding(this.dp(8), 0, 0, 0);
               row.addView(nm, new LinearLayout.LayoutParams(0, -2, 1.0F));
               row.setOnClickListener((v) -> {
                  if (checked.contains(m)) {
                     checked.remove(m);
                  } else {
                     checked.add(m);
                  }

                  on[0] = checked.contains(m);
                  cb.setText(on[0] ? "☑" : "☐");
                  cb.setTextColor(on[0] ? this.col(color.accent) : this.col(color.muted));
                  countTv.setText("已选 " + checked.size() + " 个");
               });
               listBox.addView(row);
            }
         }

         if (shown == 0) {
            TextView none = new TextView(this.activity);
            none.setText("没有匹配的模型");
            none.setTextColor(this.col(color.muted));
            none.setTextSize(12.0F);
            listBox.addView(none);
         }

         countTv.setText("已选 " + checked.size() + " 个（显示 " + shown + " 个）");
      };
      renderList[0].run();
      searchEt.addTextChangedListener(new TextWatcher() {
         public void beforeTextChanged(CharSequence s, int a, int b, int c) {
         }

         public void onTextChanged(CharSequence s, int a, int b, int c) {
         }

         public void afterTextChanged(Editable e) {
            filter[0] = e.toString().trim();
            renderList[0].run();
         }
      });
      clearAll.setOnClickListener((v) -> {
         checked.clear();
         renderList[0].run();
      });
      selectAllFiltered.setOnClickListener((v) -> {
         String f = filter[0].toLowerCase();

         for(String m : all) {
            if (f.isEmpty() || m.toLowerCase().contains(f)) {
               checked.add(m);
            }
         }

         renderList[0].run();
      });
      LinearLayout row = new LinearLayout(this.activity);
      row.setGravity(8388613);
      LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
      rlp.setMargins(0, this.dp(12), 0, 0);
      row.setLayoutParams(rlp);
      Button cancel = this.ghostButton("取消");
      row.addView(cancel);
      Button ok = this.primaryButton("保存勾选的", this.col(color.onAccent));
      LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-2, -2);
      olp.setMarginStart(this.dp(8));
      row.addView(ok, olp);
      box.addView(row);
      Dialog dlg = this.showDialogWide(box);
      cancel.setOnClickListener((v) -> dlg.dismiss());
      ok.setOnClickListener((v) -> {
         target.clear();

         for(String m : all) {
            if (checked.contains(m)) {
               target.add(m);
            }
         }

         onChanged.run();
         dlg.dismiss();
         this.showToast("已保存 " + target.size() + " 个模型到列表（记得点「保存」写入服务商）");
      });
      dlg.show();
   }

   private void pickProviderForRole(EditText baseEt, EditText keyEt, EditText modelEt) {
      List<AppDatabase.GmProvider> providers = this.db.listGmProviders();
      if (providers.isEmpty()) {
         this.showToast("还没有服务商，先去「\ud83c\udfe2 服务商」添加");
      } else {
         String[] names = new String[providers.size()];
         String[] subs = new String[providers.size()];

         for(int i = 0; i < providers.size(); ++i) {
            AppDatabase.GmProvider p = (AppDatabase.GmProvider)providers.get(i);
            names[i] = p.name;
            subs[i] = p.baseUrl + " · " + p.modelList().size() + " 个模型";
         }

         this.showStyledListPicker((View)null, "切换服务商", names, subs, -1, (which) -> {
            AppDatabase.GmProvider p = (AppDatabase.GmProvider)providers.get(which);
            baseEt.setText(p.baseUrl);
            keyEt.setText(p.apiKey == null ? "" : p.apiKey);
            keyEt.setTag("reveal?false");

            keyEt.setInputType(129);

            MaskedFieldMenu.attach(keyEt);
            this.pickModelForRole(p, modelEt);
         });
      }
   }

   private void pickModelForRole(AppDatabase.GmProvider p, EditText modelEt) {
      List<String> models = p.modelList();
      if (models.isEmpty()) {
         this.showToast("该服务商还没保存模型，可手动填写");
      } else {
         String[] arr = (String[])models.toArray(new String[0]);
         String[] subs = new String[arr.length];

         for(int i = 0; i < arr.length; ++i) {
            subs[i] = "";
         }

         this.showStyledListPicker((View)null, "选择模型（" + p.name + "）", arr, subs, -1, (which) -> modelEt.setText(arr[which]));
      }
   }

   private void showStyledListPicker(View anchor, String title, String[] items, String[] subs, int currentIndex, IntConsumer onPick) {
      LinearLayout card = new LinearLayout(this.activity);
      card.setOrientation(1);
      GradientDrawable bg = new GradientDrawable();
      bg.setColor(this.col(color.surface));
      bg.setCornerRadius((float)this.dp(16));
      bg.setStroke(this.dp(1), this.col(color.border));
      card.setBackground(bg);
      card.setPadding(this.dp(16), this.dp(14), this.dp(16), this.dp(10));
      TextView h = new TextView(this.activity);
      h.setText(title);
      h.setTextColor(this.col(color.text));
      h.setTextSize(15.0F);
      h.setTypeface((Typeface)null, 1);
      card.addView(h);
      ScrollView sv = new ScrollView(this.activity);
      LinearLayout list = new LinearLayout(this.activity);
      list.setOrientation(1);
      sv.addView(list);
      LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 0, 1.0F);
      slp.setMargins(0, this.dp(10), 0, 0);
      card.addView(sv, slp);
      Dialog dlg = new Dialog(this.activity);

      for(int i = 0; i < items.length; ++i) {
         boolean selected = i == currentIndex;
         LinearLayout row = new LinearLayout(this.activity);
         row.setOrientation(1);
         GradientDrawable rowBg = new GradientDrawable();
         rowBg.setColor(selected ? this.col(color.accentSoft) : this.col(color.surfaceInput));
         rowBg.setCornerRadius((float)this.dp(12));
         rowBg.setStroke(this.dp(1), selected ? this.col(color.accent) : this.col(color.border));
         row.setBackground(rowBg);
         row.setPadding(this.dp(12), this.dp(10), this.dp(12), this.dp(10));
         TextView t = new TextView(this.activity);
         t.setText(items[i]);
         t.setTextColor(selected ? this.col(color.accent) : this.col(color.text));
         t.setTextSize(14.0F);
         if (selected) {
            t.setTypeface((Typeface)null, 1);
         }

         row.addView(t);
         if (subs != null && i < subs.length && subs[i] != null && !subs[i].isEmpty()) {
            TextView d = new TextView(this.activity);
            d.setText(subs[i]);
            d.setTextColor(this.col(color.muted));
            d.setTextSize(11.0F);
            d.setPadding(0, this.dp(3), 0, 0);
            row.addView(d);
         }

         final int selIdx = i;
         row.setOnClickListener((v) -> {
            dlg.dismiss();
            onPick.accept(selIdx);
         });
         LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
         if (i > 0) {
            rlp.setMargins(0, this.dp(3), 0, 0);
         }

         list.addView(row, rlp);
      }

      Button cancel = this.ghostButton("取消");
      LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
      clp.setMargins(0, this.dp(8), 0, 0);
      card.addView(cancel, clp);
      dlg.setContentView(card);
      if (dlg.getWindow() != null) {
         dlg.getWindow().setBackgroundDrawable(new ColorDrawable(0));
         WindowManager.LayoutParams lp = dlg.getWindow().getAttributes();
         lp.width = (int)((float)this.activity.getResources().getDisplayMetrics().widthPixels * 0.88F);

         int rowsDp = 0;
         for (int i = 0; i < items.length; ++i) {
            rowsDp += (subs != null && i < subs.length && subs[i] != null && !subs[i].isEmpty()) ? 68 : 46;
         }
         int estDp = 100 + rowsDp;
         int maxPx = (int)((float)this.activity.getResources().getDisplayMetrics().heightPixels * 0.7F);
         lp.height = Math.min(this.dp(estDp), maxPx);
         dlg.getWindow().setAttributes(lp);
      }

      cancel.setOnClickListener((v) -> dlg.dismiss());
      dlg.show();
   }

   private void showStyledThinkPicker(View anchor, int current, IntConsumer onPick) {

      String[] icons = new String[]{"⚡", "\ud83d\udca4", "\ud83e\udde0", "\ud83d\udd2c", "\ud83d\udd2c", "\ud83d\ude80"};
      String[] titles = ReasoningNegotiator.levelNames();
      String[] descs = new String[]{"直接回答，最快", "想一点点", "先想再答", "想得更深",
              "想得很深，慢一些", "想得最深，最慢"};
      LinearLayout card = new LinearLayout(this.activity);
      card.setOrientation(1);
      GradientDrawable bg = new GradientDrawable();
      bg.setColor(this.col(color.surface));
      bg.setCornerRadius((float)this.dp(16));
      bg.setStroke(this.dp(1), this.col(color.border));
      card.setBackground(bg);
      card.setPadding(this.dp(6), this.dp(6), this.dp(6), this.dp(6));
      card.setElevation((float)this.dp(10));
      PopupWindow popup = new PopupWindow(card, this.dp(240), -2, true);
      popup.setBackgroundDrawable(new ColorDrawable(0));
      popup.setOutsideTouchable(true);
      popup.setElevation((float)this.dp(10));

      current = Math.max(0, Math.min(titles.length - 1, current));
      for(int i = 0; i < titles.length; ++i) {
         boolean selected = i == current;
         LinearLayout row = new LinearLayout(this.activity);
         row.setOrientation(0);
         row.setGravity(16);
         GradientDrawable rowBg = new GradientDrawable();
         rowBg.setColor(selected ? this.col(color.accentSoft) : this.col(color.surfaceInput));
         rowBg.setCornerRadius((float)this.dp(12));
         rowBg.setStroke(this.dp(1), selected ? this.col(color.accent) : this.col(color.border));
         row.setBackground(rowBg);
         row.setPadding(this.dp(12), this.dp(10), this.dp(12), this.dp(10));
         TextView ic = new TextView(this.activity);
         ic.setText(icons[i]);
         ic.setTextSize(16.0F);
         row.addView(ic);
         LinearLayout texts = new LinearLayout(this.activity);
         texts.setOrientation(1);
         LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1.0F);
         tlp.setMarginStart(this.dp(10));
         TextView t = new TextView(this.activity);
         t.setText(titles[i]);
         t.setTextColor(selected ? this.col(color.accent) : this.col(color.text));
         t.setTextSize(14.0F);
         if (selected) {
            t.setTypeface((Typeface)null, 1);
         }

         texts.addView(t);
         TextView d = new TextView(this.activity);
         d.setText(descs[i]);
         d.setTextColor(this.col(color.muted));
         d.setTextSize(11.0F);
         texts.addView(d);
         row.addView(texts, tlp);
         if (selected) {
            TextView check = new TextView(this.activity);
            check.setText("✓");
            check.setTextColor(this.col(color.accent));
            check.setTextSize(15.0F);
            check.setTypeface((Typeface)null, 1);
            row.addView(check);
         }

         final int popupIdx = i;
         row.setOnClickListener((v2) -> {
            onPick.accept(popupIdx);
            popup.dismiss();
         });
         LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
         if (i > 0) {
            rowLp.setMargins(0, this.dp(2), 0, 0);
         }

         card.addView(row, rowLp);
      }

      card.measure(MeasureSpec.makeMeasureSpec(this.dp(240), 1073741824), MeasureSpec.makeMeasureSpec(0, 0));
      int h = card.getMeasuredHeight();
      int[] loc = new int[2];
      anchor.getLocationOnScreen(loc);
      int y = loc[1] - h - this.dp(8);
      if (y < this.dp(80)) {
         y = loc[1] + anchor.getHeight() + this.dp(8);
      }

      popup.showAtLocation(anchor.getRootView(), 0, loc[0], y);
   }

   private int dp(int v) {
      return Math.round((float)v * this.activity.getResources().getDisplayMetrics().density);
   }

   private int col(int res) {
      return this.activity.getResources().getColor(res);
   }

   private String fmt(int n) {
      return n >= 10000 ? String.format(Locale.US, "%.1f万", (double)n / (double)10000.0F) : String.valueOf(n);
   }

   private String hhmm(long ts) {
      return (new SimpleDateFormat("HH:mm", Locale.CHINA)).format(new Date(ts));
   }

   private Button smallCardButton(String text, int bgRes, int textColorRes) {
      return this.smallCardButton(text, bgRes, textColorRes, 12.0F);
   }

   private Button smallCardButton(String text, int bgRes, int textColorRes, float textSizeSp) {
      Button b = new Button(this.activity);
      b.setText(text);
      b.setTextColor(this.col(textColorRes));
      b.setTextSize(textSizeSp);
      b.setAllCaps(false);
      b.setBackgroundResource(bgRes);
      b.setMinHeight(0);
      b.setMinimumHeight(0);
      b.setMinWidth(0);
      b.setMinimumWidth(0);
      b.setPadding(0, 0, 0, 0);
      b.setGravity(17);
      b.setIncludeFontPadding(false);
      return b;
   }

   private Button ghostButton(String text) {
      Button b = new Button(this.activity);
      b.setText(text);
      b.setTextColor(this.col(color.muted));
      b.setTextSize(12.0F);
      b.setAllCaps(false);
      b.setBackgroundResource(drawable.btn_ghost);
      b.setMinHeight(0);
      b.setMinimumHeight(0);
      b.setMinWidth(0);
      b.setMinimumWidth(0);
      b.setPadding(this.dp(14), this.dp(6), this.dp(14), this.dp(6));
      return b;
   }

   private Button primaryButton(String text, int color) {
      Button b = new Button(this.activity);
      b.setText(text);
      b.setTextColor(color);
      b.setTextSize(12.0F);
      b.setAllCaps(false);
      b.setBackgroundResource(drawable.btn_primary);
      b.setMinHeight(0);
      b.setMinimumHeight(0);
      b.setMinWidth(0);
      b.setMinimumWidth(0);
      b.setPadding(this.dp(16), this.dp(6), this.dp(16), this.dp(6));
      return b;
   }

   private EditText field(LinearLayout parent, String label, String value, String hint) {
      TextView l = new TextView(this.activity);
      l.setText(label);
      l.setTextColor(this.col(color.muted));
      l.setTextSize(11.0F);
      LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
      llp.setMargins(0, this.dp(10), 0, 0);
      parent.addView(l, llp);
      EditText e = new EditText(this.activity);
      e.setText(value == null ? "" : value);
      e.setHint(hint == null ? "" : hint);
      e.setBackgroundResource(drawable.bg_edit);
      e.setPadding(this.dp(12), this.dp(12), this.dp(12), this.dp(12));
      e.setTextColor(this.col(color.text));
      e.setHintTextColor(this.col(color.muted));
      e.setTextSize(13.0F);
      LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-1, -2);
      elp.setMargins(0, this.dp(4), 0, 0);
      parent.addView(e, elp);

      MaskedFieldMenu.attach(e);
      MaskedFieldMenu.addPasteButton(e);
      return e;
   }

   private void fieldNote(LinearLayout parent, String note) {
      if (note == null || note.isEmpty()) return;
      TextView t = new TextView(this.activity);
      t.setText(note);
      t.setTextColor(this.col(color.muted));
      t.setTextSize(10.5F);
      t.setLineSpacing(0.0F, 1.15F);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
      lp.setMargins(this.dp(2), this.dp(4), 0, 0);
      parent.addView(t, lp);
   }

   private void attachPasswordToggleGM(EditText et) {

      MaskedFieldMenu.attachMaskToggle(et);
   }

   private void confirmDanger(String title, String message, Runnable onConfirm) {

      this.confirmDanger(title, message, "删除", onConfirm);
   }

   private void confirmDanger(String title, String message, String okLabel, Runnable onConfirm) {
      LinearLayout box = this.dialogBox(title, message);
      LinearLayout row = new LinearLayout(this.activity);
      row.setGravity(8388613);
      LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
      rlp.setMargins(0, this.dp(16), 0, 0);
      row.setLayoutParams(rlp);
      Button cancel = this.ghostButton("取消");
      Button ok = this.primaryButton(okLabel, this.col(color.danger));
      row.addView(cancel);
      LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-2, -2);
      olp.setMarginStart(this.dp(8));
      row.addView(ok, olp);
      box.addView(row);
      Dialog dlg = this.showDialog(box);
      cancel.setOnClickListener((v) -> dlg.dismiss());
      ok.setOnClickListener((v) -> {
         dlg.dismiss();
         onConfirm.run();
      });
      dlg.show();
   }

   private void confirmDeleteMember(AppDatabase.GmMember m) {
      LinearLayout box = this.dialogBox("删除角色", "将删除「" + (m.name == null ? "该角色" : m.name) + "」。\n\n它的历史发言会保留在群聊记录里，但之后不再参与讨论。");
      LinearLayout row = new LinearLayout(this.activity);
      row.setGravity(8388613);
      LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
      rlp.setMargins(0, this.dp(16), 0, 0);
      row.setLayoutParams(rlp);
      Button cancel = this.ghostButton("取消");
      Button ok = this.primaryButton("删除", this.col(color.danger));
      ok.setBackgroundResource(drawable.btn_danger);
      row.addView(cancel);
      LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-2, -2);
      olp.setMarginStart(this.dp(8));
      row.addView(ok, olp);
      box.addView(row);
      Dialog dlg = this.showDialog(box);
      cancel.setOnClickListener((v) -> dlg.dismiss());
      ok.setOnClickListener((v) -> {
         this.db.deleteGmMember(m.id);
         dlg.dismiss();
         this.showMemberManager();
      });
   }

   private LinearLayout dialogBox(String title, String message) {
      LinearLayout box = new LinearLayout(this.activity);
      box.setOrientation(1);
      GradientDrawable bg = new GradientDrawable();
      bg.setColor(this.col(color.surface));
      bg.setCornerRadius((float)this.dp(20));
      bg.setStroke(this.dp(1), this.col(color.border));
      box.setBackground(bg);
      box.setPadding(this.dp(18), this.dp(18), this.dp(18), this.dp(14));
      TextView h = new TextView(this.activity);
      h.setText(title);
      h.setTextColor(this.col(color.text));
      h.setTextSize(16.0F);
      h.setTypeface((Typeface)null, 1);
      box.addView(h);
      TextView m = new TextView(this.activity);
      m.setText(message);
      m.setTextColor(this.col(color.muted));
      m.setTextSize(13.0F);
      m.setLineSpacing((float)this.dp(3), 1.0F);
      m.setPadding(0, this.dp(8), 0, 0);
      box.addView(m);
      return box;
   }

   private Dialog showDialog(View box) {
      Dialog dlg = new Dialog(this.activity);
      dlg.setContentView(box);
      if (dlg.getWindow() != null) {
         dlg.getWindow().setBackgroundDrawable(new ColorDrawable(0));
         WindowManager.LayoutParams lp = dlg.getWindow().getAttributes();
         lp.width = (int)((float)this.activity.getResources().getDisplayMetrics().widthPixels * 0.88F);
         dlg.getWindow().setAttributes(lp);
      }

      dlg.show();
      return dlg;
   }

   private Dialog showDialogWide(View box) {
      Dialog dlg = new Dialog(this.activity);
      dlg.setContentView(box);

      try {
         if (box instanceof ViewGroup) MaskedFieldMenu.attachTree((ViewGroup) box);
      } catch (Throwable t) {
         android.util.Log.w("VC_GM", "弹窗输入框粘贴按钮初始化失败: " + t);
      }
      if (dlg.getWindow() != null) {
         dlg.getWindow().setBackgroundDrawable(new ColorDrawable(0));
         DisplayMetrics dm = this.activity.getResources().getDisplayMetrics();
         int w = (int)((float)dm.widthPixels * 0.94F);
         int maxH = (int)((float)dm.heightPixels * 0.88F);
         int natural = maxH;
         try {
            box.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.AT_MOST),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int m = box.getMeasuredHeight();
            if (m > 0) natural = m;
         } catch (Exception ignored) { }
         WindowManager.LayoutParams lp = dlg.getWindow().getAttributes();
         lp.width = w;
         lp.height = Math.min(natural, maxH);
         dlg.getWindow().setAttributes(lp);
      }

      dlg.show();
      return dlg;
   }

   public interface AttachListener {
      void onPickDoc(View var1, EditText var2);

      void onPickImage(View var1, EditText var2);
   }

   private void showBasicInfoManager() {
      final Dialog dlg = new Dialog(this.activity);
      LinearLayout box = new LinearLayout(this.activity);
      box.setOrientation(1);
      GradientDrawable bg = new GradientDrawable();
      bg.setColor(this.col(color.surface));
      bg.setCornerRadius((float) this.dp(20));
      bg.setStroke(this.dp(1), this.col(color.border));
      box.setBackground(bg);
      box.setPadding(this.dp(18), this.dp(16), this.dp(18), this.dp(14));

      LinearLayout head = new LinearLayout(this.activity);
      head.setOrientation(0);
      head.setGravity(16);
      TextView h = new TextView(this.activity);
      h.setText("\ud83d\udccc 基础信息");
      h.setTextColor(this.col(color.text));
      h.setTextSize(16.0F);
      h.setTypeface((Typeface) null, 1);
      head.addView(h, new LinearLayout.LayoutParams(0, -2, 1.0F));
      box.addView(head);

      TextView sub = new TextView(this.activity);
      sub.setVisibility(android.view.View.GONE);
      sub.setTextColor(this.col(color.muted));
      sub.setTextSize(11.5F);
      sub.setLineSpacing((float) this.dp(3), 1.0F);
      LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
      slp.setMargins(0, this.dp(6), 0, 0);
      box.addView(sub, slp);

      ScrollView sv = new ScrollView(this.activity);
      LinearLayout list = new LinearLayout(this.activity);
      list.setOrientation(1);
      sv.addView(list);
      LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(-1, 0, 1.0F);
      svlp.setMargins(0, this.dp(12), 0, 0);
      box.addView(sv, svlp);

      final Runnable[] rebuild = new Runnable[1];
      rebuild[0] = () -> {
         list.removeAllViews();
         List<AppDatabase.GmBasicInfo> all = this.db.listGmBasicInfo();
         if (all.isEmpty()) {
            TextView empty = new TextView(this.activity);
            empty.setText("还没有基础信息。");
            empty.setTextColor(this.col(color.muted));
            empty.setTextSize(12.5F);
            empty.setLineSpacing((float) this.dp(4), 1.0F);
            empty.setPadding(this.dp(2), this.dp(10), this.dp(2), this.dp(10));
            list.addView(empty);
         }
         for (AppDatabase.GmBasicInfo b : all) {
            list.addView(this.buildBasicInfoCard(b, dlg, rebuild[0]));
         }
      };
      rebuild[0].run();

      LinearLayout btnRow = new LinearLayout(this.activity);
      btnRow.setOrientation(0);
      int infoCount = 0;
      try { infoCount = this.db.countGmQaArchive(); } catch (Exception ignored) { }
      Button add = this.rowActionButton("＋ 添加", true, (v) -> this.showBasicInfoEditor(null, rebuild[0]));
      Button qa = this.rowActionButton("\ud83d\udcda 用户信息库"
              + (infoCount > 0 ? "（" + infoCount + "）" : ""), false,
              (v) -> this.showQaArchiveDialog());
      Button close = this.rowActionButton("关闭", false, (v) -> dlg.dismiss());
      btnRow.addView(add, new LinearLayout.LayoutParams(0, -2, 1.0F));
      LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(0, -2, 1.0F);
      qlp.setMarginStart(this.dp(8));
      btnRow.addView(qa, qlp);
      LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1.0F);
      clp.setMarginStart(this.dp(8));
      btnRow.addView(close, clp);
      LinearLayout.LayoutParams brlp = new LinearLayout.LayoutParams(-1, -2);
      brlp.setMargins(0, this.dp(12), 0, 0);
      box.addView(btnRow, brlp);

      dlg.setContentView(box);
      if (dlg.getWindow() != null) {
         dlg.getWindow().setBackgroundDrawable(new ColorDrawable(0));
         WindowManager.LayoutParams lp = dlg.getWindow().getAttributes();
         lp.width = (int) ((float) this.activity.getResources().getDisplayMetrics().widthPixels * 0.9F);
         lp.height = (int) ((float) this.activity.getResources().getDisplayMetrics().heightPixels * 0.78F);
         dlg.getWindow().setAttributes(lp);
      }
      dlg.show();
   }

   private Button rowActionButton(String text, boolean primary, View.OnClickListener onClick) {
      Button b = new Button(this.activity);
      b.setText(text);
      b.setTextColor(this.col(primary ? color.onAccent : color.accent));
      b.setTextSize(13.0F);
      b.setAllCaps(false);
      b.setBackgroundResource(primary ? drawable.btn_primary : drawable.btn_secondary);
      b.setMinHeight(0);
      b.setMinimumHeight(0);
      b.setMinWidth(0);
      b.setMinimumWidth(0);
      b.setPadding(this.dp(8), this.dp(8), this.dp(8), this.dp(8));
      b.setSingleLine(true);
      b.setEllipsize(android.text.TextUtils.TruncateAt.END);
      if (onClick != null) b.setOnClickListener(onClick);
      return b;
   }

   private View buildBasicInfoCard(AppDatabase.GmBasicInfo b, Dialog parent, Runnable onChanged) {
      LinearLayout card = new LinearLayout(this.activity);
      card.setOrientation(1);
      GradientDrawable cbg = new GradientDrawable();
      cbg.setColor(this.col(color.surfaceInput));
      cbg.setCornerRadius((float) this.dp(12));
      cbg.setStroke(this.dp(1), this.col(color.border));
      card.setBackground(cbg);
      card.setPadding(this.dp(13), this.dp(11), this.dp(13), this.dp(11));
      LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
      clp.setMargins(0, 0, 0, this.dp(9));
      card.setLayoutParams(clp);

      String content = b.content == null ? "" : b.content.trim();
      if (content.isEmpty()) content = b.title == null ? "" : b.title.trim();
      TextView body = new TextView(this.activity);
      body.setText(content);
      body.setTextColor(this.col(color.text));
      body.setTextSize(13.0F);
      body.setLineSpacing((float) this.dp(3), 1.0F);
      card.addView(body);

      LinearLayout footRow = new LinearLayout(this.activity);
      footRow.setOrientation(0);
      footRow.setGravity(16);
      TextView del = new TextView(this.activity);
      del.setText("删除");
      del.setTextColor(this.col(color.danger));
      del.setTextSize(11.5F);
      del.setPadding(this.dp(8), this.dp(2), this.dp(2), this.dp(2));

      del.setOnClickListener((v) -> {

         String pv = b.content == null ? "" : b.content.trim().replace("\n", " ");
         if (this.isBlankSafe(pv)) pv = b.title == null ? "" : b.title.trim();
         if (pv.length() > 80) pv = pv.substring(0, 80) + "…";
         this.confirmDanger("删除这条基础信息？",
                 pv + "\n\n删除后无法恢复（角色们下一轮起就看不到它了）。",
                 () -> {
                    this.db.deleteGmBasicInfo(b.id);
                    if (onChanged != null) onChanged.run();
                 });
      });
      footRow.addView(new android.view.View(this.activity), new LinearLayout.LayoutParams(0, -2, 1.0F));
      footRow.addView(del);
      LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
      flp.setMargins(0, this.dp(8), 0, 0);
      card.addView(footRow, flp);

      card.setOnClickListener((v) -> this.showBasicInfoEditor(b, onChanged));
      return card;
   }

   private void showBasicInfoEditor(AppDatabase.GmBasicInfo editing, Runnable onSaved) {
      final Dialog dlg = new Dialog(this.activity);
      LinearLayout box = new LinearLayout(this.activity);
      box.setOrientation(1);
      GradientDrawable bg = new GradientDrawable();
      bg.setColor(this.col(color.surface));
      bg.setCornerRadius((float) this.dp(20));
      bg.setStroke(this.dp(1), this.col(color.border));
      box.setBackground(bg);
      box.setPadding(this.dp(18), this.dp(16), this.dp(18), this.dp(14));

      TextView h = new TextView(this.activity);
      h.setText(editing == null ? "添加基础信息" : "编辑基础信息");
      h.setTextColor(this.col(color.text));
      h.setTextSize(16.0F);
      h.setTypeface((Typeface) null, 1);
      box.addView(h);

      final EditText eBody = new EditText(this.activity);
      String init = editing == null ? "" : (editing.content == null ? "" : editing.content);
      eBody.setText(init);
      eBody.setHint("例如：我有域名也备案了，营业执照也有，服务器也有；"
              + "主要想卖实物和虚拟两种商品，电脑端也想能管理…");
      eBody.setBackgroundResource(drawable.bg_edit);
      eBody.setPadding(this.dp(12), this.dp(12), this.dp(12), this.dp(12));
      eBody.setTextColor(this.col(color.text));
      eBody.setHintTextColor(this.col(color.muted));
      eBody.setTextSize(13.0F);
      eBody.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
      eBody.setMinLines(6);
      eBody.setVerticalScrollBarEnabled(false);
      eBody.setInputType(android.text.InputType.TYPE_CLASS_TEXT
              | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);

      MaxHeightScrollView bodyWrap = new MaxHeightScrollView(this.activity);
      bodyWrap.setMaxHeightPx((int) ((float) this.activity.getResources()
              .getDisplayMetrics().heightPixels * 0.55F));
      bodyWrap.addView(eBody, new android.widget.FrameLayout.LayoutParams(-1, -2));
      LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-1, -2);
      elp.setMargins(0, this.dp(10), 0, 0);
      box.addView(bodyWrap, elp);

      LinearLayout btnRow = new LinearLayout(this.activity);
      btnRow.setOrientation(0);
      Button save = this.rowActionButton(editing == null ? "保存" : "保存修改", true, null);
      save.setOnClickListener((v) -> {
         String bo = eBody.getText().toString().trim();
         if (bo.isEmpty()) {
            this.showToast("还没写内容");
            return;
         }

         String ti = bo.length() > 24 ? bo.substring(0, 24) + "…" : bo;
         if (editing == null) {
            this.db.insertGmBasicInfo(ti, bo, "");
         } else {
            this.db.updateGmBasicInfo(editing.id, ti, bo, "");
         }
         dlg.dismiss();
         this.showToast(editing == null ? "已添加" : "已保存");
         if (onSaved != null) onSaved.run();
      });
      Button cancel = this.rowActionButton("取消", false, (v) -> dlg.dismiss());
      btnRow.addView(save, new LinearLayout.LayoutParams(0, -2, 1.0F));
      LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1.0F);
      clp.setMarginStart(this.dp(8));
      btnRow.addView(cancel, clp);
      LinearLayout.LayoutParams brlp = new LinearLayout.LayoutParams(-1, -2);
      brlp.setMargins(0, this.dp(14), 0, 0);
      box.addView(btnRow, brlp);

      dlg.setContentView(box);
      if (dlg.getWindow() != null) {
         dlg.getWindow().setBackgroundDrawable(new ColorDrawable(0));
         WindowManager.LayoutParams lp = dlg.getWindow().getAttributes();
         lp.width = (int) ((float) this.activity.getResources().getDisplayMetrics().widthPixels * 0.9F);
         lp.height = -2;
         dlg.getWindow().setAttributes(lp);
      }
      dlg.show();
   }

   private void showQaArchiveDialog() {
      final Dialog dlg = new Dialog(this.activity);
      LinearLayout box = new LinearLayout(this.activity);
      box.setOrientation(1);
      GradientDrawable bg = new GradientDrawable();
      bg.setColor(this.col(color.surface));
      bg.setCornerRadius((float) this.dp(20));
      bg.setStroke(this.dp(1), this.col(color.border));
      box.setBackground(bg);
      box.setPadding(this.dp(18), this.dp(16), this.dp(18), this.dp(14));

      List<AppDatabase.GmQaPair> all = null;
      try { all = this.db.listGmQaArchive(); } catch (Exception ignored) { }
      if (all == null) all = new ArrayList<>();

      int roomCount = 0, answeredCount = 0;
      String lastRoom = null;
      for (AppDatabase.GmQaPair p : all) {
         if (p.roomTitle != null && !p.roomTitle.equals(lastRoom)) { ++roomCount; lastRoom = p.roomTitle; }
         if (p.answered) ++answeredCount;
      }

      TextView h = new TextView(this.activity);
      h.setText("\ud83d\udcda 用户信息库");
      h.setTextColor(this.col(color.text));
      h.setTextSize(16.0F);
      h.setTypeface((Typeface) null, 1);
      box.addView(h);

      TextView sub = new TextView(this.activity);
      sub.setText(all.isEmpty()
              ? "还没有记录。"

              : ("共 " + roomCount + " 个群的 " + all.size() + " 组问答（已答 " + answeredCount
                  + " 组）。"));
      sub.setTextColor(this.col(color.muted));
      sub.setTextSize(11.5F);
      sub.setLineSpacing((float) this.dp(3), 1.0F);
      LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
      slp.setMargins(0, this.dp(6), 0, 0);
      box.addView(sub, slp);

      ScrollView sv = new ScrollView(this.activity);
      LinearLayout list = new LinearLayout(this.activity);
      list.setOrientation(1);
      sv.addView(list);
      LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(-1, 0, 1.0F);
      svlp.setMargins(0, this.dp(12), 0, 0);
      box.addView(sv, svlp);

      String curRoom = null;
      for (AppDatabase.GmQaPair p : all) {

         String title = (p.roomTitle == null || p.roomTitle.trim().isEmpty())
                 ? ("群聊 #" + p.roomId) : p.roomTitle.trim();
         if (!title.equals(curRoom)) {
            curRoom = title;
            LinearLayout groupHead = new LinearLayout(this.activity);
            groupHead.setOrientation(1);
            TextView gt = new TextView(this.activity);
            gt.setText("\ud83d\udcac " + title);
            gt.setTextColor(this.col(color.accent));
            gt.setTextSize(13.0F);
            gt.setTypeface((Typeface) null, 1);
            groupHead.addView(gt);
            String goal = p.roomGoal == null ? "" : p.roomGoal.trim();
            if (!goal.isEmpty() && !goal.equals(title)) {
               TextView gd = new TextView(this.activity);
               gd.setText(goal);
               gd.setTextColor(this.col(color.muted));
               gd.setTextSize(11.0F);
               gd.setLineSpacing((float) this.dp(2), 1.0F);
               gd.setPadding(0, this.dp(3), 0, 0);
               groupHead.addView(gd);
            }
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, -2);
            glp.setMargins(0, this.dp(14), 0, this.dp(6));
            list.addView(groupHead, glp);
         }

         LinearLayout pair = new LinearLayout(this.activity);
         pair.setOrientation(1);
         GradientDrawable pbg = new GradientDrawable();
         pbg.setColor(this.col(color.surfaceInput));
         pbg.setCornerRadius((float) this.dp(12));
         pbg.setStroke(this.dp(1), this.col(color.border));
         pair.setBackground(pbg);
         pair.setPadding(this.dp(13), this.dp(11), this.dp(13), this.dp(11));
         LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
         plp.setMargins(0, 0, 0, this.dp(8));
         pair.setLayoutParams(plp);

         TextView q = new TextView(this.activity);
         q.setText("\ud83d\udc64 " + (p.asker == null || p.asker.trim().isEmpty() ? "成员" : p.asker.trim())
                 + " 问：" + (p.question == null ? "" : p.question.trim()));
         q.setTextColor(this.col(color.text));
         q.setTextSize(12.5F);
         q.setLineSpacing((float) this.dp(3), 1.0F);
         pair.addView(q);

         TextView a = new TextView(this.activity);
         if (p.answered) {
            a.setText("\u2705 我的回答：" + (p.answer == null ? "" : p.answer.trim()));
            a.setTextColor(this.col(color.text2));
         } else {
            a.setText("\u26aa 我当时跳过了这题（没有回答）");
            a.setTextColor(this.col(color.muted));
         }
         a.setTextSize(12.5F);
         a.setLineSpacing((float) this.dp(3), 1.0F);
         LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
         alp.setMargins(0, this.dp(7), 0, 0);
         pair.addView(a, alp);

         list.addView(pair);
      }
      if (all.isEmpty()) {
         TextView empty = new TextView(this.activity);
         empty.setText("（空）");
         empty.setTextColor(this.col(color.muted));
         empty.setTextSize(12.5F);
         empty.setPadding(this.dp(2), this.dp(10), this.dp(2), 0);
         list.addView(empty);
      }

      Button close = this.ghostButton("关闭");
      close.setTextSize(13.0F);
      close.setPadding(this.dp(14), this.dp(10), this.dp(14), this.dp(10));
      close.setOnClickListener((v) -> dlg.dismiss());
      LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
      clp.setMargins(0, this.dp(12), 0, 0);
      box.addView(close, clp);

      dlg.setContentView(box);
      if (dlg.getWindow() != null) {
         dlg.getWindow().setBackgroundDrawable(new ColorDrawable(0));
         WindowManager.LayoutParams lp = dlg.getWindow().getAttributes();
         lp.width = (int) ((float) this.activity.getResources().getDisplayMetrics().widthPixels * 0.92F);
         lp.height = (int) ((float) this.activity.getResources().getDisplayMetrics().heightPixels * 0.82F);
         dlg.getWindow().setAttributes(lp);
      }
      dlg.show();
   }

   private void showCitationsDialog(long roomId) {
      final Dialog dlg = new Dialog(this.activity);
      LinearLayout box = new LinearLayout(this.activity);
      box.setOrientation(1);

      GradientDrawable bg = new GradientDrawable();
      bg.setColor(this.col(color.surface));
      bg.setCornerRadius((float) this.dp(20));
      bg.setStroke(this.dp(1), this.col(color.border));
      box.setBackground(bg);
      box.setPadding(this.dp(18), this.dp(16), this.dp(18), this.dp(14));

      List<AppDatabase.GmCitation> all = null;
      try { all = this.db.listGmCitations(roomId); } catch (Exception ignored) { }
      if (all == null) all = new ArrayList<>();
      int demandCount = 0;
      try { demandCount = this.db.countGmCitationDemands(roomId); } catch (Exception ignored) { }

      java.util.HashSet<String> distinctPairs = new java.util.HashSet<>();
      for (AppDatabase.GmCitation cx : all) distinctPairs.add(cx.demandRoomId + "\u0000" + cx.question);
      final int atLeastOnce = distinctPairs.size();

      final int cAsk = this.col(color.accent);
      final int cQ = 0xFF7A4BD0;
      final int cA = 0xFF2E7D46;
      final int cABg = 0xFFEAF5EC;
      final int cQBg = 0xFFF3EEFC;

      TextView h = new TextView(this.activity);
      h.setText("\ud83d\udcce 引用");
      h.setTextColor(this.col(color.text));
      h.setTextSize(16.0F);
      h.setTypeface((Typeface) null, 1);
      box.addView(h);

      TextView sub = new TextView(this.activity);
      sub.setText(all.isEmpty()
              ? "这个群还没有引用过旧问答。"

              : ("这个群有 " + atLeastOnce + " 组旧问答被角色抓进了上下文（共 " + demandCount
                  + " 套需求、累计 " + all.size() + " 次引用）。"));
      sub.setTextColor(this.col(color.muted));
      sub.setTextSize(11.5F);
      sub.setLineSpacing((float) this.dp(3), 1.0F);
      LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
      slp.setMargins(0, this.dp(6), 0, 0);
      box.addView(sub, slp);

      ScrollView sv = new ScrollView(this.activity);
      LinearLayout list = new LinearLayout(this.activity);
      list.setOrientation(1);
      sv.addView(list);
      LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(-1, 0, 1.0F);
      svlp.setMargins(0, this.dp(12), 0, 0);
      box.addView(sv, svlp);

      long curDemand = Long.MIN_VALUE;
      LinearLayout demandBody = null;
      for (AppDatabase.GmCitation x : all) {

         if (x.demandRoomId != curDemand) {
            curDemand = x.demandRoomId;
            LinearLayout demand = new LinearLayout(this.activity);
            demand.setOrientation(1);
            GradientDrawable dbg = new GradientDrawable();
            dbg.setColor(this.col(color.accentSoft));
            dbg.setCornerRadius((float) this.dp(14));
            dbg.setStroke(this.dp(1), this.col(color.accent));
            demand.setBackground(dbg);
            demand.setPadding(this.dp(12), this.dp(11), this.dp(12), this.dp(12));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, -2);
            dlp.setMargins(0, this.dp(6), 0, this.dp(12));
            demand.setLayoutParams(dlp);

            TextView dt = new TextView(this.activity);
            String t = x.demandTitle == null || x.demandTitle.trim().isEmpty()
                    ? ("旧需求 #" + x.demandRoomId) : x.demandTitle.trim();
            dt.setText("\ud83d\udccc 需求：\u300c" + t + "\u300d");
            dt.setTextColor(this.col(color.accent));
            dt.setTextSize(12.5F);
            dt.setTypeface((Typeface) null, 1);
            dt.setLineSpacing((float) this.dp(2), 1.0F);
            demand.addView(dt);

            String goal = x.demandGoal == null ? "" : x.demandGoal.trim();

            goal = goal.replaceAll("\\s*\\n+\\s*", " ").trim();
            if (goal.length() > 90) goal = goal.substring(0, 90) + "…";
            if (!goal.isEmpty() && !goal.equals(t)) {
               TextView dg = new TextView(this.activity);
               dg.setText(goal);
               dg.setTextColor(this.col(color.text2));
               dg.setTextSize(11.5F);
               dg.setLineSpacing((float) this.dp(2), 1.0F);
               LinearLayout.LayoutParams dglp = new LinearLayout.LayoutParams(-1, -2);
               dglp.setMargins(0, this.dp(4), 0, 0);
               demand.addView(dg, dglp);
            }

            demandBody = new LinearLayout(this.activity);
            demandBody.setOrientation(1);
            LinearLayout.LayoutParams dblp = new LinearLayout.LayoutParams(-1, -2);
            dblp.setMargins(0, this.dp(8), 0, 0);
            demand.addView(demandBody, dblp);
            list.addView(demand);
         }
         if (demandBody == null) continue;

         LinearLayout pair = new LinearLayout(this.activity);
         pair.setOrientation(1);
         GradientDrawable pbg = new GradientDrawable();
         pbg.setColor(this.col(color.surface));
         pbg.setCornerRadius((float) this.dp(11));
         pbg.setStroke(this.dp(1), this.col(color.border));
         pair.setBackground(pbg);
         pair.setPadding(this.dp(11), this.dp(10), this.dp(11), this.dp(10));
         LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
         plp.setMargins(0, 0, 0, this.dp(8));
         pair.setLayoutParams(plp);

         TextView who = new TextView(this.activity);
         String asker = x.asker == null || x.asker.trim().isEmpty() ? "成员" : x.asker.trim();
         String by = x.memberName == null || x.memberName.trim().isEmpty() ? "某角色" : x.memberName.trim();
         who.setText("\ud83d\udc64 " + asker + " 提问　·　" + by + " 引用了这组");
         who.setTextColor(0xFFFFFFFF);
         who.setTextSize(11.0F);
         GradientDrawable wbg = new GradientDrawable();
         wbg.setColor(cAsk);
         wbg.setCornerRadius((float) this.dp(8));
         who.setBackground(wbg);
         who.setPadding(this.dp(8), this.dp(4), this.dp(8), this.dp(4));
         who.setLineSpacing((float) this.dp(2), 1.0F);
         pair.addView(who);

         TextView q = new TextView(this.activity);
         q.setText("❓ 问题：" + (x.question == null ? "" : x.question.trim()));
         q.setTextColor(cQ);
         q.setTextSize(12.0F);
         q.setLineSpacing((float) this.dp(3), 1.0F);
         GradientDrawable qbg = new GradientDrawable();
         qbg.setColor(cQBg);
         qbg.setCornerRadius((float) this.dp(9));
         q.setBackground(qbg);
         q.setPadding(this.dp(9), this.dp(7), this.dp(9), this.dp(7));
         LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(-1, -2);
         qlp.setMargins(0, this.dp(7), 0, 0);
         pair.addView(q, qlp);

         TextView a = new TextView(this.activity);
         if (x.answer == null || x.answer.trim().isEmpty()) {
            a.setText("⚪ 用户当时跳过了这题");
            a.setTextColor(this.col(color.muted));
            a.setBackground(null);
         } else {
            a.setText("✅ 用户当时的回答：" + x.answer.trim());
            a.setTextColor(cA);
            GradientDrawable abg = new GradientDrawable();
            abg.setColor(cABg);
            abg.setCornerRadius((float) this.dp(9));
            a.setBackground(abg);
            a.setPadding(this.dp(9), this.dp(7), this.dp(9), this.dp(7));
         }
         a.setTextSize(12.0F);
         a.setLineSpacing((float) this.dp(3), 1.0F);
         LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
         alp.setMargins(0, this.dp(6), 0, 0);
         pair.addView(a, alp);

         demandBody.addView(pair);
      }
      if (all.isEmpty()) {
         TextView empty = new TextView(this.activity);
         empty.setText("（空）");
         empty.setTextColor(this.col(color.muted));
         empty.setTextSize(12.5F);
         empty.setPadding(this.dp(2), this.dp(10), this.dp(2), 0);
         list.addView(empty);
      }

      TextView note = new TextView(this.activity);
      note.setText("\ud83d\udca1 这些是用户在过去某个时间点的回答，只能作为参考，不是当前事实——"
              + "情况会变，是否仍然成立由角色自己判断。");
      note.setTextColor(this.col(color.muted));
      note.setTextSize(11.0F);
      note.setLineSpacing((float) this.dp(2), 1.0F);
      LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
      nlp.setMargins(0, this.dp(10), 0, 0);
      box.addView(note, nlp);

      Button close = this.ghostButton("关闭");
      close.setTextSize(13.0F);
      close.setPadding(this.dp(14), this.dp(10), this.dp(14), this.dp(10));
      close.setOnClickListener((v) -> dlg.dismiss());
      LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
      clp.setMargins(0, this.dp(12), 0, 0);
      box.addView(close, clp);

      boolean hadUnread = false;
      for (AppDatabase.GmCitation cx : all) { if (!cx.isRead) { hadUnread = true; break; } }
      if (hadUnread) {
         try { this.db.markGmCitationsRead(roomId); } catch (Exception ignored) { }
      }

      if (hadUnread) {
         dlg.setOnDismissListener((d) -> {
            if (this.currentPage == 0) this.showRoomList();
         });
      }

      dlg.setContentView(box);
      if (dlg.getWindow() != null) {
         dlg.getWindow().setBackgroundDrawable(new ColorDrawable(0));
         WindowManager.LayoutParams lp = dlg.getWindow().getAttributes();
         lp.width = (int) ((float) this.activity.getResources().getDisplayMetrics().widthPixels * 0.92F);
         lp.height = (int) ((float) this.activity.getResources().getDisplayMetrics().heightPixels * 0.82F);
         dlg.getWindow().setAttributes(lp);
      }
      dlg.show();
   }
}
