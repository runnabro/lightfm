package com.lightfm.radio

/** A known public stream (brand name + HTTPS URL) for a call sign, from the bundled override map. */
data class StreamOverride(val url: String, val name: String)

/**
 * Bundled call-sign → public-stream map (`iheart_streams.tsv`, `CALL\turl\tname`). Resolved offline
 * from broadcaster catalogs; covers the big commercial FM brands (iHeart/Audacy/Cumulus) that the
 * open directories don't carry. These are the broadcasters' own public simulcast URLs.
 */
object StreamOverrides {
    fun parse(bytes: ByteArray?): Map<String, StreamOverride> {
        if (bytes == null) return emptyMap()
        val map = HashMap<String, StreamOverride>()
        for (line in String(bytes, Charsets.UTF_8).lineSequence()) {
            val p = line.split('\t')
            if (p.size < 2 || p[0].isBlank() || !p[1].startsWith("https://")) continue
            map[p[0].trim().uppercase()] = StreamOverride(p[1].trim(), (p.getOrNull(2) ?: p[0]).trim())
        }
        return map
    }
}
