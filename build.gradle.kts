package com.example.autotuner

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Environment
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import java.io.File
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private var recorded: ShortArray? = null
    private var tuned: ShortArray? = null
    @Volatile private var recording = false
    private var track: AudioTrack? = null
    private lateinit var status: TextView
    private lateinit var keySpin: Spinner
    private lateinit var scaleSpin: Spinner
    private lateinit var strength: SeekBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        fun label(t: String) = TextView(this).apply { text = t }.also { root.addView(it) }
        fun button(t: String, f: () -> Unit) =
            Button(this).apply { text = t; setOnClickListener { f() } }.also { root.addView(it) }

        status = TextView(this).apply { text = "Record karo, phir Tune dabao"; textSize = 16f }
        root.addView(status)
        button("Record start") { startRec() }
        button("Record stop") { stopRec() }

        label("Key")
        keySpin = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, AutoTune.NOTES)
        }
        root.addView(keySpin)

        label("Scale")
        scaleSpin = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, AutoTune.SCALE_NAMES)
        }
        root.addView(scaleSpin)

        label("Strength (zyada = robotic Auto-Tune)")
        strength = SeekBar(this).apply { max = 100; progress = 100 }
        root.addView(strength)

        button("Tune karo") { tune() }
        button("Original suno") { play(recorded) }
        button("Tuned suno") { play(tuned) }
        setContentView(root)
    }

    private fun startRec() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            status.text = "Mic permission de do, phir dobara Record dabao"
            return
        }
        if (recording) return
        recording = true
        status.text = "Recording..."
        thread {
            val min = AudioRecord.getMinBufferSize(
                AutoTune.SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC, AutoTune.SR,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2
            )
            val chunks = ArrayList<ShortArray>()
            val buf = ShortArray(min)
            var total = 0
            rec.startRecording()
            while (recording) {
                val r = rec.read(buf, 0, buf.size)
                if (r > 0) { chunks.add(buf.copyOf(r)); total += r }
            }
            rec.stop(); rec.release()
            val all = ShortArray(total)
            var p = 0
            for (c in chunks) { c.copyInto(all, p); p += c.size }
            recorded = all
            tuned = null
            runOnUiThread { status.text = "Record ho gaya: ${total / AutoTune.SR} sec" }
        }
    }

    private fun stopRec() { recording = false }

    private fun tune() {
        val r = recorded ?: run { status.text = "Pehle record karo"; return }
        status.text = "Tune ho raha hai..."
        val key = keySpin.selectedItemPosition
        val scale = AutoTune.SCALE_NAMES[scaleSpin.selectedItemPosition]
        val s = strength.progress / 100f
        thread {
            val t = AutoTune.process(r, key, scale, s)
            tuned = t
            val dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            val f = File(dir, "autotuned_${System.currentTimeMillis()}.wav")
            AutoTune.writeWav(f, t)
            runOnUiThread { status.text = "Ho gaya! Saved:\n${f.absolutePath}" }
        }
    }

    private fun play(data: ShortArray?) {
        if (data == null || data.isEmpty()) { status.text = "Pehle record/tune karo"; return }
        track?.release()
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(AutoTune.SR)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setBufferSizeInBytes(data.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        t.write(data, 0, data.size)
        t.play()
        track = t
    }
}
