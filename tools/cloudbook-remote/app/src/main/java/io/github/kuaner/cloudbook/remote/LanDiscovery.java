// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.kuaner.cloudbook.remote;

import android.app.Activity;
import android.app.AlertDialog;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import java.net.Inet4Address;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

final class LanDiscovery {
    interface Selected { void choose(String address, int port); }

    private static final String TYPE = "_rfb._tcp.";
    private final Activity activity;
    private final NsdManager manager;
    private final Selected selected;
    private final Runnable dismissed;
    private final Map<String, NsdServiceInfo> services = new LinkedHashMap<>();
    private final ArrayAdapter<String> names;
    private AlertDialog dialog;
    private boolean started;
    private boolean closed;
    private boolean resolving;

    LanDiscovery(Activity activity, Selected selected, Runnable dismissed) {
        this.activity = activity;
        this.selected = selected;
        this.dismissed = dismissed;
        manager = activity.getSystemService(NsdManager.class);
        names = new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1);
    }

    void show() {
        ListView results = new ListView(activity);
        results.setAdapter(names);
        results.setOnItemClickListener((view, row, index, id) -> resolve(index));
        dialog = new AlertDialog.Builder(activity).setTitle("正在查找局域网 Mac…")
                .setView(results).setNegativeButton("取消", null).create();
        dialog.setOnDismissListener(window -> close());
        dialog.show();
        try {
            manager.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener);
            started = true;
        } catch (RuntimeException error) {
            dialog.setTitle("发现不可用，请手动输入 Mac 地址");
        }
    }

    void close() {
        if (closed) return;
        closed = true;
        stopDiscovery();
        if (dialog.isShowing()) dialog.dismiss();
        dismissed.run();
    }

    private void stopDiscovery() {
        if (!started) return;
        started = false;
        try { manager.stopServiceDiscovery(listener); }
        catch (IllegalArgumentException ignored) { }
    }

    private void refresh() {
        if (closed || resolving) return;
        names.clear();
        names.addAll(services.keySet());
        dialog.setTitle(services.isEmpty() ? "正在查找局域网 Mac…" : "选择已开启屏幕共享的 Mac");
    }

    @SuppressWarnings("deprecation")
    private void resolve(int index) {
        if (resolving || index >= services.size()) return;
        NsdServiceInfo service = new ArrayList<>(services.values()).get(index);
        resolving = true;
        dialog.setTitle("正在解析 Mac 地址…");
        try {
            manager.resolveService(service, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo ignored, int code) {
                    activity.runOnUiThread(() -> resolveFailed(code));
                }

                @Override public void onServiceResolved(NsdServiceInfo info) {
                    activity.runOnUiThread(() -> {
                        if (closed) return;
                        if (info.getHost() instanceof Inet4Address) {
                            selected.choose(info.getHost().getHostAddress(), info.getPort());
                            close();
                        } else {
                            resolveFailed(0);
                        }
                    });
                }
            });
        } catch (RuntimeException error) {
            resolveFailed(0);
        }
    }

    private void resolveFailed(int reason) {
        if (closed) return;
        Log.i("CloudBookRemote", "NSD resolve unavailable: " + reason);
        resolving = false;
        refresh();
        dialog.setTitle("无法解析 IPv4，请重试或手动输入地址");
    }

    private final NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
        @Override public void onDiscoveryStarted(String serviceType) {}
        @Override public void onDiscoveryStopped(String serviceType) {}
        @Override public void onStopDiscoveryFailed(String serviceType, int code) {}
        @Override public void onStartDiscoveryFailed(String serviceType, int code) {
            activity.runOnUiThread(() -> {
                if (!closed) {
                    stopDiscovery();
                    dialog.setTitle("发现不可用，请手动输入 Mac 地址");
                }
            });
        }
        @Override public void onServiceFound(NsdServiceInfo service) {
            activity.runOnUiThread(() -> {
                if (closed) return;
                services.put(service.getServiceName(), service);
                refresh();
            });
        }
        @Override public void onServiceLost(NsdServiceInfo service) {
            activity.runOnUiThread(() -> {
                if (closed) return;
                services.remove(service.getServiceName());
                refresh();
            });
        }
    };
}
