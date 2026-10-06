package com.local.vc

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.local.vc.data.AppDb
import com.local.vc.data.JobEntity
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var listBox: LinearLayout
    private lateinit var spRes: Spinner
    private lateinit var spFps: Spinner
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val pickCode = 101
    private val resVals = listOf(360, 480, 720, 1080)
    private val fpsVals = listOf(24, 30, 60)
    private var lastSig = ""
    private val textMap = HashMap<Long, TextView>()

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        io.execute {
            if (!CompressService.running) AppDb.get(this).jobDao().markInterrupted()
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
        }
        root.addView(TextView(this).apply {
            text = "🎬 Video Compressor"
            textSize = 24f
        })
        root.addView(TextView(this).apply {
            text = "ABI: " + Build.SUPPORTED_ABIS.joinToString()
            textSize = 12f
        })

        val opts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        spRes = Spinner(this)
        spRes.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, resVals.map { "${it}p" }
        )
        spRes.setSelection(2)
        spFps = Spinner(this)
        spFps.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, fpsVals.map { "$it FPS" }
        )
        spFps.setSelection(1)
        opts.addView(spRes)
        opts.addView(spFps)
        root.addView(opts)

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(Button(this).apply {
            text = "+ Select Videos"
            setOnClickListener { pickVideos() }
        })
        bar.addView(Button(this).apply {
            text = "▶ START"
            setOnClickListener { sendService(CompressService.ACTION_START) }
        })
        root.addView(bar)

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            ScrollView(this).apply { addView(listBox) },
            LinearLayout.LayoutParams(-1, 0, 1f)
        )
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }

    private fun sendService(action: String) {
        val i = Intent(this, CompressService::class.java).setAction(action)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    private fun pickVideos() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        startActivityForResult(i, pickCode)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickCode || resultCode != RESULT_OK || data == null) return

        val res = resVals[spRes.selectedItemPosition]
        val fps = fpsVals[spFps.selectedItemPosition]
        val uris = mutableListOf<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (n in 0 until clip.itemCount) uris.add(clip.getItemAt(n).uri)
        } else {
            data.data?.let { uris.add(it) }
        }

        io.execute {
            val dao = AppDb.get(this).jobDao()
            for (u in uris) {
                try {
                    contentResolver.takePersistableUriPermission(
                        u, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                }
                var name = "video"
                var size = 0L
                contentResolver.query(u, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val si = c.getColumnIndex(OpenableColumns.SIZE)
                        if (ni >= 0) name = c.getString(ni) ?: "video"
                        if (si >= 0) size = c.getLong(si)
                    }
                }
                dao.insert(
                    JobEntity(
                        inputUri = u.toString(), originalName = name, originalSize = size,
                        resolution = res, fps = fps
                    )
                )
            }
            runOnUiThread { refresh() }
        }
    }

    private fun refresh() {
        io.execute {
            val jobs = AppDb.get(this).jobDao().all()
            runOnUiThread { render(jobs) }
        }
    }

    private fun render(jobs: List<JobEntity>) {
        val sig = jobs.joinToString(",") { "${it.id}:${it.status}" }
        if (sig == lastSig) {
            for (j in jobs) textMap[j.id]?.text = jobText(j)
            return
        }
        lastSig = sig
        listBox.removeAllViews()
        textMap.clear()
        if (jobs.isEmpty()) {
            listBox.addView(TextView(this).apply { text = "কোনো জব নেই" })
            return
        }
        for (j in jobs) {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 16, 0, 16)
            }
            val tv = TextView(this).apply { text = jobText(j) }
            textMap[j.id] = tv
            col.addView(tv)
            val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

            fun add(label: String, f: () -> Unit) {
                btns.addView(Button(this).apply {
                    text = label
                    setOnClickListener { f() }
                })
            }

            when (j.status) {
                "PROCESSING" -> {
                    add("Pause") { sendService(CompressService.ACTION_PAUSE) }
                    add("Cancel") { sendService(CompressService.ACTION_CANCEL) }
                }
                "WAITING" -> add("Remove") { delete(j) }
                "COMPLETED" -> {
                    add("Play") { play(j) }
                    add("Delete") { delete(j) }
                }
                else -> {
                    add("Resume") {
                        io.execute {
                            AppDb.get(this).jobDao().setStatus(j.id, "WAITING")
                            runOnUiThread { sendService(CompressService.ACTION_START) }
                        }
                    }
                    add("Delete") { delete(j) }
                }
            }
            col.addView(btns)
            listBox.addView(col)
        }
    }

    private fun delete(j: JobEntity) {
        io.execute {
            AppDb.get(this).jobDao().delete(j.id)
            runOnUiThread { refresh() }
        }
    }

    private fun play(j: JobEntity) {
        val u = j.outputUri ?: return
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(u), "video/mp4")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Player not found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun jobText(j: JobEntity): String {
        val icon = when (j.status) {
            "COMPLETED" -> "✅"
            "PROCESSING" -> "🔄"
            "WAITING" -> "⏳"
            "PAUSED" -> "⏸️"
            "FAILED" -> "❌"
            "CANCELLED" -> "🚫"
            else -> "⚠️"
        }
        val mb = "%.1f".format(j.originalSize / 1048576.0)
        val sb = StringBuilder("$icon ${j.originalName}\n${j.resolution}p • ${j.fps} FPS • $mb MB\n${j.status}")
        if (j.status == "PROCESSING") {
            sb.append(" ${j.progress}% • ${"%.2f".format(j.speed)}x • ETA ${j.etaSeconds / 60}m ${j.etaSeconds % 60}s")
        }
        if (j.status == "FAILED" && j.error != null) sb.append("\n${j.error}")
        return sb.toString()
    }
}
