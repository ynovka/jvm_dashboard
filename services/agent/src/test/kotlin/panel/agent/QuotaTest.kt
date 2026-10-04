package panel.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuotaTest {
    @Test fun readsEmptyAndUsedDeviceRowsInKiB() {
        assertEquals(0L, quotaUsedKiB("/dev/loop0                   0          0          0   00 [--------] /opt/jvm_dashboard/data/apps\n"))
        assertEquals(32768L, quotaUsedKiB("/dev/loop0               32768     131072     131072   00 [--------] /opt/jvm_dashboard/data/apps\n"))
    }

    @Test fun rejectsMissingOrAmbiguousQuotaRows() {
        assertNull(quotaUsedKiB(""))
        assertNull(quotaUsedKiB("xfs_quota: cannot read quota"))
        assertNull(quotaUsedKiB("/dev/loop0 0 0 0 00 [--------] /apps\n/dev/loop1 0 0 0 00 [--------] /other"))
    }
}
