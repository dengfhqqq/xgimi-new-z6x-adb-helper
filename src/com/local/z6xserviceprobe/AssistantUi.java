package com.local.z6xserviceprobe;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Map;

/** Native TV interface. Switching sections never recreates the diagnostic views. */
final class AssistantUi {
    interface Actions {
        void detect(); void bind(); void prepare(); void start(); void stop();
        void restore(); void copy(); void export();
    }
    private static final int BG = Color.rgb(10, 17, 29);
    private static final int PANEL = Color.rgb(19, 30, 46);
    private static final int BORDER = Color.rgb(43, 60, 80);
    private static final int WHITE = Color.rgb(239, 245, 252);
    private static final int MUTED = Color.rgb(157, 177, 199);
    private static final int ACCENT = Color.rgb(118, 229, 207);
    private final Activity activity;
    private final Actions actions;
    private final boolean supported;
    private final FrameLayout content;
    private final View[] pages = new View[4];
    private final Button[] nav = new Button[4];
    final TextView statusView, reportView;
    final ScrollView reportScroll;
    final Button detectButton, bindButton, exportButton, startButton, stopButton, prepareButton, restoreButton;
    private TextView headline, hint, persistentValue, globalValue, daemonValue, portValue;

    AssistantUi(Activity activity, Actions actions, boolean supported) {
        this.activity = activity; this.actions = actions; this.supported = supported;
        LinearLayout root = column();
        root.setBackgroundColor(BG);
        root.setPadding(dp(24), dp(18), dp(24), dp(16));
        LinearLayout workspace = new LinearLayout(activity);
        workspace.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(workspace, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout sidebar = column();
        sidebar.setPadding(0, dp(6), dp(22), 0);
        workspace.addView(sidebar, new LinearLayout.LayoutParams(dp(180), -1));
        TextView mark = label("›_", 30, ACCENT);
        mark.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        sidebar.addView(mark);
        sidebar.addView(label("Z6X ADB 助手", 20, WHITE));
        sidebar.addView(label("无线连接 · 本地操作", 12, MUTED));
        space(sidebar, 22);
        String[] titles = {"状态概览", "使用教程", "诊断报告", "关于与适配"};
        for (int i = 0; i < nav.length; i++) {
            final int page = i;
            nav[i] = action(sidebar, titles[i], false, new View.OnClickListener() {
                @Override public void onClick(View v) { showPage(page); }
            });
            nav[i].setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            nav[i].setPadding(dp(16), 0, dp(8), 0);
        }
        sidebar.addView(new View(activity), new LinearLayout.LayoutParams(1, 0, 1));
        sidebar.addView(label("NEW Z6X / Android 8", 11, MUTED));
        sidebar.addView(label("社区工具  ·  v1.6.0", 11, MUTED));

        content = new FrameLayout(activity);
        workspace.addView(content, new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout home = column();
        home.addView(label("无线 ADB", 26, WHITE));
        home.addView(label("先检查状态，再按需操作。打开应用只会读取诊断信息。", 13, MUTED));
        space(home, 12);
        LinearLayout stateCard = card(home);
        headline = label(supported ? "正在读取设备状态…" : "当前固件不在验证范围", 21, ACCENT);
        stateCard.addView(headline);
        hint = label("状态不代表电脑已连接，最终请以 adb devices 显示 device 为准。", 13, MUTED);
        stateCard.addView(hint);
        LinearLayout metrics = horizontal(stateCard);
        persistentValue = metric(metrics, "调试配置", "待检测");
        globalValue = metric(metrics, "系统调试开关", "待检测");
        daemonValue = metric(metrics, "ADB 服务", "待检测");
        portValue = metric(metrics, "网络端口", "待检测");
        space(home, 12);
        home.addView(label("首次开启", 16, WHITE));
        home.addView(label("准备配置 → 确认已保存 → 正常重启 → 刷新状态 → 按需启动服务", 12, MUTED));
        LinearLayout mainActions = horizontal(home);
        prepareButton = action(mainActions, "1  准备调试配置（需重启）", true, new View.OnClickListener() {
            @Override public void onClick(View v) { actions.prepare(); }
        });
        startButton = action(mainActions, "2  启动 ADB 服务", false, new View.OnClickListener() {
            @Override public void onClick(View v) { actions.start(); }
        });
        LinearLayout utilities = horizontal(home);
        bindButton = action(utilities, "刷新状态", false, new View.OnClickListener() {
            @Override public void onClick(View v) { actions.bind(); }
        });
        action(utilities, "阅读完整教程  →", false, new View.OnClickListener() {
            @Override public void onClick(View v) { showPage(1); }
        });
        space(home, 6);
        home.addView(label("仅在可信局域网使用：此固件会自动允许新的主机公钥。无需 USB 线。", 12, MUTED));
        space(home, 16);
        LinearLayout connectionCard = card(home);
        connectionCard.addView(label("电脑连接", 17, WHITE));
        connectionCard.addView(label("在投影仪网络设置查看 IP，替换命令中的占位符后，在电脑终端执行。", 12, MUTED));
        TextView command = label("adb connect <投影仪IP>:5555\nadb devices -l", 16, ACCENT);
        command.setTypeface(Typeface.MONOSPACE);
        command.setPadding(0, dp(8), 0, dp(4));
        connectionCard.addView(command);
        connectionCard.addView(label("列表显示 device，再执行指定目标的 shell 命令验证；running 只是服务状态。", 12, MUTED));
        pages[0] = scroll(home);

        pages[1] = tutorialPage();

        LinearLayout diagnostics = column();
        diagnostics.addView(label("诊断报告", 24, WHITE));
        diagnostics.addView(label("保留实际读值与错误；服务运行、端口配置和电脑连接是不同状态。", 12, MUTED));
        LinearLayout tools = horizontal(diagnostics);
        detectButton = action(tools, "本地检测", false, new View.OnClickListener() {
            @Override public void onClick(View v) { actions.detect(); }
        });
        action(tools, "复制全文", false, new View.OnClickListener() {
            @Override public void onClick(View v) { actions.copy(); }
        });
        exportButton = action(tools, "导出 TXT", false, new View.OnClickListener() {
            @Override public void onClick(View v) { actions.export(); }
        });
        reportScroll = new ScrollView(activity);
        reportScroll.setFillViewport(true);
        reportScroll.setFocusable(true);
        reportView = label("尚未检测。", 13, WHITE);
        reportView.setTypeface(Typeface.MONOSPACE);
        reportView.setTextIsSelectable(true);
        reportView.setPadding(dp(16), dp(12), dp(16), dp(12));
        reportView.setBackground(shape(PANEL, BORDER, 10));
        reportScroll.addView(reportView, new ScrollView.LayoutParams(-1, -2));
        diagnostics.addView(reportScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout paging = horizontal(diagnostics);
        action(paging, "↑ 上一页", false, new View.OnClickListener() {
            @Override public void onClick(View v) { reportScroll.smoothScrollBy(0, -Math.max(dp(80), reportScroll.getHeight() - dp(30))); }
        });
        action(paging, "↓ 下一页", false, new View.OnClickListener() {
            @Override public void onClick(View v) { reportScroll.smoothScrollBy(0, Math.max(dp(80), reportScroll.getHeight() - dp(30))); }
        });
        pages[2] = diagnostics;

        LinearLayout about = column();
        about.addView(label("关于与适配", 24, WHITE));
        space(about, 12);
        LinearLayout compatibility = card(about);
        compatibility.addView(label(supported ? "✓ 设备固件校验通过" : "仅开放只读诊断与教程", 20, ACCENT));
        compatibility.addView(label("已实测：极米 NEW Z6X · Android 8.0.0\nironman / v12.15.180 / 1.4.2.19_373\n写操作同时校验完整 fingerprint；其他版本不会尝试写入。", 14, WHITE));
        compatibility.addView(label("这是社区辅助工具，未获极米官方背书。网络 ADB 已验证，USB ADB 尚未验证；获得普通 shell 权限，不是 root。", 13, MUTED));
        space(about, 12);
        about.addView(label("停止与恢复", 17, WHITE));
        about.addView(label("临时停止只请求关闭 ADB 服务；恢复使用本应用保存的原始 none 配置，之后需正常重启。不清除主机密钥，也不保证立即断开连接。", 13, MUTED));
        LinearLayout recoveryActions = horizontal(about);
        stopButton = action(recoveryActions, "停止 ADB 服务", false, new View.OnClickListener() {
            @Override public void onClick(View v) { actions.stop(); }
        });
        restoreButton = action(recoveryActions, "恢复原始配置", false, new View.OnClickListener() {
            @Override public void onClick(View v) { actions.restore(); }
        });
        space(about, 10);
        about.addView(label("隐私与权限", 17, WHITE));
        about.addView(label("没有联网权限、账号、广告或统计。诊断在本机生成；仅在导出 TXT 时请求存储权限，拒绝后可回退到应用目录。分享报告前请检查其中的设备信息。", 13, MUTED));
        about.addView(label("覆盖升级会保留原始配置备份。清除应用数据或卸载会丢失备份；自行编译的不同签名 APK 无法覆盖原安装。", 13, MUTED));
        space(about, 12);
        about.addView(label("AI 制作声明", 17, WHITE));
        about.addView(label("本项目全程由 AI 协助分析官方固件、编写源码与教程、设计界面、编译及执行检查；用户提供设备并配合实机操作和日志验证。AI 生成的分析与代码可能存在错误，已验证范围以项目记录为准。", 13, MUTED));
        about.addView(label("分析基于官方 XGIMI_IRONMAN_XH25L.bin（V12.15.180）。原包下载地址、SHA-256 和复现范围见随源码发布的固件来源说明。", 13, MUTED));
        pages[3] = scroll(about);
        for (View page : pages) content.addView(page, new FrameLayout.LayoutParams(-1, -1));

        statusView = label("准备检测…", 12, MUTED);
        statusView.setMaxLines(3);
        statusView.setPadding(dp(12), dp(7), dp(12), dp(7));
        statusView.setBackground(shape(PANEL, BORDER, 8));
        LinearLayout.LayoutParams statusLayout = new LinearLayout.LayoutParams(-1, -2);
        statusLayout.topMargin = dp(12);
        root.addView(statusView, statusLayout);
        activity.setContentView(root);
        showPage(0);
        nav[0].post(new Runnable() {
            @Override public void run() { nav[0].requestFocusFromTouch(); }
        });
    }

    void updateState(Map<String, String> values) {
        String persistent = values.get("persist.sys.usb.config");
        String global = values.get("adb_enabled");
        String daemon = values.get("init.svc.adbd");
        String port = values.get("service.adb.tcp.port");
        if (port == null || port.length() == 0) port = values.get("persist.adb.tcp.port");
        persistentValue.setText(persistent == null ? "待检测" : "adb".equals(persistent) ? "已配置 adb" : persistent);
        globalValue.setText(global == null ? "待检测" : "1".equals(global) ? "已启用" : "0".equals(global) ? "未启用" : global);
        daemonValue.setText(daemon == null ? "待检测" : "running".equals(daemon) ? "运行中" : "stopped".equals(daemon) ? "已停止" : daemon);
        portValue.setText(port == null || port.length() == 0 ? "待检测" : port);
        if (!supported) {
            headline.setText("当前固件不在验证范围");
            hint.setText("可以查看教程和只读报告；调试配置与服务启停均不可写。请勿把同名机型视为兼容。 ");
        } else if ("running".equals(daemon) && "1".equals(global)) {
            headline.setText("服务运行中，等待电脑验证");
            hint.setText("同一局域网执行 adb connect <投影仪IP>:5555，再检查 adb devices。 ");
        } else if ("1".equals(global) && "stopped".equals(daemon)) {
            headline.setText("调试已启用，可以启动服务");
            hint.setText("点击“启动 ADB 服务”，完成后到电脑验证；无需再次准备配置或重启。 ");
        } else if ("adb".equals(persistent) && "0".equals(global)) {
            headline.setText("配置已保存，请正常重启");
            hint.setText("重启后重新打开助手并刷新状态。应用不会自动重启投影仪。 ");
        } else if ("none".equals(persistent)) {
            headline.setText("尚未准备调试配置");
            hint.setText("首次使用请先阅读教程，再点击“准备调试配置”。首次操作会保存原始配置备份。 ");
        } else if ("running".equals(daemon)) {
            headline.setText("服务运行中，请刷新完整状态");
            hint.setText("本次回读未包含完整调试状态。点击“刷新状态”，实际连接仍需电脑验证。 ");
        } else {
            headline.setText("请先读取完整设备状态");
            hint.setText("点击“刷新状态”；读取失败或未知值不代表调试已关闭，请查看诊断报告。 ");
        }
    }

    private View tutorialPage() {
        LinearLayout page = column();
        page.addView(label("使用教程", 24, WHITE));
        page.addView(label("遥控器方向键或鼠标均可操作。先选章节，再上下滚动正文。", 12, MUTED));
        LinearLayout chapters = horizontal(page);
        LinearLayout chapters2 = horizontal(page);
        final ScrollView reading = new ScrollView(activity);
        reading.setFillViewport(true);
        reading.setFocusable(true);
        final LinearLayout article = column();
        article.setPadding(dp(18), dp(14), dp(18), dp(18));
        article.setBackground(shape(PANEL, BORDER, 10));
        final TextView title = label(TutorialContent.TITLES[0], 20, ACCENT);
        final TextView body = label(TutorialContent.BODIES[0], 15, WHITE);
        body.setLineSpacing(dp(4), 1.08f);
        article.addView(title); space(article, 8); article.addView(body);
        reading.addView(article, new ScrollView.LayoutParams(-1, -2));
        final Button[] chapterButtons = new Button[TutorialContent.TITLES.length];
        for (int i = 0; i < TutorialContent.TITLES.length; i++) {
            final int chapter = i;
            Button b = action(i < 4 ? chapters : chapters2, TutorialContent.TITLES[i], false, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    title.setText(TutorialContent.TITLES[chapter]);
                    body.setText(TutorialContent.BODIES[chapter]);
                    for (int j = 0; j < chapterButtons.length; j++) chapterButtons[j].setSelected(j == chapter);
                    reading.scrollTo(0, 0);
                }
            });
            b.setTextSize(12);
            chapterButtons[i] = b;
            b.setSelected(i == 0);
        }
        page.addView(reading, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout controls = horizontal(page);
        action(controls, "↑ 上一页", false, new View.OnClickListener() {
            @Override public void onClick(View v) { reading.smoothScrollBy(0, -Math.max(dp(100), reading.getHeight()-dp(28))); }
        });
        action(controls, "↓ 下一页", false, new View.OnClickListener() {
            @Override public void onClick(View v) { reading.smoothScrollBy(0, Math.max(dp(100), reading.getHeight()-dp(28))); }
        });
        action(controls, "复制连接命令", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard == null) { Toast.makeText(activity, "剪贴板不可用", Toast.LENGTH_SHORT).show(); return; }
                clipboard.setPrimaryClip(ClipData.newPlainText("ADB 连接命令", "adb connect <投影仪IP>:5555\nadb devices -l\nadb -s <投影仪IP>:5555 shell id"));
                Toast.makeText(activity, "已复制；请将 <投影仪IP> 替换为实际地址", Toast.LENGTH_LONG).show();
            }
        });
        return page;
    }

    private void showPage(int page) {
        for (int i = 0; i < pages.length; i++) {
            if (pages[i] != null) pages[i].setVisibility(i == page ? View.VISIBLE : View.GONE);
            nav[i].setSelected(i == page);
        }
    }
    private TextView metric(LinearLayout parent, String caption, String initial) {
        LinearLayout item = column(); item.setPadding(0, dp(12), dp(8), 0);
        parent.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
        item.addView(label(caption, 11, MUTED));
        TextView value = label(initial, 17, WHITE); item.addView(value); return value;
    }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout box = column(); box.setPadding(dp(18), dp(14), dp(18), dp(14));
        box.setBackground(shape(PANEL, BORDER, 12));
        parent.addView(box, new LinearLayout.LayoutParams(-1, -2)); return box;
    }
    private ScrollView scroll(View view) {
        ScrollView scroll = new ScrollView(activity); scroll.setFillViewport(true);
        scroll.setClipToPadding(false); scroll.addView(view, new ScrollView.LayoutParams(-1, -2)); return scroll;
    }
    private LinearLayout column() { LinearLayout view = new LinearLayout(activity); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout horizontal(LinearLayout parent) {
        LinearLayout view = new LinearLayout(activity); view.setOrientation(LinearLayout.HORIZONTAL);
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2)); return view;
    }
    private TextView label(String text, int size, int color) {
        TextView view = new TextView(activity); view.setText(text); view.setTextSize(size);
        view.setTextColor(color); view.setPadding(0, dp(2), 0, dp(2)); return view;
    }
    private void space(LinearLayout parent, int height) { parent.addView(new View(activity), new LinearLayout.LayoutParams(1, dp(height))); }
    private Button action(LinearLayout parent, String title, boolean primary, View.OnClickListener listener) {
        Button button = new Button(activity); button.setText(title); button.setTextSize(14);
        button.setTextColor(new android.content.res.ColorStateList(
            new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
            new int[] {Color.rgb(108, 126, 145), WHITE}));
        button.setAllCaps(false); button.setFocusable(true);
        button.setMinHeight(dp(46)); button.setMinimumHeight(dp(46));
        button.setPadding(dp(12), dp(6), dp(12), dp(6));
        button.setBackground(buttonBackground(primary)); button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = parent.getOrientation() == LinearLayout.HORIZONTAL
            ? new LinearLayout.LayoutParams(0, -2, 1) : new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(dp(3), dp(5), dp(3), dp(5)); parent.addView(button, params);
        return button;
    }
    private StateListDrawable buttonBackground(boolean primary) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, shape(Color.rgb(23, 34, 48), Color.rgb(35, 47, 61), 8));
        states.addState(new int[]{android.R.attr.state_focused}, shape(Color.rgb(32, 83, 87), ACCENT, 8));
        states.addState(new int[]{android.R.attr.state_pressed}, shape(Color.rgb(32, 83, 87), ACCENT, 8));
        states.addState(new int[]{android.R.attr.state_selected}, shape(Color.rgb(28, 52, 65), Color.rgb(68, 139, 138), 8));
        states.addState(new int[]{}, shape(primary ? Color.rgb(28, 81, 78) : PANEL, primary ? Color.rgb(66, 153, 139) : BORDER, 8));
        return states;
    }
    private GradientDrawable shape(int color, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color);
        drawable.setCornerRadius(dp(radius)); drawable.setStroke(dp(1), stroke); return drawable;
    }
    private int dp(int value) { return (int)(value * activity.getResources().getDisplayMetrics().density + .5f); }
}
