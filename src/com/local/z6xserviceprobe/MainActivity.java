package com.local.z6xserviceprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.Environment;
import android.os.UserManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.InputStreamReader;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Foreground diagnostics with explicitly confirmed manual ADB requests. No vendor SDK initializer is loaded. */
public final class MainActivity extends Activity {
    private static final int REQUEST_WRITE_EXTERNAL_STORAGE = 7301;
    private static final String ROOT_DESCRIPTOR = "com.xgimi.aidl.IXgimiService";
    private static final String COMMON_DESCRIPTOR = "com.xgimi.aidl.IGimiCommon";
    // Verified from official boot-com.xgimi.api DEX, not guessed transaction IDs.
    private static final int GET_COMMON = 1;
    private static final int SET_PROPERTY = 18;
    private static final int GET_PROPERTY = 19;
    private static final String[] PROPERTIES = {
        "sys.usb.config", "sys.usb.state", "persist.sys.usb.config",
        "service.adb.tcp.port", "persist.adb.tcp.port", "init.svc.adbd",
        "ro.adb.secure", "ro.debuggable", "ro.secure", "ro.product.board",
        "ro.xgimi.gmui.version", "vold.decrypt", "sys.usb.configfs"
    };
    private static final String[] MANUAL_PROPERTIES = {
        "init.svc.adbd", "sys.usb.state", "sys.usb.config",
        "service.adb.tcp.port", "persist.adb.tcp.port"
    };

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private AssistantUi ui;
    private boolean supportedFirmware;
    private TextView reportView;
    private TextView statusView;
    private ScrollView reportScroll;
    private Button detectButton;
    private Button bindButton;
    private Button exportButton;
    private Button startAdbdButton;
    private Button stopAdbdButton;
    private Button enableUsbAdbButton;
    private Button restoreUsbButton;
    private String latestReport = "尚未检测。";
    private boolean destroyed;
    private boolean busy;
    private boolean exportBusy;
    private boolean exportPending;
    private boolean manualBusy;
    private boolean manualBinding;
    private boolean manualServiceBound;
    private boolean manualActionStart;
    private boolean usbBusy;
    private boolean usbBinding;
    private boolean usbBindingRegistered;
    private boolean usbRestore;
    private Session usbSession;
    private UsbAdbGuard.Plan usbWritePlan;
    private AlertDialog usbConfirmation;
    private Session manualSession;
    private volatile Session current;
    private volatile IBinder boundRootForProbe;
    private boolean binding;
    private boolean serviceBound;
    private boolean serviceBindingRegistered;
    private boolean manualBindingRegistered;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ServiceConnection xgimiConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            if (!binding || current == null || service == null) {
                if (binding) appendUi("绑定回调无效：服务 Binder 为 null 或检测已结束。");
                return;
            }
            binding = false;
            serviceBound = true;
            if (bindTimeout != null) mainHandler.removeCallbacks(bindTimeout);
            boundRootForProbe = service;
            final Session session = current;
            line(session, "显式绑定回调：收到非空 Binder，开始后台协议检测。");
            runBoundProbe(session);
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            appendUi("XGIMI 服务连接断开；当前结果不能据此判定服务状态。");
        }
        @Override public void onNullBinding(ComponentName name) {
            if (!binding) return;
            binding = false;
            if (bindTimeout != null) mainHandler.removeCallbacks(bindTimeout);
            finishProbe("显式绑定回调：onNullBinding，服务返回 null Binder。", true);
        }
        @Override public void onBindingDied(ComponentName name) {
            if (!binding) return;
            binding = false;
            if (bindTimeout != null) mainHandler.removeCallbacks(bindTimeout);
            finishProbe("显式绑定回调：onBindingDied，绑定失效。", true);
        }
    };
    private final ServiceConnection manualConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            if (!manualBinding || manualSession == null || service == null) {
                if (manualBinding) finishManual("绑定回调无效：服务 Binder 为 null。", true);
                return;
            }
            manualBinding = false;
            manualServiceBound = true;
            if (manualTimeout != null) mainHandler.removeCallbacks(manualTimeout);
            final Session session = manualSession;
            final boolean start = manualActionStart;
            line(session, "显式绑定回调：收到非空 Binder，开始执行已确认的手动请求。");
            runManualProbe(session, service, start);
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            if (manualSession != null) line(manualSession, "XGIMI 服务连接断开；手动请求结果不能据此判定。");
        }
        @Override public void onNullBinding(ComponentName name) {
            if (!manualBinding) return;
            manualBinding = false;
            if (manualTimeout != null) mainHandler.removeCallbacks(manualTimeout);
            finishManual("显式绑定回调：onNullBinding，服务返回 null Binder。", true);
        }
        @Override public void onBindingDied(ComponentName name) {
            if (!manualBinding) return;
            manualBinding = false;
            if (manualTimeout != null) mainHandler.removeCallbacks(manualTimeout);
            finishManual("显式绑定回调：onBindingDied，绑定失效。", true);
        }
    };
    private final ServiceConnection usbConfigConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, final IBinder service) {
            if (!usbBinding || usbSession == null || service == null) {
                if (usbBinding) finishUsbConfig("配置绑定未返回有效 Binder，未写入。", true);
                return;
            }
            usbBinding = false;
            if (usbBindTimeout != null) mainHandler.removeCallbacks(usbBindTimeout);
            final Session session = usbSession;
            final UsbAdbGuard.Plan confirmed = usbWritePlan;
            try {
                executor.execute(new Runnable() {
                    @Override public void run() {
                        try {
                            UsbAdbGuard.requireDevice(Build.BOARD, Build.VERSION.INCREMENTAL, Build.FINGERPRINT);
                            IBinder common = checkedCommon(service, session);
                            String persistent = getRemoteProperty(common, "persist.sys.usb.config");
                            String sys = getRemoteProperty(common, "sys.usb.config");
                            observe(session, "persist.sys.usb.config", persistent);
                            observe(session, "sys.usb.config", sys);
                            line(session, "读取 persist.sys.usb.config=" + propertyValue(persistent));
                            line(session, "读取 sys.usb.config=" + propertyValue(sys));
                            UsbAdbGuard.Plan plan = UsbAdbGuard.plan(usbRestore, persistent, sys,
                                readUsbBackup(), Build.BOARD, Build.VERSION.INCREMENTAL, Build.FINGERPRINT);
                            if (confirmed == null) showUsbConfirmation(session, plan);
                            else {
                                UsbAdbGuard.requireUnchanged(confirmed, persistent, sys, Build.FINGERPRINT);
                                executeUsbConfig(session, common, confirmed, plan);
                            }
                        } catch (final Throwable error) {
                            runOnUiThread(new Runnable() {
                                @Override public void run() {
                                    if (usbSession == session && current == session && !session.cancelled) finishUsbConfig("USB 配置流程停止：" + describe(error), true);
                                }
                            });
                        }
                    }
                });
            } catch (Throwable error) {
                finishUsbConfig("无法启动 USB 配置后台查询：" + describe(error), true);
            }
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            if (usbSession != null) line(usbSession, "XGIMI 连接断开，不能据此确认配置写入成功。");
        }
        @Override public void onNullBinding(ComponentName name) {
            if (usbBusy) finishUsbConfig("配置绑定 onNullBinding：未写入。", true);
        }
        @Override public void onBindingDied(ComponentName name) {
            if (usbBusy) finishUsbConfig("配置绑定 onBindingDied：未确认写入成功。", true);
        }
    };
    private Runnable bindTimeout;
    private Runnable manualTimeout;
    private Runnable usbBindTimeout;

    private static final class Session {
        final StringBuilder report = new StringBuilder();
        final java.util.Map<String, String> values = new java.util.concurrent.ConcurrentHashMap<String, String>();
        volatile boolean cancelled;
        long reportGeneration;
        long renderedGeneration;
    }

    private static final class ExportResult {
        final String path;
        final boolean fallback;
        final String detail;
        ExportResult(String path, boolean fallback, String detail) {
            this.path = path;
            this.fallback = fallback;
            this.detail = detail;
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        boolean supported;
        try {
            UsbAdbGuard.requireDevice(Build.BOARD, Build.VERSION.INCREMENTAL, Build.FINGERPRINT);
            supported = true;
        } catch (IllegalStateException unsupported) { supported = false; }
        ui = new AssistantUi(this, new AssistantUi.Actions() {
            @Override public void detect() { startProbe(); }
            @Override public void bind() { startBoundProbe(); }
            @Override public void prepare() { beginUsbConfig(false); }
            @Override public void start() { confirmAdbd(true); }
            @Override public void stop() { confirmAdbd(false); }
            @Override public void restore() { beginUsbConfig(true); }
            @Override public void copy() { copyReport(); }
            @Override public void export() { requestExport(); }
        }, supported);
        supportedFirmware = supported;
        reportView = ui.reportView; statusView = ui.statusView; reportScroll = ui.reportScroll;
        detectButton = ui.detectButton; bindButton = ui.bindButton; exportButton = ui.exportButton;
        startAdbdButton = ui.startButton; stopAdbdButton = ui.stopButton;
        enableUsbAdbButton = ui.prepareButton; restoreUsbButton = ui.restoreButton;
        setManualButtonsEnabled(true);
        startBoundProbe();
    }

    private void startProbe() {
        if (busy || destroyed) return;
        if (manualBusy || usbBusy) return;
        busy = true;
        detectButton.setEnabled(false);
        bindButton.setEnabled(false);
        setManualButtonsEnabled(false);
        statusView.setText("读取中；如果服务响应较慢，可以先复制当前结果。");
        final Session session = new Session();
        current = session;
        latestReport = "正在读取…";
        reportView.setText(latestReport);
        reportScroll.scrollTo(0, 0);
        boundRootForProbe = null;
        try {
            executor.execute(new Runnable() {
                @Override public void run() {
                    try {
                        probe(session);
                    } catch (Throwable error) {
                        line(session, "检测意外中断：" + describe(error));
                    } finally {
                        final String result = reportSnapshot(session);
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                if (destroyed || current != session || session.cancelled) return;
                                busy = false;
                                detectButton.setEnabled(true);
                                bindButton.setEnabled(true);
                                setManualButtonsEnabled(true);
                                latestReport = result;
                                reportView.setText(result);
                                statusView.setText(session.cancelled ? "已停止后续读取，可重新检测。" : "只读检测完成，可复制全文或上下翻页。");
                            }
                        });
                    }
                }
            });
        } catch (RejectedExecutionException error) {
            busy = false;
            detectButton.setEnabled(true);
            bindButton.setEnabled(true);
            setManualButtonsEnabled(true);
            statusView.setText("无法启动读取：" + describe(error));
        }
    }

    /** Direct ServiceManager read, retained as a comparison path. */
    private void startBoundProbe() {
        if (busy || destroyed) return;
        if (manualBusy || usbBusy) return;
        busy = true;
        binding = false;
        serviceBound = false;
        detectButton.setEnabled(false);
        bindButton.setEnabled(false);
        setManualButtonsEnabled(false);
        statusView.setText("正在异步绑定 XGIMI 服务；最多等待 10 秒，不会主动创建服务。");
        final Session session = new Session();
        current = session;
        boundRootForProbe = null;
        latestReport = "正在绑定服务…";
        reportView.setText(latestReport);
        reportScroll.scrollTo(0, 0);
        final Intent intent = new Intent().setComponent(new ComponentName(
            "com.xgimi.xgimiservice", "com.xgimi.xgimiservice.services.XgimiServices"));
        try {
            boolean accepted = bindService(intent, xgimiConnection, 0);
            if (!accepted) {
                finishProbe("显式 bindService 返回 false：系统未接受该绑定请求。", true);
                return;
            }
            binding = true;
            serviceBindingRegistered = true;
            line(session, "bindService 返回 true（flags=0；未使用 BIND_AUTO_CREATE）。");
            bindTimeout = new Runnable() {
                @Override public void run() {
                    if (!binding || current != session) return;
                    binding = false;
                    finishProbe("显式绑定等待超过 10 秒：未收到 onServiceConnected。", true);
                }
            };
            mainHandler.postDelayed(bindTimeout, 10000L);
        } catch (Throwable error) {
            finishProbe("显式 bindService 异常：" + describe(error), true);
        }
    }

    private void runBoundProbe(final Session session) {
        try {
            executor.execute(new Runnable() {
                @Override public void run() {
                    try {
                        probe(session);
                    } catch (Throwable error) {
                        line(session, "检测意外中断：" + describe(error));
                    } finally {
                        final String result = reportSnapshot(session);
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                if (destroyed || current != session || session.cancelled) return;
                                safelyUnbind();
                                busy = false;
                                detectButton.setEnabled(true);
                                bindButton.setEnabled(true);
                                setManualButtonsEnabled(true);
                                latestReport = result;
                                reportView.setText(result);
                                statusView.setText("绑定服务只读检测完成，可复制全文或上下翻页。");
                            }
                        });
                    }
                }
            });
        } catch (RejectedExecutionException error) {
            finishProbe("无法启动绑定后的后台读取：" + describe(error), true);
        }
    }

    private void confirmAdbd(final boolean start) {
        if (manualBusy || destroyed || usbBusy || !supportedFirmware) return;
        if (busy) {
            statusView.setText("当前仍在读取诊断信息，请等待本次读取完成。");
            return;
        }
        String action = start ? "启动 ADB 服务" : "停止 ADB 服务";
        new AlertDialog.Builder(this)
            .setTitle("确认手动操作")
            .setMessage(start
                ? "将请求启动投影仪的 ADB 服务。首次使用应先准备调试配置并正常重启；服务运行不代表电脑已连接。此固件会自动允许新的主机公钥，请只在可信局域网操作。"
                : "将请求停止 ADB 服务，现有调试连接可能中断。此操作不恢复调试配置，也不删除已保存的主机密钥。")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认" + action, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    startManualProbe(start);
                }
            })
            .show();
    }

    private void startManualProbe(final boolean start) {
        if (manualBusy || destroyed || busy || usbBusy) return;
        manualBusy = true;
        manualActionStart = start;
        manualBinding = false;
        manualServiceBound = false;
        setManualButtonsEnabled(false);
        detectButton.setEnabled(false);
        bindButton.setEnabled(false);
        statusView.setText("正在异步绑定 XGIMI 服务；最多等待 10 秒，不会主动创建服务。");
        final Session session = new Session();
        manualSession = session;
        current = session;
        latestReport = "正在准备手动请求…";
        reportView.setText(latestReport);
        reportScroll.scrollTo(0, 0);
        final Intent intent = new Intent().setComponent(new ComponentName(
            "com.xgimi.xgimiservice", "com.xgimi.xgimiservice.services.XgimiServices"));
        try {
            boolean accepted = bindService(intent, manualConnection, 0);
            if (!accepted) {
                finishManual("显式 bindService 返回 false：系统未接受该绑定请求，未发送 tx18。", true);
                return;
            }
            manualBinding = true;
            manualBindingRegistered = true;
            line(session, "bindService 返回 true（flags=0；未使用 BIND_AUTO_CREATE）。");
            manualTimeout = new Runnable() {
                @Override public void run() {
                    if (!manualBinding || manualSession != session) return;
                    manualBinding = false;
                    finishManual("显式绑定等待超过 10 秒：未收到 onServiceConnected，未发送 tx18。", true);
                }
            };
            mainHandler.postDelayed(manualTimeout, 10000L);
        } catch (Throwable error) {
            finishManual("显式 bindService 异常：" + describe(error) + "，未发送 tx18。", true);
        }
    }

    private void runManualProbe(final Session session, final IBinder root, final boolean start) {
        try {
            executor.execute(new Runnable() {
                @Override public void run() {
                    try {
                        manualTransaction(session, root, start);
                    } catch (Throwable error) {
                        line(session, "手动请求意外中断：" + describe(error));
                    } finally {
                        final String result = reportSnapshot(session);
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                if (destroyed || manualSession != session || current != session || session.cancelled) return;
                                safelyUnbindManual();
                                manualBusy = false;
                                detectButton.setEnabled(true);
                                bindButton.setEnabled(true);
                                setManualButtonsEnabled(true);
                                latestReport = result;
                                reportView.setText(result);
                                statusView.setText("手动请求流程结束；报告中的状态回读仅供参考，仍需实际 ADB 验证。");
                            }
                        });
                    }
                }
            });
        } catch (RejectedExecutionException error) {
            finishManual("无法启动手动请求后台线程：" + describe(error), true);
        }
    }

    private void manualTransaction(Session session, IBinder root, boolean start) throws Exception {
        UsbAdbGuard.requireDevice(Build.BOARD, Build.VERSION.INCREMENTAL, Build.FINGERPRINT);
        String key = start ? "ctl.start" : "ctl.stop";
        String propertyValueToWrite = "adbd";
        line(session, "Z6X ADB 助手 1.6.0 / com.local.z6xserviceprobe");
        line(session, "手动请求：" + key + "=" + propertyValueToWrite);
        line(session, "【严格接口校验】");
        String rootDescriptor = root.getInterfaceDescriptor();
        line(session, "XGIMI descriptor=" + MainActivity.value(rootDescriptor));
        if (!ROOT_DESCRIPTOR.equals(rootDescriptor)) {
            line(session, "接口不匹配：未发送 transaction 1 或 transaction 18。");
            return;
        }
        IBinder common;
        try {
            common = getCommon(root);
        } catch (Throwable error) {
            line(session, "transaction 1 / getGimiCommon 失败：" + describe(error) + "；未发送 transaction 18。");
            return;
        }
        if (common == null) {
            line(session, "transaction 1 返回 null IGimiCommon：未发送 transaction 18。");
            return;
        }
        String commonDescriptor = common.getInterfaceDescriptor();
        line(session, "IGimiCommon descriptor=" + value(commonDescriptor));
        if (!COMMON_DESCRIPTOR.equals(commonDescriptor)) {
            line(session, "IGimiCommon 接口不匹配：未发送 transaction 18。");
            return;
        }
        if (session.cancelled || Thread.currentThread().isInterrupted()) {
            line(session, "检测已取消：未发送 transaction 18。");
            return;
        }
        line(session, "接口匹配；仅允许写入白名单 ctl.start/ctl.stop，值固定为 adbd。");
        UsbAdbGuard.requireDevice(Build.BOARD, Build.VERSION.INCREMENTAL, Build.FINGERPRINT);
        setRemoteProperty(common, key, propertyValueToWrite);
        line(session, "transaction 18 已返回无异常：已发出 " + key + "=adbd 请求；不等于 adbd 已成功运行。");
        try {
            Thread.sleep(1000L);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            line(session, "等待状态回读时线程被中断：请求已发出，不等于成功。");
            return;
        }
        line(session, "【1 秒后远端状态回读 / transaction 19】");
        for (String property : MANUAL_PROPERTIES) {
            if (session.cancelled) return;
            try {
                String raw = getRemoteProperty(common, property);
                observe(session, property, raw);
                line(session, property + " = " + propertyValue(raw));
            } catch (Throwable error) {
                line(session, property + " = 读取失败：" + describe(error));
            }
        }
        line(session, "请求已发出，不等于成功；请另行实测 adb devices 或 5555 端口。");
    }

    private static void setRemoteProperty(IBinder common, String key, String value) throws Exception {
        if (!("ctl.start".equals(key) || "ctl.stop".equals(key)) || !"adbd".equals(value)) {
            throw new IllegalArgumentException("拒绝写入非白名单属性或值");
        }
        Parcel request = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(COMMON_DESCRIPTOR);
            request.writeString(key);
            request.writeString(value);
            if (!common.transact(SET_PROPERTY, request, reply, 0)) {
                throw new IllegalStateException("transact=false：远端未处理 transaction 18");
            }
            if (reply.dataAvail() < 4) throw new IllegalStateException("远端响应为空或不完整，缺少异常头");
            reply.readException();
        } finally {
            reply.recycle();
            request.recycle();
        }
    }

    private void finishManual(final String message, boolean failed) {
        if (destroyed) return;
        if (manualTimeout != null) mainHandler.removeCallbacks(manualTimeout);
        safelyUnbindManual();
        manualBinding = false;
        manualBusy = false;
        if (manualSession != null) {
            manualSession.cancelled = failed;
            line(manualSession, message);
        }
        detectButton.setEnabled(true);
        bindButton.setEnabled(true);
        setManualButtonsEnabled(true);
        statusView.setText(failed ? "手动请求结束，未确认改变成功。" : message);
    }

    private void safelyUnbindManual() {
        try {
            if (manualBindingRegistered) unbindService(manualConnection);
        } catch (Throwable ignored) { }
        manualBinding = false;
        manualServiceBound = false;
        manualBindingRegistered = false;
    }

    private void setManualButtonsEnabled(boolean enabled) {
        boolean available = enabled && supportedFirmware && !manualBusy && !busy && !usbBusy;
        if (startAdbdButton != null) startAdbdButton.setEnabled(available);
        if (stopAdbdButton != null) stopAdbdButton.setEnabled(available);
        if (enableUsbAdbButton != null) enableUsbAdbButton.setEnabled(available);
        if (restoreUsbButton != null) restoreUsbButton.setEnabled(available);
    }

    private void beginUsbConfig(boolean restore) {
        if (destroyed || busy || manualBusy || usbBusy || !supportedFirmware) return;
        usbBusy = true;
        usbRestore = restore;
        usbWritePlan = null;
        usbSession = new Session();
        current = usbSession;
        reportView.setText("");
        latestReport = "";
        line(usbSession, "Z6X ADB 助手 1.6.0：" + (restore ? "恢复原 USB 配置预检" : "启用 USB 调试配置预检"));
        line(usbSession, "BOARD=" + value(Build.BOARD) + " / INCREMENTAL=" + value(Build.VERSION.INCREMENTAL));
        usbControlsBusy();
        bindUsbConfig();
    }

    private void usbControlsBusy() {
        detectButton.setEnabled(!usbBusy && !busy && !manualBusy);
        bindButton.setEnabled(!usbBusy && !busy && !manualBusy);
        setManualButtonsEnabled(!usbBusy);
    }

    private void bindUsbConfig() {
        if (destroyed || !usbBusy || usbSession == null || usbSession.cancelled) {
            finishUsbConfig("已取消 USB 配置流程，未继续写入。", true);
            return;
        }
        final Session session = usbSession;
        statusView.setText("正在显式绑定现有 XGIMI 服务，最多等待 10 秒…");
        try {
            Intent intent = new Intent().setComponent(new ComponentName(
                "com.xgimi.xgimiservice", "com.xgimi.xgimiservice.services.XgimiServices"));
            boolean accepted = bindService(intent, usbConfigConnection, 0);
            if (!accepted) { finishUsbConfig("bindService=false；未发送 USB 配置写入。", true); return; }
            usbBinding = true;
            usbBindingRegistered = true;
            line(session, "bindService=true，flags=0（不主动创建服务）。");
            usbBindTimeout = new Runnable() {
                @Override public void run() {
                    if (usbSession == session && usbBinding) finishUsbConfig("绑定等待 10 秒超时；未确认写入。", true);
                }
            };
            mainHandler.postDelayed(usbBindTimeout, 10000L);
        } catch (Throwable error) {
            finishUsbConfig("USB 配置绑定失败：" + describe(error), true);
        }
    }

    private IBinder checkedCommon(IBinder root, Session session) throws Exception {
        String descriptor = root.getInterfaceDescriptor();
        line(session, "root descriptor=" + value(descriptor));
        if (!ROOT_DESCRIPTOR.equals(descriptor)) throw new IllegalStateException("root descriptor 不匹配，拒绝后续事务");
        IBinder common = getCommon(root);
        if (common == null) throw new IllegalStateException("transaction 1 返回 null Binder");
        descriptor = common.getInterfaceDescriptor();
        line(session, "common descriptor=" + value(descriptor));
        if (!COMMON_DESCRIPTOR.equals(descriptor)) throw new IllegalStateException("common descriptor 不匹配，拒绝读写事务");
        return common;
    }

    private SharedPreferences usbBackupPreferences() {
        return getSharedPreferences("usb_adb_baseline_v15", MODE_PRIVATE);
    }

    private UsbAdbGuard.Backup readUsbBackup() {
        SharedPreferences preferences = usbBackupPreferences();
        if (preferences.getAll().isEmpty()) return null;
        return new UsbAdbGuard.Backup(preferences.getString("baseline_value", null),
            preferences.getString("fingerprint", null), preferences.getString("board", null),
            preferences.getString("incremental", null), preferences.getString("saved_at", null));
    }

    private void showUsbConfirmation(final Session session, final UsbAdbGuard.Plan plan) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (destroyed || usbSession != session || current != session || session.cancelled) return;
                unbindUsbConfig();
                line(session, "persist.sys.usb.config：" + propertyValue(plan.current) + " → " + propertyValue(plan.target));
                line(session, "当前 sys.usb.config=" + propertyValue(plan.sysCurrent));
                line(session, plan.message);
                if (!plan.write) { finishUsbConfig(plan.message, false); return; }
                String warning = plan.restore
                    ? "只恢复备份中的 USB 配置 none。当前 Java 调试状态仍可能保持开启，不能承诺网络 ADB 立即关闭；需自行正常重启后重建关闭状态。此操作不清除已存主机密钥，也不代表撤销授权。"
                    : "配置可能立即尝试开启 USB ADB；系统授权流程仍需要你正常重启。此固件会自动授权主机，仅用于可信局域网。";
                usbConfirmation = new AlertDialog.Builder(MainActivity.this)
                    .setTitle(plan.restore ? "确认恢复原始配置" : "确认准备调试配置")
                    .setMessage("persist.sys.usb.config\n当前：" + plan.current + "\n目标：" + plan.target
                        + "\n\n" + plan.message + "\n\n" + warning + "\n\n程序不会自动重启。是否继续？")
                    .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface dialog, int which) {
                            finishUsbConfig("用户取消；未写入 USB 配置。", false);
                        }
                    })
                    .setPositiveButton("确认" + (plan.restore ? "恢复" : "启用"), new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface dialog, int which) {
                            usbConfirmation = null;
                            if (session.cancelled || destroyed) { finishUsbConfig("已取消，未写入。", true); return; }
                            usbWritePlan = plan;
                            line(session, "用户已确认：重新绑定并重读配置，一致后才保存基线/写入。");
                            bindUsbConfig();
                        }
                    })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override public void onCancel(DialogInterface dialog) { finishUsbConfig("确认框已取消，未写入。", false); }
                    }).create();
                usbConfirmation.show();
                statusView.setText("已完成预检，等待你确认当前值 → 目标值。");
            }
        });
    }

    private void executeUsbConfig(final Session session, IBinder common, UsbAdbGuard.Plan confirmed,
                                  UsbAdbGuard.Plan refreshed) throws Exception {
        if (session.cancelled || !refreshed.write || !confirmed.target.equals(refreshed.target)) {
            throw new IllegalStateException("操作已取消或最新决策不允许写入");
        }
        if (confirmed.saveBackup) {
            if (readUsbBackup() != null) throw new IllegalStateException("基线状态已变化，拒绝覆盖原备份");
            String savedAt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date());
            boolean saved = usbBackupPreferences().edit().putString("baseline_value", "none")
                .putString("fingerprint", Build.FINGERPRINT).putString("board", Build.BOARD)
                .putString("incremental", Build.VERSION.INCREMENTAL).putString("saved_at", savedAt).commit();
            if (!saved) throw new IllegalStateException("SharedPreferences.commit=false，未写入属性；请保留应用数据检查基线");
            UsbAdbGuard.Backup reread = readUsbBackup();
            if (reread == null || !reread.valid(Build.FINGERPRINT)) throw new IllegalStateException("保存后基线校验失败，未写入属性");
            line(session, "已同步保存原始 none 基线；时间=" + savedAt + "；fingerprint=" + Build.FINGERPRINT);
        }
        if (session.cancelled) throw new IllegalStateException("保存基线后操作取消：未写入属性，基线保留");
        writeUsbPersistent(session, common, confirmed.target, confirmed.restore);
        line(session, "tx18 已返回无异常：已请求 persist.sys.usb.config=" + confirmed.target + "；不等于 ADB 成功。");
        Thread.sleep(1000L);
        boolean persistentConfirmed = false;
        try {
            String postPersist = getRemoteProperty(common, "persist.sys.usb.config");
            observe(session, "persist.sys.usb.config", postPersist);
            persistentConfirmed = confirmed.target.equals(postPersist);
            line(session, "persist.sys.usb.config 后验=" + propertyValue(postPersist)
                + " / 精确目标=" + propertyValue(confirmed.target) + " / 匹配=" + persistentConfirmed);
        } catch (Throwable error) {
            line(session, "persist.sys.usb.config 后验失败：" + describe(error));
        }
        for (String key : new String[] { "sys.usb.config", "sys.usb.state", "init.svc.adbd",
                "service.adb.tcp.port", "persist.adb.tcp.port", "sys.usb.configfs" }) {
            try {
                String raw = getRemoteProperty(common, key);
                observe(session, key, raw);
                line(session, key + "=" + propertyValue(raw));
            }
            catch (Throwable error) { line(session, key + " 回读失败：" + describe(error)); }
        }
        readGlobalSetting(session, Settings.Global.ADB_ENABLED);
        readUsbSysfsState(session);
        line(session, "本次系统运行秒数=" + android.os.SystemClock.elapsedRealtime() / 1000L);
        line(session, "属性返回/USB state 不证明物理枚举、RSA 通道或 ADB 连接成功。程序不发送重启指令。");
        if (confirmed.restore) line(session, "本次只恢复 USB 配置；若 adbd 仍为 running，可使用已有“停止 ADB 服务”按钮，不会自动再写 ctl.stop。");
        final boolean verifiedTarget = persistentConfirmed;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (destroyed || usbSession != session || current != session || session.cancelled) return;
                if (verifiedTarget) finishUsbConfig("persist 已精确回读为目标值；请自行正常重启后再检测，尚未确认 ADB 成功。", false);
                else finishUsbConfig("未确认 USB 配置写入成功；流程停止，请导出报告。不会自动回滚或重试。", true);
            }
        });
    }

    private void writeUsbPersistent(final Session session, IBinder common, String target, boolean restore) throws Exception {
        UsbAdbGuard.requireDevice(Build.BOARD, Build.VERSION.INCREMENTAL, Build.FINGERPRINT);
        UsbAdbGuard.Backup backup = readUsbBackup();
        if (!(restore ? "none".equals(target) : "adb".equals(target))) throw new IllegalArgumentException("拒绝非固定目标配置");
        UsbAdbGuard.requireDurableBackup(backup, Build.FINGERPRINT, new UsbAdbGuard.BackupCommitter() {
            @Override public boolean commit(UsbAdbGuard.Backup baseline) {
                SharedPreferences preferences = usbBackupPreferences();
                long generation = preferences.getLong("backup_sync_generation", 0L);
                if (generation < 0 || generation == Long.MAX_VALUE) return false;
                // A failed SharedPreferences commit still changes its memory map. Always change
                // generation and synchronously commit before *every* USB property write/retry.
                return preferences.edit().putString("baseline_value", baseline.value)
                    .putString("fingerprint", baseline.fingerprint).putString("board", baseline.board)
                    .putString("incremental", baseline.incremental).putString("saved_at", baseline.savedAt)
                    .putLong("backup_sync_generation", generation + 1L).commit();
            }
        });
        UsbAdbGuard.Backup verified = readUsbBackup();
        if (verified == null || !verified.valid(Build.FINGERPRINT)) throw new IllegalStateException("同步后基线不匹配，拒绝写入");
        line(session, "本次有效 none 基线已强制同步到磁盘，保留原始 saved_at=" + verified.savedAt);
        if (session.cancelled || Thread.currentThread().isInterrupted()) throw new IllegalStateException("同步后操作取消，未发送属性写入");
        Parcel request = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(COMMON_DESCRIPTOR);
            request.writeString("persist.sys.usb.config");
            request.writeString(target);
            if (!common.transact(SET_PROPERTY, request, reply, 0)) throw new IllegalStateException("transact=false：远端未处理 tx18");
            if (reply.dataAvail() < 4) throw new IllegalStateException("tx18 响应缺少异常头");
            reply.readException();
        } finally { reply.recycle(); request.recycle(); }
    }

    private void unbindUsbConfig() {
        if (usbBindTimeout != null) mainHandler.removeCallbacks(usbBindTimeout);
        if (usbBindingRegistered) {
            try { unbindService(usbConfigConnection); } catch (Throwable ignored) { }
        }
        usbBinding = false;
        usbBindingRegistered = false;
    }

    private void finishUsbConfig(String message, boolean failed) {
        unbindUsbConfig();
        if (usbSession != null) { usbSession.cancelled = failed; line(usbSession, message); }
        usbBusy = false;
        usbWritePlan = null;
        if (!destroyed) {
            usbControlsBusy();
            statusView.setText(message);
        }
    }

    private void readUsbSysfsState(Session session) {
        String path = "/sys/class/android_usb/android0/state";
        try {
            File file = new File(path);
            line(session, path + " exists=" + file.exists() + " / canRead=" + file.canRead());
            InputStreamReader reader = new InputStreamReader(new FileInputStream(file), "UTF-8");
            try {
                char[] buffer = new char[256];
                int count = reader.read(buffer);
                line(session, "最终 USB sysfs state=" + propertyValue(count < 0 ? "" : new String(buffer, 0, count).trim()));
            } finally { reader.close(); }
        } catch (Throwable error) { line(session, "最终 USB sysfs state 读取失败：" + describe(error)); }
        line(session, "应用不可见/不可读不等于节点不存在；sys.usb.state=adb 也不证明物理 USB 已枚举。");
    }

    private void appendUi(final String message) {
        if (destroyed) return;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (destroyed) return;
                latestReport = latestReport + message + "\n";
                reportView.setText(latestReport);
            }
        });
    }

    private void finishProbe(final String message, boolean failed) {
        if (destroyed) return;
        if (bindTimeout != null) mainHandler.removeCallbacks(bindTimeout);
        safelyUnbind();
        binding = false;
        busy = false;
        if (current != null) {
            current.cancelled = failed;
            latestReport = latestReport + message + "\n";
            reportView.setText(latestReport);
        }
        detectButton.setEnabled(true);
        bindButton.setEnabled(true);
        setManualButtonsEnabled(true);
        statusView.setText(failed ? "绑定检测结束，可重新检测。" : message);
    }

    private void safelyUnbind() {
        try {
            if (serviceBindingRegistered) unbindService(xgimiConnection);
        } catch (Throwable ignored) { }
        binding = false;
        serviceBound = false;
        serviceBindingRegistered = false;
        boundRootForProbe = null;
    }

    private void probe(Session session) {
        line(session, "Z6X ADB 助手 1.6.0 / com.local.z6xserviceprobe");
        line(session, "时间：" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date()));
        line(session, "");
        line(session, "【设备信息 / Android Build】");
        line(session, "MANUFACTURER=" + value(Build.MANUFACTURER));
        line(session, "BRAND=" + value(Build.BRAND));
        line(session, "MODEL=" + value(Build.MODEL));
        line(session, "BOARD=" + value(Build.BOARD));
        line(session, "DEVICE=" + value(Build.DEVICE));
        line(session, "PRODUCT=" + value(Build.PRODUCT));
        line(session, "Android=" + value(Build.VERSION.RELEASE) + " / API=" + Build.VERSION.SDK_INT);
        line(session, "DISPLAY=" + value(Build.DISPLAY));
        line(session, "ID=" + value(Build.ID));
        line(session, "INCREMENTAL=" + value(Build.VERSION.INCREMENTAL));
        line(session, "FINGERPRINT=" + value(Build.FINGERPRINT));
        line(session, "本次系统运行秒数=" + android.os.SystemClock.elapsedRealtime() / 1000L);
        line(session, "");
        line(session, "【已安装应用 / PackageManager】");
        packageVersion(session, "com.android.newsettings");
        packageVersion(session, "com.xgimi.minitvfactory");
        if (cancelled(session)) return;
        authDiagnostics(session);
        if (cancelled(session)) return;

        line(session, "");
        line(session, "【本地属性 / 反射 SystemProperties.get】");
        line(session, "以下为本应用进程可读值；空值不能证明开关关闭或服务停止。");
        Method localGetter = null;
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            localGetter = properties.getMethod("get", String.class);
            line(session, "反射方法：已找到 get(String)");
        } catch (Throwable error) {
            line(session, "反射方法不可用：" + describe(error));
        }
        if (localGetter != null) {
            for (String key : PROPERTIES) {
                if (cancelled(session)) return;
                try {
                    Object result = localGetter.invoke(null, key);
                    if (result instanceof String) observe(session, key, (String) result);
                    line(session, key + " = " + propertyValue(result));
                } catch (Throwable error) {
                    line(session, key + " = 读取失败：" + describe(error));
                }
            }
        }
        if (cancelled(session)) return;

        line(session, "");
        line(session, "【极米服务 / ServiceManager → XGIMI】");
        IBinder managerRoot = null;
        try {
            Class<?> manager = Class.forName("android.os.ServiceManager");
            Method getService = manager.getMethod("getService", String.class);
            Object result = getService.invoke(null, "XGIMI");
            if (!(result instanceof IBinder)) {
                line(session, result == null ? "getService(\"XGIMI\") 返回 null：服务不存在、未就绪或不可访问。" : "返回类型不是 IBinder：" + result.getClass().getName());
            } else {
                managerRoot = (IBinder) result;
                line(session, "getService(\"XGIMI\")：收到非空 Binder");
            }
        } catch (Throwable error) {
            line(session, "获取 ServiceManager 服务失败：" + describe(error));
        }
        IBinder root = boundRootForProbe != null ? boundRootForProbe : managerRoot;
        if (boundRootForProbe != null) line(session, "本次协议查询来源：显式 bindService Binder（ServiceManager 结果仅作对照）。");
        else if (root == null) { line(session, "没有可用 XGIMI Binder：未发送远端事务。"); return; }
        if (cancelled(session)) return;
        try {
            String descriptor = root.getInterfaceDescriptor();
            line(session, "XGIMI descriptor=" + value(descriptor));
            if (!ROOT_DESCRIPTOR.equals(descriptor)) {
                line(session, "接口与已核实协议不符：停止远端事务，未尝试其他事务号。");
                return;
            }
        } catch (Throwable error) {
            line(session, "读取 XGIMI descriptor 失败：" + describe(error));
            return;
        }
        if (cancelled(session)) return;
        IBinder common;
        try {
            common = getCommon(root);
            if (common == null) {
                line(session, "transaction 1 返回 null IGimiCommon：停止远端查询。");
                return;
            }
            line(session, "transaction 1 / getGimiCommon：收到非空 Binder");
        } catch (Throwable error) {
            line(session, "transaction 1 / getGimiCommon 失败：" + describe(error));
            return;
        }
        if (cancelled(session)) return;
        try {
            String descriptor = common.getInterfaceDescriptor();
            line(session, "IGimiCommon descriptor=" + value(descriptor));
            if (!COMMON_DESCRIPTOR.equals(descriptor)) {
                line(session, "IGimiCommon 接口与已核实协议不符：停止远端属性查询。");
                return;
            }
        } catch (Throwable error) {
            line(session, "读取 IGimiCommon descriptor 失败：" + describe(error));
            return;
        }
        line(session, "");
        line(session, "【远端属性 / IGimiCommon transaction 19】");
        line(session, "逐项记录远端返回值；调用完成不代表 ADB 已开启。");
        for (String key : PROPERTIES) {
            if (cancelled(session)) return;
            try {
                String raw = getRemoteProperty(common, key);
                observe(session, key, raw);
                line(session, key + " = " + propertyValue(raw));
            } catch (Throwable error) {
                line(session, key + " = 远端读取失败：" + describe(error));
            }
        }
        line(session, "");
        line(session, "本报告仅记录读取结果；ADB 连接和网络端口是否开放需要另行实测。");
    }

    /** Local read-only observations; none of these APIs enables ADB or authorizes a host. */
    private void authDiagnostics(Session session) {
        line(session, "");
        line(session, "【全局设置 / Settings.Global，只读】");
        readGlobalSetting(session, Settings.Global.ADB_ENABLED);
        readGlobalSetting(session, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED);
        try {
            int permission = getPackageManager().checkPermission("android.permission.WRITE_SECURE_SETTINGS", getPackageName());
            line(session, "本应用 WRITE_SECURE_SETTINGS="
                + (permission == PackageManager.PERMISSION_GRANTED ? "GRANTED" : "DENIED")
                + "（仅查询权限；未调用 Settings.Global.putInt）");
        } catch (Throwable error) {
            line(session, "WRITE_SECURE_SETTINGS 权限查询失败：" + describe(error));
        }
        try {
            line(session, "本应用 Process.myUid=" + android.os.Process.myUid());
        } catch (Throwable error) {
            line(session, "本应用 UID 读取失败：" + describe(error));
        }

        line(session, "");
        line(session, "【USB 当前可见状态，只读】");
        try {
            Object service = getSystemService(Context.USB_SERVICE);
            if (service == null) line(session, "UsbManager 服务=<null：服务不存在或不可见>");
            else line(session, "UsbManager 服务=" + service.getClass().getName()
                + (service instanceof UsbManager ? "（可获得 UsbManager）" : "（类型不符）"));
        } catch (Throwable error) {
            line(session, "UsbManager 服务查询失败：" + describe(error));
        }
        try {
            // A null receiver only reads the latest sticky broadcast; it registers no callback.
            Intent state = registerReceiver(null, new IntentFilter("android.hardware.usb.action.USB_STATE"));
            if (state == null) {
                line(session, "ACTION_USB_STATE=<没有可读 sticky 广播，不能据此判断断开或 ADB 关闭>");
            } else {
                line(session, "ACTION_USB_STATE=" + value(state.getAction()) + "（最近一次 sticky 快照）");
                for (String key : new String[] { "connected", "configured", "adb" }) {
                    try {
                        if (!state.hasExtra(key)) line(session, "USB." + key + "=<extra 缺失：未知>");
                        else {
                            Object raw = state.getExtras() == null ? null : state.getExtras().get(key);
                            line(session, "USB." + key + "=" + (raw instanceof Boolean ? raw.toString()
                                : "<类型异常或 null：" + (raw == null ? "null" : raw.getClass().getName()) + ">"));
                        }
                    } catch (Throwable error) {
                        line(session, "USB." + key + " 读取失败：" + describe(error));
                    }
                }
            }
        } catch (Throwable error) {
            line(session, "ACTION_USB_STATE 读取失败：" + describe(error));
        }
        for (String feature : new String[] { "android.hardware.usb.host", "android.hardware.usb.accessory" }) {
            try {
                line(session, feature + "=" + getPackageManager().hasSystemFeature(feature));
            } catch (Throwable error) {
                line(session, feature + " 查询失败：" + describe(error));
            }
        }
        for (String path : new String[] { "/sys/class/android_usb", "/sys/class/android_usb/android0/state" }) {
            try {
                File file = new File(path);
                line(session, path + "：exists=" + file.exists() + " / canRead=" + file.canRead());
            } catch (Throwable error) {
                line(session, path + " 文件状态读取失败：" + describe(error));
            }
        }
        line(session, "普通应用的 exists=false/canRead=false 也可能是访问受限，不能单凭此确认内核节点不存在。");
        try {
            InputStreamReader reader = new InputStreamReader(
                new FileInputStream("/sys/class/android_usb/android0/state"), "UTF-8");
            try {
                char[] buffer = new char[256];
                int count = reader.read(buffer);
                line(session, "android0/state 内容=" + propertyValue(count < 0 ? "" : new String(buffer, 0, count).trim()));
            } finally {
                reader.close();
            }
        } catch (Throwable error) {
            line(session, "android0/state 内容读取失败：" + describe(error));
        }

        line(session, "");
        line(session, "【授权页面与开发者入口 / PackageManager，只查询】");
        inspectActivity(session, new ComponentName("com.android.systemui",
            "com.android.systemui.usb.UsbDebuggingActivity"));
        inspectActivity(session, new ComponentName("com.android.systemui",
            "com.android.systemui.usb.UsbDebuggingSecondaryUserActivity"));
        try {
            String action = Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS;
            ResolveInfo resolved = getPackageManager().resolveActivity(new Intent(action),
                PackageManager.MATCH_DEFAULT_ONLY | PackageManager.MATCH_DISABLED_COMPONENTS);
            line(session, "开发者设置 action=" + action);
            if (resolved == null || resolved.activityInfo == null) {
                line(session, "开发者设置解析=<没有匹配 Activity>");
            } else {
                ActivityInfo info = resolved.activityInfo;
                line(session, "开发者设置解析=" + info.packageName + "/" + info.name);
                activityDetails(session, info);
            }
        } catch (Throwable error) {
            line(session, "开发者设置解析失败：" + describe(error));
        }

        line(session, "");
        line(session, "【当前用户限制 / UserManager，只读】");
        try {
            Object service = getSystemService(Context.USER_SERVICE);
            if (!(service instanceof UserManager)) {
                line(session, "no_debugging_features=<UserManager 不可用或类型不符>");
            } else {
                Bundle restrictions = ((UserManager) service).getUserRestrictions();
                if (restrictions == null) line(session, "no_debugging_features=<返回 null restrictions：未知>");
                else {
                    String restriction = UserManager.DISALLOW_DEBUGGING_FEATURES;
                    boolean present = restrictions.containsKey(restriction);
                    boolean denied = restrictions.getBoolean(restriction, false);
                    line(session, restriction + "=" + denied
                        + (present ? "（限制项存在）" : "（限制项不存在，默认不限制）"));
                }
            }
        } catch (Throwable error) {
            line(session, "no_debugging_features 读取失败：" + describe(error));
        }
        line(session, "诊断值只反映可读标记、广播和组件状态，不能证明 RSA 授权管理器或其监听线程正在运行。");
        line(session, "本固件的 RSA 流程需以实际连接验证；不要用是否出现授权弹窗判断调试通道已启动。");
        line(session, "未启动授权页面、未写入 adb_enabled，也未向系统提交授权请求。");
    }

    private void readGlobalSetting(Session session, String key) {
        try {
            String raw = Settings.Global.getString(getContentResolver(), key);
            observe(session, key, raw);
            line(session, key + "=" + propertyValue(raw));
        } catch (Throwable error) {
            line(session, key + " 读取失败：" + describe(error));
        }
    }

    private void inspectActivity(Session session, ComponentName component) {
        line(session, "Activity=" + component.flattenToShortString());
        try {
            ActivityInfo info = getPackageManager().getActivityInfo(component,
                PackageManager.MATCH_DISABLED_COMPONENTS);
            activityDetails(session, info);
        } catch (PackageManager.NameNotFoundException error) {
            line(session, "  <未找到该 Activity：未安装、未声明或不可见>");
        } catch (Throwable error) {
            line(session, "  查询失败：" + describe(error));
        }
    }

    private void activityDetails(Session session, ActivityInfo info) {
        line(session, "  enabled=" + info.enabled + " / exported=" + info.exported
            + " / permission=" + (info.permission == null ? "<无组件权限>" : value(info.permission))
            + " / applicationEnabled=" + (info.applicationInfo == null ? "<未知>" : String.valueOf(info.applicationInfo.enabled)));
    }

    private void packageVersion(Session session, String packageName) {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(packageName, PackageManager.GET_DISABLED_COMPONENTS);
            line(session, packageName + "\n  versionName=" + value(info.versionName) + " / versionCode=" + info.versionCode
                + " / " + (info.applicationInfo != null && info.applicationInfo.enabled ? "启用" : "禁用或状态未知"));
        } catch (PackageManager.NameNotFoundException error) {
            line(session, packageName + " = 未找到（未安装或系统未向本应用提供）");
        } catch (Throwable error) {
            line(session, packageName + " = 查询失败：" + describe(error));
        }
    }

    private static IBinder getCommon(IBinder root) throws Exception {
        Parcel request = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(ROOT_DESCRIPTOR);
            if (!root.transact(GET_COMMON, request, reply, 0)) throw new IllegalStateException("transact=false：远端未处理 transaction 1");
            if (reply.dataAvail() < 4) throw new IllegalStateException("远端响应为空或不完整，缺少异常头");
            reply.readException();
            if (reply.dataAvail() < 4) throw new IllegalStateException("远端响应不完整，缺少 Binder 返回值");
            return reply.readStrongBinder();
        } finally {
            reply.recycle();
            request.recycle();
        }
    }

    private static String getRemoteProperty(IBinder common, String key) throws Exception {
        if (!allowed(key)) throw new IllegalArgumentException("属性不在只读白名单中");
        Parcel request = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(COMMON_DESCRIPTOR);
            request.writeString(key);
            if (!common.transact(GET_PROPERTY, request, reply, 0)) throw new IllegalStateException("transact=false：远端未处理 transaction 19");
            if (reply.dataAvail() < 4) throw new IllegalStateException("远端响应为空或不完整，缺少异常头");
            reply.readException();
            if (reply.dataAvail() < 4) throw new IllegalStateException("远端响应不完整，缺少 String 返回值");
            return reply.readString();
        } finally {
            reply.recycle();
            request.recycle();
        }
    }

    private static boolean allowed(String key) {
        for (String name : PROPERTIES) if (name.equals(key)) return true;
        return false;
    }

    private boolean cancelled(Session session) {
        if (!session.cancelled && !Thread.currentThread().isInterrupted()) return false;
        session.cancelled = true;
        line(session, "\n已离开检测页面，停止后续读取。");
        return true;
    }

    private void observe(Session session, String key, String raw) {
        if (raw == null) session.values.remove(key);
        else session.values.put(key, raw);
    }

    private String reportSnapshot(Session session) {
        synchronized (session.report) { return session.report.toString(); }
    }

    private void line(final Session session, String line) {
        final String snapshot;
        final long generation;
        synchronized (session.report) {
            session.report.append(line).append('\n');
            snapshot = session.report.toString();
            generation = ++session.reportGeneration;
        }
        final java.util.Map<String, String> values = new java.util.HashMap<String, String>(session.values);
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (destroyed || current != session || generation < session.renderedGeneration) return;
                session.renderedGeneration = generation;
                latestReport = snapshot;
                reportView.setText(snapshot);
                ui.updateState(values);
            }
        });
    }

    private static String propertyValue(Object result) {
        if (result == null) return "<返回 null：不据此判断状态>";
        if (!(result instanceof String)) return "<返回类型异常：" + result.getClass().getName() + ">";
        String text = (String) result;
        if (text.length() == 0) return "<空字符串：未设置或不可读，不据此判断状态>";
        return '"' + text.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t").replace("\"", "\\\"") + '"';
    }

    private static String value(String text) { return text == null ? "<null>" : text.length() == 0 ? "<空>" : text; }

    private static String describe(Throwable error) {
        while (error instanceof InvocationTargetException && ((InvocationTargetException) error).getTargetException() != null) {
            error = ((InvocationTargetException) error).getTargetException();
        }
        String message = error.getMessage();
        return error.getClass().getName() + (message == null || message.length() == 0 ? "" : ": " + message);
    }

    private void copyReport() {
        try {
            ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager == null) throw new IllegalStateException("剪贴板服务不可用");
            manager.setPrimaryClip(ClipData.newPlainText("Z6X ADB 助手", latestReport));
            Toast.makeText(this, "已复制诊断全文", Toast.LENGTH_SHORT).show();
        } catch (Throwable error) {
            Toast.makeText(this, "复制失败：" + describe(error), Toast.LENGTH_LONG).show();
        }
    }

    private void requestExport() {
        if (exportBusy || destroyed) return;
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
            exportPending = true;
            statusView.setText("需要存储权限以写入公共 Download；拒绝后会回退到应用 Documents 目录。");
            try {
                requestPermissions(new String[] { Manifest.permission.WRITE_EXTERNAL_STORAGE },
                    REQUEST_WRITE_EXTERNAL_STORAGE);
            } catch (Throwable error) {
                exportPending = false;
                queueExport(true, "权限请求异常：" + describe(error));
            }
            return;
        }
        queueExport(false, null);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_WRITE_EXTERNAL_STORAGE || !exportPending || destroyed) return;
        exportPending = false;
        boolean granted = grantResults != null && grantResults.length > 0
            && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) queueExport(false, "已获得存储权限，写入公共 Download。");
        else queueExport(true, "未获得存储权限，回退到应用 Documents 目录。");
    }

    private void queueExport(final boolean forceFallback, final String preface) {
        if (exportBusy || destroyed) return;
        exportBusy = true;
        exportButton.setEnabled(false);
        if (preface != null) statusView.setText(preface);
        final String snapshot = latestReport == null ? "" : latestReport;
        try {
            executor.execute(new Runnable() {
                @Override public void run() {
                    try {
                        final ExportResult result = writeExport(snapshot, forceFallback);
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                if (destroyed) return;
                                exportBusy = false;
                                exportButton.setEnabled(true);
                                String mode = result.fallback ? "已回退到应用 Documents" : "已写入公共 Download";
                                statusView.setText(mode + "：\n" + result.path
                                    + (result.detail == null ? "" : "\n" + result.detail));
                            }
                        });
                    } catch (final Throwable error) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                if (destroyed) return;
                                exportBusy = false;
                                exportButton.setEnabled(true);
                                statusView.setText("TXT 导出失败：" + describe(error));
                            }
                        });
                    }
                }
            });
        } catch (RejectedExecutionException error) {
            exportBusy = false;
            exportButton.setEnabled(true);
            statusView.setText("无法开始 TXT 导出：" + describe(error));
        }
    }

    private ExportResult writeExport(String snapshot, boolean forceFallback) throws Exception {
        String filename = "Z6X-Service-Probe-"
            + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
        String publicFailure = null;
        if (!forceFallback) {
            File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File target = new File(downloads, filename);
            try {
                writeUtf8(target, snapshot);
                return new ExportResult(target.getAbsolutePath(), false, null);
            } catch (Throwable error) {
                publicFailure = "公共 Download 写入失败：" + describe(error);
            }
        } else {
            publicFailure = "用户未授予公共存储权限";
        }

        File documents = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (documents == null) throw new IllegalStateException(publicFailure + "；应用 Documents 目录不可用");
        File fallback = new File(documents, filename);
        try {
            writeUtf8(fallback, snapshot);
            return new ExportResult(fallback.getAbsolutePath(), true, publicFailure);
        } catch (Throwable error) {
            throw new IllegalStateException(publicFailure + "；应用 Documents 写入失败：" + describe(error), error);
        }
    }

    private static void writeUtf8(File target, String text) throws Exception {
        File parent = target.getParentFile();
        if (parent == null || (!parent.exists() && !parent.mkdirs()) || !parent.isDirectory()) {
            throw new IllegalStateException("目标目录不可用：" + (parent == null ? "<null>" : parent.getAbsolutePath()));
        }
        Writer writer = new OutputStreamWriter(new FileOutputStream(target, false), "UTF-8");
        try {
            writer.write(text == null ? "" : text);
            writer.flush();
        } finally {
            writer.close();
        }
    }

    @Override protected void onStop() {
        if (current != null && busy) {
            current.cancelled = true;
            finishProbe("已离开应用，取消后续诊断；返回后可重新检测。", true);
        } else if (binding || serviceBound) safelyUnbind();
        if (manualSession != null && manualBusy) {
            manualSession.cancelled = true;
            finishManual("已离开应用，取消后续启停流程；已发送的请求不会撤销，请重新检测确认。", true);
        } else if (manualBinding || manualServiceBound) safelyUnbindManual();
        if (usbBusy) {
            if (usbSession != null) usbSession.cancelled = true;
            if (usbConfirmation != null) { usbConfirmation.dismiss(); usbConfirmation = null; }
            finishUsbConfig("已离开应用，取消后续配置操作；如请求已发送需回读确认。", true);
        }
        super.onStop();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (current != null) current.cancelled = true;
        safelyUnbind();
        if (manualSession != null) manualSession.cancelled = true;
        safelyUnbindManual();
        if (usbSession != null) usbSession.cancelled = true;
        unbindUsbConfig();
        executor.shutdownNow();
        super.onDestroy();
    }

}
