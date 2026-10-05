package com.shihua66666.nuntra.core

/**
 * 文本匹配工具：关注人名、关键词、短信关键词共用一套语义。
 *
 * 统一规则（三处必须一致，否则用户会遇到「同一个词有时匹配有时不匹配」）：
 *  · 忽略大小写
 *  · 忽略首尾空白
 *  · 纯 ASCII 词要求分词边界；含中文的词不做边界要求
 *
 * 为什么中文不做边界：中文没有词边界，若强行要求边界，
 * 输入「快递」时「您有一份快递」会匹配不到，这显然不是用户期望。
 * 而英文要求边界可以避免 work 误命中 homework。
 */
object Keywords {

    /** 转义正则元字符，允许用户输入 ( ) [ ] . * + ? 等字符而不会崩。 */
    fun escape(raw: String): String {
        val sb = StringBuilder(raw.length * 2)
        for (c in raw) {
            when (c) {
                "\\", ".", "(", ")", "[", "]", "{", "}", "*", "+", "?", "^", "$", "|", "-" -> sb.append("\\").append(c)
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun isAsciiWordChar(c: Char): Boolean {
        val code = c.code
        return (code in 48..57) || (code in 65..90) || (code in 97..122) || code == 95
    }

    private val regexCache = HashMap<String, Regex>()

    /**
     * 编译关键词为正则。
     *
     * 带简单上限的缓存：关键词通常只有几十个，但每条通知都要匹配，
     * 重复编译正则会明显吃 CPU。
     */
    fun compile(keyword: String): Regex? {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return null
        regexCache[trimmed]?.let { return it }
        val body = escape(trimmed)
        val pureAsciiWord = trimmed.all { isAsciiWordChar(it) }
        val pattern = if (pureAsciiWord) {
            "(?<![A-Za-z0-9_])" + body + "(?![A-Za-z0-9_])"
        } else {
            body
        }
        val regex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull() ?: return null
        if (regexCache.size > CACHE_LIMIT) regexCache.clear()
        regexCache[trimmed] = regex
        return regex
    }

    /** 文本是否命中关键词。 */
    fun hit(text: String?, keyword: String): Boolean {
        if (text.isNullOrEmpty()) return false
        val regex = compile(keyword) ?: return false
        return regex.containsMatchIn(text)
    }

    /** 返回 haystacks 中命中的全部关键词（保持输入顺序）。 */
    fun hits(haystacks: List<String?>, keywords: Collection<String>): List<String> {
        if (keywords.isEmpty()) return emptyList()
        val joined = haystacks.filterNotNull().filter { it.isNotEmpty() }
        if (joined.isEmpty()) return emptyList()
        return keywords.filter { keyword -> joined.any { hit(it, keyword) } }
    }

    /** 关注人匹配：名字在任一文本片段中出现即算命中（忽略大小写与首尾空白）。 */
    fun contactHit(haystacks: List<String?>, contactName: String): Boolean {
        val name = contactName.trim()
        if (name.isEmpty()) return false
        val needle = name.lowercase()
        return haystacks.filterNotNull().any { it.lowercase().contains(needle) }
    }

    private const val CACHE_LIMIT = 200
}
