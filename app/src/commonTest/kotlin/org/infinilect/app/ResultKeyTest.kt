// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.infinilect.core.PublicationId
import org.infinilect.core.SourceId

class ResultKeyTest {
    @Test fun keysAreStableAndKeepBothIdentityComponentsWithoutAmbiguousConcatenation() {
        val id = PublicationId(SourceId("archive"), "123")
        assertEquals(id.resultKey(), id.copy().resultKey())
        assertNotEquals(id.resultKey(), id.copy(sourceId = SourceId("gutenberg")).resultKey())
        assertNotEquals(id.resultKey(), id.copy(localId = "124").resultKey())
        assertNotEquals(
            PublicationId(SourceId("a:b"), "c").resultKey(),
            PublicationId(SourceId("a"), "b:c").resultKey(),
        )
    }
}
