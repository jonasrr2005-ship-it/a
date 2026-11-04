package de.zugreise.app

/** Vergleich von Bahnhofsnamen ("Hamburg Hauptbahnhof" == "Hamburg Hbf"). */
object StationNames {

    fun normalize(s: String): String = s.lowercase()
        .replace("hauptbahnhof", "hbf")
        .replace("bahnhof", "bf")
        .replace(Regex("""\(.*?\)"""), "")
        .replace(Regex("[^a-z0-9äöüß]"), "")

    fun same(a: String, b: String): Boolean {
        val x = normalize(a)
        val y = normalize(b)
        if (x.isEmpty() || y.isEmpty()) return false
        return x == y || (minOf(x.length, y.length) >= 4 && (x.startsWith(y) || y.startsWith(x)))
    }
}
