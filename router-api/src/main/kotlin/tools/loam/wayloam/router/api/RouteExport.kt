package tools.loam.wayloam.router.api

import java.io.Writer

object RouteExport {
    /** Streams standard GPX 1.1 without buffering a second copy of a continent-scale track. */
    fun writeGpx(route: RouteResult, writer: Writer, name: String = "WAYLOAM route") {
        writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        writer.write("<gpx version=\"1.1\" creator=\"WAYLOAM Router\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        writer.write("<trk><name>${escape(name)}</name><trkseg>\n")
        route.points.forEach { point ->
            writer.write("<trkpt lat=\"${point.latitude}\" lon=\"${point.longitude}\">")
            point.elevationMeters?.let { writer.write("<ele>$it</ele>") }
            writer.write("</trkpt>\n")
        }
        writer.write("</trkseg></trk></gpx>\n")
    }

    private fun escape(value: String): String = value
        .filter { it == '\t' || it == '\n' || it == '\r' || it >= ' ' }
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}
