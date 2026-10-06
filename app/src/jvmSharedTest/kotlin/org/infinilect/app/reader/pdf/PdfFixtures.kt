// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.pdf

import java.io.ByteArrayOutputStream

/** Original small real PDF: catalog/page tree, font, streams, byte-offset xref and trailer.
 * No copied publication, downloaded fixture or third-party fixture license. */
internal fun smallPdf(count: Int = 1, width: Int = 612, height: Int = 792, imageFilter: String? = null): ByteArray {
    val objects = mutableListOf<String>()
    val pageStart = 4
    val kids = (0 until count).joinToString(" ") { "${pageStart+it*2} 0 R" }
    objects += "<< /Type /Catalog /Pages 2 0 R >>"
    objects += "<< /Type /Pages /Count $count /Kids [$kids] >>"
    objects += "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
    val imageNumber = pageStart + count*2
    repeat(count) { index ->
        val page = pageStart+index*2
        objects += "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $width $height] /Resources << /Font << /F1 3 0 R >> ${if(imageFilter == null) "" else "/XObject << /Im1 $imageNumber 0 R >>"} >> /Contents ${page+1} 0 R >>"
        val commands = "0.1 0.3 0.7 rg 36 36 100 100 re f\nBT /F1 18 Tf 36 170 Td (INFINILECT original page ${index+1}) Tj ET\n" +
            if(imageFilter == null) "" else "q 20 0 0 20 180 36 cm /Im1 Do Q\n"
        objects += "<< /Length ${commands.toByteArray(Charsets.US_ASCII).size} >>\nstream\n${commands}endstream"
    }
    if(imageFilter != null) objects += "<< /Type /XObject /Subtype /Image /Width 1 /Height 1 /BitsPerComponent 8 /ColorSpace /DeviceRGB /Filter /$imageFilter /Length 1 >>\nstream\nx\nendstream"
    val out=ByteArrayOutputStream()
    fun write(s:String) { out.write(s.toByteArray(Charsets.US_ASCII)) }
    write("%PDF-1.4\n")
    val offsets=mutableListOf(0)
    objects.forEachIndexed { index,body -> offsets += out.size();write("${index+1} 0 obj\n$body\nendobj\n") }
    val xref=out.size()
    write("xref\n0 ${objects.size+1}\n0000000000 65535 f \n")
    offsets.drop(1).forEach { write("${it.toString().padStart(10,'0')} 00000 n \n") }
    write("trailer\n<< /Size ${objects.size+1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
    return out.toByteArray()
}
