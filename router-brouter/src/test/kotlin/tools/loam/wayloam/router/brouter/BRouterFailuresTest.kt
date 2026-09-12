package tools.loam.wayloam.router.brouter

import org.junit.Assert.assertEquals
import org.junit.Test
import tools.loam.wayloam.router.api.RoutingFailureCode

class BRouterFailuresTest {
    @Test fun nativeErrorMessagesKeepDistinctRecoveryActions() {
        mapOf(
            "datafile E5_N45.rd5 not found" to RoutingFailureCode.MISSING_DATA,
            "from-position not mapped in existing datafile" to RoutingFailureCode.NO_ROUTE,
            "target island detected for section 0" to RoutingFailureCode.NO_ROUTE,
            "lookup version mismatch (old rd5?)" to RoutingFailureCode.INCOMPATIBLE_DATA,
            "error reading datafile: java.io.EOFException" to RoutingFailureCode.INCOMPATIBLE_DATA,
            "top index checksum error" to RoutingFailureCode.INCOMPATIBLE_DATA,
            "file of size 4 too short, should be 100" to RoutingFailureCode.INCOMPATIBLE_DATA,
            "routing timeout after 90 seconds" to RoutingFailureCode.TIMEOUT,
            "memory limit reached" to RoutingFailureCode.TIMEOUT,
        ).forEach { (message, code) -> assertEquals(message, code, classifyBRouterFailure(message).code) }
    }
}
