package com.linyuan.app;

import android.app.Activity;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class GalleryView {

    private final Activity act;
    private final View panel;
    private LinearLayout grid;
    private TextView countView;

    private int columns = 2;

    public GalleryView(Activity act, View panel) {
        this.act = act;
        this.panel = panel;
        this.grid = panel.findViewById(R.id.galleryGridContainer);
        this.countView = panel.findViewById(R.id.galleryCount);
    }

    public boolean isVisible() {
        return panel != null && panel.getVisibility() == View.VISIBLE;
    }

    public View panelView() { return panel; }

    public void show() {
        if (panel == null) return;
        panel.setVisibility(View.VISIBLE);
        Button refresh = panel.findViewById(R.id.galleryRefreshButton);
        if (refresh != null && refresh.getTag() == null) {
            refresh.setTag("wired");
            refresh.setOnClickListener(v -> render());
        }
        render();
        View sv = panel.findViewById(R.id.galleryScroll);
        if (sv instanceof ScrollView) ((ScrollView) sv).scrollTo(0, 0);
    }

    public void hide() {
        if (panel != null) panel.setVisibility(View.GONE);
    }

    private List<File> listImages() {
        File dir = ImageGen.genDir(act);
        File[] fs = dir.listFiles((d, n) -> n.startsWith("img_") && n.endsWith(".png"));
        List<File> out = new ArrayList<>();
        if (fs != null) out.addAll(Arrays.asList(fs));
        out.sort(Comparator.comparing(File::getName).reversed());
        return out;
    }

    public void render() {
        if (grid == null) return;
        grid.removeAllViews();

        List<File> imgs = listImages();
        if (countView != null) {
            countView.setText(imgs.isEmpty() ? "还没有生成的图片" : ("共 " + imgs.size() + " 张"));
        }
        if (imgs.isEmpty()) {
            TextView empty = new TextView(act);
            empty.setText("还没有图片。");
            empty.setTextColor(0xFF98A0AE);
            empty.setTextSize(13);
            empty.setLineSpacing(0, 1.4f);
            empty.setPadding(dp(8), dp(24), dp(8), dp(8));
            grid.addView(empty);
            return;
        }

        LinearLayout row = null;
        for (int i = 0; i < imgs.size(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = dp(8);
                row.setLayoutParams(rlp);
                grid.addView(row);
            }
            View cell = buildCell(imgs.get(i));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            clp.rightMargin = (i % columns == columns - 1) ? 0 : dp(8);
            cell.setLayoutParams(clp);
            if (row != null) row.addView(cell);
        }
    }

    private View buildCell(final File img) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);

        android.graphics.drawable.GradientDrawable cardBg =
                new android.graphics.drawable.GradientDrawable();
        cardBg.setColor(0xFFFFFFFF);
        cardBg.setCornerRadius(dp(12));
        cardBg.setStroke(dp(1), 0xFFE7E1D6);
        box.setBackground(cardBg);
        box.setPadding(dp(6), dp(6), dp(6), dp(6));

        ImageView iv = new ImageView(act);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(150));
        iv.setLayoutParams(ilp);

        android.graphics.drawable.GradientDrawable thumbBg =
                new android.graphics.drawable.GradientDrawable();
        thumbBg.setColor(galleryBoxColor(act));
        thumbBg.setCornerRadius(dp(8));
        iv.setBackground(thumbBg);
        iv.setClipToOutline(true);

        Bitmap bm = decodeSampled(img, 300);
        if (bm != null) iv.setImageBitmap(bm);
        box.addView(iv);

        TextView cap = new TextView(act);
        cap.setTextSize(10);
        cap.setTextColor(0xFF6B7280);
        cap.setMaxLines(2);
        cap.setPadding(dp(2), dp(6), dp(2), dp(2));
        String meta = readPrompt(img);
        cap.setText(meta.isEmpty() ? timeLabel(img) : meta);
        box.addView(cap);

        box.setOnClickListener(v -> showFullScreen(img));
        return box;
    }

    /** 图/缩略图的底板色：跟着 StyledDialog.card 一起走主题（深色下白卡里不能再铺浅灰）。 */
    private static int galleryBoxColor(android.app.Activity act) {
        boolean dark = act.getSharedPreferences("voice_companion", android.content.Context.MODE_PRIVATE)
                .getBoolean("darkTheme", true);
        return dark ? 0xFF1A2233 : 0xFFF3F4F7;
    }

    private void showFullScreen(final File img) {
        LinearLayout root = StyledDialog.card(act);

        root.addView(StyledDialog.title(act, "🖼 " + timeLabel(img)));

        LinearLayout imgBox = new LinearLayout(act);
        android.graphics.drawable.GradientDrawable imgBoxBg =
                new android.graphics.drawable.GradientDrawable();
        imgBoxBg.setColor(galleryBoxColor(act));
        imgBoxBg.setCornerRadius(dp(12));
        imgBox.setBackground(imgBoxBg);
        imgBox.setPadding(dp(6), dp(6), dp(6), dp(6));
        LinearLayout.LayoutParams imgBoxLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        imgBoxLp.topMargin = dp(10);
        imgBox.setLayoutParams(imgBoxLp);

        final ZoomableImageView ziv = new ZoomableImageView(act);
        Bitmap bm = decodeSampled(img, 1600);
        if (bm != null) ziv.setImageBitmap(bm);
        ziv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        imgBox.addView(ziv);
        root.addView(imgBox);

        final LinearLayout paramBox = new LinearLayout(act);
        paramBox.setOrientation(LinearLayout.VERTICAL);
        fillParamCards(paramBox, img);
        final NestedLimitScrollView paramScroll = new NestedLimitScrollView(act);
        paramScroll.setMaxHeightPx(dp(190));
        paramScroll.addView(paramBox, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        paramScroll.setVisibility(View.GONE);
        LinearLayout.LayoutParams psLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        psLp.topMargin = dp(10);
        paramScroll.setLayoutParams(psLp);
        root.addView(paramScroll);

        Button save = StyledDialog.primaryBtn(act, "💾 保存到相册");
        Button params = StyledDialog.ghostBtn(act, "ℹ️ 参数");
        Button del = StyledDialog.dangerBtn(act, "🗑 删除");
        Button close = StyledDialog.ghostBtn(act, "关闭");
        root.addView(StyledDialog.buttonRows(act, save, params, del, close));

        final android.app.Dialog dlg = StyledDialog.showFixedHeight(act, root, 0.9f, 0.82f);
        save.setOnClickListener(v -> saveToGallery(img));
        params.setOnClickListener(v -> {
            boolean show = paramScroll.getVisibility() != View.VISIBLE;
            paramScroll.setVisibility(show ? View.VISIBLE : View.GONE);
            params.setText(show ? "ℹ️ 收起参数" : "ℹ️ 参数");
        });
        del.setOnClickListener(v -> {
            dlg.dismiss();
            confirmDelete(img);
        });
        close.setOnClickListener(v -> dlg.dismiss());
    }

    private void fillParamCards(LinearLayout box, File img) {
        try {
            File m = new File(img.getAbsolutePath() + ".json");
            String prompt = "", model = "", size = "";
            long created = 0;
            if (m.exists()) {
                JSONObject j = new JSONObject(readText(m));
                prompt = j.optString("prompt", "");
                model = j.optString("model", "");
                size = j.optString("size", "");
                created = j.optLong("createdAt", 0);
            }
            if (!prompt.isEmpty()) addParamCard(box, "✍️ 提示词", prompt);
            if (!model.isEmpty()) addParamCard(box, "🤖 模型", model);

            StringBuilder sizeText = new StringBuilder();
            if (!size.isEmpty()) {
                sizeText.append(size);
                if ("1024x1024".equals(size)) sizeText.append("（1:1 方形）");
                else if ("1792x1024".equals(size)) sizeText.append("（16:9 横版）");
                else if ("1024x1792".equals(size)) sizeText.append("（9:16 竖版）");
            }
            if (sizeText.length() > 0) addParamCard(box, "📐 尺寸", sizeText.toString());

            if (created > 0) {
                addParamCard(box, "🕐 生成时间", new SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date(created)));
            }
            addParamCard(box, "📦 文件", img.getName() + " · "
                    + String.format(Locale.CHINA, "%.0f KB", img.length() / 1024.0));
        } catch (Exception e) {
            addParamCard(box, "参数", "读取失败：" + e.getMessage());
        }
    }

    private void addParamCard(LinearLayout box, String label, String value) {
        LinearLayout card = StyledDialog.chip(act, label + "：" + value);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        card.setLayoutParams(lp);
        box.addView(card);
    }

    private void saveToGallery(File img) {
        try {
            String name = "vcgen_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA)
                    .format(new Date()) + ".png";
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            if (Build.VERSION.SDK_INT >= 29) {
                v.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/lin渊");
                v.put(MediaStore.Images.Media.IS_PENDING, 1);
            }
            Uri uri = act.getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) {
                toast("保存失败（拿不到相册写入位置）");
                return;
            }
            InputStream is = new java.io.FileInputStream(img);
            FileOutputStream os = (FileOutputStream) act.getContentResolver().openOutputStream(uri);
            if (os == null) { is.close(); toast("保存失败"); return; }
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
            os.close();
            is.close();
            if (Build.VERSION.SDK_INT >= 29) {

                ContentValues done = new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING, 0);
                act.getContentResolver().update(uri, done, null, null);
            }
            toast("已保存到系统相册（图库 → Pictures/lin渊）");
        } catch (Exception e) {
            toast("保存失败：" + e.getMessage());
        }
    }

    private void confirmDelete(final File img) {

        StyledDialog.confirm(act, "删除这张图片？",
                "从画廊里删掉它（不会影响已保存到系统相册的副本）。",
                "删除", true, () -> {
                    try {
                        img.delete();
                        File meta = new File(img.getAbsolutePath() + ".json");
                        if (meta.exists()) meta.delete();
                        toast("已删除");
                        render();
                    } catch (Exception e) {
                        toast("删除失败：" + e.getMessage());
                    }
                });
    }

    private String readPrompt(File img) {
        try {
            File m = new File(img.getAbsolutePath() + ".json");
            if (!m.exists()) return "";
            String t = readText(m);
            JSONObject j = new JSONObject(t);
            String p = j.optString("prompt", "");
            if (p.length() > 40) p = p.substring(0, 40) + "…";
            return p;
        } catch (Exception e) {
            return "";
        }
    }

    private String readMetaForDisplay(File img) {
        StringBuilder sb = new StringBuilder();
        try {
            File m = new File(img.getAbsolutePath() + ".json");
            if (m.exists()) {
                JSONObject j = new JSONObject(readText(m));
                String p = j.optString("prompt", "");
                if (!p.isEmpty()) sb.append("提示词：").append(p).append("\n");
                String model = j.optString("model", "");
                if (!model.isEmpty()) sb.append("模型：").append(model).append("\n");
                String size = j.optString("size", "");
                if (!size.isEmpty()) {
                    sb.append("尺寸：").append(size);
                    if ("1024x1024".equals(size)) sb.append("（1:1 方形）");
                    else if ("1792x1024".equals(size)) sb.append("（16:9 横版）");
                    else if ("1024x1792".equals(size)) sb.append("（9:16 竖版）");
                    sb.append("\n");
                }
                long t = j.optLong("createdAt", 0);
                if (t > 0) sb.append("生成时间：").append(
                        new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date(t))).append("\n");
            }
            sb.append("文件：").append(img.getName()).append("\n");
            sb.append("大小：").append(String.format(Locale.CHINA, "%.0f KB", img.length() / 1024.0));
        } catch (Exception e) {
            sb.append("（参数读取失败：" ).append(e.getMessage()).append("）");
        }
        return sb.toString();
    }

    private String timeLabel(File img) {
        String n = img.getName();

        try {
            String core = n.substring(4, n.length() - 4);
            String[] parts = core.split("_");
            if (parts.length >= 2) {
                String d = parts[0], t = parts[1];
                return d.substring(4, 6) + "-" + d.substring(6, 8) + " "
                        + t.substring(0, 2) + ":" + t.substring(2, 4);
            }
        } catch (Exception ignored) { }
        return n;
    }

    private static String readText(File f) throws Exception {
        java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String l;
        while ((l = br.readLine()) != null) sb.append(l);
        br.close();
        return sb.toString();
    }

    static Bitmap decodeSampled(File f, int maxPx) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int w = o.outWidth, h = o.outHeight;
            if (w <= 0 || h <= 0) return null;
            int scale = 1;
            while (Math.max(w, h) / scale > maxPx * 2) scale *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = scale;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
        } catch (Throwable t) {
            return null;
        }
    }

    private int dp(int v) {
        return (int) (v * act.getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        android.widget.Toast.makeText(act, s, android.widget.Toast.LENGTH_SHORT).show();
    }
}
