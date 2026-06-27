package com.irozar.ipdfmaster.pdfeditorspike

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream.AppendMode
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.io.File

object PdfLocalTools {
    fun rotatePage(pdf: File, pageIndex: Int) = edit(pdf) { doc ->
        val page = doc.getPage(pageIndex)
        page.rotation = (page.rotation + 90) % 360
    }

    fun duplicatePage(pdf: File, pageIndex: Int) = edit(pdf) { doc ->
        val source = doc.getPage(pageIndex)
        val imported = doc.importPage(source)
        doc.pages.remove(imported)
        doc.pages.insertAfter(imported, source)
    }

    fun deletePage(pdf: File, pageIndex: Int) = edit(pdf) { doc ->
        if (doc.numberOfPages <= 1) error("Cannot delete the only page")
        doc.removePage(pageIndex)
    }

    fun extractPage(pdf: File, pageIndex: Int, out: File) {
        PDDocument.load(pdf).use { source ->
            PDDocument().use { target ->
                target.addPage(target.importPage(source.getPage(pageIndex)))
                target.save(out)
            }
        }
    }

    fun splitPages(pdf: File, outDir: File): List<File> {
        outDir.mkdirs()
        PDDocument.load(pdf).use { source ->
            return (0 until source.numberOfPages).map { index ->
                val out = File(outDir, "${pdf.nameWithoutExtension}_page_${index + 1}.pdf")
                PDDocument().use { target ->
                    target.addPage(target.importPage(source.getPage(index)))
                    target.save(out)
                }
                out
            }
        }
    }

    fun merge(inputs: List<File>, out: File) {
        val merger = PDFMergerUtility()
        inputs.forEach { merger.addSource(it) }
        merger.destinationFileName = out.absolutePath
        merger.mergeDocuments(com.tom_roush.pdfbox.io.MemoryUsageSetting.setupMainMemoryOnly())
    }

    fun compress(pdf: File) = edit(pdf) { }

    fun imagesToPdf(context: Context, uris: List<Uri>, out: File) {
        PDDocument().use { doc ->
            uris.forEach { uri ->
                val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return@forEach
                val page = PDPage(PDRectangle(bitmap.width.toFloat(), bitmap.height.toFloat()))
                doc.addPage(page)
                val image = LosslessFactory.createFromImage(doc, bitmap)
                PDPageContentStream(doc, page, AppendMode.APPEND, true, true).use { cs ->
                    cs.drawImage(image, 0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
                }
            }
            doc.save(out)
        }
    }

    private fun edit(pdf: File, block: (PDDocument) -> Unit) {
        PDDocument.load(pdf).use { doc ->
            block(doc)
            val tmp = File(pdf.parentFile, "tmp_${pdf.name}")
            doc.save(tmp)
            tmp.copyTo(pdf, overwrite = true)
            tmp.delete()
        }
    }
}
