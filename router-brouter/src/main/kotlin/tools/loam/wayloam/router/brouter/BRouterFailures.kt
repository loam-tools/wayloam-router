package tools.loam.wayloam.router.brouter

import tools.loam.wayloam.router.api.RoutingException
import tools.loam.wayloam.router.api.RoutingFailureCode

internal fun classifyBRouterFailure(message: String): RoutingException {
    val text = message.lowercase()
    return when {
        listOf("checksum", "checkum", "too short", "unsupported data-format", "crc", "corrupt", "lookup version", "lookupversion", "incompatible", "eofexception")
            .any(text::contains) -> IncompatibleRoutingDataException(message)
        text.contains("not mapped") || text.contains("island") || text.contains("no track") ||
            text.contains("no route") -> NoRouteException(message)
        (text.contains("rd5") || text.contains("datafile") || text.contains("segment")) &&
            (text.contains("not found") || text.contains("does not exist") || text.contains("missing")) ->
            MissingRoutingDataException(message)
        text.contains("timeout") || text.contains("time limit") || text.contains("terminated") ||
            text.contains("killed") || text.contains("memory limit") -> RoutingTimeoutException(message)
        else -> BRouterEngineException(message)
    }
}

open class BRouterEngineException(message: String, code: RoutingFailureCode = RoutingFailureCode.ENGINE_ERROR) :
    RoutingException(code, message)
class MissingBRouterProfileException(message: String) : BRouterEngineException(message, RoutingFailureCode.MISSING_PROFILE)
class MissingRoutingDataException(message: String) : BRouterEngineException(message, RoutingFailureCode.MISSING_DATA)
class IncompatibleRoutingDataException(message: String) : BRouterEngineException(message, RoutingFailureCode.INCOMPATIBLE_DATA)
class RoutingTimeoutException(message: String) : BRouterEngineException(message, RoutingFailureCode.TIMEOUT)
class NoRouteException(message: String) : BRouterEngineException(message, RoutingFailureCode.NO_ROUTE)
