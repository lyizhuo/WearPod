package site.whitezaak.wearpod.offline

import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineSessionStateTest {

    @Test
    fun `change summary accumulates imports and deletes across one session`() {
        var summary = ChangeSummary()

        summary += PageChange.Imported(added = 2, skipped = 1, total = 10)
        summary += PageChange.Deleted(deleted = 3, total = 7)
        summary += PageChange.Imported(added = 1, skipped = 0, total = 8)

        assertEquals(
            ChangeSummary(added = 3, deleted = 3, skipped = 1),
            summary,
        )
    }
}
