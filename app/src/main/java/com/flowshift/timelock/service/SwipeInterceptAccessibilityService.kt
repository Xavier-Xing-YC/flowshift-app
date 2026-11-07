package com.flowshift.timelock.service

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.flowshift.timelock.R
import com.flowshift.timelock.model.InterceptRule
import com.flowshift.timelock.model.RuleRepository
import com.flowshift.timelock.model.TimerMode
import com.flowshift.timelock.model.TransitionStyle
import com.flowshift.timelock.ui.TransitionBridgeActivity
import com.flowshift.timelock.ui.TransitionOverlayHelper
import java.util.Calendar

/**
 * 核心无障碍拦截服务：
 * 1. 支持三种计时方式（单次连续、当日累计、随机盲盒区间，以及三合一组合触发）。
 * 2. 达到时间阈值后进入【待命状态 (Standby)】，不生硬打断当前视频，而是在用户手指下滑切换下一个视频
 *    (TYPE_VIEW_SCROLLED) 的一瞬间触发跳转。
 * 3. 支持【强制停留冷却锁】：跳转到墨墨背单词后开始倒计时 N 分钟，未满 N 分钟前如果切回抖音，
 *    立即触发回弹拦截。
 */
class SwipeInterceptAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentForegroundPackage: String = ""

    // 计时状态
    private var activeSourceRule: InterceptRule? = null
    private var continuousSessionSeconds: Long = 0L
    private var rolledRandomLimitSeconds: Long = 600L
    private var isStandbyForNextSwipe: Boolean = false

    // 冷却锁状态（记录某条规则还需在目标App中停留多少秒）
    private val remainingCooldownSecondsMap = mutableMapOf<String, Long>()
    private var lastRedirectTimestampMs: Long = 0L

    private val ticker = object : Runnable {
        override fun run() {
            onSecondTick()
            mainHandler.postDelayed(this, 1000L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        startForegroundGuardNotification()
        mainHandler.post(ticker)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg != "android" && pkg != "com.android.systemui" && pkg != packageName) {
                    handleForegroundPackageChanged(pkg)
                }
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val rule = activeSourceRule ?: return
                if (pkg == rule.sourcePackage && isStandbyForNextSwipe) {
                    // 优先判断纵向正向滚动（手指上滑刷下一条视频，scrollDeltaY > 0 或兼容旧机型直接触发）
                    val isVerticalNextSwipe = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        event.scrollDeltaY >= 0
                    } else {
                        true
                    }
                    if (isVerticalNextSwipe && System.currentTimeMillis() - lastRedirectTimestampMs > 1800L) {
                        triggerRedirect(rule, isCooldownBounce = false)
                    }
                }
            }
        }
    }

    private fun handleForegroundPackageChanged(newPkg: String) {
        if (newPkg == currentForegroundPackage) return
        currentForegroundPackage = newPkg

        val rules = RuleRepository.loadRules(this).filter { it.enabled }
        val matchedSourceRule = rules.find { it.sourcePackage == newPkg }

        if (matchedSourceRule != null) {
            // 1. 先检查该规则是否处于“目标App未学满 N 分钟”的冷却锁期内
            val pendingCooldownSec = remainingCooldownSecondsMap[matchedSourceRule.id] ?: 0L
            if (pendingCooldownSec > 0L) {
                triggerRedirect(matchedSourceRule, isCooldownBounce = true)
                return
            }

            // 2. 正常进入受限App，初始化本轮计时与随机阈值
            if (activeSourceRule?.id != matchedSourceRule.id) {
                activeSourceRule = matchedSourceRule
                continuousSessionSeconds = 0L
                rolledRandomLimitSeconds = matchedSourceRule.rollRandomThresholdSeconds()
                isStandbyForNextSwipe = false
            }
        } else {
            activeSourceRule = null
            isStandbyForNextSwipe = false
        }
    }

    private fun onSecondTick() {
        val rules = RuleRepository.loadRules(this).filter { it.enabled }

        // 1. 如果当前前台是某个处于冷却期的目标学习App（如墨墨背单词），扣减其必须停留的倒计时
        rules.forEach { rule ->
            val leftSec = remainingCooldownSecondsMap[rule.id] ?: 0L
            if (leftSec > 0L && currentForegroundPackage == rule.targetPackage) {
                val updated = (leftSec - 1L).coerceAtLeast(0L)
                remainingCooldownSecondsMap[rule.id] = updated
            }
        }

        // 2. 如果当前前台是受限App（如抖音），累加时长并判断是否进入“等待下一次下滑”待命状态
        val rule = activeSourceRule ?: return
        if (currentForegroundPackage == rule.sourcePackage && !isStandbyForNextSwipe) {
            continuousSessionSeconds += 1L
            val dailyUsedSeconds = queryTodayUsageSeconds(rule.sourcePackage) + continuousSessionSeconds

            val continuousReached = continuousSessionSeconds >= rule.continuousLimitMinutes * 60L
            val dailyReached = dailyUsedSeconds >= rule.dailyLimitMinutes * 60L
            val randomReached = continuousSessionSeconds >= rolledRandomLimitSeconds

            val shouldEnterStandby = when (rule.timerMode) {
                TimerMode.CONTINUOUS -> continuousReached
                TimerMode.DAILY_CUMULATIVE -> dailyReached
                TimerMode.RANDOM_RANGE -> randomReached
                TimerMode.ALL_COMBINED -> continuousReached || dailyReached || randomReached
            }

            if (shouldEnterStandby) {
                isStandbyForNextSwipe = true
            }
        }
    }

    private fun triggerRedirect(rule: InterceptRule, isCooldownBounce: Boolean) {
        lastRedirectTimestampMs = System.currentTimeMillis()
        isStandbyForNextSwipe = false
        continuousSessionSeconds = 0L

        // 设置或保留目标App强制停留冷却时间（单位：秒）
        if ((remainingCooldownSecondsMap[rule.id] ?: 0L) <= 0L) {
            remainingCooldownSecondsMap[rule.id] = (rule.requiredTargetStayMinutes.coerceAtLeast(1)) * 60L
        }

        when (rule.transitionStyle) {
            TransitionStyle.DUAL_CURTAIN_FLY -> {
                TransitionOverlayHelper.playVerticalFlyTransition(
                    context = this,
                    sourceAppName = rule.sourceAppName,
                    targetAppName = rule.targetAppName,
                    stayMinutes = rule.requiredTargetStayMinutes,
                    isCooldownBounce = isCooldownBounce,
                    onMidPointLaunch = {
                        launchTargetAppWithCustomAnim(rule.targetPackage)
                    }
                )
            }
            TransitionStyle.SYSTEM_ANIM_ONLY -> {
                val bridgeIntent = Intent(this, TransitionBridgeActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    putExtra(TransitionBridgeActivity.EXTRA_TARGET_PACKAGE, rule.targetPackage)
                }
                startActivity(bridgeIntent)
            }
            TransitionStyle.INSTANT -> {
                launchTargetAppWithCustomAnim(rule.targetPackage, useAnim = false)
            }
        }
    }

    private fun launchTargetAppWithCustomAnim(targetPkg: String, useAnim: Boolean = true) {
        val launchIntent = packageManager.getLaunchIntentForPackage(targetPkg)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        } ?: return

        if (useAnim) {
            val options = ActivityOptions.makeCustomAnimation(
                this,
                R.anim.slide_in_bottom,
                R.anim.slide_out_top
            )
            startActivity(launchIntent, options.toBundle())
        } else {
            startActivity(launchIntent)
        }
    }

    private fun queryTodayUsageSeconds(targetPkg: String): Long {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                cal.timeInMillis,
                System.currentTimeMillis()
            )
            val totalMs = stats?.filter { it.packageName == targetPkg }
                ?.sumOf { it.totalTimeInForeground } ?: 0L
            totalMs / 1000L
        } catch (e: Exception) {
            0L
        }
    }

    private fun startForegroundGuardNotification() {
        val channelId = "flowshift_guard_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "瞬移锁后台保活服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持手势检测与时间锁稳定运行，防止系统杀后台导致掉权限"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
            val notification = Notification.Builder(this, channelId)
                .setContentTitle("瞬移锁 · 正在守护专注")
                .setContentText("下滑拦截与冷却锁运行中")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setOngoing(true)
                .build()
            startForeground(1001, notification)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
