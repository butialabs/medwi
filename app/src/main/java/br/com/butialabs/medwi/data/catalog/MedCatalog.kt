package br.com.butialabs.medwi.data.catalog

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.text.Normalizer
import java.util.Locale

/**
 * Offline medicine-name catalog for autocomplete, bundled in `assets/catalog/`:
 *  - `pt.json`: Brazilian registered medicines (ANVISA open data)
 *  - `en.json`: U.S. NDC directory (openFDA, public domain)
 * Regenerate both with `tools/catalog/build_catalog.py`.
 *
 * The catalog for the device language is searched first; the other one fills in when
 * it has no match. Matching ignores case and accents ("losartana" finds "Losartana Potássica").
 */
class MedCatalog(
    private val appContext: Context,
    private val language: () -> String = { Locale.getDefault().language },
) {

    private class Entry(val name: String, val key: String)

    private val loaded = HashMap<String, List<Entry>>()

    private suspend fun catalog(lang: String): List<Entry> {
        loaded[lang]?.let { return it }
        return withContext(Dispatchers.IO) {
            val entries = runCatching {
                val text = appContext.assets.open("catalog/$lang.json").bufferedReader().use { it.readText() }
                Json.decodeFromString<List<String>>(text).map { Entry(it, fold(it)) }
            }.getOrDefault(emptyList())
            synchronized(loaded) { loaded[lang] = entries }
            entries
        }
    }

    /** Catalog files to search, device language first. */
    private fun order(): List<String> =
        if (language().lowercase() == "pt") listOf("pt", "en") else listOf("en", "pt")

    /** Prefix matches first, then substring matches, capped at [limit]. */
    suspend fun search(query: String, limit: Int = 25): List<String> {
        val q = fold(query.trim())
        if (q.isEmpty()) return emptyList()
        for (lang in order()) {
            val hits = withContext(Dispatchers.Default) { match(catalog(lang), q, limit) }
            if (hits.isNotEmpty()) return hits
        }
        return emptyList()
    }

    private fun match(entries: List<Entry>, q: String, limit: Int): List<String> {
        val prefix = ArrayList<String>()
        val contains = ArrayList<String>()
        for (e in entries) {
            when {
                e.key.startsWith(q) -> prefix += e.name
                contains.size < limit && e.key.contains(q) -> contains += e.name
            }
            if (prefix.size >= limit) break
        }
        return (prefix + contains).take(limit)
    }

    companion object {
        private val MARKS = Regex("\\p{Mn}+")

        /** Case- and accent-insensitive comparison key. */
        fun fold(text: String): String =
            MARKS.replace(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD), "")
    }
}
