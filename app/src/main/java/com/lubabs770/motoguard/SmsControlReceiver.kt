package com.lubabs770.motoguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsManager

/**
 * The SMS transport for ControlApi — the remote escape hatch for when adb isn't
 * reachable and nobody trusted is at the glass.
 *
 * Someone on the allow-list texts the device:
 *
 *     MG <secret> open
 *     MG <secret> pin 4821
 *     MG <secret> status
 *
 * ...and gets a one-line SMS back. Anything else on this phone's inbox is
 * ignored without a trace.
 *
 * WHY SMS_RECEIVED AND NOT MMS: SMS_RECEIVED is broadcast to every app holding
 * RECEIVE_SMS, so the guard can listen without disturbing the device's real SMS
 * app. Inbound MMS goes only to the *default* SMS app (WAP_PUSH_DELIVER), so
 * accepting MMS would mean displacing Google Messages on the relay — a much
 * bigger change for zero gain, since a PIN fits in a text.
 *
 * The manifest gates this receiver on BROADCAST_SMS, which only the system
 * holds, so no other app on the device can forge a command by faking the intent.
 */
class SmsControlReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return

        val from = parts[0].displayOriginatingAddress
        if (!ControlApi.senderAllowed(from)) return

        // Multipart texts arrive as several PDUs in one broadcast; a long secret
        // plus a command can straddle the 160-char boundary.
        val body = parts.joinToString("") { it.messageBody ?: "" }

        val result = ControlApi.handle(ctx, body)
        if (result.reply.isNotEmpty()) reply(from, result.reply)
    }

    /**
     * Replies are GSM-7 only and single-segment. This carrier cannot send UCS-2
     * at all — one non-ASCII character silently drops the whole message — so
     * anything outside printable ASCII is stripped rather than risked.
     */
    private fun reply(to: String?, text: String) {
        if (to.isNullOrEmpty()) return
        val safe = text.filter { it.code in 32..126 }.take(150)
        try {
            @Suppress("DEPRECATION")
            SmsManager.getDefault().sendTextMessage(to, null, safe, null, null)
        } catch (_: Exception) { /* no SEND_SMS grant yet, or no service */ }
    }
}
