package kz.ordaops.android.feature.operator.knowledge

import androidx.compose.runtime.Immutable

/**
 * HTML базы знаний в читаемые блоки — порт RichText.swift.
 *
 * Статьи в вебе хранятся размеченными (редактор TipTap), и показывать их «как
 * есть» нельзя. Разбираем вручную, регулярками: разметка простая и
 * предсказуемая (абзацы, списки, таблицы), а `Html.fromHtml` не умеет таблицы.
 */
object RichText {

    enum class Kind { Paragraph, Heading, ListItem, Quote, TableRow, TableHeader, Image }

    /** Блок статьи: список рисуем маркерами, цитату — отступом. */
    @Immutable
    data class Block(val kind: Kind, val text: String, val cells: List<String> = emptyList())

    private const val HEADING = '\u0001'
    private const val ROW = '\u0002'
    private const val CELL = '\u0003'
    private const val HEAD_CELL = '\u0005'
    private const val BULLET = '•'
    private const val QUOTE = '“'
    private const val IMAGE = '\u0006'

    private val opt = setOf(RegexOption.IGNORE_CASE)

    private fun String.sub(pattern: String, replacement: String) = replace(Regex(pattern, opt), replacement)

    /** Разобрать HTML в блоки. Нет текста — пустой список. Тяжёлое: звать вне главного потока и кэшировать. */
    fun blocks(html: String?): List<Block> {
        if (html.isNullOrEmpty()) return emptyList()

        // Таблицы — первыми и целиком: у них своя разметка, общая чистка тегов склеила бы ячейки.
        // Картинки — отдельной строкой с адресом: на экране их рисует Coil.
        var working = extractTables(html.sub("""<img[^>]*?src\s*=\s*["']([^"']+)["'][^>]*>""", "\n$IMAGE\$1\n"))
        working = working
            .sub("""<br\s*/?>""", "\n")
            .sub("""</(p|div|h[1-6]|li|blockquote)>""", "\n")
            .sub("""<li[^>]*>""", "$BULLET ")
            .sub("""<blockquote[^>]*>""", "$QUOTE")
            .sub("""<h[1-6][^>]*>""", "$HEADING")

        // Остальные теги убираем целиком.
        working = decodeEntities(working.replace(Regex("<[^>]+>"), ""))

        return working
            .split("\n")
            .map { it.trimWs() }
            .filter { it.isNotEmpty() }
            .map { line ->
                when {
                    line.startsWith(ROW) -> {
                        val cells = tableCells(line)
                        // Строка с ячейками `th` — это шапка, где бы она ни лежала.
                        val isHeader = line.contains(HEAD_CELL)
                        Block(
                            if (isHeader) Kind.TableHeader else Kind.TableRow,
                            cells.joinToString(if (isHeader) " · " else " — "),
                            cells,
                        )
                    }
                    line.startsWith(IMAGE) -> {
                        val url = line.drop(1).trimWs()
                        // Относительный адрес без сайта не загрузить — такую картинку пропускаем.
                        if (url.startsWith("http")) Block(Kind.Image, url) else Block(Kind.Paragraph, "")
                    }
                    line.startsWith(HEADING) -> Block(Kind.Heading, line.drop(1).trimWs())
                    line.startsWith(BULLET) -> Block(Kind.ListItem, line.drop(1).trimWs())
                    line.startsWith(QUOTE) -> Block(Kind.Quote, line.drop(1).trimWs())
                    else -> Block(Kind.Paragraph, line)
                }
            }
            .filter { it.text.isNotEmpty() }
    }

    /**
     * Пометить строки таблицы служебными знаками. Шапку узнаём по самим ячейкам
     * (`th`), а не по `<thead>`: редакторы сплошь и рядом кладут `th` прямо в `tbody`.
     */
    private fun extractTables(html: String): String = html
        // `th` должен кончаться именем тега, иначе съест и `<thead>`.
        .sub("""<th(\s[^>]*)?>""", "$HEAD_CELL")
        .sub("""<td[^>]*>""", "$CELL")
        .sub("""</t[dh]>""", "")
        .sub("""<tr[^>]*>""", "\n$ROW")
        .sub("""</tr>""", "\n")
        .sub("""</?(table|thead|tbody)[^>]*>""", "\n")

    private fun tableCells(line: String): List<String> =
        line.drop(1).split(CELL, HEAD_CELL).map { it.trimWs() }.filter { it.isNotEmpty() }

    private fun String.trimWs() = trim { it.isWhitespace() || it == ' ' }

    /** Короткий список сущностей намеренно: редактор выдаёт ограниченный набор. */
    private fun decodeEntities(text: String): String {
        var result = text
        for ((entity, ch) in listOf(
            "&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"",
            "&#39;" to "'", "&apos;" to "'", "&mdash;" to "—", "&ndash;" to "–",
            "&laquo;" to "«", "&raquo;" to "»", "&hellip;" to "…",
        )) result = result.replace(entity, ch)
        return result
    }
}
