package com.flowshift.timelock.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 为什么跨 App 动画有时会被系统忽略？
 * 安卓原生的 ActivityOptions.makeCustomAnimation 在部分国产 ROM（如 MIUI/HyperOS、OriginOS）跨不同进程 Task 栈切换时，
 * 系统窗口管理器（WMS）可能会强制使用默认桌面切换动画。
 *
 * 解决方案（双轨无缝飞入幕布）：
 * 1. 不会导致掉权限！因为我们使用的是标准 TYPE_APPLICATION_OVERLAY 图层，动效仅持续 340ms 后立即移除释放，
 *    零内存泄漏、零后台 GPU 占用。
 * 2. 在触发下滑瞬间，先绘制上半屏“当前App向上飞出 (-100% Y)”与下半屏“目标App名片从底部飞入 (100% -> 0% Y)”，
 *    同时在 120ms 处调起目标 App，视觉上就像刷到了下一条全屏短视频一样自然。
 */
object TransitionOverlayHelper {

    fun playVerticalFlyTransition(
        context: Context,
        sourceAppName: String,
        targetAppName: String,
        stayMinutes: Int,
        isCooldownBounce: Boolean,
        onMidPointLaunch: () -> Unit
    ) {
        if (!Settings.canDrawOverlays(context)) {
            onMidPointLaunch()
            return
        }

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = context.resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels.toFloat()

        val rootContainer = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }

        // 上层卡片：模拟当前短视频向上滑出
        val outgoingPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#141413"))
            val hint = TextView(context).apply {
                text = "$sourceAppName · 已达设定时间，正在上滑切出"
                setTextColor(Color.parseColor("#8E8D87"))
                textSize = 13f
                gravity = Gravity.CENTER
            }
            addView(hint)
        }

        // 下层卡片：模拟目标学习 App 从屏幕底部飞入
        val incomingPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F5F3EE"))
            translationY = screenHeight

            val badge = TextView(context).apply {
                text = if (isCooldownBounce) "冷却锁生效中 · 未满停留时长" else "FLOWSHIFT · 顺滑接管"
                setTextColor(Color.parseColor("#D84A27"))
                textSize = 12f
                letterSpacing = 0.12f
                gravity = Gravity.CENTER
            }
            val title = TextView(context).apply {
                text = targetAppName
                setTextColor(Color.parseColor("#141413"))
                textSize = 26f
                setPadding(0, 24, 0, 16)
                gravity = Gravity.CENTER
            }
            val sub = TextView(context).apply {
                text = "需专注停留满 $stayMinutes 分钟后解锁 $sourceAppName"
                setTextColor(Color.parseColor("#6E6D68"))
                textSize = 14f
                gravity = Gravity.CENTER
            }
            addView(badge)
            addView(title)
            addView(sub)
        }

        rootContainer.addView(
            outgoingPanel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        rootContainer.addView(
            incomingPanel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )

        try {
            wm.addView(rootContainer, layoutParams)
        } catch (e: Exception) {
            onMidPointLaunch()
            return
        }

        var launched = false
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 340L
            interpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f)
            addUpdateListener { anim ->
                val fraction = anim.animatedValue as Float
                outgoingPanel.translationY = -screenHeight * fraction
                incomingPanel.translationY = screenHeight * (1f - fraction)
                if (fraction >= 0.35f && !launched) {
                    launched = true
                    onMidPointLaunch()
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    rootContainer.postDelayed({
                        try {
                            wm.removeViewImmediate(rootContainer)
                        } catch (_: Exception) {}
                    }, 120L)
                }
            })
        }
        animator.start()
    }
}
