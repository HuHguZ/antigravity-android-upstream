package com.agy.gateway.handlers;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.telecom.TelecomManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * Gateway handler exposing Android telephony status, call answer/end/speaker/dial,
 * call history, and contacts search. Every sensitive Android API is strictly guarded
 * with runtime permission checks returning structured {@code permission_denied} errors.
 */
public final class CallHandler {

    private static final String TAG = "CallHandler";
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final long RECENT_CALL_FALLBACK_WINDOW_MS = 120_000L;

    private final Context context;
    private final TelephonyManager telephonyManager;
    private final TelecomManager telecomManager;
    private final AudioManager audioManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile String trackedNumber = "";
    private volatile int trackedCallState = TelephonyManager.CALL_STATE_IDLE;
    private volatile boolean listenerRegistered = false;
    private PhoneStateListener phoneStateListener;

    public CallHandler(Context context) {
        this.context = context.getApplicationContext();
        this.telephonyManager = (TelephonyManager) this.context.getSystemService(Context.TELEPHONY_SERVICE);
        this.telecomManager = (TelecomManager) this.context.getSystemService(Context.TELECOM_SERVICE);
        this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        ensurePhoneStateListenerRegistered();
    }

    @SuppressWarnings("deprecation")
    private void ensurePhoneStateListenerRegistered() {
        if (listenerRegistered || telephonyManager == null) {
            return;
        }
        if (!hasPermission(Manifest.permission.READ_PHONE_STATE)) {
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (listenerRegistered || telephonyManager == null) {
                    return;
                }
                if (!hasPermission(Manifest.permission.READ_PHONE_STATE)) {
                    return;
                }
                try {
                    phoneStateListener = new PhoneStateListener() {
                        @Override
                        public void onCallStateChanged(int state, String phoneNumber) {
                            trackedCallState = state;
                            if (state == TelephonyManager.CALL_STATE_IDLE) {
                                trackedNumber = "";
                            } else if (phoneNumber != null && !phoneNumber.trim().isEmpty()) {
                                trackedNumber = phoneNumber.trim();
                            }
                        }
                    };
                    telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
                    listenerRegistered = true;
                } catch (Throwable t) {
                    Log.w(TAG, "PhoneStateListener registration failed: " + t.getMessage());
                }
            }
        });
    }

    @SuppressWarnings("deprecation")
    public void release() {
        if (!listenerRegistered || telephonyManager == null || phoneStateListener == null) {
            return;
        }
        final PhoneStateListener listener = phoneStateListener;
        listenerRegistered = false;
        phoneStateListener = null;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    telephonyManager.listen(listener, PhoneStateListener.LISTEN_NONE);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    @SuppressWarnings("deprecation")
    public JSONObject handleStatus() {
        JSONObject resp = new JSONObject();
        try {
            ensurePhoneStateListenerRegistered();

            boolean hasPhoneState = hasPermission(Manifest.permission.READ_PHONE_STATE);
            boolean hasAnswerCalls = Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                    || hasPermission(Manifest.permission.ANSWER_PHONE_CALLS);
            boolean hasCallLog = hasPermission(Manifest.permission.READ_CALL_LOG);
            boolean hasContacts = hasPermission(Manifest.permission.READ_CONTACTS);
            boolean hasCallPhone = hasPermission(Manifest.permission.CALL_PHONE);

            boolean speakerphone = false;
            if (audioManager != null) {
                try {
                    speakerphone = audioManager.isSpeakerphoneOn();
                } catch (Throwable ignored) {
                }
            }

            String stateStr = "unknown";
            String missingPerm = null;
            int rawState = TelephonyManager.CALL_STATE_IDLE;
            boolean stateResolved = false;

            if (telephonyManager != null) {
                if (Build.VERSION.SDK_INT >= 31 && !hasPhoneState) {
                    missingPerm = Manifest.permission.READ_PHONE_STATE;
                } else {
                    try {
                        rawState = telephonyManager.getCallState();
                        stateStr = mapCallState(rawState);
                        stateResolved = true;
                    } catch (SecurityException se) {
                        missingPerm = Manifest.permission.READ_PHONE_STATE;
                    } catch (Throwable t) {
                        Log.w(TAG, "getCallState failed: " + t.getMessage());
                    }
                }
            } else {
                stateStr = "idle";
                stateResolved = true;
            }

            String incomingNumber = "";
            String contactName = "";
            if (stateResolved && rawState != TelephonyManager.CALL_STATE_IDLE) {
                incomingNumber = trackedNumber != null ? trackedNumber : "";
                if (incomingNumber.isEmpty() && hasCallLog) {
                    String[] recent = queryLatestCallLogEntry();
                    if (recent != null) {
                        incomingNumber = recent[0];
                        contactName = recent[1];
                    }
                }
                if (contactName.isEmpty() && !incomingNumber.isEmpty() && hasContacts) {
                    contactName = lookupContactName(incomingNumber);
                }
            } else if (stateResolved) {
                trackedNumber = "";
            }

            JSONObject perms = new JSONObject();
            perms.put("read_phone_state", hasPhoneState);
            perms.put("answer_phone_calls", hasAnswerCalls);
            perms.put("read_call_log", hasCallLog);
            perms.put("read_contacts", hasContacts);
            perms.put("call_phone", hasCallPhone);

            resp.put("ok", true);
            resp.put("state", stateStr);
            resp.put("speakerphone", speakerphone);
            resp.put("incoming_number", incomingNumber);
            resp.put("contact_name", contactName);
            resp.put("permissions", perms);
            if (missingPerm != null) {
                resp.put("missing_permission", missingPerm);
            }
        } catch (Exception e) {
            try {
                resp.put("ok", false);
                resp.put("error", e.getMessage());
            } catch (Exception ignored) {
            }
        }
        return resp;
    }

    @SuppressWarnings("deprecation")
    public JSONObject handleAnswer() {
        JSONObject resp = new JSONObject();
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                resp.put("ok", false);
                resp.put("error", "api_level_unsupported");
                return resp;
            }
            if (!hasPermission(Manifest.permission.ANSWER_PHONE_CALLS)) {
                return permissionDenied(Manifest.permission.ANSWER_PHONE_CALLS);
            }
            if (telecomManager == null) {
                resp.put("ok", false);
                resp.put("error", "telecom_manager_unavailable");
                return resp;
            }
            telecomManager.acceptRingingCall();
            resp.put("ok", true);
            resp.put("answered", true);
        } catch (SecurityException se) {
            return permissionDenied(Manifest.permission.ANSWER_PHONE_CALLS);
        } catch (Throwable t) {
            Log.w(TAG, "acceptRingingCall failed: " + t.getMessage());
            try {
                resp.put("ok", false);
                resp.put("error", t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            } catch (Exception ignored) {
            }
        }
        return resp;
    }

    @SuppressWarnings("deprecation")
    public JSONObject handleEnd() {
        JSONObject resp = new JSONObject();
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                resp.put("ok", false);
                resp.put("error", "api_level_unsupported");
                return resp;
            }
            if (!hasPermission(Manifest.permission.ANSWER_PHONE_CALLS)) {
                return permissionDenied(Manifest.permission.ANSWER_PHONE_CALLS);
            }
            if (telecomManager == null) {
                resp.put("ok", false);
                resp.put("error", "telecom_manager_unavailable");
                return resp;
            }
            boolean ended = telecomManager.endCall();
            resp.put("ok", true);
            resp.put("ended", ended);
        } catch (SecurityException se) {
            return permissionDenied(Manifest.permission.ANSWER_PHONE_CALLS);
        } catch (Throwable t) {
            Log.w(TAG, "endCall failed: " + t.getMessage());
            try {
                resp.put("ok", false);
                resp.put("error", t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            } catch (Exception ignored) {
            }
        }
        return resp;
    }

    @SuppressWarnings("deprecation")
    public JSONObject handleSpeaker(JSONObject payload) {
        JSONObject resp = new JSONObject();
        try {
            if (audioManager == null) {
                resp.put("ok", false);
                resp.put("error", "audio_manager_unavailable");
                return resp;
            }
            boolean enabled = payload == null || payload.optBoolean("enabled", true);
            audioManager.setSpeakerphoneOn(enabled);
            resp.put("ok", true);
            resp.put("speakerphone", audioManager.isSpeakerphoneOn());
        } catch (Throwable t) {
            Log.w(TAG, "setSpeakerphoneOn failed: " + t.getMessage());
            try {
                resp.put("ok", false);
                resp.put("error", t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            } catch (Exception ignored) {
            }
        }
        return resp;
    }

    public JSONObject handleHistory(JSONObject payload) {
        if (!hasPermission(Manifest.permission.READ_CALL_LOG)) {
            return permissionDenied(Manifest.permission.READ_CALL_LOG);
        }
        JSONObject resp = new JSONObject();
        int limit = payload != null ? payload.optInt("limit", DEFAULT_LIMIT) : DEFAULT_LIMIT;
        limit = Math.max(1, Math.min(limit, MAX_LIMIT));
        boolean missedOnly = payload != null && payload.optBoolean("missed_only", false);

        JSONArray calls = new JSONArray();
        String[] projection = new String[]{
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
        };
        String selection = missedOnly ? (CallLog.Calls.TYPE + " = ?") : null;
        String[] selectionArgs = missedOnly
                ? new String[]{String.valueOf(CallLog.Calls.MISSED_TYPE)}
                : null;
        String sortOrder = CallLog.Calls.DATE + " DESC";

        Cursor cursor = null;
        try {
            ContentResolver cr = context.getContentResolver();
            cursor = cr.query(CallLog.Calls.CONTENT_URI, projection, selection, selectionArgs, sortOrder);
            if (cursor != null) {
                int idxNumber = cursor.getColumnIndex(CallLog.Calls.NUMBER);
                int idxName = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME);
                int idxType = cursor.getColumnIndex(CallLog.Calls.TYPE);
                int idxDate = cursor.getColumnIndex(CallLog.Calls.DATE);
                int idxDuration = cursor.getColumnIndex(CallLog.Calls.DURATION);
                while (cursor.moveToNext() && calls.length() < limit) {
                    JSONObject row = new JSONObject();
                    String number = idxNumber >= 0 ? safeStr(cursor.getString(idxNumber)) : "";
                    String name = idxName >= 0 ? safeStr(cursor.getString(idxName)) : "";
                    int type = idxType >= 0 ? cursor.getInt(idxType) : 0;
                    long dateMs = idxDate >= 0 ? cursor.getLong(idxDate) : 0L;
                    long durationSec = idxDuration >= 0 ? cursor.getLong(idxDuration) : 0L;
                    row.put("number", number);
                    row.put("name", name);
                    row.put("type", mapCallLogType(type));
                    row.put("date_ms", dateMs);
                    row.put("duration_sec", durationSec);
                    calls.put(row);
                }
            }
            resp.put("ok", true);
            resp.put("count", calls.length());
            resp.put("calls", calls);
        } catch (SecurityException se) {
            return permissionDenied(Manifest.permission.READ_CALL_LOG);
        } catch (Throwable t) {
            Log.w(TAG, "call_history query failed: " + t.getMessage());
            try {
                resp.put("ok", false);
                resp.put("error", t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            } catch (Exception ignored) {
            }
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return resp;
    }

    public JSONObject handleContactsSearch(JSONObject payload) {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
            return permissionDenied(Manifest.permission.READ_CONTACTS);
        }
        JSONObject resp = new JSONObject();
        String query = payload != null ? payload.optString("query", "").trim() : "";
        int limit = payload != null ? payload.optInt("limit", DEFAULT_LIMIT) : DEFAULT_LIMIT;
        limit = Math.max(1, Math.min(limit, MAX_LIMIT));

        JSONArray contacts = new JSONArray();
        String[] projection = new String[]{
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.LABEL
        };
        String selection = null;
        String[] selectionArgs = null;
        if (!query.isEmpty()) {
            selection = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ? OR "
                    + ContactsContract.CommonDataKinds.Phone.NUMBER + " LIKE ?";
            String pattern = "%" + query + "%";
            selectionArgs = new String[]{pattern, pattern};
        }
        String sortOrder = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC";

        Set<String> seen = new HashSet<>();
        Cursor cursor = null;
        try {
            ContentResolver cr = context.getContentResolver();
            cursor = cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection, selection, selectionArgs, sortOrder);
            if (cursor != null) {
                int idxName = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
                int idxNumber = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
                int idxType = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE);
                int idxLabel = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LABEL);
                while (cursor.moveToNext() && contacts.length() < limit) {
                    String name = idxName >= 0 ? safeStr(cursor.getString(idxName)) : "";
                    String number = idxNumber >= 0 ? safeStr(cursor.getString(idxNumber)) : "";
                    if (number.isEmpty()) {
                        continue;
                    }
                    String dedupeKey = name + "|" + normalizeDigits(number);
                    if (!seen.add(dedupeKey)) {
                        continue;
                    }
                    int type = idxType >= 0 ? cursor.getInt(idxType) : ContactsContract.CommonDataKinds.Phone.TYPE_OTHER;
                    String customLabel = idxLabel >= 0 ? cursor.getString(idxLabel) : null;
                    CharSequence typeLabel = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                            context.getResources(), type, customLabel);
                    JSONObject row = new JSONObject();
                    row.put("name", name);
                    row.put("number", number);
                    row.put("label", typeLabel != null ? typeLabel.toString() : "");
                    contacts.put(row);
                }
            }
            resp.put("ok", true);
            resp.put("count", contacts.length());
            resp.put("contacts", contacts);
        } catch (SecurityException se) {
            return permissionDenied(Manifest.permission.READ_CONTACTS);
        } catch (Throwable t) {
            Log.w(TAG, "contacts_search failed: " + t.getMessage());
            try {
                resp.put("ok", false);
                resp.put("error", t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            } catch (Exception ignored) {
            }
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return resp;
    }

    public JSONObject handleDial(JSONObject payload) {
        JSONObject resp = new JSONObject();
        try {
            String rawNumber = payload != null ? payload.optString("number", "").trim() : "";
            if (rawNumber.isEmpty()) {
                resp.put("ok", false);
                resp.put("error", "missing phone number");
                return resp;
            }
            boolean direct = payload != null && payload.optBoolean("direct", false);
            if (direct && !hasPermission(Manifest.permission.CALL_PHONE)) {
                return permissionDenied(Manifest.permission.CALL_PHONE);
            }

            Uri telUri = Uri.fromParts("tel", rawNumber, null);
            Intent intent = new Intent(direct ? Intent.ACTION_CALL : Intent.ACTION_DIAL, telUri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);

            resp.put("ok", true);
            resp.put("dialed", true);
            resp.put("direct", direct);
            resp.put("number", rawNumber);
        } catch (SecurityException se) {
            return permissionDenied(Manifest.permission.CALL_PHONE);
        } catch (Throwable t) {
            Log.w(TAG, "call_dial failed: " + t.getMessage());
            try {
                resp.put("ok", false);
                resp.put("error", t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            } catch (Exception ignored) {
            }
        }
        return resp;
    }

    private String[] queryLatestCallLogEntry() {
        Cursor cursor = null;
        try {
            String[] projection = new String[]{
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.DATE
            };
            cursor = context.getContentResolver().query(
                    CallLog.Calls.CONTENT_URI, projection, null, null, CallLog.Calls.DATE + " DESC");
            if (cursor != null && cursor.moveToFirst()) {
                long dateMs = cursor.getLong(2);
                if (Math.abs(System.currentTimeMillis() - dateMs) <= RECENT_CALL_FALLBACK_WINDOW_MS) {
                    return new String[]{
                            safeStr(cursor.getString(0)),
                            safeStr(cursor.getString(1))
                    };
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private String lookupContactName(String phoneNumber) {
        Cursor cursor = null;
        try {
            Uri lookupUri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber));
            cursor = context.getContentResolver().query(
                    lookupUri,
                    new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME},
                    null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                return safeStr(cursor.getString(0));
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return "";
    }

    private boolean hasPermission(String permission) {
        try {
            return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private static JSONObject permissionDenied(String missingPermission) {
        JSONObject resp = new JSONObject();
        try {
            resp.put("ok", false);
            resp.put("error", "permission_denied");
            resp.put("missing_permission", missingPermission);
        } catch (Exception ignored) {
        }
        return resp;
    }

    private static String mapCallState(int state) {
        switch (state) {
            case TelephonyManager.CALL_STATE_RINGING:
                return "ringing";
            case TelephonyManager.CALL_STATE_OFFHOOK:
                return "offhook";
            case TelephonyManager.CALL_STATE_IDLE:
            default:
                return "idle";
        }
    }

    private static String mapCallLogType(int type) {
        switch (type) {
            case CallLog.Calls.INCOMING_TYPE:
                return "incoming";
            case CallLog.Calls.OUTGOING_TYPE:
                return "outgoing";
            case CallLog.Calls.MISSED_TYPE:
                return "missed";
            case CallLog.Calls.VOICEMAIL_TYPE:
                return "voicemail";
            case CallLog.Calls.REJECTED_TYPE:
                return "rejected";
            case CallLog.Calls.BLOCKED_TYPE:
                return "blocked";
            default:
                return "unknown";
        }
    }

    private static String normalizeDigits(String raw) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c >= '0' && c <= '9') || c == '+') {
                sb.append(c);
            }
        }
        return sb.length() > 0 ? sb.toString() : raw;
    }

    private static String safeStr(String s) {
        return s == null ? "" : s.trim();
    }
}
