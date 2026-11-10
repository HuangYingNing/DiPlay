package com.shilapi.xcertplay

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
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

/** Captures the non-BYD hotspot access controls with large text in compact, full and RTL layouts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SystemHotspotAccessScreenshotsTest {
    @Test @Config(qualifiers = "en-w320dp-h600dp")
    fun compactLargeFont() = capture("compact-large-font", 320, "en-US")

    @Test @Config(qualifiers = "en-w960dp-h700dp")
    fun fullLargeFont() = capture("full-large-font", 960, "en-US")

    @Test @Config(qualifiers = "ar-ldrtl-w320dp-h600dp")
    fun compactArabicLargeFont() = capture("compact-arabic-large-font", 320, "ar-SA")

    private fun capture(name: String, widthDp: Int, language: String) {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        AirPlayPersistence.saveWirelessHotspotMode(activity, WirelessHotspotMode.MANUAL)
        CarHotspotSettings.setEnabled(activity, true)
        try {
            val locale = Locale.forLanguageTag(language)
            val config = Configuration(activity.resources.configuration).apply {
                fontScale = 1.5f
                setLocale(locale)
                setLayoutDirection(locale)
            }
            @Suppress("DEPRECATION")
            activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
            val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            ReflectionHelpers.setField(activity, "settingsSectionFilter", setOf(SettingsSection.AUTOMATIC_CONNECTION))
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "allSettingsSections",
                ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, content))
            assertTrue(allViews(content).filterIsInstance<TextView>().any {
                it.text.toString() == activity.getString(R.string.auto_car_hotspot_title)
            })
            val width = (widthDp * activity.resources.displayMetrics.density).toInt()
            content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            content.layout(0, 0, width, content.measuredHeight)
            val bitmap = Bitmap.createBitmap(width, content.measuredHeight, Bitmap.Config.ARGB_8888)
            try {
                content.draw(Canvas(bitmap))
                val directory = File("build/reports/hotspot-system-access-ui").apply { mkdirs() }
                FileOutputStream(File(directory, "$name.png")).use { output ->
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                }
            } finally {
                bitmap.recycle()
            }
        } finally {
            activity.finish()
            CarHotspotSettings.setEnabled(activity, false)
        }
    }

    private fun allViews(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(allViews(view.getChildAt(index)))
    }
}
