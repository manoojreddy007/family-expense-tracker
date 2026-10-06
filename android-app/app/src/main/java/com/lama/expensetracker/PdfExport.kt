package com.lama.expensetracker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.ByteArrayOutputStream
import java.io.File

object PdfExport {
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private val NAVY = 0xFF173B52.toInt()
    private val TEAL = 0xFF167C78.toInt()
    private val MUTED = 0xFF667985.toInt()
    private val LIGHT = 0xFFF1F6F5.toInt()
    private val INCOME = 0xFF20836F.toInt()
    private val EXPENSE = 0xFFB75048.toInt()

    fun create(context: Context, data: DashboardData, month: String): ByteArray {
        val document = PdfDocument()
        val rows = data.rows
        var pageNumber = 0
        var page: PdfDocument.Page? = null
        var y = 0f

        fun paint(color: Int, size: Float, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }

        fun beginPage(first: Boolean) {
            pageNumber++
            page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
            val canvas = page!!.canvas
            canvas.drawColor(android.graphics.Color.WHITE)
            val titlePaint = paint(NAVY, 24f, true)
            val subPaint = paint(MUTED, 10f)
            if (first) {
                val header = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = NAVY }
                canvas.drawRoundRect(RectF(28f, 24f, 567f, 157f), 18f, 18f, header)
                canvas.drawText("LA-MA  EXPENSE REPORT", 48f, 67f, paint(android.graphics.Color.WHITE, 21f, true))
                canvas.drawText(if (month.isBlank()) "All transaction history" else "Transactions for $month", 48f, 92f, paint(0xFFD5E7E5.toInt(), 12f))
                canvas.drawText("Generated ${java.time.LocalDate.now()}", 48f, 120f, paint(0xFFD5E7E5.toInt(), 9f))

                canvas.drawRoundRect(RectF(28f, 174f, 567f, 250f), 14f, 14f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LIGHT })
                canvas.drawText("INCOME", 48f, 199f, paint(MUTED, 9f, true))
                canvas.drawText("EXPENSES", 230f, 199f, paint(MUTED, 9f, true))
                canvas.drawText("SAVINGS", 414f, 199f, paint(MUTED, 9f, true))
                canvas.drawText("Rs %.2f".format(data.income), 48f, 228f, paint(INCOME, 17f, true))
                canvas.drawText("Rs %.2f".format(data.expense), 230f, 228f, paint(EXPENSE, 17f, true))
                canvas.drawText("Rs %.2f".format(data.income - data.expense), 414f, 228f, paint(NAVY, 17f, true))
                y = 282f
            } else {
                canvas.drawText("LA-MA  |  EXPENSE REPORT", 36f, 48f, titlePaint)
                canvas.drawText("${if (month.isBlank()) "All history" else month}  ·  Continued", 36f, 68f, subPaint)
                y = 102f
            }
            val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEAL }
            canvas.drawRoundRect(RectF(30f, y, 565f, y + 32f), 8f, 8f, headerPaint)
            canvas.drawText("TRANSACTION", 44f, y + 21f, paint(android.graphics.Color.WHITE, 9f, true))
            canvas.drawText("TYPE", 365f, y + 21f, paint(android.graphics.Color.WHITE, 9f, true))
            canvas.drawText("AMOUNT", 486f, y + 21f, paint(android.graphics.Color.WHITE, 9f, true))
            y += 46f
        }

        fun finishPage() {
            val currentPage = page ?: return
            val footer = paint(MUTED, 9f)
            currentPage.canvas.drawText("LA-MA Expense Tracker", 36f, 815f, footer)
            currentPage.canvas.drawText("Page $pageNumber", 510f, 815f, footer)
            document.finishPage(currentPage)
        }

        beginPage(first = true)
        if (rows.isEmpty()) {
            page!!.canvas.drawText("No transactions for this period.", 42f, y + 20f, paint(MUTED, 12f))
        } else {
            rows.forEach { row ->
                if (y > 755f) {
                    finishPage()
                    beginPage(first = false)
                }
                val canvas = page!!.canvas
                val isIncome = row.type == "Income"
                val amountPaint = paint(if (isIncome) INCOME else EXPENSE, 12f, true)
                val title = row.name.take(38)
                canvas.drawText(title, 44f, y, paint(NAVY, 11f, true))
                canvas.drawText(row.type.uppercase(), 365f, y, paint(if (isIncome) INCOME else EXPENSE, 8f, true))
                val amount = "Rs %.2f".format(row.amount)
                canvas.drawText(amount, 560f - amountPaint.measureText(amount), y, amountPaint)
                val details = listOf(row.date, row.category, row.addedBy).filter { it.isNotBlank() }.joinToString("   ·   ")
                canvas.drawText(details.take(76), 44f, y + 17f, paint(MUTED, 9f))
                if (row.notes.isNotBlank()) canvas.drawText(row.notes.take(82), 44f, y + 32f, paint(MUTED, 8f))
                val rowHeight = if (row.notes.isBlank()) 48f else 58f
                canvas.drawLine(42f, y + rowHeight - 4f, 553f, y + rowHeight - 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE4EBEA.toInt(); strokeWidth = 0.7f })
                y += rowHeight
            }
        }
        finishPage()
        val output = ByteArrayOutputStream()
        document.writeTo(output)
        document.close()
        return output.toByteArray()
    }

    fun shareFile(context: Context, bytes: ByteArray): File {
        val directory = File(context.cacheDir, "reports").apply { mkdirs() }
        return File(directory, "lama-expense-report.pdf").apply { writeBytes(bytes) }
    }
}
