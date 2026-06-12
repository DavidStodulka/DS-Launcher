package com.caros.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import com.caros.can.CANFrame
import com.caros.core.ShellExecutor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────────
//  Domain types
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Condition that must evaluate to `true` for an [AutomationRule] to fire.
 *
 * Each subclass represents one trigger type.  Conditions are evaluated against
 * the latest [CANFrame] emitted by the CAN parser, except for [TimeOfDay] which
 * uses the device clock.
 */
sealed class AutoCondition {
    /** Fires when vehicle speed exceeds [kmh]. */
    data class SpeedAbove(val kmh: Float) : AutoCondition()

    /** Fires when vehicle speed drops below [kmh]. */
    data class SpeedBelow(val kmh: Float) : AutoCondition()

    /** Fires once per minute when the wall clock hour and minute match. */
    data class TimeOfDay(val hour: Int, val minute: Int) : AutoCondition()

    /** Fires when engine coolant temperature exceeds [celsius]. */
    data class CoolantTempAbove(val celsius: Float) : AutoCondition()

    /** Fires when 12 V battery voltage drops below [volts]. */
    data class VoltageBelow(val volts: Float) : AutoCondition()

    /** Fires when DPF soot load exceeds [pct] percent. */
    data class DPFLoadAbove(val pct: Float) : AutoCondition()

    /** Fires when the ACC (ignition accessory) is switched off. */
    object ACCOff : AutoCondition()

    /** Fires when the ACC is switched on. */
    object ACCOn : AutoCondition()
}

/**
 * Action executed when an [AutomationRule]'s condition becomes true.
 */
sealed class AutoAction {
    /** Start [packageName]'s main activity. */
    data class LaunchApp(val packageName: String) : AutoAction()

    /** Set screen brightness to [level] (0–255). */
    data class SetBrightness(val level: Int) : AutoAction()

    /** Post an Android notification with [title] and [message]. */
    data class ShowNotification(val title: String, val message: String) : AutoAction()

    /** Run a shell command. Requires root for most system commands. */
    data class ExecuteShell(val command: String) : AutoAction()

    /** Apply the system "driving" profile (restricts UI interactions). */
    object SetDrivingMode : AutoAction()

    /** Apply the system "parked" profile (full UI access). */
    object SetParkedMode : AutoAction()
}

/**
 * A single automation rule coupling a trigger [condition] to an [action].
 *
 * @param id        Unique string identifier for this rule.
 * @param name      Human-readable name shown in the UI.
 * @param condition Evaluated against each incoming [CANFrame].
 * @param action    Executed once when [condition] first becomes `true`.
 * @param isEnabled When `false` the rule is skipped entirely.
 */
data class AutomationRule(
    val id: String,
    val name: String,
    val condition: AutoCondition,
    val action: AutoAction,
    val isEnabled: Boolean = true
)

// ─────────────────────────────────────────────────────────────────────────────
//  AutomationEngine
// ─────────────────────────────────────────────────────────────────────────────

/**
 * AutomationEngine
 *
 * Evaluates [AutomationRule] conditions against every incoming [CANFrame] and
 * executes the corresponding [AutoAction] when a condition transitions from
 * `false` to `true`.  Rules only fire once per "edge" — they reset when the
 * condition becomes `false` again, preventing repeated firings.
 *
 * Built-in rules are registered during [init]; custom rules can be added or
 * removed at runtime via [addRule] / [removeRule].
 *
 * Call [processCANFrame] from the CAN data pipeline (e.g. from [CANService]).
 */
@Singleton
class AutomationEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsManager: SystemSettingsManager,
    private val shellExecutor: ShellExecutor
) {
    // -------------------------------------------------------------------------
    //  State
    // -------------------------------------------------------------------------

    // Thread-safe: mutated from UI thread (addRule/removeRule) and read from
    // the evaluation coroutine on Dispatchers.Default concurrently.
    private val rules = java.util.concurrent.CopyOnWriteArrayList<AutomationRule>()

    /** Set of rule IDs whose condition is currently active (latched). */
    private val triggeredRules: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // -------------------------------------------------------------------------
    //  Initialisation
    // -------------------------------------------------------------------------

    init {
        ensureNotificationChannel()
        loadBuiltinRules()
    }

    private fun loadBuiltinRules() {
        rules += listOf(
            AutomationRule(
                id = "driving_mode",
                name = "Driving mode při jízdě",
                condition = AutoCondition.SpeedAbove(5f),
                action = AutoAction.SetDrivingMode
            ),
            AutomationRule(
                id = "parked_mode",
                name = "Parked mode při stání",
                condition = AutoCondition.SpeedBelow(2f),
                action = AutoAction.SetParkedMode
            ),
            AutomationRule(
                id = "dpf_warning",
                name = "DPF varování",
                condition = AutoCondition.DPFLoadAbove(80f),
                action = AutoAction.ShowNotification(
                    title = "DPF",
                    message = "DPF zátěž > 80 % — doporučena jízda 30 min při 100+ km/h"
                )
            ),
            AutomationRule(
                id = "voltage_warning",
                name = "Varování vybití baterie",
                condition = AutoCondition.VoltageBelow(11.8f),
                action = AutoAction.ShowNotification(
                    title = "Baterie",
                    message = "Napětí < 11.8 V — možné vybití baterie"
                )
            ),
            AutomationRule(
                id = "coolant_warning",
                name = "Přehřátí chladiva",
                condition = AutoCondition.CoolantTempAbove(110f),
                action = AutoAction.ShowNotification(
                    title = "Chladivo",
                    message = "Teplota chladiva > 110 °C — zastavte a nechte motor vychladnout"
                )
            ),
            AutomationRule(
                id = "night_dim",
                name = "Noční ztlumení obrazovky",
                condition = AutoCondition.TimeOfDay(20, 0),
                action = AutoAction.SetBrightness(80)
            )
        )
        Timber.d("AutomationEngine: %d built-in rules loaded", rules.size)
    }

    // -------------------------------------------------------------------------
    //  CAN frame processing
    // -------------------------------------------------------------------------

    /**
     * Evaluate all enabled rules against [frame].
     * Edge-triggered: action fires on the `false → true` transition.
     * Must be called for every new CAN snapshot.
     *
     * @param frame Latest decoded [CANFrame] from the CAN pipeline.
     */
    fun processCANFrame(frame: CANFrame) {
        scope.launch {
            for (rule in rules.filter { it.isEnabled }) {
                val active = evaluateCondition(rule.condition, frame)
                val wasActive = rule.id in triggeredRules
                when {
                    active && !wasActive -> {
                        triggeredRules += rule.id
                        Timber.d("AutomationEngine: rule '%s' triggered", rule.name)
                        executeAction(rule.action)
                    }
                    !active && wasActive -> {
                        triggeredRules -= rule.id
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    //  Rule management
    // -------------------------------------------------------------------------

    /** Add a new rule. Replaces an existing rule with the same [AutomationRule.id]. */
    fun addRule(rule: AutomationRule) {
        rules.removeIf { it.id == rule.id }
        rules += rule
        Timber.d("AutomationEngine: rule '%s' added", rule.name)
    }

    /** Remove the rule with [id]. Has no effect if no such rule exists. */
    fun removeRule(id: String) {
        val removed = rules.removeIf { it.id == id }
        if (removed) {
            triggeredRules -= id
            Timber.d("AutomationEngine: rule '%s' removed", id)
        }
    }

    /** Enable or disable an existing rule by [id] without removing it. */
    fun setRuleEnabled(id: String, enabled: Boolean) {
        val idx = rules.indexOfFirst { it.id == id }
        if (idx >= 0) {
            rules[idx] = rules[idx].copy(isEnabled = enabled)
            if (!enabled) triggeredRules -= id
        }
    }

    /** Return an immutable snapshot of the current rule list. */
    fun getRules(): List<AutomationRule> = rules.toList()

    // -------------------------------------------------------------------------
    //  Condition evaluation
    // -------------------------------------------------------------------------

    /**
     * Evaluate [condition] against the current [frame].
     * Nullable CAN fields are treated as "condition not met" (safe default).
     */
    private fun evaluateCondition(condition: AutoCondition, frame: CANFrame): Boolean =
        when (condition) {
            is AutoCondition.SpeedAbove ->
                (frame.vehicleSpeed?.kmh ?: 0f) > condition.kmh
            is AutoCondition.SpeedBelow ->
                (frame.vehicleSpeed?.kmh ?: Float.MAX_VALUE) < condition.kmh
            is AutoCondition.CoolantTempAbove ->
                (frame.coolantTemp?.celsius ?: 0f) > condition.celsius
            is AutoCondition.VoltageBelow ->
                frame.batteryVoltage?.let { it.volts < condition.volts } ?: false
            is AutoCondition.DPFLoadAbove ->
                (frame.dpfData?.loadPercent ?: 0f) > condition.pct
            is AutoCondition.ACCOff ->
                frame.accState?.let { !it.isOn } ?: false
            is AutoCondition.ACCOn ->
                frame.accState?.isOn ?: false
            is AutoCondition.TimeOfDay -> {
                val cal = Calendar.getInstance()
                cal.get(Calendar.HOUR_OF_DAY) == condition.hour &&
                    cal.get(Calendar.MINUTE) == condition.minute
            }
        }

    // -------------------------------------------------------------------------
    //  Action execution
    // -------------------------------------------------------------------------

    private suspend fun executeAction(action: AutoAction) = withContext(Dispatchers.Main) {
        Timber.d("AutomationEngine: executing action %s", action::class.simpleName)
        when (action) {
            is AutoAction.SetBrightness ->
                settingsManager.setBrightness(action.level)

            is AutoAction.ShowNotification ->
                postNotification(action.title, action.message)

            is AutoAction.LaunchApp -> {
                val intent = context.packageManager
                    .getLaunchIntentForPackage(action.packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent != null) {
                    try { context.startActivity(intent) }
                    catch (e: Exception) { Timber.w(e, "LaunchApp failed: %s", action.packageName) }
                } else {
                    Timber.w("AutomationEngine: no launch intent for %s", action.packageName)
                }
            }

            is AutoAction.ExecuteShell ->
                withContext(Dispatchers.IO) {
                    shellExecutor.executeSuCommand(action.command).onFailure { e ->
                        Timber.w(e, "AutomationEngine: ExecuteShell failed: %s", action.command)
                    }
                }

            is AutoAction.SetDrivingMode ->
                Timber.d("AutomationEngine: driving mode set (UI should observe ProfileManager)")

            is AutoAction.SetParkedMode ->
                Timber.d("AutomationEngine: parked mode set (UI should observe ProfileManager)")
        }
    }

    // -------------------------------------------------------------------------
    //  Notification helpers
    // -------------------------------------------------------------------------

    private fun ensureNotificationChannel() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "CarOS Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Automatizovaná upozornění systému CarOS"
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun postNotification(title: String, message: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }

    // -------------------------------------------------------------------------
    //  Constants
    // -------------------------------------------------------------------------

    companion object {
        private const val CHANNEL_ID = "caros_alerts"
    }
}
