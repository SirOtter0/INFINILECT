// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.gutenberg
internal actual fun testGutenbergXml(bytes: ByteArray): OpdsXmlReader = platformOpdsXmlReader(bytes)
