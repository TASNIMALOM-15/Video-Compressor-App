package com.local.vc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.local.vc.data.AppDb
import com.local.vc.data.JobEntity
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

class CompressService : Service() {

    companion object {
        const val ACTION_START = "vc.START"
        const val ACTION_PAUSE = "vc.PAUSE"
        const val ACTION_CANCEL = "vc.CANCEL"
        const val CH = "compress"
        const val NOTI_ID = 1
        const val DONE_ID = 2

        @Volatile
        var running = false
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val dao by lazy { AppDb.get(this).jobDao() }
    private val holder = ProgressHolder()

    private var transformer: Transformer? = null
    private var current: JobEntity? = null
    private var plan: Plan? = null
    private var tempFile: File? = null
    private var startTime = 0L

    @Volatile private var completedCount = 0
    @Volatile private var waitingCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH, "Compression", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg(buildNoti("🎬 Video Compressor", "Starting...", -1, false))
        when (intent?.action) {
            ACTION_PAUSE -> if (!stopCurrent("PAUSED")) finishAll()
            ACTION_CANCEL -> if (!stopCurrent("CANCELLED")) finishAll()
            else -> if (current == null) next()
        }
        return START_NOT_STICKY
    }

    private fun startFg(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTI_ID, n)
        }
    }

    private fun next() {
        io.execute {
            val job = dao.next()
            if (job == null) {
                main.post { finishAll() }
                return@execute
            }
            try {
                val p = Planner.plan(this, Uri.parse(job.inputUri), job.originalSize, job.resolution, job.fps)
                waitingCount = dao.count("WAITING") - 1
                main.post { begin(job, p) }
            } catch (e: Exception) {
                dao.update(job.copy(status = "FAILED", error = (e.message ?: "probe error").take(300)))
                main.post { next() }
            }
        }
    }

    private fun begin(job: JobEntity, p: Plan) {
        current = job
        plan = p
        val tmp = File(cacheDir, "job_${job.id}.mp4")
        if (tmp.exists()) tmp.delete()
        tempFile = tmp
        startTime = SystemClock.elapsedRealtime()
        io.execute { dao.update(job.copy(status = "PROCESSING", progress = 0, error = null)) }

        val effects = ArrayList<Effect>()
        if (p.needScale) effects.add(Presentation.createForHeight(p.targetHeight))
        if (p.needFpsDrop) effects.add(FrameDropEffect.createDefaultFrameDropEffect(p.targetFps.toFloat()))

        try {
            val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.parse(job.inputUri)))
                .setEffects(Effects(emptyList(), effects))
                .build()
            val enc = DefaultEncoderFactory.Builder(this)
                .setRequestedVideoEncoderSettings(
                    VideoEncoderSettings.Builder().setBitrate(p.videoBitrate).build()
                )
                .build()
            val t = Transformer.Builder(this)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(enc)
                .addListener(listener)
                .build()
            transformer = t
            t.start(item, tmp.absolutePath)
            main.postDelayed(ticker, 1000)
        } catch (e: Exception) {
            fail(e.message ?: "start error")
        }
    }

    private val listener = object : Transformer.Listener {
        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
            main.removeCallbacks(ticker)
            onDone()
        }

        override fun onError(
            composition: Composition,
            exportResult: ExportResult,
            exportException: ExportException
        ) {
            main.removeCallbacks(ticker)
            fail(exportException.message ?: "export error")
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val t = transformer ?: return
            val job = current ?: return
            val p = plan ?: return
            if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                val pct = holder.progress
                val elapsed = (SystemClock.elapsedRealtime() - startTime) / 1000.0
                val frac = pct / 100.0
                val speed = if (elapsed > 0) (frac * p.durationMs / 1000.0 / elapsed).toFloat() else 0f
                val eta = if (frac > 0.01) (elapsed / frac - elapsed).toLong() else 0L
                io.execute { dao.setProgress(job.id, pct, speed, eta) }
                val line = "${job.resolution}p • ${job.fps} FPS • $pct% • ${"%.2f".format(speed)}x • ETA ${eta / 60} min"
                val n = buildNoti("🎬 ${job.originalName}", line, pct, true)
                getSystemService(NotificationManager::class.java).notify(NOTI_ID, n)
            }
            main.postDelayed(this, 1000)
        }
    }

    private fun onDone() {
        val job = current ?: return
        val tmp = tempFile ?: return
        transformer = null
        io.execute {
            try {
                val saved = saveOutput(job, tmp)
                tmp.delete()
                dao.update(
                    job.copy(
                        status = "COMPLETED", progress = 100, outputName = saved.first,
                        outputUri = saved.second, completedAt = System.currentTimeMillis(), etaSeconds = 0
                    )
                )
                completedCount++
                notifyDone(saved.first, saved.second)
            } catch (e: Exception) {
                dao.update(job.copy(status = "FAILED", error = (e.message ?: "save error").take(300)))
            }
            main.post {
                current = null
                next()
            }
        }
    }

    private fun fail(msg: String) {
        val job = current ?: return
        main.removeCallbacks(ticker)
        transformer = null
        tempFile?.delete()
        current = null
        io.execute {
            dao.update(job.copy(status = "FAILED", error = msg.take(300)))
            main.post { next() }
        }
    }

    private fun stopCurrent(status: String): Boolean {
        val job = current ?: return false
        main.removeCallbacks(ticker)
        transformer?.cancel()
        transformer = null
        tempFile?.delete()
        current = null
        io.execute {
            dao.update(job.copy(status = status, progress = 0, error = null))
            main.post { if (status == "PAUSED") finishAll() else next() }
        }
        return true
    }

    private fun finishAll() {
        stopForeground(true)
        stopSelf()
    }

    private fun saveOutput(job: JobEntity, tmp: File): Pair<String, String> {
        val base = job.originalName.substringBeforeLast('.')
        val name = "${base}_${job.resolution}p_${job.fps}fps.mp4"
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VideoCompressor")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)
                ?: throw IOException("Output create failed")
            val out = contentResolver.openOutputStream(uri) ?: throw IOException("Output open failed")
            out.use { o -> tmp.inputStream().use { it.copyTo(o) } }
            val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            contentResolver.update(uri, done, null, null)
            return Pair(name, uri.toString())
        }
        val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        dir.mkdirs()
        val f = File(dir, name)
        tmp.copyTo(f, true)
        return Pair(name, Uri.fromFile(f).toString())
    }

    private fun baseBuilder(): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else Notification.Builder(this)

    private fun svcIntent(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code, Intent(this, CompressService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun buildNoti(title: String, text: String, pct: Int, actions: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = baseBuilder()
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText("Completed: $completedCount • Waiting: $waitingCount")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (pct >= 0) b.setProgress(100, pct, false) else b.setProgress(100, 0, true)
        if (actions) {
            b.addAction(0, "Pause", svcIntent(ACTION_PAUSE, 1))
            b.addAction(0, "Cancel", svcIntent(ACTION_CANCEL, 2))
        }
        return b.build()
    }

    private fun notifyDone(name: String, uri: String) {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(uri), "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val pi = PendingIntent.getActivity(
            this, 3, view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = baseBuilder()
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("✅ Compression Complete")
            .setContentText(name)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(DONE_ID + completedCount, n)
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        transformer?.cancel()
        val job = current
        if (job != null) io.execute { dao.update(job.copy(status = "INTERRUPTED")) }
        io.shutdown()
        super.onDestroy()
    }
}
