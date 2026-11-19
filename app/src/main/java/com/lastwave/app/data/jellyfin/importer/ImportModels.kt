package com.lastwave.app.data.jellyfin.importer

import com.lastwave.app.playback.PlayableTrack
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.text.Normalizer

class ImportException(message: String) : Exception(message)

/** One song from an exported playlist, before it is matched to your library. */
data class ImportTrack(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val isrc: String? = null,
)

data class ParsedPlaylist(val name: String?, val tracks: List<ImportTrack>)

/** A song and what it matched to (null = no confident match; [suggestions] are near misses). */
data class ImportOutcome(
    val source: ImportTrack,
    val match: PlayableTrack?,
    val suggestions: List<PlayableTrack>,
)

/** Reads CSV (Exportify, Soundiiz, TuneMyMusic and similar) and JSON playlist exports. */
object ImportParsers {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): ParsedPlaylist {
        val trimmed = text.trimStart('﻿', ' ', '\n', '\r', '\t')
        return if (trimmed.startsWith("{") || trimmed.startsWith("[")) parseJson(trimmed) else parseCsv(trimmed)
    }

    // ---- CSV ----

    private fun splitRows(text: String, delimiter: Char): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == delimiter -> { row.add(cell.toString()); cell.setLength(0) }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row.add(cell.toString()); cell.setLength(0)
                    if (row.any { it.isNotBlank() }) rows.add(row)
                    row = ArrayList()
                }
                else -> cell.append(c)
            }
            i++
        }
        row.add(cell.toString())
        if (row.any { it.isNotBlank() }) rows.add(row)
        return rows
    }

    private val TITLE = listOf("track name", "trackname", "track", "title", "song name", "song", "name")
    private val ARTIST = listOf("artist name(s)", "artist names", "artist name", "artistname", "artists", "artist")
    private val ALBUM = listOf("album name", "albumname", "album")
    private val DURATION = listOf("duration (ms)", "duration_ms", "duration ms", "durationms", "duration")
    private val ISRC = listOf("isrc")

    fun parseCsv(text: String): ParsedPlaylist {
        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        val delimiter = listOf(',', ';', '\t').maxByOrNull { d -> firstLine.count { it == d } } ?: ','
        val rows = splitRows(text, delimiter)
        if (rows.isEmpty()) return ParsedPlaylist(null, emptyList())
        val header = rows[0].map { it.trim().lowercase() }
        fun col(names: List<String>) = names.firstNotNullOfOrNull { n -> header.indexOf(n).takeIf { it >= 0 } }
        val titleCol = col(TITLE)
        val artistCol = col(ARTIST)
        val hasHeader = titleCol != null && artistCol != null
        val t = titleCol ?: 0
        val a = artistCol ?: 1
        val al = col(ALBUM)
        val d = col(DURATION)
        val isrc = col(ISRC)
        val body = if (hasHeader) rows.drop(1) else rows
        val tracks = body.mapNotNull { r ->
            val title = r.getOrNull(t)?.trim().orEmpty()
            val artist = r.getOrNull(a)?.trim().orEmpty().replace(';', ',')
            if (title.isBlank()) return@mapNotNull null
            ImportTrack(
                title = title,
                artist = artist,
                album = al?.let { r.getOrNull(it)?.trim() }?.takeIf { it.isNotBlank() },
                durationMs = d?.let { r.getOrNull(it)?.trim()?.toDoubleOrNull()?.toLong() }
                    ?.let { if (it in 1..9_999) it * 1000 else it }, // seconds -> ms
                isrc = isrc?.let { r.getOrNull(it)?.trim() }?.takeIf { it.isNotBlank() },
            )
        }
        return ParsedPlaylist(null, tracks)
    }

    // ---- JSON ----

    fun parseJson(text: String): ParsedPlaylist {
        val root = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw ImportException("That file isn't valid JSON")
        }
        val tracks = ArrayList<ImportTrack>()
        var name: String? = null
        fun walk(el: JsonElement) {
            when (el) {
                is JsonObject -> {
                    val track = extract(el)
                    if (track != null) {
                        tracks.add(track)
                        return
                    }
                    if (name == null && (el.containsKey("tracks") || el.containsKey("items"))) {
                        name = (el["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                    }
                    el.values.forEach(::walk)
                }
                is JsonArray -> el.forEach(::walk)
                else -> Unit
            }
        }
        walk(root)
        return ParsedPlaylist(name, tracks)
    }

    private fun JsonObject.str(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k -> (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() } }

    private fun JsonObject.long(vararg keys: String): Long? =
        keys.firstNotNullOfOrNull { k -> (this[k] as? JsonPrimitive)?.longOrNull }

    private fun artistOf(o: JsonObject): String? {
        o.str("artistName", "artist_name", "artist")?.let { return it }
        return when (val a = o["artists"]) {
            is JsonPrimitive -> a.contentOrNull?.takeIf { it.isNotBlank() }
            is JsonArray -> a.mapNotNull {
                (it as? JsonPrimitive)?.contentOrNull ?: ((it as? JsonObject)?.str("name"))
            }.joinToString(", ").takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private fun extract(o: JsonObject): ImportTrack? {
        val title = o.str("trackName", "track_name", "title", "track", "name") ?: return null
        val artist = artistOf(o) ?: return null
        val album = o.str("albumName", "album_name", "album") ?: (o["album"] as? JsonObject)?.str("name")
        val ms = o.long("duration_ms", "durationMs", "duration")
        val isrc = o.str("isrc") ?: (o["external_ids"] as? JsonObject)?.str("isrc")
        return ImportTrack(title, artist, album, ms, isrc)
    }
}

/** Decides whether a library song is the song an export meant. Pure functions, easy to test. */
object ImportMatcher {
    private val NOISE = Regex(
        "[\\(\\[][^\\)\\]]*(feat|ft\\.|with |remaster|remix|version|edit|live|mono|stereo|deluxe|bonus|explicit|radio)[^\\)\\]]*[\\)\\]]",
        RegexOption.IGNORE_CASE,
    )
    private val DASH_SUFFIX = Regex("\\s-\\s(.*(remaster|version|edit|mono|stereo|live).*)$", RegexOption.IGNORE_CASE)

    fun norm(s: String): String {
        val decomposed = Normalizer.normalize(s, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "")
        return decomposed.lowercase().replace("&", " and ").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }

    fun baseTitle(s: String): String = norm(s.replace(NOISE, " ").replace(DASH_SUFFIX, ""))

    private fun tokens(s: String) = norm(s).split(' ').filter { it.isNotBlank() }.toSet()

    private fun dice(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return 2.0 * a.intersect(b).size / (a.size + b.size)
    }

    private fun artistParts(s: String): List<Set<String>> =
        s.split(Regex("\\s*(,|;|&|\\band\\b|feat\\.?|ft\\.?|x)\\s*", RegexOption.IGNORE_CASE))
            .map(::tokens).filter { it.isNotEmpty() }

    /** The first credited artist's name as written, for looking the artist up. */
    fun artistParts0(s: String): List<String> =
        s.split(Regex("\\s*(,|;|&|\\bfeat\\.?|\\bft\\.?)\\s*", RegexOption.IGNORE_CASE)).map { it.trim() }.filter { it.isNotEmpty() }

    fun titleScore(a: String, b: String): Double {
        val x = baseTitle(a)
        val y = baseTitle(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        return dice(x.split(' ').toSet(), y.split(' ').toSet())
    }

    fun artistScore(src: String, cand: String): Double {
        val s = artistParts(src)
        val c = artistParts(cand)
        if (s.isEmpty() || c.isEmpty()) return 0.0
        // Best pairing between any credited artist on each side.
        return s.maxOf { sp -> c.maxOf { cp -> dice(sp, cp) } }
    }

    fun score(src: ImportTrack, cand: PlayableTrack): Double {
        val title = titleScore(src.title, cand.title)
        val artist = artistScore(src.artist, cand.artist)
        var s = 0.65 * title + 0.35 * artist
        if (src.album != null && cand.album != null && norm(src.album) == norm(cand.album)) s += 0.05
        val d1 = src.durationMs
        val d2 = cand.durationMs
        if (d1 != null && d2 != null && d1 > 0 && d2 > 0) {
            val diff = kotlin.math.abs(d1 - d2)
            if (diff <= 3_000) s += 0.05 else if (diff > 15_000) s -= 0.15
        }
        return s
    }

    /** True when [cand] is confidently the same song. */
    fun accepts(src: ImportTrack, cand: PlayableTrack): Boolean {
        val title = titleScore(src.title, cand.title)
        val artist = artistScore(src.artist, cand.artist)
        return title >= 0.75 && artist >= 0.5 && score(src, cand) >= 0.80
    }

    fun rank(src: ImportTrack, candidates: List<PlayableTrack>): List<PlayableTrack> =
        candidates.distinctBy { it.internalTrackId }.sortedByDescending { score(src, it) }
}

/**
 * Compares text written in different scripts or romanisations (Russian in Cyrillic vs "mertvaya karyera"):
 * both sides are turned into the same rough sound-alike spelling, then compared by edit distance.
 */
object LooseMatch {
    private val CYR = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e", 'ж' to "zh", 'з' to "z",
        'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r",
        'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh",
        'щ' to "shch", 'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
        'і' to "i", 'ї' to "yi", 'є' to "ye", 'ґ' to "g",
    )

    fun fold(raw: String): String {
        val sb = StringBuilder()
        for (ch in raw.lowercase()) sb.append(CYR[ch] ?: ch.toString())
        var s = sb.toString().replace(Regex("[^\\p{L}\\p{N}]+"), "")
        for ((a, b) in listOf(
            "shch" to "S", "sh" to "S", "ch" to "C", "zh" to "Z", "kh" to "H", "ts" to "T",
            "yu" to "U", "iu" to "U", "ju" to "U", "ya" to "A", "ia" to "A", "ja" to "A", "yo" to "O", "jo" to "O",
            "j" to "Z", "y" to "i", "w" to "v", "c" to "k", "q" to "k", "x" to "ks", "h" to "H",
        )) s = s.replace(a, b)
        return s.replace(Regex("(.)\\1+"), "$1")
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    fun ratio(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        if (minOf(a.length, b.length) < 3) return 0.0
        return 1.0 - levenshtein(a, b).toDouble() / maxOf(a.length, b.length)
    }

    fun titleRatio(a: String, b: String) = ratio(fold(ImportMatcher.baseTitle(a)), fold(ImportMatcher.baseTitle(b)))

    /** Best match between any credited artist on each side. */
    fun artistRatio(src: String, cand: List<String>): Double {
        val sp = ImportMatcher.artistParts0(src).map { fold(ImportMatcher.norm(it)) }.filter { it.isNotEmpty() }
        val cp = cand.flatMap { ImportMatcher.artistParts0(it) }.map { fold(ImportMatcher.norm(it)) }.filter { it.isNotEmpty() }
        if (sp.isEmpty() || cp.isEmpty()) return 0.0
        return sp.maxOf { x -> cp.maxOf { y -> ratio(x, y) } }
    }
}

