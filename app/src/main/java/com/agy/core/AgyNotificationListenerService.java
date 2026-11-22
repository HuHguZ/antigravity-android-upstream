package com.agy.core;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NotificationListenerService capturing active and recent notifications from
 * third-party apps (Telegram, WhatsApp, SMS, etc.) and exposing WearOS-style
 * RemoteInput Quick Reply and dismissal to {@code NotificationInboxHandler}.
 */
public final class AgyNotificationListenerService extends NotificationListenerService {

    private static final String TAG = "AgyNotifListener";
    private static final int MAX_ENTRIES = 100;
    private static final int DEFAULT_LIST_LIMIT = 30;
    private static final int MAX_TEXT_CHARS = 2000;

    private static final Object STORE_LOCK = new Object();
    private static final LinkedHashMap<String, NotificationEntry> STORE = new LinkedHashMap<>();
    private static final ConcurrentHashMap<String, String> APP_LABEL_CACHE = new ConcurrentHashMap<>();

    private static volatile AgyNotificationListenerService instance;
    private static volatile boolean connected = false;

    private static final class NotificationEntry {
        final String key;
        final String packageName;
        final String appLabel;
        final String title;
        final String text;
        final String bigText;
        final String conversationTitle;
        final long postedAt;
        volatile boolean active;
        volatile Notification.Action replyAction;
        volatile RemoteInput[] replyInputs;
        final String replyLabel;

        NotificationEntry(String key, String packageName, String appLabel,
                          String title, String text, String bigText,
                          String conversationTitle, long postedAt, boolean active,
                          Notification.Action replyAction, RemoteInput[] replyInputs,
                          String replyLabel) {
            this.key = key;
            this.packageName = packageName;
            this.appLabel = appLabel;
            this.title = title;
            this.text = text;
            this.bigText = bigText;
            this.conversationTitle = conversationTitle;
            this.postedAt = postedAt;
            this.active = active;
            this.replyAction = replyAction;
            this.replyInputs = replyInputs;
            this.replyLabel = replyLabel;
        }

        boolean canReply() {
            return active
                    && replyAction != null
                    && replyAction.actionIntent != null
                    && replyInputs != null
                    && replyInputs.length > 0;
        }

        JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("key", key);
                obj.put("package_name", packageName);
                obj.put("app_label", appLabel);
                obj.put("title", title);
                obj.put("text", text);
                obj.put("big_text", bigText);
                obj.put("conversation_title", conversationTitle);
                obj.put("posted_at", postedAt);
                obj.put("active", active);
                obj.put("can_reply", canReply());
                obj.put("reply_label", canReply() ? replyLabel : "");
            } catch (Exception ignored) {
            }
            return obj;
        }
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        instance = this;
        connected = true;
        Log.i(TAG, "NotificationListenerService connected");
        syncActiveNotifications();
    }

    @Override
    public void onListenerDisconnected() {
        connected = false;
        if (instance == this) {
            instance = null;
        }
        Log.i(TAG, "NotificationListenerService disconnected");
        super.onListenerDisconnected();
    }

    @Override
    public void onDestroy() {
        connected = false;
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        instance = this;
        connected = true;
        try {
            ingestNotification(sbn);
        } catch (Throwable t) {
            Log.w(TAG, "onNotificationPosted error: " + t.getMessage());
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) {
            return;
        }
        String key = sbn.getKey();
        if (key == null || key.isEmpty()) {
            return;
        }
        synchronized (STORE_LOCK) {
            NotificationEntry existing = STORE.get(key);
            if (existing != null) {
                existing.active = false;
                existing.replyAction = null;
                existing.replyInputs = null;
            }
        }
    }

    private void syncActiveNotifications() {
        try {
            StatusBarNotification[] activeList = getActiveNotifications();
            if (activeList == null) {
                return;
            }
            Set<String> activeKeys = new HashSet<>();
            for (StatusBarNotification sbn : activeList) {
                if (sbn != null && sbn.getKey() != null) {
                    activeKeys.add(sbn.getKey());
                    ingestNotification(sbn);
                }
            }
            synchronized (STORE_LOCK) {
                for (NotificationEntry entry : STORE.values()) {
                    if (entry.active && !activeKeys.contains(entry.key)) {
                        entry.active = false;
                        entry.replyAction = null;
                        entry.replyInputs = null;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "syncActiveNotifications error: " + t.getMessage());
        }
    }

    private void ingestNotification(StatusBarNotification sbn) {
        if (sbn == null) {
            return;
        }
        Notification notif = sbn.getNotification();
        if (notif == null) {
            return;
        }
        String pkg = sbn.getPackageName();
        if (pkg == null || pkg.isEmpty() || pkg.equals(getPackageName())) {
            return;
        }

        Notification.Action replyAction = findReplyAction(notif);
        RemoteInput[] replyInputs = replyAction != null ? replyAction.getRemoteInputs() : null;
        boolean hasReply = replyAction != null
                && replyAction.actionIntent != null
                && replyInputs != null
                && replyInputs.length > 0;

        // Skip group summaries unless they expose a reply action
        if (!hasReply && (notif.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
            return;
        }

        // Skip ongoing low-value system/background progress noise unless replyable
        boolean isOngoing = sbn.isOngoing()
                || (notif.flags & Notification.FLAG_ONGOING_EVENT) != 0
                || (notif.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0;
        if (isOngoing && !hasReply) {
            return;
        }
        String category = notif.category;
        if (!hasReply && (Notification.CATEGORY_PROGRESS.equals(category)
                || Notification.CATEGORY_SERVICE.equals(category)
                || Notification.CATEGORY_TRANSPORT.equals(category))) {
            return;
        }

        Bundle extras = notif.extras;
        String title = extras != null ? charSeqToString(extras.getCharSequence(Notification.EXTRA_TITLE)) : "";
        String text = extras != null ? charSeqToString(extras.getCharSequence(Notification.EXTRA_TEXT)) : "";
        String bigText = extras != null ? charSeqToString(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)) : "";
        String convTitle = extras != null
                ? charSeqToString(extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE))
                : "";

        // Enrich MessagingStyle notifications (Telegram, WhatsApp, SMS) when text/bigText is sparse
        if (extras != null) {
            String messagingDigest = extractMessagingStyleText(extras);
            if (!messagingDigest.isEmpty()) {
                if (bigText.isEmpty()) {
                    bigText = messagingDigest;
                }
                if (text.isEmpty()) {
                    text = messagingDigest;
                }
            }
        }

        if (title.isEmpty() && text.isEmpty() && bigText.isEmpty() && !hasReply) {
            return;
        }

        String key = sbn.getKey();
        if (key == null || key.isEmpty()) {
            key = pkg + ":" + sbn.getId() + ":" + sbn.getPostTime();
        }
        String appLabel = resolveAppLabel(pkg);
        long postedAt = sbn.getPostTime() > 0L ? sbn.getPostTime() : System.currentTimeMillis();
        String replyLabel = hasReply ? extractReplyLabel(replyAction, replyInputs) : "";

        NotificationEntry entry = new NotificationEntry(
                key,
                pkg,
                appLabel,
                truncate(title, 300),
                truncate(text, MAX_TEXT_CHARS),
                truncate(bigText, MAX_TEXT_CHARS),
                truncate(convTitle, 300),
                postedAt,
                true,
                hasReply ? replyAction : null,
                hasReply ? replyInputs : null,
                replyLabel);

        synchronized (STORE_LOCK) {
            STORE.remove(key);
            STORE.put(key, entry);
            trimStoreLocked();
        }
    }

    private static void trimStoreLocked() {
        if (STORE.size() <= MAX_ENTRIES) {
            return;
        }
        // Prefer evicting oldest inactive entries first
        Iterator<NotificationEntry> it = STORE.values().iterator();
        while (STORE.size() > MAX_ENTRIES && it.hasNext()) {
            NotificationEntry e = it.next();
            if (!e.active) {
                it.remove();
            }
        }
        // If still over cap, evict oldest entries overall
        Iterator<NotificationEntry> fallbackIt = STORE.values().iterator();
        while (STORE.size() > MAX_ENTRIES && fallbackIt.hasNext()) {
            fallbackIt.next();
            fallbackIt.remove();
        }
    }

    private String resolveAppLabel(String pkg) {
        String cached = APP_LABEL_CACHE.get(pkg);
        if (cached != null) {
            return cached;
        }
        String label = pkg;
        try {
            PackageManager pm = getPackageManager();
            if (pm != null) {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                CharSequence cs = pm.getApplicationLabel(ai);
                if (cs != null && cs.length() > 0) {
                    label = cs.toString();
                }
            }
        } catch (Throwable ignored) {
        }
        APP_LABEL_CACHE.put(pkg, label);
        return label;
    }

    private static Notification.Action findReplyAction(Notification notif) {
        if (notif.actions != null) {
            Notification.Action found = findReplyInArray(notif.actions);
            if (found != null) {
                return found;
            }
        }
        try {
            Notification.WearableExtender extender = new Notification.WearableExtender(notif);
            List<Notification.Action> wearActions = extender.getActions();
            if (wearActions != null && !wearActions.isEmpty()) {
                return findReplyInArray(wearActions.toArray(new Notification.Action[0]));
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Notification.Action findReplyInArray(Notification.Action[] actions) {
        Notification.Action fallback = null;
        for (Notification.Action act : actions) {
            if (act == null || act.actionIntent == null) {
                continue;
            }
            RemoteInput[] inputs = act.getRemoteInputs();
            if (inputs == null || inputs.length == 0) {
                continue;
            }
            for (RemoteInput ri : inputs) {
                if (ri != null && ri.getAllowFreeFormInput()) {
                    return act;
                }
            }
            if (fallback == null) {
                fallback = act;
            }
        }
        return fallback;
    }

    private static String extractReplyLabel(Notification.Action action, RemoteInput[] inputs) {
        if (inputs != null) {
            for (RemoteInput ri : inputs) {
                if (ri != null && ri.getLabel() != null) {
                    String s = ri.getLabel().toString().trim();
                    if (!s.isEmpty()) {
                        return s;
                    }
                }
            }
        }
        if (action != null && action.title != null) {
            String s = action.title.toString().trim();
            if (!s.isEmpty()) {
                return s;
            }
        }
        return "Reply";
    }

    private static String extractMessagingStyleText(Bundle extras) {
        try {
            Parcelable[] rawMessages = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
            if (rawMessages == null || rawMessages.length == 0) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            int start = Math.max(0, rawMessages.length - 5);
            for (int i = start; i < rawMessages.length; i++) {
                if (!(rawMessages[i] instanceof Bundle)) {
                    continue;
                }
                Bundle msg = (Bundle) rawMessages[i];
                String msgText = charSeqToString(msg.getCharSequence("text"));
                if (msgText.isEmpty()) {
                    continue;
                }
                String sender = charSeqToString(msg.getCharSequence("sender"));
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                if (!sender.isEmpty()) {
                    sb.append(sender).append(": ");
                }
                sb.append(msgText);
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static boolean isConnected() {
        return connected && instance != null;
    }

    public static boolean isEnabled(Context context) {
        if (context == null) {
            return false;
        }
        try {
            String flat = Settings.Secure.getString(
                    context.getContentResolver(), "enabled_notification_listeners");
            if (flat == null || flat.isEmpty()) {
                return false;
            }
            String pkg = context.getPackageName();
            String[] parts = flat.split(":");
            for (String part : parts) {
                if (part != null && (part.startsWith(pkg + "/") || part.equals(pkg))) {
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "isEnabled check failed: " + t.getMessage());
        }
        return false;
    }

    public static int getStoredCount() {
        synchronized (STORE_LOCK) {
            return STORE.size();
        }
    }

    public static JSONArray listNotifications(String packageFilter, boolean replyableOnly, int limit) {
        AgyNotificationListenerService svc = instance;
        if (svc != null && connected) {
            svc.syncActiveNotifications();
        }
        int clampedLimit = limit <= 0 ? DEFAULT_LIST_LIMIT : Math.min(limit, MAX_ENTRIES);
        String filterLower = packageFilter != null ? packageFilter.trim().toLowerCase(Locale.US) : "";

        List<NotificationEntry> snapshot;
        synchronized (STORE_LOCK) {
            snapshot = new ArrayList<>(STORE.values());
        }
        Collections.sort(snapshot, new Comparator<NotificationEntry>() {
            @Override
            public int compare(NotificationEntry a, NotificationEntry b) {
                return Long.compare(b.postedAt, a.postedAt);
            }
        });

        JSONArray result = new JSONArray();
        for (NotificationEntry entry : snapshot) {
            if (result.length() >= clampedLimit) {
                break;
            }
            if (replyableOnly && !entry.canReply()) {
                continue;
            }
            if (!filterLower.isEmpty()) {
                String pkgLower = entry.packageName.toLowerCase(Locale.US);
                String lblLower = entry.appLabel.toLowerCase(Locale.US);
                if (!pkgLower.equals(filterLower)
                        && !pkgLower.contains(filterLower)
                        && !lblLower.contains(filterLower)) {
                    continue;
                }
            }
            result.put(entry.toJson());
        }
        return result;
    }

    public static JSONObject replyToNotification(Context context, String key, String message) {
        JSONObject resp = new JSONObject();
        try {
            if (key == null || key.trim().isEmpty()) {
                resp.put("ok", false);
                resp.put("error", "missing notification key");
                return resp;
            }
            if (message == null || message.trim().isEmpty()) {
                resp.put("ok", false);
                resp.put("error", "missing reply message");
                return resp;
            }
            AgyNotificationListenerService svc = instance;
            if (!connected || svc == null) {
                resp.put("ok", false);
                resp.put("error", "notification_listener_not_connected");
                return resp;
            }

            String cleanKey = key.trim();
            NotificationEntry entry;
            synchronized (STORE_LOCK) {
                entry = STORE.get(cleanKey);
            }
            if (entry == null) {
                svc.syncActiveNotifications();
                synchronized (STORE_LOCK) {
                    entry = STORE.get(cleanKey);
                }
            }
            if (entry == null) {
                resp.put("ok", false);
                resp.put("error", "notification_not_found");
                resp.put("key", cleanKey);
                return resp;
            }
            if (!entry.canReply()) {
                resp.put("ok", false);
                resp.put("error", "notification_not_replyable");
                resp.put("key", cleanKey);
                return resp;
            }

            Notification.Action action = entry.replyAction;
            RemoteInput[] inputs = entry.replyInputs;
            PendingIntent pendingIntent = action != null ? action.actionIntent : null;
            if (pendingIntent == null || inputs == null || inputs.length == 0) {
                resp.put("ok", false);
                resp.put("error", "notification_not_replyable");
                resp.put("key", cleanKey);
                return resp;
            }

            Intent fillIntent = new Intent();
            Bundle results = new Bundle();
            for (RemoteInput ri : inputs) {
                if (ri != null && ri.getResultKey() != null) {
                    results.putCharSequence(ri.getResultKey(), message);
                }
            }
            RemoteInput.addResultsToIntent(inputs, fillIntent, results);
            Context sendCtx = svc != null ? svc : (context != null ? context.getApplicationContext() : null);
            pendingIntent.send(sendCtx, 0, fillIntent);

            try {
                JSONObject data = new JSONObject();
                data.put("key", cleanKey);
                data.put("package_name", entry.packageName);
                CoreEventWatcher.appendEventLine(svc.getFilesDir(),
                        "inbox.notification.replied", "android:notification_listener", data);
            } catch (Exception ignored) {
            }

            resp.put("ok", true);
            resp.put("replied", true);
            resp.put("key", cleanKey);
        } catch (PendingIntent.CanceledException ce) {
            try {
                resp.put("ok", false);
                resp.put("error", "reply_pending_intent_canceled");
                resp.put("key", key != null ? key.trim() : "");
            } catch (Exception ignored) {
            }
        } catch (Throwable t) {
            Log.w(TAG, "replyToNotification failed: " + t.getMessage());
            try {
                resp.put("ok", false);
                resp.put("error", t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            } catch (Exception ignored) {
            }
        }
        return resp;
    }

    public static JSONObject dismissNotification(String key) {
        JSONObject resp = new JSONObject();
        try {
            if (key == null || key.trim().isEmpty()) {
                resp.put("ok", false);
                resp.put("error", "missing notification key");
                return resp;
            }
            AgyNotificationListenerService svc = instance;
            if (!connected || svc == null) {
                resp.put("ok", false);
                resp.put("error", "notification_listener_not_connected");
                return resp;
            }
            String cleanKey = key.trim();
            svc.cancelNotification(cleanKey);
            synchronized (STORE_LOCK) {
                NotificationEntry existing = STORE.get(cleanKey);
                if (existing != null) {
                    existing.active = false;
                    existing.replyAction = null;
                    existing.replyInputs = null;
                }
            }
            resp.put("ok", true);
            resp.put("dismissed", true);
            resp.put("key", cleanKey);
        } catch (Throwable t) {
            Log.w(TAG, "dismissNotification failed: " + t.getMessage());
            try {
                resp.put("ok", false);
                resp.put("error", t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            } catch (Exception ignored) {
            }
        }
        return resp;
    }

    private static String charSeqToString(CharSequence cs) {
        return cs == null ? "" : cs.toString().trim();
    }

    private static String truncate(String s, int maxChars) {
        if (s == null) {
            return "";
        }
        if (s.length() <= maxChars) {
            return s;
        }
        return s.substring(0, Math.max(0, maxChars - 1)) + "…";
    }
}
