package app.dim.data.report

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.OutputStream
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Draws a [Report] as a print-friendly PDF with Android's built-in [PdfDocument]. Every page is A4: the summary is portrait, the charts and
 * the wide episode table are landscape. White background, dark text, tinted tables and charts that still read in black and white.
 * Runs on the calling thread, so call it off the main thread.
 */
object ReportPdf {
    const val A4_SHORT = 595   // A4 in PostScript points
    const val A4_LONG = 842

    const val DISCLAIMER = "This report contains self-reported tracking data from the Dim app and is intended to support clinical review. " +
        "It does not establish a diagnosis."

    /** Writes the PDF to [out] (flushed, not closed). Throws if the document can't be produced. */
    fun write(report: Report, out: OutputStream) {
        val pages = ReportLayout(report, null, 0).run()   // pass 1: only counts the pages, so every footer can say "of N"
        val doc = PdfDocument()
        try {
            ReportLayout(report, doc, pages).run()
            doc.writeTo(out)
            out.flush()
        } finally {
            doc.close()
        }
    }
}

private const val WHITE = 0xFFFFFFFF.toInt()
private const val INK = 0xFF1B1B21.toInt()
private const val MUTED = 0xFF62636C.toInt()
private const val RULE = 0xFFC9CAD2.toInt()
private const val GRID = 0xFFE4E5EA.toInt()
private const val ZEBRA = 0xFFF8F8FB.toInt()
private const val HEAD = 0xFF3B3270.toInt()
private const val ACCENT = 0xFF5B4BA8.toInt()
private const val ACCENT2 = 0xFF8A7BD0.toInt()
private const val TINT = 0xFFEFEDF8.toInt()
private const val TINT2 = 0xFFE2DEF3.toInt()
private const val WARM = 0xFFD9822B.toInt()
private const val TEAL = 0xFF2F8F83.toInt()
// Pain ramp: one hue, getting darker with severity, so it also reads on a black-and-white printer.
private const val P_NONE = 0xFFEDEDF2.toInt()
private const val P_NOSCORE = 0xFFB9B9C9.toInt()
private const val P_MILD = 0xFFCBC4EC.toInt()
private const val P_MOD = 0xFF9D90D8.toInt()
private const val P_SEV = 0xFF6B59B9.toInt()
private const val P_WORST = 0xFF372B7E.toInt()

/** Soft version of [painTint] for table cells, so the dark text on top stays easy to read. */
private fun painCell(p: Int): Int = when { p <= 3 -> 0xFFE9E6F7.toInt(); p <= 6 -> 0xFFD0C9ED.toInt(); p <= 8 -> 0xFFB1A5E0.toInt(); else -> 0xFF9686D3.toInt() }

private fun painTint(p: Int?): Int = when { p == null -> P_NOSCORE; p <= 3 -> P_MILD; p <= 6 -> P_MOD; p <= 8 -> P_SEV; else -> P_WORST }

private class Col(val title: String, val w: Float, val right: Boolean = false)
private class Tile(val label: String, val value: String, val sub: String, val big: Boolean, val accent: Int)
private class Area(val l: Float, val t: Float, val r: Float, val b: Float) { val w get() = r - l; val h get() = b - t }
private class Pt(val x: Double, val y: Double?)

/** One layout run. With [doc] == null nothing is drawn and only the page count is worked out. */
private class ReportLayout(private val r: Report, private val doc: PdfDocument?, private val total: Int) {
    private var landscape = true
    private val pw get() = if (landscape) ReportPdf.A4_LONG.toFloat() else ReportPdf.A4_SHORT.toFloat()
    private val ph get() = if (landscape) ReportPdf.A4_SHORT.toFloat() else ReportPdf.A4_LONG.toFloat()
    private val left get() = if (landscape) 30f else 38f
    private val right get() = pw - left
    private val width get() = right - left
    private val top get() = if (pageNo == 1) 34f else 50f
    private val footerTop get() = ph - 40f
    private val bottom get() = footerTop - 8f

    private var page: PdfDocument.Page? = null
    private var canvas: Canvas? = null
    private var pageNo = 0
    private var y = 0f

    private fun paint(size: Float, bold: Boolean = false, italic: Boolean = false, color: Int = INK) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color
        typeface = Typeface.create(Typeface.SANS_SERIF, when { bold && italic -> Typeface.BOLD_ITALIC; bold -> Typeface.BOLD; italic -> Typeface.ITALIC; else -> Typeface.NORMAL })
    }
    private val pTitle = paint(22f, bold = true, color = HEAD)
    private val pSub = paint(9.5f, color = MUTED)
    private val pPage = paint(16f, bold = true, color = HEAD)
    private val pHead = paint(12f, bold = true, color = HEAD)
    private val pSubHead = paint(9.5f, bold = true)
    private val pBody = paint(9f)
    private val pBold = paint(9f, bold = true)
    private val pLabel = paint(7.5f, bold = true, color = MUTED)
    private val pSmall = paint(8f, color = MUTED)
    private val pItalic = paint(8f, italic = true, color = MUTED)
    private val pFoot = paint(7.5f, color = MUTED)
    private val pCard = paint(9f, bold = true)
    private val pAxis = paint(7f, color = MUTED)
    private val tmp = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun lh(p: Paint) = p.textSize * 1.36f
    private fun baseline(p: Paint, at: Float = y) = at + p.textSize

    // ───────────────────────── document flow ─────────────────────────

    fun run(): Int {
        newPage(true)
        cover()
        highlights()
        summaryColumns()
        r.health?.takeIf { !it.hasData }?.let { h -> heading("Health Connect summary", 30f); h.notes.forEach { para(it, pItalic, after = 2f) } }
        if (!r.isEmpty) { trendsPage(); patternsPage() }
        r.health?.takeIf { it.hasData }?.let { healthPage(it) }
        if (!r.isEmpty) logPage()
        endPage()
        return pageNo
    }

    /** Starts the next page, in the same orientation unless [landscapePage] says otherwise. [title] is a page heading for a new section. */
    private fun newPage(landscapePage: Boolean = landscape, title: String? = null, subtitle: String? = null) {
        endPage()
        pageNo++
        landscape = landscapePage
        if (doc != null) {
            val p = doc.startPage(PdfDocument.PageInfo.Builder(pw.toInt(), ph.toInt(), pageNo).create())
            page = p; canvas = p.canvas
        }
        y = top
        if (pageNo > 1) {   // running header after the first page
            val who = r.patientName?.let { "$it · " } ?: ""
            txt("Dim migraine report · $who${r.periodLabel}", left, 30f, pSmall, width)
            ln(left, 36f, right, 36f, RULE, 0.7f)
        }
        if (title != null) {
            txt(title, left, baseline(pPage), pPage); y += lh(pPage)
            if (subtitle != null) { txt(subtitle, left, baseline(pSmall), pSmall); y += lh(pSmall) }
            y += 6f
        }
    }

    private fun endPage() {
        val c = canvas ?: return
        ln(left, footerTop, right, footerTop, RULE, 0.7f)
        var fy = footerTop + 11f
        for (l in wrap(ReportPdf.DISCLAIMER, pFoot, width - 90f)) { c.drawText(l, left, fy, pFoot); fy += lh(pFoot) }
        val num = "Page $pageNo of $total"
        c.drawText(num, right - pFoot.measureText(num), footerTop + 11f, pFoot)
        page?.let { doc?.finishPage(it) }; page = null; canvas = null
    }

    /** Starts a new page unless [h] points still fit. */
    private fun ensure(h: Float) { if (y + h > bottom && y > top + 0.5f) newPage() }

    // ───────────────────────── drawing primitives ─────────────────────────

    private fun box(x: Float, yy: Float, w: Float, h: Float, fill: Int? = null, stroke: Int? = null, rad: Float = 0f, sw: Float = 0.7f) {
        val c = canvas ?: return
        val rf = RectF(x, yy, x + w, yy + h)
        if (fill != null) { tmp.style = Paint.Style.FILL; tmp.color = fill; if (rad > 0f) c.drawRoundRect(rf, rad, rad, tmp) else c.drawRect(rf, tmp) }
        if (stroke != null) { tmp.style = Paint.Style.STROKE; tmp.strokeWidth = sw; tmp.color = stroke; if (rad > 0f) c.drawRoundRect(rf, rad, rad, tmp) else c.drawRect(rf, tmp) }
    }
    private fun ln(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, sw: Float = 0.7f) {
        val c = canvas ?: return
        tmp.style = Paint.Style.STROKE; tmp.strokeWidth = sw; tmp.color = color; c.drawLine(x1, y1, x2, y2, tmp)
    }
    private fun dashed(x1: Float, yy: Float, x2: Float, color: Int) {
        var x = x1; while (x < x2) { ln(x, yy, minOf(x + 3f, x2), yy, color, 0.8f); x += 6f }
    }
    private fun dot(cx: Float, cy: Float, rad: Float, color: Int) {
        val c = canvas ?: return
        tmp.style = Paint.Style.FILL; tmp.color = color; c.drawCircle(cx, cy, rad, tmp)
    }
    private fun txt(s: String, x: Float, base: Float, p: Paint, maxWidth: Float? = null) {
        val c = canvas ?: return
        var t = s
        if (maxWidth != null && p.measureText(t) > maxWidth) {
            val n = p.breakText(t, true, (maxWidth - p.measureText("…")).coerceAtLeast(1f), null); t = t.take(n).trimEnd() + "…"
        }
        c.drawText(t, x, base, p)
    }
    private fun txtR(s: String, xRight: Float, base: Float, p: Paint) { canvas?.drawText(s, xRight - p.measureText(s), base, p) }
    private fun txtC(s: String, cx: Float, base: Float, p: Paint) { canvas?.drawText(s, cx - p.measureText(s) / 2f, base, p) }

    /** Greedy word wrap on [p]'s metrics. Keeps explicit line breaks and splits words that are wider than a line. */
    fun wrap(s: String, p: Paint, w: Float): List<String> {
        val out = ArrayList<String>()
        for (para in s.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            val clean = para.replace('\t', ' ').filter { !it.isISOControl() }.trim()
            if (clean.isEmpty()) { if (out.isNotEmpty() && out.last().isNotEmpty()) out.add(""); continue }
            var cur = ""
            for (word in clean.split(Regex(" +"))) {
                var wd = word
                while (p.measureText(wd) > w) {
                    var k = maxOf(1, p.breakText(wd, true, w.coerceAtLeast(1f), null))
                    if (k < wd.length && k > 1 && Character.isHighSurrogate(wd[k - 1])) k--   // never cut an emoji in half
                    if (cur.isNotEmpty()) { out.add(cur); cur = "" }
                    out.add(wd.substring(0, k)); wd = wd.substring(k)
                }
                if (wd.isEmpty()) continue
                val cand = if (cur.isEmpty()) wd else "$cur $wd"
                if (p.measureText(cand) <= w) cur = cand else { out.add(cur); cur = wd }
            }
            if (cur.isNotEmpty()) out.add(cur)
        }
        while (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.size - 1)
        return if (out.isEmpty()) listOf("") else out
    }

    private fun heading(s: String, keep: Float = 60f) {
        ensure(lh(pHead) + 8f + keep)   // never leave a heading alone at the bottom of a page
        y += 8f
        headingAt(s, left, width)
    }
    /** Section heading at an explicit column (no page-break logic). */
    private fun headingAt(s: String, x: Float, w: Float) {
        box(x, y + 1f, 3.5f, pHead.textSize, ACCENT)
        txt(s, x + 9f, baseline(pHead), pHead, w - 9f)
        y += lh(pHead) + 1f
        ln(x, y, x + w, y, RULE, 0.7f); y += 6f
    }
    private fun subHeadingAt(s: String, x: Float, w: Float) { txt(s, x, baseline(pSubHead), pSubHead, w); y += lh(pSubHead) + 2f }
    private fun paraAt(s: String, x: Float, w: Float, p: Paint = pItalic, after: Float = 4f) {
        for (l in wrap(s, p, w)) { txt(l, x, baseline(p), p); y += lh(p) }
        y += after
    }

    private fun subHeading(s: String) { ensure(lh(pSubHead) + 30f); txt(s, left, baseline(pSubHead), pSubHead); y += lh(pSubHead) + 2f }

    private fun para(s: String, p: Paint = pBody, x: Float = left, w: Float = width, after: Float = 4f) {
        for (l in wrap(s, p, w)) { ensure(lh(p)); txt(l, x, baseline(p), p); y += lh(p) }
        y += after
    }

    // ───────────────────────── tables ─────────────────────────

    /**
     * Header row repeated on every page, zebra rows, optional per-cell fill and a full-width note line under a row. A row (with the start of its
     * note) is never split across pages. Column widths are scaled to fill the page width.
     */
    private fun table(cols: List<Col>, rows: List<List<String>>, size: Float = 8.5f, fills: ((Int, Int) -> Int?)? = null,
                      notes: List<String?>? = null, boldFirst: Boolean = false, padX: Float = 4f,
                      x0: Float = left, tw: Float = width, stopAt: Float? = null): Int {
        val k = tw / cols.sumOf { it.w.toDouble() }.toFloat()
        val ws = cols.map { it.w * k }
        val xs = ArrayList<Float>(); var acc = x0; ws.forEach { xs.add(acc); acc += it }
        val pCell = paint(size); val pCellBold = paint(size, bold = true)
        val pHeadT = paint(size - 0.5f, bold = true, color = HEAD); val pNote = paint(size - 0.5f, italic = true, color = MUTED)
        val padY = 2.6f
        val headLines = cols.mapIndexed { i, c -> wrap(c.title, pHeadT, ws[i] - 2 * padX) }
        val headH = headLines.maxOf { it.size } * lh(pHeadT) + 2 * padY + 1f

        fun drawHead() {
            box(x0, y, tw, headH, TINT2)
            cols.indices.forEach { i ->
                headLines[i].forEachIndexed { n, l ->
                    val b = baseline(pHeadT, y + padY + n * lh(pHeadT))
                    if (cols[i].right) txtR(l, xs[i] + ws[i] - padX, b, pHeadT) else txt(l, xs[i] + padX, b, pHeadT)
                }
            }
            ln(x0, y + headH, x0 + tw, y + headH, ACCENT, 1f)
            y += headH
        }
        /** False when [stopAt] is set and [h] would cross it; otherwise starts a new page (and repeats the header) when needed. */
        fun need(h: Float): Boolean {
            if (stopAt != null) return y + h <= stopAt
            if (y + h > bottom && y > top + 0.5f) { newPage(); drawHead() }
            return true
        }

        if (rows.isEmpty()) return 0
        // A single cell can never be taller than one page (only absurdly long free text could); it is cut with "…" rather than overflow the page.
        val maxCellLines = ((bottom - 50f - headH - 4 * lh(pNote) - 2 * padY) / lh(pCell)).toInt().coerceAtLeast(3)
        fun cellLines(i: Int) = cols.indices.map { c ->
            val l = wrap(rows[i].getOrElse(c) { "" }, if (boldFirst && c == 0) pCellBold else pCell, ws[c] - 2 * padX)
            if (l.size <= maxCellLines) l else l.take(maxCellLines - 1) + (l[maxCellLines - 1].trimEnd().dropLast(1) + "…")
        }
        val firstH = cellLines(0).maxOf { it.size } * lh(pCell) + 2 * padY
        if (stopAt != null) { if (y + headH + firstH > stopAt) return 0 }
        else if (y + headH + firstH > bottom && y > top + 0.5f) newPage()
        drawHead()

        for (i in rows.indices) {
            val cells = cellLines(i)
            val rh = cells.maxOf { it.size } * lh(pCell) + 2 * padY
            val noteLines = notes?.getOrNull(i)?.let { wrap("Note: $it", pNote, tw - 2 * padX) } ?: emptyList()
            val head = noteLines.take(2); val rest = noteLines.drop(2)
            val blockH = rh + head.size * lh(pNote) + if (head.isNotEmpty()) padY else 0f
            if (!need(blockH)) return i
            if (i % 2 == 1) box(x0, y, tw, blockH, ZEBRA)
            cols.indices.forEach { c ->
                fills?.invoke(i, c)?.let { box(xs[c], y, ws[c], rh, it) }
                cells[c].forEachIndexed { n, l ->
                    val b = baseline(pCell, y + padY + n * lh(pCell)); val p = if (boldFirst && c == 0) pCellBold else pCell
                    if (cols[c].right) txtR(l, xs[c] + ws[c] - padX, b, p) else txt(l, xs[c] + padX, b, p)
                }
            }
            head.forEachIndexed { n, l -> txt(l, x0 + padX, baseline(pNote, y + rh + n * lh(pNote)), pNote) }
            y += blockH
            ln(x0, y, x0 + tw, y, GRID, 0.5f)
            for (l in rest) {   // a very long note carries on over the next lines and pages
                if (!need(lh(pNote) + 1f)) break
                if (i % 2 == 1) box(x0, y, tw, lh(pNote) + 1f, ZEBRA)
                txt(l, x0 + padX, baseline(pNote, y), pNote); y += lh(pNote) + 1f
            }
        }
        y += 6f
        return rows.size
    }

    // ───────────────────────── page 1: summary ─────────────────────────

    private val pCol = paint(8.5f)

    private fun cover() {
        val bandH = 50f
        box(left, y, width, bandH, TINT)
        box(left, y, 5f, bandH, ACCENT)
        txt("Migraine Report", left + 16f, y + 25f, pTitle)
        txt("Doctor-ready summary of self-reported tracking data", left + 16f, y + 40f, pSub)
        val cells = buildList {
            r.patientName?.let { add(Triple("PATIENT", it, 118f)) }
            add(Triple("REPORTING PERIOD", r.periodLabel, 236f)); add(Triple("REPORT GENERATED", r.generatedLabel, 136f))
        }
        var x = right - cells.sumOf { it.third.toDouble() }.toFloat() - 4f
        cells.forEach { (l, v, w) -> txt(l, x, y + 19f, pLabel, w - 10f); txt(v, x, y + 33f, pBold, w - 10f); x += w }
        y += bandH + 6f
        if (r.isEmpty) para("No episodes were recorded in this period.", paint(10f, italic = true, color = MUTED), after = 2f)
    }

    private fun tiles(items: List<Tile>) {
        val gap = 6f; val h = 58f
        val w = (width - gap * (items.size - 1)) / items.size
        ensure(h)
        items.forEachIndexed { i, t ->
            val x = left + i * (w + gap)
            box(x, y, w, h, TINT)
            box(x, y, 3.5f, h, t.accent)
            txt(t.label, x + 10f, y + 13f, pLabel, w - 14f)
            if (t.big) txt(t.value, x + 10f, y + 35f, paint(17f, bold = true, color = HEAD), w - 14f)
            else {
                val p = paint(9.5f, bold = true, color = HEAD)
                wrap(t.value, p, w - 14f).take(2).forEachIndexed { n, l -> txt(l, x + 10f, y + 26f + n * 11.5f, p, w - 14f) }
            }
            txt(t.sub, x + 10f, y + h - 6f, paint(7.5f, color = MUTED), w - 14f)
        }
        y += h + 5f
    }

    private fun highlights() {
        val o = r.overview; val p = r.patterns; val n = o.totalEpisodes
        heading("At a glance", 70f)
        fun item(l: PatternList): Pair<String, String> = l.items.firstOrNull()?.let { it.name to "${it.count} of $n episodes" } ?: (NOT_RECORDED to "")
        val s = item(p.symptoms); val t = item(p.triggers); val l = item(p.locations)
        tiles(listOf(
            Tile("EPISODES", "$n", if (o.ongoingEpisodes > 0) "${o.ongoingEpisodes} ongoing" else "in this period", true, ACCENT),
            Tile("MIGRAINE DAYS", "${o.migraineDays}", "of ${ChronoUnit.DAYS.between(r.charts.spanFrom, r.charts.spanTo) + 1} days", true, ACCENT),
            Tile("AVG PAIN", o.avgPain?.let { fmt1(it) + " / 10" } ?: NOT_RECORDED, "${o.painRecords} of $n rated", o.avgPain != null, ACCENT),
            Tile("AVG DURATION", o.avgDurationMin?.let { ReportBuilder.duration(it) } ?: NOT_RECORDED, "${o.durationRecords} of $n completed", o.avgDurationMin != null, ACCENT),
            Tile("TOP SYMPTOM", s.first, s.second, false, ACCENT2),
            Tile("TOP TRIGGER", t.first, t.second, false, ACCENT2),
            Tile("TOP LOCATION", l.first, l.second, false, ACCENT2),
            Tile("HIGHEST PAIN", o.maxPain?.let { "$it / 10" } ?: NOT_RECORDED, o.maxPainDate ?: "", o.maxPain != null, ACCENT2),
        ))
        para("Top items are the most frequently recorded observations in this period. They do not show that anything caused an episode.", pItalic, after = 0f)
    }

    private fun fmt1(d: Double) = String.format(Locale.ENGLISH, "%.1f", d)

    private fun tied(counts: List<Count>): Pair<String, Int>? {
        val m = counts.maxOfOrNull { it.count } ?: return null
        if (m == 0) return null
        val best = counts.filter { it.count == m }
        return (if (best.size > 2) "No clear peak" else best.joinToString(", ") { it.name }) to m
    }

    /**
     * Three side-by-side blocks under the tiles: patient information, migraine overview, medication. Anything that does not fit above the footer
     * (long medicine lists, long profile notes) continues on the next page instead of being cut off.
     */
    private fun summaryColumns() {
        val blocks = buildList { if (r.profile != null) add(0); add(1); if (r.medications != null) add(2) }
        val weights = mapOf(0 to 0.285f, 1 to 0.385f, 2 to 0.33f)
        val gap = 14f
        val avail = width - gap * (blocks.size - 1)
        val wsum = blocks.sumOf { weights.getValue(it).toDouble() }.toFloat()
        val y0 = y + 6f
        var maxY = y0; var x = left
        val overflow = ArrayList<() -> Unit>()
        blocks.forEach { b ->
            val w = avail * weights.getValue(b) / wsum
            y = y0
            when (b) { 0 -> patientColumn(r.profile!!, x, w, overflow); 1 -> overviewColumn(x, w); else -> medicationColumn(r.medications!!, x, w, overflow) }
            maxY = maxOf(maxY, y); x += w + gap
        }
        y = maxY
        if (overflow.isNotEmpty()) { newPage(true, "Summary (continued)"); overflow.forEach { it() } }
    }

    /** Label / value rows with zebra shading. Returns the rows that did not fit above the footer. */
    private fun kvRows(x: Float, w: Float, rows: List<Pair<String, String>>, labelW: Float = 92f, truncate: Boolean = false): List<Pair<String, String>> {
        val padX = 5f; val padY = 2.4f
        rows.forEachIndexed { i, (k, v) ->
            var lines = wrap(v, pCol, w - labelW - 2 * padX)
            var h = lines.size * lh(pCol) + 2 * padY
            if (y + h > bottom) {
                if (!truncate) return rows.drop(i)
                val fit = ((bottom - y - 2 * padY) / lh(pCol)).toInt().coerceAtLeast(1)
                lines = lines.take(fit - 1) + (lines[fit - 1].trimEnd().dropLast(1) + "…"); h = lines.size * lh(pCol) + 2 * padY
            }
            if (i % 2 == 0) box(x, y, w, h, ZEBRA)
            txt(k, x + padX, baseline(pLabel, y + padY + 1f), pLabel, labelW - 4f)
            lines.forEachIndexed { n, l -> txt(l, x + padX + labelW, baseline(pCol, y + padY + n * lh(pCol)), pCol) }
            y += h
        }
        ln(x, y, x + w, y, RULE, 0.7f)
        return emptyList()
    }

    private fun patientColumn(p: ProfileSection, x: Float, w: Float, overflow: MutableList<() -> Unit>) {
        headingAt("Patient information", x, w)
        if (!p.hasAnyEntry) paraAt("No profile information has been entered in the app.", x, w)
        val rest = kvRows(x, w, p.rows)
        y += 4f
        if (rest.isNotEmpty()) overflow.add { headingAt("Patient information (continued)", left, width); kvRows(left, width, rest, 110f, truncate = true) }
    }

    private fun overviewColumn(x: Float, w: Float) {
        val o = r.overview; val n = o.totalEpisodes
        headingAt("Migraine overview", x, w)
        val days = ChronoUnit.DAYS.between(r.charts.spanFrom, r.charts.spanTo) + 1
        val wk = tied(r.charts.weekday); val tod = tied(r.charts.timeOfDay)
        fun eps(c: Int, each: Boolean) = "$c ${if (c == 1) "episode" else "episodes"}" + if (each) " each" else ""
        fun tie(t: Pair<String, Int>?) = t != null && (t.first.contains(",") || t.first == "No clear peak")
        table(listOf(Col("Measure", 118f), Col("Value", 92f), Col("Based on", 92f)), listOf(
            listOf("Reporting period", r.periodLabel.substringBefore(" ("), r.periodLabel.substringAfter("(", "").removeSuffix(")")),
            listOf("Total recorded episodes", "$n", if (o.ongoingEpisodes > 0) "${o.ongoingEpisodes} ongoing" else ""),
            listOf("Distinct migraine days", "${o.migraineDays}", "of $days days"),
            listOf("Average recorded pain score", o.avgPain?.let { fmt1(it) + " / 10" } ?: NOT_RECORDED, "${o.painRecords} of $n rated"),
            listOf("Highest recorded pain", o.maxPain?.let { "$it / 10" } ?: NOT_RECORDED, o.maxPainDate ?: ""),
            listOf("Average duration (completed)", o.avgDurationMin?.let { ReportBuilder.duration(it) } ?: NOT_RECORDED, "${o.durationRecords} of $n completed"),
            listOf("Longest completed episode", o.longestMin?.let { ReportBuilder.duration(it) } ?: NOT_RECORDED, o.longestDate ?: ""),
            listOf("Most frequent weekday", wk?.first ?: NOT_RECORDED, wk?.let { eps(it.second, tie(wk)) } ?: ""),
            listOf("Most frequent start time", tod?.first ?: NOT_RECORDED, tod?.let { eps(it.second, tie(tod)) } ?: ""),
        ), size = 8f, boldFirst = true, x0 = x, tw = w, stopAt = bottom)
        paraAt("Migraine days are counted by the day an episode started. Ongoing episodes have no end time and are left out of the duration figures.", x, w, pItalic, 0f)
    }

    private fun medicationColumn(m: MedicationSection, x: Float, w: Float, overflow: MutableList<() -> Unit>) {
        headingAt("Medication", x, w)
        subHeadingAt("Current medication list", x, w)
        val cur = m.routine.map { listOf(it.name, "Routine", it.time) } + m.occasional.map { listOf(it, "Occasional", "—") }
        val curCols = listOf(Col("Medicine", 100f), Col("List", 56f), Col("Scheduled", 58f))
        if (cur.isEmpty()) paraAt("No medicines are listed in the app.", x, w, pItalic, 2f)
        else {
            val done = table(curCols, cur, size = 8f, boldFirst = true, x0 = x, tw = w, stopAt = bottom - 70f)
            if (done < cur.size) { val rest = cur.drop(done); overflow.add { subHeading("Current medication list (continued)"); table(curCols, rest, boldFirst = true) } }
        }
        paraAt("As entered in the app. Doses and dosing frequency are not recorded in Dim.", x, w, pItalic, 6f)

        val n = r.overview.totalEpisodes
        val epCols = listOf(Col("Medicine", 100f), Col("Episodes", 56f), Col("Share", 58f))
        val ep = m.duringEpisodes.map { listOf(it.name, "${it.count}", "${Math.round(it.count * 100.0 / n.coerceAtLeast(1))}%") }
        if (y + 60f > bottom) {   // no room left in this column: the whole block moves to the next page
            overflow.add { subHeading("Medicines recorded during episodes in this period"); if (ep.isEmpty()) para(NOT_RECORDED) else table(epCols, ep, boldFirst = true) }
            return
        }
        subHeadingAt("Recorded during episodes in this period", x, w)
        if (ep.isEmpty()) paraAt(NOT_RECORDED, x, w, pBody, 2f)
        else {
            val done = table(epCols, ep, size = 8f, boldFirst = true, x0 = x, tw = w, stopAt = bottom)
            if (done < ep.size) { val rest = ep.drop(done); overflow.add { subHeading("Recorded during episodes (continued)"); table(epCols, rest, boldFirst = true) } }
        }
        paraAt("The first list is the app's current list; the second is what was logged on individual episodes and may differ.", x, w, pItalic, 0f)
    }

    // ───────────────────────── chart building blocks ─────────────────────────

    private fun card(x: Float, yy: Float, w: Float, h: Float, title: String, sub: String? = null): Area {
        box(x, yy, w, h, WHITE, RULE, rad = 6f)
        txt(title, x + 10f, yy + 15f, pCard, w - 20f)
        if (sub != null) txt(sub, x + 10f, yy + 26f, pAxis, w - 20f)
        return Area(x + 10f, yy + if (sub != null) 34f else 24f, x + w - 10f, yy + h - 8f)
    }

    private fun noData(a: Area, msg: String = "No data recorded") { txtC(msg, (a.l + a.r) / 2f, (a.t + a.b) / 2f, pItalic) }

    private fun niceStep(raw: Double): Double {
        if (raw <= 0.0) return 1.0
        val pow = Math.pow(10.0, Math.floor(Math.log10(raw))); val f = raw / pow
        return pow * when { f <= 1.0 -> 1.0; f <= 2.0 -> 2.0; f <= 5.0 -> 5.0; else -> 10.0 }
    }

    private fun axisFmt(v: Double): String = when { v >= 10_000 -> "${Math.round(v / 1000)}k"; v >= 1000 && v % 1000 == 0.0 -> "${(v / 1000).toInt()}k"; v % 1.0 == 0.0 -> "${v.toInt()}"; else -> fmt1(v) }

    private fun barChart(x: Float, yy: Float, w: Float, h: Float, title: String, sub: String?, labels: List<String>, values: List<Int>,
                         color: Int = ACCENT, yFmt: (Double) -> String = ::axisFmt, showValues: Boolean = true) {
        val a = card(x, yy, w, h, title, sub)
        if (canvas == null) return
        if (values.isEmpty() || values.all { it == 0 }) { noData(a); return }
        val maxV = values.max().toDouble()
        val step = niceStep(maxV / 4.0).coerceAtLeast(1.0)
        val hi = Math.ceil(maxV / step) * step
        val pl = a.l + 26f; val pr = a.r; val pt = a.t + 6f; val pb = a.b - 13f
        fun yOf(v: Double) = (pb - (pb - pt) * (v / hi)).toFloat()
        var g = 0.0
        while (g <= hi + 0.001) { ln(pl, yOf(g), pr, yOf(g), if (g == 0.0) RULE else GRID, 0.6f); txtR(yFmt(g), pl - 4f, yOf(g) + 2.5f, pAxis); g += step }
        val n = values.size; val slot = (pr - pl) / n; val bw = (slot * 0.62f).coerceIn(1.5f, 30f)
        values.forEachIndexed { i, v ->
            if (v <= 0) return@forEachIndexed
            val bx = pl + slot * i + (slot - bw) / 2f
            box(bx, yOf(v.toDouble()), bw, pb - yOf(v.toDouble()), color)
            if (showValues && slot >= 15f) txtC(yFmt(v.toDouble()), bx + bw / 2f, yOf(v.toDouble()) - 2.5f, paint(6.5f, color = INK))
        }
        val maxLab = labels.maxOfOrNull { pAxis.measureText(it) } ?: 0f
        val every = Math.ceil(((maxLab + 6f) / slot).toDouble()).toInt().coerceAtLeast(1)
        labels.forEachIndexed { i, l -> if (i % every == 0) txtC(l, pl + slot * i + slot / 2f, pb + 9.5f, pAxis) }
    }

    private fun lineChart(x: Float, yy: Float, w: Float, h: Float, title: String, sub: String?, pts: List<Pt>,
                          xMin: Double, xMax: Double, yMin: Double, yMax: Double, yStep: Double, xTicks: List<Pair<Double, String>>,
                          color: Int = ACCENT, avg: Double? = null, avgLabel: String? = null, markers: List<Double> = emptyList(), connect: Boolean = true) {
        val a = card(x, yy, w, h, title, sub)
        if (canvas == null) return
        if (pts.none { it.y != null }) { noData(a); return }
        val pl = a.l + 28f; val pr = a.r - 6f; val pt = a.t + 6f; val pb = a.b - 13f
        val xr = (xMax - xMin).takeIf { it > 0.0 } ?: 1.0
        fun xOf(v: Double) = (pl + (pr - pl) * ((v - xMin) / xr)).toFloat()
        fun yOf(v: Double) = (pb - (pb - pt) * ((v - yMin) / (yMax - yMin))).toFloat()
        var g = yMin
        while (g <= yMax + 0.001) { ln(pl, yOf(g), pr, yOf(g), if (g == yMin) RULE else GRID, 0.6f); txtR(axisFmt(g), pl - 4f, yOf(g) + 2.5f, pAxis); g += yStep }
        xTicks.forEach { (v, l) ->
            val tx = xOf(v); ln(tx, pb, tx, pb + 2.5f, RULE, 0.6f)
            val lw = pAxis.measureText(l); txt(l, (tx - lw / 2f).coerceIn(pl - 10f, a.r - lw), pb + 9.5f, pAxis)
        }
        avg?.let {
            dashed(pl, yOf(it), pr, WARM)
            val lp = paint(7f, bold = true, color = WARM); val lab = avgLabel ?: "avg ${fmt1(it)}"
            box(pr - lp.measureText(lab) - 3f, yOf(it) - 10.5f, lp.measureText(lab) + 4f, 9f, WHITE)   // keeps the label readable over the data
            txtR(lab, pr, yOf(it) - 3f, lp)
        }
        markers.forEach { mx -> ln(xOf(mx), pb - 7f, xOf(mx), pb, WARM, 1.6f) }
        var prev: Pt? = null
        pts.forEach { p ->
            val v = p.y
            if (v == null) { prev = null; return@forEach }
            val q = prev; val qy = q?.y
            if (connect && q != null && qy != null) ln(xOf(q.x), yOf(qy), xOf(p.x), yOf(v), color, 1.3f)
            prev = p
        }
        val rad = if (pts.size > 80) 1.4f else 2.3f
        pts.forEach { p -> p.y?.let { dot(xOf(p.x), yOf(it), rad, color) } }
    }

    private fun donut(x: Float, yy: Float, w: Float, h: Float, title: String, sub: String?, slices: List<Triple<String, Int, Int>>, centre: String, centreSub: String) {
        val a = card(x, yy, w, h, title, sub)
        if (canvas == null) return
        val totalN = slices.sumOf { it.second }
        if (totalN == 0) { noData(a); return }
        val d = minOf(a.h - 4f, a.w * 0.46f); val sw = d * 0.24f
        val cx = a.l + d / 2f + 2f; val cy = (a.t + a.b) / 2f
        val rf = RectF(cx - d / 2f + sw / 2f, cy - d / 2f + sw / 2f, cx + d / 2f - sw / 2f, cy + d / 2f - sw / 2f)
        var start = -90f
        val c = canvas!!
        val visible = slices.count { it.second > 0 }
        slices.forEach { (_, v, col) ->
            if (v <= 0) return@forEach
            val sweep = 360f * v / totalN
            tmp.style = Paint.Style.STROKE; tmp.strokeWidth = sw; tmp.color = col
            c.drawArc(rf, start, if (visible > 1) (sweep - 1.2f).coerceAtLeast(0.5f) else sweep, false, tmp)
            start += sweep
        }
        txtC(centre, cx, cy + 2f, paint(15f, bold = true, color = HEAD)); txtC(centreSub, cx, cy + 12f, pAxis)
        var ly = a.t + 4f; val lx = a.l + d + 18f
        slices.forEach { (label, v, col) ->
            box(lx, ly, 8f, 8f, col, if (col == P_NONE) RULE else null)
            txt(label, lx + 13f, ly + 7f, paint(7.5f), a.r - lx - 50f)
            txtR("$v · ${Math.round(v * 100.0 / totalN)}%", a.r, ly + 7f, paint(7.5f, bold = true))
            ly += 13.5f
        }
    }

    private fun hBars(x: Float, yy: Float, w: Float, h: Float, title: String, sub: String?, items: List<Count>, color: Int = ACCENT, rowH: Float = 10.6f) {
        val a = card(x, yy, w, h, title, sub)
        if (canvas == null) return
        val rows = items.filter { it.count > 0 }.take(((a.h) / rowH).toInt().coerceAtLeast(1))
        if (rows.isEmpty()) { noData(a, NOT_RECORDED); return }
        val maxV = rows.maxOf { it.count }.toFloat()
        val labelW = a.w * 0.44f; val countW = 18f
        val bx = a.l + labelW + 4f; val bwMax = a.w - labelW - countW - 6f
        rows.forEachIndexed { i, it ->
            val ry = a.t + i * rowH
            txt(it.name, a.l, ry + 8f, paint(7.5f), labelW)
            box(bx, ry + 1.5f, bwMax, rowH - 4f, ZEBRA)
            box(bx, ry + 1.5f, (bwMax * it.count / maxV).coerceAtLeast(2f), rowH - 4f, color)
            txtR("${it.count}", a.r, ry + 8f, paint(7.5f, bold = true))
        }
    }

    private fun stackedBars(x: Float, yy: Float, w: Float, h: Float, title: String, sub: String?, groups: List<ContextGroup>) {
        val a = card(x, yy, w, h, title, sub)
        if (canvas == null) return
        if (groups.all { it.recorded == 0 }) { noData(a, NOT_RECORDED); return }
        val shades = listOf(P_MILD, P_MOD, P_SEV)
        val rowH = a.h / groups.size
        groups.forEachIndexed { gi, g ->
            val ry = a.t + gi * rowH
            txt(g.name, a.l, ry + 8f, paint(7.5f, bold = true))
            txtR(if (g.recorded == 0) NOT_RECORDED else "${g.recorded} of ${r.overview.totalEpisodes} recorded", a.r, ry + 8f, pAxis)
            if (g.recorded == 0) return@forEachIndexed
            var sx = a.l; val by = ry + 12f; val bh = (rowH - 17f).coerceIn(10f, 16f)
            g.segments.forEachIndexed { si, s ->
                if (s.count == 0) return@forEachIndexed
                val sw = a.w * s.count / g.recorded
                box(sx, by, sw, bh, shades[si.coerceAtMost(2)], WHITE, sw = 0.8f)
                val label = "${s.name} ${s.count}"; val p = paint(6.5f, bold = true, color = if (si == 0) INK else WHITE)
                val tx = if (p.measureText(label) + 4f <= sw) label else if (p.measureText("${s.count}") + 3f <= sw) "${s.count}" else ""
                if (tx.isNotEmpty()) txtC(tx, sx + sw / 2f, by + bh / 2f + 2.3f, p)
                sx += sw
            }
        }
    }

    private fun heatmap(x: Float, yy: Float, w: Float, h: Float) {
        val cd = r.charts
        val a = card(x, yy, w, h, "Episode calendar",
            (if (cd.calTruncated) "Last 52 weeks. " else "") + "Each square is one day; darker means higher recorded pain on that day.")
        if (canvas == null) return
        // Legend on the title line, right-aligned.
        val legend = listOf("No episode" to P_NONE, "Episode, no pain score" to P_NOSCORE, "Mild 1–3" to P_MILD, "Moderate 4–6" to P_MOD, "Severe 7–8" to P_SEV, "Worst 9–10" to P_WORST)
        var lx = a.r - legend.sumOf { (11f + pAxis.measureText(it.first) + 12f).toDouble() }.toFloat() + 12f
        legend.forEach { (l, col) -> box(lx, yy + 8f, 8f, 8f, col, if (col == P_NONE) RULE else null); txt(l, lx + 11f, yy + 15f, pAxis); lx += 11f + pAxis.measureText(l) + 12f }

        val start = cd.calFrom.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val cols = (ChronoUnit.DAYS.between(start, cd.calTo) / 7 + 1).toInt()
        val gap = 2f; val labW = 18f
        val size = minOf((a.w - labW) / cols - gap, (a.h - 12f) / 7f - gap, 15f).coerceAtLeast(3f)
        val gx = a.l + labW; val gy = a.t + 11f
        val mf = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)
        var lastMonth = -1; var lastEnd = -100f
        for (c in 0 until cols) {
            val ws = start.plusDays(7L * c); val mxp = gx + c * (size + gap)
            if (ws.monthValue != lastMonth && (c == 0 || ws.dayOfMonth <= 7)) {
                lastMonth = ws.monthValue
                if (mxp >= lastEnd + 4f) { txt(mf.format(ws), mxp, gy - 3f, pAxis); lastEnd = mxp + pAxis.measureText(mf.format(ws)) }
            }
        }
        listOf(0 to "Mon", 2 to "Wed", 4 to "Fri").forEach { (row, l) -> txt(l, a.l, gy + row * (size + gap) + size - 1.5f, pAxis) }
        for (c in 0 until cols) for (row in 0 until 7) {
            val d = start.plusDays(7L * c + row)
            if (d.isBefore(cd.calFrom) || d.isAfter(cd.calTo)) continue
            val info = cd.calendar[d]
            box(gx + c * (size + gap), gy + row * (size + gap), size, size, if (info == null) P_NONE else painTint(info.maxPain), rad = 1.5f)
        }
    }

    // ───────────────────────── chart pages (landscape) ─────────────────────────

    private fun dateTicks(from: LocalDate, to: LocalDate, n: Int): List<Pair<Double, String>> {
        val days = ChronoUnit.DAYS.between(from, to).coerceAtLeast(1)
        val f = DateTimeFormatter.ofPattern(if (days > 200) "MMM yy" else "d MMM", Locale.ENGLISH)
        return (0..n).map { i -> val d = from.plusDays(Math.round(days * i.toDouble() / n)); (i.toDouble() / n) to f.format(d) }
    }

    private fun trendsPage() {
        newPage(true, "Trends", "Counts are by the day each episode started. Charts show what was recorded; they do not show causes.")
        val c = r.charts; val gap = 12f
        val h = (bottom - y - gap) / 2f
        val lw = width * 0.64f; val rw = width - lw - gap; val rx = left + lw + gap; val y0 = y; val y1 = y0 + h + gap
        val n = r.overview.totalEpisodes

        barChart(left, y0, lw, h, c.timeline.title, "$n episodes", c.timeline.buckets.map { it.label }, c.timeline.buckets.map { it.count })

        val slices = listOf(P_NONE, P_MILD, P_MOD, P_SEV, P_WORST, P_NOSCORE).let { cols -> c.painBands.mapIndexed { i, b -> Triple(b.name, b.count, cols[i]) } }
        donut(rx, y0, rw, h, "Pain severity", "Episodes by recorded pain score", slices, "$n", if (n == 1) "episode" else "episodes")

        // Pain over time: every rated episode as a point (x = when it started), with the average as a dashed line.
        val from = c.spanFrom.atStartOfDay(r.zone).toInstant().toEpochMilli().toDouble()
        val to = c.spanTo.plusDays(1).atStartOfDay(r.zone).toInstant().toEpochMilli().toDouble()
        lineChart(left, y1, lw, h, "Pain intensity over time", "${c.painPoints.size} of $n episodes have a pain score (0–10)",
            c.painPoints.map { Pt(it.epochMs.toDouble(), it.pain.toDouble()) }, from, to, 0.0, 10.0, 2.0,
            dateTicks(c.spanFrom, c.spanTo, 5).map { (f, l) -> (from + (to - from) * f) to l },
            avg = r.overview.avgPain, avgLabel = r.overview.avgPain?.let { "average ${fmt1(it)}" },
            connect = c.painPoints.size <= 40)   // with many episodes a joined line would zig-zag and suggest a trend between unrelated episodes

        val hh = (h - gap) / 2f
        barChart(rx, y1, rw, hh, "Time of day episodes started", null, c.timeOfDay.map { it.name.substringBefore(" ") }, c.timeOfDay.map { it.count })
        barChart(rx, y1 + hh + gap, rw, hh, "Day of week", null, c.weekday.map { it.name }, c.weekday.map { it.count })
        y = bottom
    }

    private fun patternsPage() {
        newPage(true, "Patterns and context", "Most frequently recorded items. These are observations from the log, not proven causes.")
        val c = r.charts; val gap = 12f; val n = r.overview.totalEpisodes
        heatmap(left, y, width, 148f); y += 148f + gap
        val h = (bottom - y - gap) / 2f; val w3 = (width - 2 * gap) / 3f
        fun rec(l: PatternList) = "Recorded in ${l.episodesWithData} of $n episodes"
        val y0 = y; val y1 = y0 + h + gap
        hBars(left, y0, w3, h, "Symptoms", rec(r.patterns.symptoms), r.patterns.symptoms.items)
        hBars(left + w3 + gap, y0, w3, h, "Triggers", rec(r.patterns.triggers), r.patterns.triggers.items, ACCENT2)
        hBars(left + 2 * (w3 + gap), y0, w3, h, "Pain locations", rec(r.patterns.locations), r.patterns.locations.items)
        hBars(left, y1, w3, h, "Medicines logged on episodes", rec(c.medicines), c.medicines.items, TEAL)
        stackedBars(left + w3 + gap, y1, w3, h, "Context at onset", "As recorded on each episode", c.context)
        barChart(left + 2 * (w3 + gap), y1, w3, h, "Duration of completed episodes", "${r.overview.durationRecords} of $n completed",
            c.durations.map { it.name }, c.durations.map { it.count }, ACCENT2)
        y = bottom
    }

    private fun healthPage(h: HealthSection) {
        val s = h.series ?: return
        val unit = when (s.unit) { "day" -> "Daily values"; "week" -> "Weekly averages"; else -> "Monthly averages" }
        newPage(true, "Health Connect", "Health Connect data stored on this device, limited to the reporting period. $unit; orange ticks mark periods with a recorded migraine. Context only; no causes are implied.")
        table(listOf(Col("Metric", 300f), Col("Average", 150f), Col("Days recorded", 150f), Col("Coverage", 182f)),
            h.rows.map { listOf(it.label, it.value, "${it.records} ${it.unit}", if (it.limited) "Limited" else "Good") }, size = 8f, boldFirst = true,
            fills = { i, c -> if (c == 3 && h.rows[i].limited) TINT2 else null })
        para("Days with any Health Connect data in this period: ${h.daysWithData}. " + h.notes.joinToString(" "), paint(7.5f, italic = true, color = MUTED), after = 6f)
        val pts = s.points; val n = pts.size
        val gap = 12f; val cw = (width - gap) / 2f
        val migraineX = pts.mapIndexedNotNull { i, p -> if (p.migraine) i.toDouble() else null }
        val ticks = (0 until n).filter { n <= 6 || it % Math.ceil(n / 6.0).toInt() == 0 }.map { it.toDouble() to pts[it].label }
        val xMax = (n - 1).toDouble().coerceAtLeast(1.0)

        fun lineFor(vals: List<Double?>, title: String, sub: String, color: Int, forceZero: Boolean, fmtAvg: (Double) -> String): ((Float, Float, Float, Float) -> Unit)? {
            val present = vals.filterNotNull(); if (present.isEmpty()) return null
            var lo = if (forceZero) 0.0 else present.min(); var hi = present.max()
            if (hi - lo < 1e-9) { val pad = maxOf(2.0, Math.abs(hi) * 0.1); if (!forceZero) lo -= pad; hi += pad }   // all values equal: still draw a readable axis
            val step = niceStep((hi - lo).coerceAtLeast(1.0) / 4.0)
            val yMin = Math.floor(lo / step) * step; var yMax = Math.ceil(hi / step) * step; if (yMax <= yMin) yMax = yMin + step
            val av = present.average()
            return { x, yy, w, h -> lineChart(x, yy, w, h, title, sub, vals.mapIndexed { i, v -> Pt(i.toDouble(), v) }, 0.0, xMax, yMin, yMax, step, ticks, color,
                avg = av, avgLabel = "average ${fmtAvg(av)}", markers = migraineX) }
        }
        val charts = listOfNotNull<(Float, Float, Float, Float) -> Unit>(
            lineFor(pts.map { it.sleepHours }, "Sleep duration (hours)", "${pts.count { it.sleepHours != null }} ${s.unit}s with data", ACCENT, true) { fmt1(it) + " h" },
            pts.mapNotNull { it.steps }.takeIf { it.isNotEmpty() }?.let { v ->
                { x: Float, yy: Float, w: Float, h: Float ->
                    barChart(x, yy, w, h, "Steps (daily average)", "${v.size} ${s.unit}s with data", pts.map { it.label }, pts.map { it.steps ?: 0 }, TEAL, showValues = false)
                    migraineX.forEach { mx -> val a = Area(x + 10f, yy + 34f, x + w - 10f, yy + h - 8f); val slot = (a.w - 26f) / n
                        ln(a.l + 26f + slot * mx.toFloat() + slot / 2f, a.b - 20f, a.l + 26f + slot * mx.toFloat() + slot / 2f, a.b - 13f, WARM, 1.6f) }
                }
            },
            lineFor(pts.map { it.restingHr?.toDouble() }, "Resting heart rate (bpm)", "${pts.count { it.restingHr != null }} ${s.unit}s with data", ACCENT2, false) { "${Math.round(it)} bpm" },
            lineFor(pts.map { it.hrv }, "Heart rate variability (RMSSD, ms)", "${pts.count { it.hrv != null }} ${s.unit}s with data", TEAL, false) { "${Math.round(it)} ms" },
        )
        if (charts.isEmpty()) { para("No chartable Health Connect values in this period.", pItalic); return }
        val rows = if (charts.size > 2) 2 else 1
        val h = (bottom - y - gap * (rows - 1)) / rows * (if (rows == 1) 0.62f else 1f)
        charts.forEachIndexed { i, draw -> draw(left + (i % 2) * (cw + gap), y + (i / 2) * (h + gap), cw, h) }
        y = bottom
    }

    // ───────────────────────── episode log ─────────────────────────

    private fun short(v: String): String = if (v == NOT_RECORDED || v == "Not reported") "—" else v

    private fun logPage() {
        val n = r.episodes.size
        newPage(true, "Episode log", "$n ${if (n == 1) "episode" else "episodes"}, oldest first · ${r.periodLabel}")
        para("— means not recorded (or not reported). Pain cells are shaded by severity; darker means higher. Hydration, last meal and caffeine are shown as logged on each episode.", pItalic, after = 5f)
        val cols = listOf(Col("#", 22f), Col("Date", 56f), Col("Start", 44f), Col("End", 48f), Col("Dur.", 38f), Col("Pain", 30f), Col("Aura", 28f),
            Col("Symptoms", 88f), Col("Triggers", 74f), Col("Pain location", 68f), Col("Medication", 62f), Col("Stress", 40f), Col("Hydration", 46f),
            Col("Last meal", 36f), Col("Caffeine", 42f), Col("In sleep", 32f), Col("Onset place", 34f))
        fun abbr(v: String) = when (v) {
            "Less than 500 ml" -> "< 500 ml"; "500 ml – 1 L" -> "0.5–1 L"; "More than 1 L" -> "> 1 L"
            "Less than 2 h ago" -> "< 2 h"; "2–6 h ago" -> "2–6 h"; "More than 6 h ago" -> "> 6 h"
            "More than 1" -> "> 1 cup"; else -> v
        }
        val rows = r.episodes.map { e ->
            listOf("${e.number}", e.date.substringAfter(", ").let { it + "\n" + e.date.substringBefore(",") }, e.start,
                if (e.ongoing) "Ongoing" else e.end, if (e.ongoing) "—" else short(e.duration),
                short(e.pain).replace(" / ", "/"), short(e.aura),
                short(e.symptoms), short(e.triggers), short(e.painLocation), short(e.medication),
                short(e.stress), abbr(short(e.hydration)), abbr(short(e.lastMeal)), abbr(short(e.caffeine)), short(e.beganInSleep), short(e.onsetPlace))
        }
        val pains = r.episodes.map { e -> e.pain.substringBefore(" ").toIntOrNull() }
        table(cols, rows, size = 7.2f, padX = 3f,
            fills = { i, c -> when { c == 5 && pains[i] != null -> painCell(pains[i]!!); c == 3 && r.episodes[i].ongoing -> TINT2; else -> null } },
            notes = r.episodes.map { it.notes })
    }
}
