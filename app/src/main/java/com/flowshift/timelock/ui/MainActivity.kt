package com.flowshift.timelock.ui

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowshift.timelock.model.InterceptRule
import com.flowshift.timelock.model.RuleRepository
import com.flowshift.timelock.model.TimerMode
import com.flowshift.timelock.model.TransitionStyle

// 极简纸墨配色（无 AI 廉价渐变、无霓虹发光，致敬 Braun 与瑞士网格排版）
val PaperBg = Color(0xFFF5F3EE)
val CardSurface = Color(0xFFFAF9F5)
val InkPrimary = Color(0xFF161615)
val InkSecondary = Color(0xFF6E6D68)
val HairlineBorder = Color(0xFFE2DFD7)
val AccentTerracotta = Color(0xFFD84A27)
val StatusForest = Color(0xFF2A6F4E)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = PaperBg
                ) {
                    FlowShiftHomeScreen()
                }
            }
        }
    }
}

@Composable
fun FlowShiftHomeScreen() {
    val context = LocalContext.current
    var rules by remember { mutableStateOf(RuleRepository.loadRules(context)) }
    var selectedRule by remember { mutableStateOf(rules.firstOrNull() ?: RuleRepository.defaultRules().first()) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Text(
                        text = "FLOWSHIFT / 瞬移锁",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = InkSecondary,
                        letterSpacing = 1.5.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "时间锁与顺滑接管",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = InkPrimary
                    )
                }
                Box(
                    modifier = Modifier
                        .border(1.dp, StatusForest, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "60FPS 双轨过渡",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = StatusForest
                    )
                }
            }
        }

        // 权限状态自检栏
        item {
            PermissionStatusSection(context)
        }

        // 规则配置卡片
        item {
            RuleEditorCard(
                rule = selectedRule,
                onRuleChanged = { updated ->
                    selectedRule = updated
                    val newList = rules.map { if (it.id == updated.id) updated else it }
                    rules = newList
                    RuleRepository.saveRules(context, newList)
                }
            )
        }
    }
}

@Composable
fun PermissionStatusSection(context: Context) {
    val overlayGranted = Settings.canDrawOverlays(context)
    val usageGranted = hasUsageStatsPermission(context)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardSurface, RoundedCornerShape(8.dp))
            .border(1.dp, HairlineBorder, RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "01 / 核心权限与防掉权限自检",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = InkSecondary
        )
        PermissionRow(
            title = "无障碍服务 (监听下滑与前台包名)",
            granted = false,
            actionText = "去开启"
        ) {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        PermissionRow(
            title = "悬浮窗权限 (绘制上滑飞出/底部飞入幕布)",
            granted = overlayGranted,
            actionText = if (overlayGranted) "已就绪" else "去授权"
        ) {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
            )
        }
        PermissionRow(
            title = "使用情况访问 (统计今日累计时长)",
            granted = usageGranted,
            actionText = if (usageGranted) "已就绪" else "去授权"
        ) {
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }
}

@Composable
fun PermissionRow(title: String, granted: Boolean, actionText: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, fontSize = 14.sp, color = InkPrimary)
        Text(
            text = actionText,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = if (granted) StatusForest else AccentTerracotta,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun RuleEditorCard(rule: InterceptRule, onRuleChanged: (InterceptRule) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardSurface, RoundedCornerShape(8.dp))
            .border(1.dp, HairlineBorder, RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "02 / 跳转规则与计时模式",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = InkSecondary
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = "受限应用 (触发源)", fontSize = 12.sp, color = InkSecondary)
                Text(text = "${rule.sourceAppName} (${rule.sourcePackage})", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = InkPrimary)
            }
            Text(text = "➔", fontSize = 18.sp, color = AccentTerracotta)
            Column(horizontalAlignment = Alignment.End) {
                Text(text = "接管应用 (目标)", fontSize = 12.sp, color = InkSecondary)
                Text(text = "${rule.targetAppName} (${rule.targetPackage})", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = InkPrimary)
            }
        }

        Divider(color = HairlineBorder)

        Text(
            text = "单次连续上限：${rule.continuousLimitMinutes} 分钟  |  今日累计上限：${rule.dailyLimitMinutes} 分钟",
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            color = InkPrimary
        )
        Text(
            text = "随机盲盒区间：${rule.randomMinMinutes} ~ ${rule.randomMaxMinutes} 分钟内随机触发",
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            color = InkPrimary
        )
        Text(
            text = "强制停留冷却锁：必须在 ${rule.targetAppName} 停留满 ${rule.requiredTargetStayMinutes} 分钟",
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            color = AccentTerracotta
        )
    }
}

private fun hasUsageStatsPermission(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = appOps.checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        Process.myUid(),
        context.packageName
    )
    return mode == AppOpsManager.MODE_ALLOWED
}
