package com.flowshift.timelock.ui

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import com.flowshift.timelock.R

/**
 * 透明桥接 Activity：
 * 当从后台无障碍服务启动目标 App 时，先以自定义底部飞入动画启动此透明桥接页并立即接力拉起目标 App，
 * 最大限度兼容原生 Android Activity 转场动画机制。
 */
class TransitionBridgeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val targetPkg = intent.getStringExtra(EXTRA_TARGET_PACKAGE)
        if (!targetPkg.isNullOrEmpty()) {
            val launchIntent = packageManager.getLaunchIntentForPackage(targetPkg)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            if (launchIntent != null) {
                val options = ActivityOptions.makeCustomAnimation(
                    this,
                    R.anim.slide_in_bottom,
                    R.anim.slide_out_top
                )
                startActivity(launchIntent, options.toBundle())
            }
        }
        finish()
        overridePendingTransition(R.anim.slide_in_bottom, R.anim.slide_out_top)
    }

    companion object {
        const val EXTRA_TARGET_PACKAGE = "extra_target_package"
    }
}
