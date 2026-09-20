package com.luomic.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.app.Activity;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** luo mic 主界面：一键开始/停止，显示连接状态与实时电平。 */
public class MainActivity extends Activity {

    private static final int REQ_PERM = 1001;

    private TextView statusTitle;
    private TextView statusDetail;
    private TextView addrText;
    private TextView statText;
    private View dot;
    private ProgressBar levelBar;
    private Button btnToggle;

    private boolean serviceRunning;
    private long lastUiAt;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String json = intent.getStringExtra(MicService.EXTRA_STATE);
            if (json != null) {
                applyState(json);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusTitle = findViewById(R.id.statusTitle);
        statusDetail = findViewById(R.id.statusDetail);
        addrText = findViewById(R.id.addrText);
        statText = findViewById(R.id.statText);
        dot = findViewById(R.id.dot);
        levelBar = findViewById(R.id.levelBar);
        btnToggle = findViewById(R.id.btnToggle);
        Button btnRescan = findViewById(R.id.btnRescan);
        Button btnSettings = findViewById(R.id.btnSettings);

        btnToggle.setOnClickListener(v -> {
            if (serviceRunning) {
                MicService.stop(this);
                serviceRunning = false;
                renderIdle();
            } else {
                if (!hasMicPermission()) {
                    requestPermissions();
                    return;
                }
                MicService.start(this);
                serviceRunning = true;
                statusTitle.setText("启动中…");
                statusDetail.setText("正在启动麦克风服务…");
            }
        });

        btnRescan.setOnClickListener(v -> {
            if (!serviceRunning) {
                toast("请先点“开始”");
                return;
            }
            MicService.rescan(this);
            toast("已发送重新扫描请求");
        });

        btnSettings.setOnClickListener(v -> showSettings());
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(MicService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
        renderIdle();
        if (!hasMicPermission()) {
            statusDetail.setText("尚未授予录音权限，点“开始”会弹出授权请求。");
        }
        requestNotificationPermissionIfNeeded();
    }

    @Override
    protected void onStop() {
        try {
            unregisterReceiver(stateReceiver);
        } catch (Exception ignored) {
            // 未注册时忽略
        }
        super.onStop();
    }

    /* ================= 权限 ================= */

    private boolean hasMicPermission() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        List<String> need = new ArrayList<>();
        need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        requestPermissions(need.toArray(new String[0]), REQ_PERM);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // 通知不是必需项，静默请求一次即可；被拒绝也不影响传输
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_PERM + 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM) {
            if (hasMicPermission()) {
                MicService.start(this);
                serviceRunning = true;
                statusTitle.setText("启动中…");
                statusDetail.setText("正在启动麦克风服务…");
            } else {
                toast("没有录音权限无法采集麦克风");
                statusDetail.setText("录音权限被拒绝：请到系统设置 → 应用 → luo mic → 权限 中开启。");
            }
        }
    }

    /* ================= 状态渲染 ================= */

    private void renderIdle() {
        if (serviceRunning) {
            return;
        }
        statusTitle.setText("未启动");
        statusDetail.setText("点击下方按钮开始：手机将自动搜索同一局域网内的电脑。");
        dot.setBackground(circle(Color.parseColor("#9AA4B2")));
        levelBar.setProgress(0);
        statText.setText("");
        String last = MicService.lastServerLabel(this);
        addrText.setText("本机 IP：" + Discovery.localIpv4()
                + (last.isEmpty() ? "" : "\n上次的电脑：" + last + "（启动后自动直连）"));
        btnToggle.setText("开始");
    }

    private void applyState(String json) {
        String phase = Proto.jsonString(json, "phase");
        String detail = Proto.jsonString(json, "detail");
        boolean connected = "true".equals(Proto.jsonString(json, "connected"));
        boolean streaming = "true".equals(Proto.jsonString(json, "streaming"));
        float db = parseFloat(Proto.jsonString(json, "levelDb"), -120f);
        int fps = Proto.jsonInt(json, "fps", 0);
        int kbps = Proto.jsonInt(json, "kbps", 0);
        int rate = Proto.jsonInt(json, "rate", 48000);
        int frameMs = Proto.jsonInt(json, "frameMs", 20);
        String serverIp = Proto.jsonString(json, "serverIp");
        String serverName = Proto.jsonString(json, "serverName");

        serviceRunning = phase != null && !"idle".equals(phase);
        if (phase == null) {
            return;
        }
        switch (phase) {
            case "idle":
                renderIdle();
                return;
            case "scanning":
                statusTitle.setText("正在扫描电脑…");
                dot.setBackground(circle(Color.parseColor("#FFB020")));
                break;
            case "found":
            case "connecting":
                statusTitle.setText("正在连接…");
                dot.setBackground(circle(Color.parseColor("#FFB020")));
                break;
            case "streaming":
                statusTitle.setText("正在传输");
                dot.setBackground(circle(Color.parseColor("#3DDC97")));
                break;
            case "connected":
                statusTitle.setText("已连接（待电脑接收）");
                dot.setBackground(circle(Color.parseColor("#3DDC97")));
                break;
            case "retry":
                statusTitle.setText("连接中断，自动重连中…");
                dot.setBackground(circle(Color.parseColor("#FF5A5A")));
                break;
            default:
                statusTitle.setText(phase);
                break;
        }
        if (detail != null) {
            statusDetail.setText(detail);
        }
        btnToggle.setText("停止");
        addrText.setText("本机 IP：" + Discovery.localIpv4()
                + (serverIp == null || serverIp.isEmpty() ? "" : "\n电脑：" + serverName + " @ " + serverIp)
                + "\n音频：" + (rate / 1000) + " kHz / " + frameMs + " ms / 单声道");

        if (streaming) {
            // -60dB ~ 0dB 映射到 0 ~ 100
            int level = (int) Math.max(0, Math.min(100, (db + 60f) / 60f * 100f));
            levelBar.setProgress(level);
            statText.setText(kbps + " kbps · " + fps + " fps");
        } else {
            levelBar.setProgress(0);
            statText.setText(connected ? "已连接" : "");
        }
        lastUiAt = System.currentTimeMillis();
    }

    private static float parseFloat(String s, float def) {
        if (s == null) {
            return def;
        }
        try {
            return Float.parseFloat(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static GradientDrawable circle(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    /* ================= 设置 ================= */

    private void showSettings() {
        SharedPreferences prefs = getSharedPreferences("luomic", MODE_PRIVATE);
        final boolean[] push = {prefs.getBoolean("push_enabled", true)};
        int rate = prefs.getInt("rate", 48000);
        final int[] selectedRate = {rate};

        String[] rateItems = {"48 kHz（推荐，音质最好）", "16 kHz（省流量，弱网更稳）"};
        String[] pushItems = {"电脑请求时开始推流（推荐）", "关闭推流（只保持连接）"};

        new AlertDialog.Builder(this)
                .setTitle("设置")
                .setSingleChoiceItems(rateItems, rate == 16000 ? 1 : 0, (d, which) -> {
                    selectedRate[0] = which == 1 ? 16000 : 48000;
                })
                .setPositiveButton("保存", (d, which) -> {
                    prefs.edit().putInt("rate", selectedRate[0]).apply();
                    toast("已保存，重新“开始”后生效");
                })
                .setNegativeButton("取消", null)
                .setNeutralButton("推流开关", (d, which) -> new AlertDialog.Builder(this)
                        .setTitle("推流开关")
                        .setSingleChoiceItems(pushItems, push[0] ? 0 : 1, (d2, w2) -> {
                            push[0] = w2 == 0;
                        })
                        .setPositiveButton("确定", (d2, w2) -> {
                            prefs.edit().putBoolean("push_enabled", push[0]).apply();
                            if (serviceRunning) {
                                MicService.setPush(this, push[0]);
                            }
                            toast(push[0] ? "已开启推流" : "已关闭推流");
                        })
                        .show())
                .show();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
