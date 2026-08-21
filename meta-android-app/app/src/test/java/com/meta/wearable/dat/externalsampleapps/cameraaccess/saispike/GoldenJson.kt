/* sai-fi — voice concierge. */

// A JSON writer that preserves key order and formats exactly like `JSON.stringify(x, null, 2)`.
//
// `org.json` can do neither: `JSONObject` is backed by a HashMap, so the order keys were written in
// is lost, and its `toString(2)` indents differently. Both matter here — the golden files are
// committed and read as diffs, and a serializer that reshuffled keys would make every regeneration
// look like a change.
//
// The format is JS's because these files were first written by a TypeScript generator in cloud-api,
// and matching it byte for byte is what proves this Kotlin port produces the same fixtures that one
// did, rather than merely a self-consistent set of its own.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.saispike

import java.util.Locale
import org.json.JSONObject

/** A JSON value that remembers the order it was built in. */
sealed class Jv {
  data class Str(val v: String) : Jv()

  data class Num(val v: Long) : Jv()

  data class Bool(val v: Boolean) : Jv()

  object Nul : Jv()

  data class Arr(val v: List<Jv>) : Jv()

  data class Obj(val v: List<Pair<String, Jv>>) : Jv()
}

fun jstr(v: String): Jv = Jv.Str(v)

fun jnum(v: Long): Jv = Jv.Num(v)

fun jbool(v: Boolean): Jv = Jv.Bool(v)

fun jarr(vararg items: Jv): Jv = Jv.Arr(items.toList())

fun jarr(items: List<Jv>): Jv = Jv.Arr(items)

fun jobj(vararg entries: Pair<String, Jv>): Jv.Obj = Jv.Obj(entries.toList())

/** Build the org.json value the production helpers take. Key order is irrelevant to them. */
fun Jv.Obj.asJsonObject(): JSONObject = JSONObject(render(this))

/**
 * `JSON.stringify(value, null, 2)`.
 *
 * The escaping rules are JS's: the two structural characters, the five short escapes, and `\u00xx`
 * for anything else below 0x20. Everything from 0x20 up is emitted literally, including non-ASCII —
 * which is why the accent in "CÉ LA VI" survives into the fixtures instead of becoming an escape.
 */
fun render(value: Jv, indent: String = ""): String {
  val inner = "$indent  "
  return when (value) {
    is Jv.Str -> quote(value.v)
    is Jv.Num -> value.v.toString()
    is Jv.Bool -> value.v.toString()
    is Jv.Nul -> "null"
    is Jv.Arr ->
        if (value.v.isEmpty()) "[]"
        else value.v.joinToString(",\n", "[\n", "\n$indent]") { inner + render(it, inner) }
    is Jv.Obj ->
        if (value.v.isEmpty()) "{}"
        else
            value.v.joinToString(",\n", "{\n", "\n$indent}") { (k, v) ->
              "$inner${quote(k)}: ${render(v, inner)}"
            }
  }
}

/** The whole file: the array, then the trailing newline the TS generator wrote. */
fun renderFile(fixtures: List<Jv>): String = render(Jv.Arr(fixtures)) + "\n"

private fun quote(s: String): String {
  val sb = StringBuilder(s.length + 2)
  sb.append('"')
  for (c in s) {
    when (c) {
      '"' -> sb.append("\\\"")
      '\\' -> sb.append("\\\\")
      '\b' -> sb.append("\\b")
      '\u000C' -> sb.append("\\f")
      '\n' -> sb.append("\\n")
      '\r' -> sb.append("\\r")
      '\t' -> sb.append("\\t")
      else -> if (c < ' ') sb.append(String.format(Locale.ROOT, "\\u%04x", c.code)) else sb.append(c)
    }
  }
  sb.append('"')
  return sb.toString()
}
