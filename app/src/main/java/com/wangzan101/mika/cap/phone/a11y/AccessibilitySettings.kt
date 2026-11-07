package com.wangzan101.mika.cap.phone.a11y

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.Display

/**
 * 在用户主屏打开系统无障碍设置。
 * 不经过内置助手、虚拟屏或无障碍服务：服务没连上时，这是唯一还能打开的恢复入口。
 */
object AccessibilitySettings {
    fun open(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic().apply { launchDisplayId = Display.DEFAULT_DISPLAY }
        return try {
            context.startActivity(intent, options.toBundle())
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no accessibility settings activity", e)
            false
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not open accessibility settings", e)
            false
        }
    }

    private const val TAG = "A11ySettings"
}
