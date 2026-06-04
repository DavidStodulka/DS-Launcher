package com.caros.communication

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────────
//  Domain types
// ─────────────────────────────────────────────────────────────────────────────

/** Current state of the telephony call stack from CarOS's perspective. */
enum class CallState {
    /** No call in progress. */
    IDLE,

    /** An incoming call is ringing. */
    RINGING,

    /** A call is connected and audio is active. */
    ACTIVE,

    /** An active call has been placed on hold. */
    ON_HOLD
}

/**
 * Snapshot of an in-progress or most-recently-ended call.
 *
 * @param number         The remote phone number (may be empty for private numbers).
 * @param contactName    Resolved contact display name, or [number] if not found.
 * @param contactPhotoUri Content URI string for the contact's photo, or null.
 * @param state          Current [CallState].
 */
data class CallInfo(
    val number: String = "",
    val contactName: String = "",
    val contactPhotoUri: String? = null,
    val state: CallState = CallState.IDLE
)

// ─────────────────────────────────────────────────────────────────────────────
//  CallManager
// ─────────────────────────────────────────────────────────────────────────────

/**
 * CallManager
 *
 * Translates [TelephonyManager] call-state callbacks into a [StateFlow] of
 * [CallInfo] objects and exposes simple control actions (accept, reject).
 *
 * **Wiring:** Register [onCallStateChanged] in a [android.telephony.PhoneStateListener]
 * or a [android.content.BroadcastReceiver] for
 * `android.intent.action.PHONE_STATE`.
 *
 * **Permissions required:**
 *  - `READ_PHONE_STATE` — to receive call-state broadcasts
 *  - `READ_CONTACTS` — for contact display-name lookup
 *  - `ANSWER_PHONE_CALLS` (API 26+) — to answer calls via [acceptCall]
 */
@Singleton
class CallManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // -------------------------------------------------------------------------
    //  Observable state
    // -------------------------------------------------------------------------

    private val _callInfo = MutableStateFlow(CallInfo())

    /** Live [CallInfo] updated whenever the call state changes. */
    val callInfo: StateFlow<CallInfo> = _callInfo.asStateFlow()

    // -------------------------------------------------------------------------
    //  State updates (called from PhoneStateListener / BroadcastReceiver)
    // -------------------------------------------------------------------------

    /**
     * Handle a telephony state change.
     *
     * @param state  One of [TelephonyManager.CALL_STATE_RINGING],
     *               [TelephonyManager.CALL_STATE_OFFHOOK],
     *               [TelephonyManager.CALL_STATE_IDLE].
     * @param number Incoming phone number; may be null or empty for hidden/private.
     */
    fun onCallStateChanged(state: Int, number: String?) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                val num = number ?: ""
                val (name, photoUri) = resolveContact(num)
                Timber.d("CallManager: RINGING from %s (%s)", num, name)
                _callInfo.value = CallInfo(
                    number = num,
                    contactName = name,
                    contactPhotoUri = photoUri,
                    state = CallState.RINGING
                )
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                Timber.d("CallManager: OFFHOOK (call active)")
                _callInfo.value = _callInfo.value.copy(state = CallState.ACTIVE)
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                Timber.d("CallManager: IDLE (call ended)")
                _callInfo.value = CallInfo()
            }
        }
    }

    /** Manually mark an active call as on-hold (for UI purposes). */
    fun onCallHeld() {
        _callInfo.value = _callInfo.value.copy(state = CallState.ON_HOLD)
    }

    // -------------------------------------------------------------------------
    //  Call control
    // -------------------------------------------------------------------------

    /**
     * Accept an incoming ringing call.
     *
     * On Android 8+ (API 26+) uses [TelecomManager.acceptRingingCall] which
     * requires the `ANSWER_PHONE_CALLS` permission.
     */
    fun acceptCall() {
        try {
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            @Suppress("DEPRECATION")
            tm.acceptRingingCall()
            Timber.d("CallManager: acceptCall via TelecomManager")
        } catch (e: SecurityException) {
            Timber.w(e, "ANSWER_PHONE_CALLS permission required to accept call")
        } catch (e: Exception) {
            Timber.w(e, "acceptCall failed")
        }
    }

    /**
     * Reject the current ringing call or end an active call.
     *
     * Uses [TelecomManager.endCall] (API 28+). Requires `ANSWER_PHONE_CALLS`.
     */
    fun rejectCall() {
        try {
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            @Suppress("DEPRECATION")
            tm.endCall()
            Timber.d("CallManager: rejectCall via TelecomManager")
        } catch (e: SecurityException) {
            Timber.w(e, "ANSWER_PHONE_CALLS permission required to reject call")
        } catch (e: Exception) {
            Timber.w(e, "rejectCall failed")
        }
    }

    // -------------------------------------------------------------------------
    //  Contact lookup
    // -------------------------------------------------------------------------

    /**
     * Attempt to resolve [number] against the system contacts database.
     *
     * @param number E.164 or local phone number string.
     * @return Pair of (displayName, photoUri string or null).
     *         displayName falls back to [number] if no match is found.
     */
    private fun resolveContact(number: String): Pair<String, String?> {
        if (number.isEmpty()) return Pair("", null)
        return try {
            val lookupUri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number)
            )
            context.contentResolver.query(
                lookupUri,
                arrayOf(
                    ContactsContract.PhoneLookup.DISPLAY_NAME,
                    ContactsContract.PhoneLookup.PHOTO_URI
                ),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0) ?: number
                    val photo = cursor.getString(1)
                    Pair(name, photo)
                } else {
                    Pair(number, null)
                }
            } ?: Pair(number, null)
        } catch (e: SecurityException) {
            Timber.w(e, "READ_CONTACTS permission required for contact lookup")
            Pair(number, null)
        } catch (e: Exception) {
            Timber.w(e, "Contact lookup failed for %s", number)
            Pair(number, null)
        }
    }
}
