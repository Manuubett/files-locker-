package com.manuubett.lindaplay

/*
    PAYGATE for Linda Play

    Talks to the payment server and shows the M-PESA payment window.
    Used only when a parent starts Child Mode, so the Kids Zone and the
    parent exit can never be blocked by it.

        gate.check(onDenied = { ... }) {
            // The parent is allowed in (trial or paid).
        }
*/

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.concurrent.thread

class PayGate(
    private val activity: Activity,
    private val appId: String,
    private val api: String = "https://paytrack-app.onrender.com",
    private val graceDays: Int = 3
) {

    data class Status(
        val active: Boolean,
        val plan: String,          // "paid", "trial" or "none"
        val expiresAt: Long,
        val price: Int,
        val periodDays: Int,
        val trialUsed: Boolean
    )

    @Volatile
    var lastStatus: Status? = null
        private set

    private class HttpError(val code: Int, message: String) : Exception(message)

    private val prefs = activity.getSharedPreferences("paygate_$appId", Context.MODE_PRIVATE)

    // Android's per-phone ID. It survives uninstalling and reinstalling the app.
    private val deviceId: String by lazy {
        val id = Settings.Secure.getString(activity.contentResolver, Settings.Secure.ANDROID_ID)
        if (!id.isNullOrBlank() && id.length >= 8) id
        else prefs.getString("device", null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString("device", it).apply() }
    }

    private var dialog: AlertDialog? = null
    private var infoView: TextView? = null
    private var statusView: TextView? = null
    private var phoneField: EditText? = null
    private var payButton: Button? = null
    private var checkButton: Button? = null

    @Volatile
    private var busy = false

    // Called when the parent taps Cancel on the payment window
    @Volatile
    private var deniedCallback: () -> Unit = {}

    private val blue = Color.rgb(30, 64, 120)


    // ---------------------------------------------------------------- public

    /**
     * Runs [onAllowed] if the parent may continue, otherwise shows the
     * payment window. [onDenied] runs if the parent cancels the window.
     */
    fun check(onDenied: (() -> Unit)? = null, onAllowed: () -> Unit) {
        if (onDenied != null) deniedCallback = onDenied

        thread {
            try {
                val j = request("/api/status/$deviceId?app=$appId")
                val s = Status(
                    active = j.getBoolean("active"),
                    plan = j.optString("plan", "none"),
                    expiresAt = j.optLong("expiresAt", 0L),
                    price = j.optInt("price", 0),
                    periodDays = j.optInt("periodDays", 30),
                    trialUsed = j.optBoolean("trialUsed", false)
                )
                lastStatus = s
                prefs.edit().putLong("exp", s.expiresAt).apply()

                ui {
                    if (s.active) {
                        dialog?.dismiss()
                        onAllowed()
                    } else {
                        showWindow(
                            if (s.trialUsed) "Your free trial has ended. Subscribe to keep using Linda Play."
                            else "Pay with M-PESA to keep using Linda Play.",
                            s, onAllowed
                        )
                    }
                }

            } catch (e: HttpError) {
                // Unknown or switched-off app: do not let anyone in on the offline grace
                ui { showWindow("This app is not available right now (${e.message}).", null, onAllowed, canPay = false) }

            } catch (e: Exception) {
                // Offline or the server is waking up: let recent subscribers in for a few days
                val cached = prefs.getLong("exp", 0L)
                if (cached > 0 && System.currentTimeMillis() < cached + graceDays * DAY_MS) {
                    ui { dialog?.dismiss(); onAllowed() }
                } else {
                    ui { showWindow("Can't reach the server. Connect to the internet to continue.", lastStatus, onAllowed) }
                }
            }
        }
    }


    // ---------------------------------------------------------------- window

    private fun showWindow(message: String, s: Status?, onAllowed: () -> Unit, canPay: Boolean = true) {
        if (activity.isFinishing || activity.isDestroyed) return

        if (dialog?.isShowing != true) buildWindow(onAllowed)

        infoView?.text = (if (s != null && s.price > 0) "KSh ${s.price} for ${s.periodDays} days\n\n" else "") + message
        payButton?.text = if (s != null && s.price > 0) "Pay KSh ${s.price}" else "Pay with M-PESA"
        payButton?.isEnabled = canPay && !busy
        phoneField?.visibility = if (canPay) View.VISIBLE else View.GONE
    }

    private fun buildWindow(onAllowed: () -> Unit) {
        val pad = (20 * activity.resources.displayMetrics.density).toInt()

        val info = TextView(activity).apply {
            textSize = 15f
            setTextColor(Color.rgb(35, 45, 62))
        }
        val phone = EditText(activity).apply {
            hint = "M-PESA number, e.g. 0712345678 or 0112345678"
            inputType = InputType.TYPE_CLASS_PHONE
        }
        val status = TextView(activity).apply {
            textSize = 13f
            setTextColor(Color.rgb(100, 112, 132))
            setPadding(0, pad / 3, 0, 0)
        }

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(info)
            addView(phone)
            addView(status)
        }

        val d = AlertDialog.Builder(
            activity, android.R.style.Theme_Material_Light_Dialog_Alert
        )
            .setTitle("Subscription needed")
            .setView(layout)
            .setCancelable(false)
            .setPositiveButton("Pay", null)             // listeners are set below so the window stays open
            .setNeutralButton("I already paid", null)
            .setNegativeButton("Cancel") { _, _ -> deniedCallback() }
            .create()
        d.show()

        d.window?.setBackgroundDrawable(GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = 24 * activity.resources.displayMetrics.density
        })

        infoView = info
        phoneField = phone
        statusView = status
        payButton = d.getButton(AlertDialog.BUTTON_POSITIVE)
        checkButton = d.getButton(AlertDialog.BUTTON_NEUTRAL)

        payButton?.setTextColor(blue)
        checkButton?.setTextColor(blue)
        d.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.rgb(100, 112, 132))

        payButton?.setOnClickListener { pay(phone.text.toString(), onAllowed) }
        checkButton?.setOnClickListener {
            say("Checking...")
            check(onAllowed = onAllowed)
        }
        dialog = d
    }

    private fun say(text: String) = ui { statusView?.text = text }

    private fun setBusy(value: Boolean) {
        busy = value
        ui {
            payButton?.isEnabled = !value
            checkButton?.isEnabled = !value
        }
    }


    // ---------------------------------------------------------------- paying

    private fun pay(phoneRaw: String, onAllowed: () -> Unit) {
        if (busy) return

        val phone = phoneRaw.replace(Regex("\\s"), "")
        if (!Regex("^(?:\\+?254|0)?[71]\\d{8}$").matches(phone)) {
            say("Enter a valid M-PESA number, e.g. 0712345678 or 0112345678.")
            return
        }

        setBusy(true)
        say("Sending M-PESA prompt to your phone...")

        thread {
            try {
                val start = request(
                    "/api/pay",
                    JSONObject().put("deviceId", deviceId).put("phone", phone).put("appId", appId)
                )
                val checkoutId = start.getString("checkoutId")
                say("Enter your M-PESA PIN on your phone...")

                var result = "timeout"
                var reason = ""
                var i = 0
                while (i < 45 && result == "timeout") {
                    Thread.sleep(2000)
                    val s = request("/api/pay/$checkoutId")
                    val st = s.optString("status")
                    if (st == "paid") {
                        result = "paid"
                    } else if (st == "failed") {
                        result = "failed"
                        reason = s.optString("reason", "Payment was not completed")
                    }
                    i++
                }

                when (result) {
                    "paid" -> {
                        say("Payment received. Thank you!")
                        setBusy(false)
                        check(onAllowed = onAllowed)    // unlocks and closes the window
                    }
                    "failed" -> say(reason)
                    else -> say("Timed out. If money was deducted, tap \"I already paid\".")
                }

            } catch (e: Exception) {
                say(e.message ?: "Payment failed.")
            } finally {
                setBusy(false)
            }
        }
    }


    // ---------------------------------------------------------------- network

    private fun request(path: String, body: JSONObject? = null): JSONObject {
        val conn = URL(api.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20000
            conn.readTimeout = 70000                  // the server may be waking up
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }

            val code = conn.responseCode
            val stream = if (code < 400) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
            val json = try { JSONObject(text) } catch (e: Exception) { JSONObject() }

            if (code >= 400) throw HttpError(code, json.optString("error", "Request failed"))
            return json
        } finally {
            conn.disconnect()
        }
    }

    private fun ui(block: () -> Unit) {
        if (!activity.isFinishing && !activity.isDestroyed) activity.runOnUiThread(block)
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
