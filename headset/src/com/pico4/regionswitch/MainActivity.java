package com.pico4.regionswitch;

import android.app.*;
import android.os.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;

/** A normal sandboxed panel: no root permission, network listener or executable IPC input. */
public final class MainActivity extends Activity {
    private File channel;
    private TextView current, phase, detail, uninstall;
    private Button cn, global, recover, upgrade, initialize;
    private final Handler handler = new Handler();
    private long waitingUntil;
    private final Runnable poll = new Runnable() {
        public void run() { refresh(); handler.postDelayed(this, 1000); }
    };
    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + 0.5f); }
    private GradientDrawable background(int color, int radius) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius)); return shape;
    }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setPadding(0, dp(5), 0, dp(5)); return view;
    }
    private Button button(String title, int color, View.OnClickListener listener) {
        Button button = new Button(this); button.setText(title); button.setTextSize(20); button.setTextColor(Color.WHITE);
        button.setAllCaps(false); button.setBackground(background(color, 14)); button.setOnClickListener(listener);
        return button;
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        channel = new File(createDeviceProtectedStorageContext().getFilesDir(), "control"); channel.mkdirs();
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(36), dp(22), dp(36), dp(20)); page.setBackgroundColor(0xff111827);
        TextView title = text("区域切换", 30, Color.WHITE); title.setTypeface(null, Typeface.BOLD); page.addView(title);
        page.addView(text("切换八个区域应用 · 两区数据分别保留 · 底层属性保持", 15, 0xff94a3b8));
        current = text("正在连接本地模块…", 23, 0xff93c5fd); page.addView(current);
        LinearLayout choices = new LinearLayout(this);
        cn = button("切换到国区", 0xff2563eb, v -> confirm("cn"));
        global = button("切换到全球区", 0xff0f766e, v -> confirm("global"));
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, dp(76), 1); a.setMargins(0, dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(0, dp(76), 1); b.setMargins(dp(10), dp(10), 0, dp(10));
        choices.addView(cn, a); choices.addView(global, b); page.addView(choices);
        upgrade = button("完成八组件升级", 0xff7c3aed, v -> new AlertDialog.Builder(this)
            .setTitle("升级八组件管理").setMessage("保留当前区域及七个应用的数据。现有国区体感数据归入国区银行，全球体感使用独立数据；Matrix和商店版本保持。")
            .setNegativeButton("取消", null).setPositiveButton("开始升级", (d, w) -> request("upgrade", "")).show());
        page.addView(upgrade, new LinearLayout.LayoutParams(-1, dp(54)));
        initialize = button("验证首次安装", 0xff7c3aed, v -> request("recover-install", ""));
        page.addView(initialize, new LinearLayout.LayoutParams(-1, dp(54)));
        initialize.setVisibility(View.GONE);
        phase = text("", 17, Color.WHITE); page.addView(phase);
        recover = button("恢复未完成的切换", 0xffb45309, v -> new AlertDialog.Builder(this)
            .setTitle("恢复切换").setMessage("将恢复原区域的应用和数据，不会清空数据。")
            .setNegativeButton("取消", null).setPositiveButton("开始恢复", (d, w) -> request("recover", "")).show());
        page.addView(recover, new LinearLayout.LayoutParams(-1, dp(54)));
        ScrollView scroll = new ScrollView(this); detail = text("", 14, 0xffcbd5e1); detail.setTextIsSelectable(true); scroll.addView(detail);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        uninstall = text("准备卸载模块", 13, 0xff94a3b8);
        uninstall.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("准备卸载模块")
            .setMessage("先恢复完整国区并保留两区数据，再处理运动中心缓存。完成后可在Magisk移除模块；此操作不会自动重启。")
            .setNegativeButton("取消", null).setPositiveButton("准备卸载", (d, w) -> request("prepare-uninstall", "")).show());
        page.addView(uninstall);
        page.addView(text("请先退出 VD。切换期间区域应用会重启，面板可重新打开查看结果。", 13, 0xff94a3b8));
        setContentView(page); refresh();
    }
    private void confirm(String target) {
        new AlertDialog.Builder(this).setTitle("切换到" + (target.equals("cn") ? "国区" : "全球区"))
            .setMessage("将切换八个区域应用并恢复该区域的数据，底层区域属性保持。头显无需重启，请先退出 VD；开始后由模块在后台完成。")
            .setNegativeButton("取消", null).setPositiveButton("开始切换", (d, w) -> request("switch", target)).show();
    }
    private void request(String action, String target) {
        try {
            JSONObject value = new JSONObject().put("id", UUID.randomUUID().toString()).put("action", action).put("target", target);
            File next = new File(channel, "request.new");
            Files.write(next.toPath(), value.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(next.toPath(), new File(channel, "request.json").toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            if (action.equals("status")) return;
            waitingUntil = SystemClock.elapsedRealtime() + 4000; phase.setText("请求已提交，等待本地模块…");
            cn.setEnabled(false); global.setEnabled(false); recover.setEnabled(false); uninstall.setEnabled(false); upgrade.setEnabled(false); initialize.setEnabled(false);
        } catch (Exception e) { detail.setText("无法提交请求：" + e.getMessage()); }
    }
    private void refresh() {
        try {
            File file = new File(channel, "state.json");
            if (!file.isFile()) { cn.setEnabled(false); global.setEnabled(false); upgrade.setEnabled(false); uninstall.setEnabled(false); recover.setVisibility(View.GONE); initialize.setEnabled(false); return; }
            JSONObject state = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            String profile = state.optString("current"); boolean busy = state.optBoolean("busy");
            boolean pending = state.optBoolean("pending"), ready = state.optBoolean("ready");
            boolean needsUpgrade = state.optBoolean("upgrade_needed");
            boolean initial = state.optBoolean("initialization_pending");
            initialize.setVisibility(initial ? View.VISIBLE : View.GONE); initialize.setEnabled(initial && !busy);
            current.setText("当前区域：" + (profile.equals("cn") ? "国区" : profile.equals("global") ? "全球区" : "待恢复"));
            if (SystemClock.elapsedRealtime() < waitingUntil && !busy) return;
            phase.setText(state.optString("phase")); detail.setText(state.optString("message"));
            cn.setEnabled(ready && !busy && !pending && !needsUpgrade && !profile.equals("cn"));
            global.setEnabled(ready && !busy && !pending && !needsUpgrade && !profile.equals("global"));
            upgrade.setVisibility(needsUpgrade && !initial ? View.VISIBLE : View.GONE); upgrade.setEnabled(ready && !busy && !pending);
            cn.setAlpha(cn.isEnabled() ? 1f : 0.4f); global.setAlpha(global.isEnabled() ? 1f : 0.4f);
            recover.setVisibility(pending ? View.VISIBLE : View.GONE); recover.setEnabled(!busy);
            uninstall.setEnabled(ready && !busy && !pending && !needsUpgrade);
        } catch (Exception e) { phase.setText("等待模块状态更新…"); }
    }
    @Override public void onResume() { super.onResume(); request("status", ""); handler.post(poll); }
    @Override public void onPause() { handler.removeCallbacks(poll); super.onPause(); }
}
