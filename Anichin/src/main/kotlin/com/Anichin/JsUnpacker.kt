package com.Anichin

import java.util.regex.Pattern

object JsUnpacker {
    fun unpack(packedJS: String?): String? {
        if (packedJS == null) return null
        var js = packedJS
        val p = Pattern.compile("eval\\(function\\(p,a,c,k,e,d\\)\\{.*\\}\\)")
        val m = p.matcher(js)
        if (m.find()) {
            val packed = m.group(0)
            js = js.replace(packed, unPack(packed))
            return js
        }
        return null
    }

    private fun unPack(packedJS: String?): String {
        var packed = packedJS ?: return ""
        try {
            packed = packed.substring(packed.indexOf('}'))
            packed = packed.substring(packed.indexOf("return p}('") + 11)
            val encoded = packed.substring(0, packed.indexOf("',"))
            packed = packed.substring(packed.indexOf(",'") + 2)
            val base = packed.substring(0, packed.indexOf("',")).toInt()
            packed = packed.substring(packed.indexOf(",'") + 2)
            val count = packed.substring(0, packed.indexOf("','")).toInt()
            packed = packed.substring(packed.indexOf("'.split('|'),") + 13)
            val dictionary = packed.substring(0, packed.indexOf("))")).split("|".toRegex()).toTypedArray()

            return decode(encoded, base, count, dictionary)
        } catch (e: Exception) {
            return packedJS ?: ""
        }
    }

    private fun decode(encoded: String, base: Int, count: Int, dictionary: Array<String>): String {
        val sb = StringBuilder()
        val p = Pattern.compile("\\b\\w+\\b")
        val m = p.matcher(encoded)
        while (m.find()) {
            val key = m.group(0)
            val index = Integer.parseInt(key, base)
            var word = ""
            if (index < dictionary.size) {
                word = dictionary[index]
            }
            if (word.isEmpty()) word = key
            m.appendReplacement(sb, word)
        }
        m.appendTail(sb)
        return sb.toString()
    }
}
