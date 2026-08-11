package com.brownian.tracker.export

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.content.FileProvider
import com.brownian.tracker.physics.MsdPoint
import com.brownian.tracker.physics.PolydisperseSizingResult
import com.brownian.tracker.tracker.ParticleTrack
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class DataExporter(private val context: Context) {

    /**
     * Export complete experimental session data to JSON format
     */
    fun exportToJSON(
        tracks: List<ParticleTrack>,
        scaleMicronsPerPixel: Float,
        tempCelsius: Double,
        diffusionCoeff: Double,
        polydisperseSizing: PolydisperseSizingResult?,
        msdPoints: List<MsdPoint>,
        experimentalParams: Map<String, Any>
    ): File {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val filename = "brownian_nta_${timestamp}.json"
        
        val json = JSONObject().apply {
            put("export_timestamp", System.currentTimeMillis())
            put("export_date", timestamp)
            put("app_version", "1.0")
            
            // Experimental Parameters
            put("experimental_params", JSONObject(experimentalParams))
            
            // Calibration
            put("calibration", JSONObject().apply {
                put("scale_um_per_pixel", scaleMicronsPerPixel)
            })
            
            // Physics Results
            put("physics_results", JSONObject().apply {
                put("temperature_celsius", tempCelsius)
                put("temperature_kelvin", tempCelsius + 273.15)
                put("diffusion_coefficient_um2_per_s", diffusionCoeff)
                
                polydisperseSizing?.let { sizing ->
                    put("particle_sizing", JSONObject().apply {
                        put("mean_diameter_um", sizing.meanDiameter)
                        put("median_diameter_um", sizing.medianDiameter)
                        put("std_dev_um", sizing.stdDev)
                        put("polydispersity_index", sizing.pdi)
                        put("particle_count", sizing.individualResults.size)
                        
                        put("individual_particles", JSONArray().apply {
                            sizing.individualResults.forEach { result ->
                                put(JSONObject().apply {
                                    put("track_id", result.trackId)
                                    put("diameter_um", result.diameter)
                                    put("diffusion_coefficient", result.D)
                                    put("point_count", result.pointCount)
                                })
                            }
                        })
                        
                        put("size_histogram", JSONObject().apply {
                            put("bins", JSONArray(sizing.sizeHistogram.bins))
                            put("counts", JSONArray(sizing.sizeHistogram.counts))
                        })
                    })
                }
            })
            
            // MSD Curve Data
            put("msd_analysis", JSONObject().apply {
                put("points", JSONArray().apply {
                    msdPoints.forEach { point ->
                        put(JSONObject().apply {
                            put("time_delay_s", point.dt)
                            put("msd_um2", point.msd)
                        })
                    }
                })
            })
            
            // Particle Trajectories
            put("trajectories", JSONArray().apply {
                tracks.forEach { track ->
                    put(JSONObject().apply {
                        put("track_id", track.id)
                        put("point_count", track.points.size)
                        
                        put("positions", JSONArray().apply {
                            track.points.forEach { point ->
                                put(JSONObject().apply {
                                    put("t", point.t)
                                    put("x_px", point.x)
                                    put("y_px", point.y)
                                    put("x_um", point.x * scaleMicronsPerPixel)
                                    put("y_um", point.y * scaleMicronsPerPixel)
                                })
                            }
                        })
                    })
                }
            })
        }
        
        val exportDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "BrownianNTA")
        exportDir.mkdirs()
        
        val file = File(exportDir, filename)
        file.writeText(json.toString(2))
        
        return file
    }

    /**
     * Export to CSV format (simplified, good for Excel/Python)
     */
    fun exportToCSV(
        tracks: List<ParticleTrack>,
        scaleMicronsPerPixel: Float
    ): File {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val filename = "brownian_tracks_${timestamp}.csv"
        
        val csv = StringBuilder()
        csv.append("track_id,time_s,x_px,y_px,x_um,y_um\n")
        
        tracks.forEach { track ->
            track.points.forEach { point ->
                csv.append("${track.id},${point.t},${point.x},${point.y},")
                csv.append("${point.x * scaleMicronsPerPixel},${point.y * scaleMicronsPerPixel}\n")
            }
        }
        
        val exportDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "BrownianNTA")
        exportDir.mkdirs()
        
        val file = File(exportDir, filename)
        file.writeText(csv.toString())
        
        return file
    }

    /**
     * Export MSD curve data to CSV
     */
    fun exportMSDToCSV(msdPoints: List<MsdPoint>): File {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val filename = "msd_curve_${timestamp}.csv"
        
        val csv = StringBuilder()
        csv.append("time_delay_s,msd_um2\n")
        
        msdPoints.forEach { point ->
            csv.append("${point.dt},${point.msd}\n")
        }
        
        val exportDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "BrownianNTA")
        exportDir.mkdirs()
        
        val file = File(exportDir, filename)
        file.writeText(csv.toString())
        
        return file
    }

    /**
     * Share exported file via Android share dialog
     */
    fun shareFile(file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (file.extension == "json") "application/json" else "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Brownian Motion Data Export")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        
        context.startActivity(Intent.createChooser(intent, "Share data via..."))
    }
}
