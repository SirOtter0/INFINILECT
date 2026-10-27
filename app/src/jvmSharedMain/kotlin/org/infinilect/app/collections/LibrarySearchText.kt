// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.collections

import java.text.Normalizer
import java.util.Locale

private val marks = Regex("\\p{M}+")
private val spaces = Regex("\\s+")
internal actual fun librarySearchText(value: String): String =
    Normalizer.normalize(value,Normalizer.Form.NFD).replace(marks, "").lowercase(Locale.ROOT).trim().replace(spaces," ")
