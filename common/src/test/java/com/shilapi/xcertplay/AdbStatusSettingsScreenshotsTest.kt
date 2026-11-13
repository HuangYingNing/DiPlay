package com.shilapi.xcertplay

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.LinearLayout
import com.shilapi.xcertplay.hud.BydOutputSettings
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

/** 保存大字体下 ADB 两种运行状态的紧凑和宽屏截图。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdbStatusSettingsScreenshotsTest {
    @Test fun statusRowsFitCompactAndFullSettings() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        try {
            BydOutputSettings.setLegacyVehicleProbe(activity, false)
            val locale = Locale.forLanguageTag("zh-CN")
            val config = Configuration(activity.resources.configuration).apply {
                fontScale = 1.5f
                setLocale(locale)
            }
            @Suppress("DEPRECATION")
            activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
            val directory = File("build/reports/adb-status-ui").apply { mkdirs() }
            for ((name, field, widthDp) in listOf(
                Triple("compact-check", "adbCheckInProgress", 320),
                Triple("full-check", "adbCheckInProgress", 960),
                Triple("compact-allow", "adbEnableInProgress", 320),
                Triple("full-allow", "adbEnableInProgress", 960),
            )) {
                ReflectionHelpers.setField(activity, "adbCheckInProgress", field == "adbCheckInProgress")
                ReflectionHelpers.setField(activity, "adbEnableInProgress", field == "adbEnableInProgress")
                val section = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                DiPlayActivity::class.java.getDeclaredMethod("advancedVehicleData", LinearLayout::class.java)
                    .apply { isAccessible = true }.invoke(activity, section)
                val width = (widthDp * activity.resources.displayMetrics.density).toInt()
                section.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                section.layout(0, 0, width, section.measuredHeight)
                val bitmap = Bitmap.createBitmap(width, section.measuredHeight, Bitmap.Config.ARGB_8888)
                try {
                    section.draw(Canvas(bitmap))
                    FileOutputStream(File(directory, "$name.png")).use { output ->
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            activity.finish()
        }
    }
}
