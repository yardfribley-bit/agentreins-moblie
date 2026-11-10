package com.wangzan101.mika.settings

import android.content.Context
import com.wangzan101.mika.core.compare.PricePlatform
import com.wangzan101.mika.core.compare.PricePlatforms

/** 比价要查的 App。没存过时用默认名单，存过就用存下来的那几行。 */
class PriceCompareStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun text(): String = prefs.getString(KEY, null) ?: PricePlatforms.format(PricePlatforms.DEFAULT)

    fun platforms(): List<PricePlatform> = PricePlatforms.parse(prefs.getString(KEY, "").orEmpty()).platforms

    fun save(text: String): PricePlatforms.Parsed {
        val parsed = PricePlatforms.parse(text)
        prefs.edit().putString(KEY, PricePlatforms.format(parsed.platforms)).apply()
        return parsed
    }

    fun reset() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val PREFS = "price_compare"
        const val KEY = "platforms"
    }
}
