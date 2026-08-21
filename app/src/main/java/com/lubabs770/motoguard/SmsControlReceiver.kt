package com.lubabs770.motoguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsManager

/**
 * The keyholder's channel. Inbound texts are handed to ControlApi, which decides
 * whether they are a command at all; this class only reassembles and replies.
 *
 * WHY SMS AND NOT MMS: SMS_RECEIVED is broadcast to every app holding
 * RECEIVE_SMS, so the guard can listen without displacing the device's real SMS
 * app. Inbound MMS is delivered only to the *default* SMS app
 * (WAP_PUSH_DELIVER), so accepting MMS would mean taking that role over — a much
 * bigger change for no gain, since a code fits in a text.
 *
 * The manifest gates this receiver on BROADCAST_SMS, held only by the system, so
 * no other app on the device can forge a command by faking the intent.
 */
class SmsControlReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return

        val from = parts[0].displayOriginatingAddress
        // Multipart texts arrive as several PDUs in one broadcast; a code plus a
        // command can straddle the 160-character boundary.
        val body = parts.joinToString("") { it.messageBody ?: "" }

        val result = ControlApi.handle(ctx, from, body)
        // The reply goes to whoever sent this; `notify` goes to the numbers on
        // record. A challenge is always in `notify`, never in `reply` — that is
        // what stops a forged sender from receiving the digits it needs.
        if (result.reply.isNotEmpty()) send(from, result.reply)
        for ((to, text) in result.notify) send(to, text)
    }

    /**
     * Replies are printable ASCII only. This carrier cannot send UCS-2 at all —
     * a single non-GSM character silently drops the whole message — so anything
     * outside the safe range is stripped rather than risked. Longer replies go
     * out as a proper multipart message.
     */
    private fun send(to: String?, text: String) {
        if (to.isNullOrEmpty()) return
        // Printable ASCII, PLUS newline. Newline is code 10, so a naive
        // "in 32..126" filter silently flattens every multi-line message
        // into one run-on blob. LF is in the GSM-7 alphabet and this
        // carrier sends it fine.
        val safe = text.filter { it.code in 32..126 || it == '\n' }.take(600)
        try {
            @Suppress("DEPRECATION")
            val sms = SmsManager.getDefault()
            val parts = sms.divideMessage(safe)
            if (parts.size <= 1) sms.sendTextMessage(to, null, safe, null, null)
            else sms.sendMultipartTextMessage(to, null, parts, null, null)
        } catch (_: Exception) { /* no SEND_SMS grant yet, or no service */ }
    }
}
