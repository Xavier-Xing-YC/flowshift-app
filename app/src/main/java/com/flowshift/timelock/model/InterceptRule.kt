package com.flowshift.timelock.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

enum class TimerMode {
    CONTINUOUS,        // 单次连续使用时长
    DAILY_CUMULATIVE,  // 当日累计使用时长
    RANDOM_RANGE,      // 随机区间盲盒模式
    ALL_COMBINED       // 组合模式（满足任一阈值即进入下滑待命）
}

enum class TransitionStyle {
    DUAL_CURTAIN_FLY,  // 推荐：WindowManager 悬浮幕布 + Activity 向上飞出/底部飞入（最稳、不被系统吃动画）
    SYSTEM_ANIM_ONLY,  // 仅使用 ActivityOptions.makeCustomAnimation（零悬浮窗开销）
    INSTANT            // 无动画极速瞬切
}

data class InterceptRule(
    val id: String,
    val name: String,
    val sourcePackage: String,
    val sourceAppName: String,
    val targetPackage: String,
    val targetAppName: String,
    val timerMode: TimerMode = TimerMode.ALL_COMBINED,
    val continuousLimitMinutes: Int = 15,
    val dailyLimitMinutes: Int = 45,
    val randomMinMinutes: Int = 8,
    val randomMaxMinutes: Int = 20,
    val requiredTargetStayMinutes: Int = 5, // 冷却锁：必须在目标App停留满 N 分钟
    val transitionStyle: TransitionStyle = TransitionStyle.DUAL_CURTAIN_FLY,
    val enabled: Boolean = true
) {
    fun rollRandomThresholdSeconds(): Long {
        val minSec = (randomMinMinutes.coerceAtLeast(1)) * 60L
        val maxSec = (randomMaxMinutes.coerceAtLeast(randomMinMinutes + 1)) * 60L
        return Random.nextLong(minSec, maxSec + 1)
    }
}

object RuleRepository {
    private const val PREF_NAME = "flowshift_rules_pref"
    private const val KEY_RULES = "rules_json"

    fun loadRules(context: Context): List<InterceptRule> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_RULES, null) ?: return defaultRules()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<InterceptRule>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    InterceptRule(
                        id = obj.optString("id", "rule_$i"),
                        name = obj.optString("name", "抖音 ➔ 墨墨背单词"),
                        sourcePackage = obj.getString("sourcePackage"),
                        sourceAppName = obj.optString("sourceAppName", "抖音"),
                        targetPackage = obj.getString("targetPackage"),
                        targetAppName = obj.optString("targetAppName", "墨墨背单词"),
                        timerMode = TimerMode.valueOf(obj.optString("timerMode", TimerMode.ALL_COMBINED.name)),
                        continuousLimitMinutes = obj.optInt("continuousLimitMinutes", 15),
                        dailyLimitMinutes = obj.optInt("dailyLimitMinutes", 45),
                        randomMinMinutes = obj.optInt("randomMinMinutes", 8),
                        randomMaxMinutes = obj.optInt("randomMaxMinutes", 20),
                        requiredTargetStayMinutes = obj.optInt("requiredTargetStayMinutes", 5),
                        transitionStyle = TransitionStyle.valueOf(
                            obj.optString("transitionStyle", TransitionStyle.DUAL_CURTAIN_FLY.name)
                        ),
                        enabled = obj.optBoolean("enabled", true)
                    )
                )
            }
            list
        } catch (e: Exception) {
            defaultRules()
        }
    }

    fun saveRules(context: Context, rules: List<InterceptRule>) {
        val arr = JSONArray()
        rules.forEach { r ->
            val obj = JSONObject().apply {
                put("id", r.id)
                put("name", r.name)
                put("sourcePackage", r.sourcePackage)
                put("sourceAppName", r.sourceAppName)
                put("targetPackage", r.targetPackage)
                put("targetAppName", r.targetAppName)
                put("timerMode", r.timerMode.name)
                put("continuousLimitMinutes", r.continuousLimitMinutes)
                put("dailyLimitMinutes", r.dailyLimitMinutes)
                put("randomMinMinutes", r.randomMinMinutes)
                put("randomMaxMinutes", r.randomMaxMinutes)
                put("requiredTargetStayMinutes", r.requiredTargetStayMinutes)
                put("transitionStyle", r.transitionStyle.name)
                put("enabled", r.enabled)
            }
            arr.put(obj)
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RULES, arr.toString())
            .apply()
    }

    fun defaultRules(): List<InterceptRule> = listOf(
        InterceptRule(
            id = "default_douyin_momo",
            name = "刷抖音顺滑切入背单词",
            sourcePackage = "com.ss.android.ugc.aweme",
            sourceAppName = "抖音",
            targetPackage = "com.maimemo.android.momo",
            targetAppName = "墨墨背单词",
            timerMode = TimerMode.ALL_COMBINED,
            continuousLimitMinutes = 15,
            dailyLimitMinutes = 45,
            randomMinMinutes = 8,
            randomMaxMinutes = 20,
            requiredTargetStayMinutes = 5,
            transitionStyle = TransitionStyle.DUAL_CURTAIN_FLY,
            enabled = true
        )
    )
}
