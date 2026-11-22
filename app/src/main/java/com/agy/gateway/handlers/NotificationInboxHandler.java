package com.agy.gateway.handlers;

import android.content.Context;

import com.agy.core.AgyNotificationListenerService;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Gateway handler exposing Android NotificationListenerService inbox status,
 * listing, WearOS-style RemoteInput Quick Reply, and dismissal.
 */
public final class NotificationInboxHandler {

    private final Context context;

    public NotificationInboxHandler(Context context) {
        this.context = context.getApplicationContext();
    }

    public JSONObject handleStatus() {
        JSONObject resp = new JSONObject();
        try {
            boolean enabled = AgyNotificationListenerService.isEnabled(context);
            boolean connected = AgyNotificationListenerService.isConnected();
            int count = AgyNotificationListenerService.getStoredCount();
            resp.put("ok", true);
            resp.put("enabled", enabled);
            resp.put("connected", connected);
            resp.put("count", count);
        } catch (Exception e) {
            try {
                resp.put("ok", false);
                resp.put("error", e.getMessage());
            } catch (Exception ignored) {
            }
        }
        return resp;
    }

    public JSONObject handleList(JSONObject payload) {
        JSONObject resp = new JSONObject();
        try {
            String pkg = "";
            boolean replyableOnly = false;
            int limit = 30;
            if (payload != null) {
                pkg = payload.optString("package", "").trim();
                if (pkg.isEmpty()) {
                    pkg = payload.optString("package_name", "").trim();
                }
                replyableOnly = payload.optBoolean("replyable_only", false);
                limit = payload.optInt("limit", 30);
            }
            boolean enabled = AgyNotificationListenerService.isEnabled(context);
            boolean connected = AgyNotificationListenerService.isConnected();
            JSONArray notifications = AgyNotificationListenerService.listNotifications(
                    pkg, replyableOnly, limit);

            resp.put("ok", true);
            resp.put("enabled", enabled);
            resp.put("connected", connected);
            resp.put("count", notifications.length());
            resp.put("notifications", notifications);
        } catch (Exception e) {
            try {
                resp.put("ok", false);
                resp.put("error", e.getMessage());
            } catch (Exception ignored) {
            }
        }
        return resp;
    }

    public JSONObject handleReply(JSONObject payload) {
        String key = payload != null ? payload.optString("key", "").trim() : "";
        String message = payload != null ? payload.optString("message", "").trim() : "";
        if (message.isEmpty() && payload != null) {
            message = payload.optString("text", "").trim();
        }
        return AgyNotificationListenerService.replyToNotification(context, key, message);
    }

    public JSONObject handleDismiss(JSONObject payload) {
        String key = payload != null ? payload.optString("key", "").trim() : "";
        return AgyNotificationListenerService.dismissNotification(key);
    }
}
