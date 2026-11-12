package com.shilapi.xcertplay

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
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

/** 生成大字体下诊断页的紧凑、宽屏及阿拉伯语截图。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SystemPackageArchiveSettingsScreenshotsTest {
    @Test fun exportControlRendersAtLargeFonts() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        try {
            for ((filename, widthDp, language) in listOf(
                Triple("compact-zh", 320, "zh-CN"),
                Triple("full-en", 960, "en-US"),
                Triple("compact-ar-rtl", 320, "ar-SA"),
            )) {
                val locale = Locale.forLanguageTag(language)
                val config = Configuration(activity.resources.configuration).apply {
                    fontScale = 1.5f
                    setLocale(locale)
                    setLayoutDirection(locale)
                }
                @Suppress("DEPRECATION")
                activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
                val layout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                DiPlayActivity::class.java.getDeclaredMethod("diagnosticsSettings", LinearLayout::class.java)
                    .apply { isAccessible = true }.invoke(activity, layout)
                assertTrue(views(layout).filterIsInstance<TextView>().any {
                    it.text.toString() == activity.getString(R.string.system_package_export)
                })
                val width = (widthDp * activity.resources.displayMetrics.density).toInt()
                layout.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                layout.layout(0, 0, width, layout.measuredHeight)
                val bitmap = Bitmap.createBitmap(width, layout.measuredHeight, Bitmap.Config.ARGB_8888)
                try {
                    layout.draw(Canvas(bitmap))
                    val directory = File("build/reports/system-package-ui").apply { mkdirs() }
                    FileOutputStream(File(directory, "$filename.png")).use { output ->
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

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}
