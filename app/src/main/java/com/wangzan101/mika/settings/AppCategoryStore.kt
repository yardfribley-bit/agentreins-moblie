package com.wangzan101.mika.settings

import android.content.Context
import com.wangzan101.mika.core.agent.RelevantApps

/** Missing key = unconfirmed suggestion; empty set = confirmed exclusion. */
class AppCategoryStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("app_categories", Context.MODE_PRIVATE)
    fun confirmed(pkg: String): Boolean = prefs.contains(pkg)
    fun categories(pkg: String, label: String = ""): Set<String> = if (confirmed(pkg)) prefs.getStringSet(pkg, emptySet()).orEmpty().toSet()
        else RelevantApps.suggestedCategories(pkg, label)
    fun save(pkg: String, categories: Set<String>) { prefs.edit().putStringSet(pkg, categories.toSet()).apply() }
    fun overrides(): Map<String, Set<String>> = prefs.all.mapNotNull { (k, v) ->
        (v as? Set<*>)?.filterIsInstance<String>()?.toSet()?.let { k to it }
    }.toMap()
}
