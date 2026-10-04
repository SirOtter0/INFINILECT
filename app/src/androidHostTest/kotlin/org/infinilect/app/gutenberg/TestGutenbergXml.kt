// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.gutenberg
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser
/** Host simulation of Android's token seam, not Android-device verification. */
internal actual fun testGutenbergXml(bytes: ByteArray): OpdsXmlReader = AndroidOpdsXmlReader(bytes, object : KXmlParser() {
    override fun setFeature(feature: String, value: Boolean) {
        if (feature == XmlPullParser.FEATURE_PROCESS_DOCDECL) check(!value && !getFeature(feature))
        else super.setFeature(feature, value)
    }
})
