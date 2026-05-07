package com.boxofdanger.mmsviewer

import android.content.Context
import android.util.Log
import com.getpebble.android.kit.PebbleKit
import com.getpebble.android.kit.util.PebbleDictionary
import io.rebble.pebblekit2.client.DefaultPebbleSender
import kotlinx.coroutines.*
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Hybrid bridge:
 * - PebbleKit2 (0.1.0) for launching the watch app.
 * - Old PebbleKit (4.0.1) for sending data with ACK flow control.
 *
 * This avoids the PebbleKit2 data-sending bugs while keeping the PebbleKit2 launch success.
 */
class PebbleBridge(context: Context) {

    companion object {
        private const val TAG = "PebbleBridge"

        // Must match the UUID in the Pebble watchapp's package.json
        @JvmField
        val WATCH_APP_UUID: UUID =
            UUID.fromString("25cf01ab-9d74-47cf-bcd4-15d6a832a543")

        // AppMessage keys — must match watch app
        private const val KEY_CHUNK_INDEX  = 0
        private const val KEY_CHUNK_COUNT  = 1
        private const val KEY_CHUNK_DATA   = 2
        private const val KEY_IMAGE_WIDTH  = 3
        private const val KEY_IMAGE_HEIGHT = 4
        private const val KEY_SENDER       = 5

        // Chunk size — must match watch app
        private const val CHUNK_SIZE = 4096

        private const val MAX_RETRIES = 3
        private const val INTER_CHUNK_DELAY_MS = 50L
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var sendJob: Job? = null

    /**
     * Send a processed image to the watch.
     * Launches the watchapp first via PK2, then streams chunks via PK1.
     */
    fun sendImage(image: ImageProcessor.Result, senderName: String?) {
        // Cancel any in-progress send
        sendJob?.cancel()

        sendJob = scope.launch {
            try {
                doSendImage(image, senderName ?: "")
            } catch (e: CancellationException) {
                Log.i(TAG, "Send cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Send failed", e)
            }
        }
    }

    private suspend fun doSendImage(image: ImageProcessor.Result, sender: String) {
        val imageData = image.gcolorData
        val width = image.width
        val height = image.height
        val totalChunks = (imageData.size + CHUNK_SIZE - 1) / CHUNK_SIZE

        Log.i(TAG, "Sending image ${width}x${height} ($totalChunks chunks) from '$sender'")

        val pebble2 = DefaultPebbleSender(appContext)

        try {
            // ── 1. Launch the watch app (PebbleKit2) ─────────────
            Log.i(TAG, "Launching watch app via PK2...")
            try {
                pebble2.startAppOnTheWatch(WATCH_APP_UUID)
            } catch (e: Exception) {
                Log.w(TAG, "startAppOnTheWatch failed: ${e.message}")
            }

            // ── 2. Wait for app to be ready (Polling Chunk 0) ────
            val maxWaitAttempts = 30  // 30 attempts x 2s = 60s max wait
            var appReady = false

            for (attempt in 1..maxWaitAttempts) {
                delay(2000L)

                // Re-launch periodically in case notification pushed us out
                if (attempt % 5 == 0) {
                    try {
                        pebble2.startAppOnTheWatch(WATCH_APP_UUID)
                        Log.d(TAG, "Re-sent PK2 launch command")
                    } catch (_: Exception) {}
                }

                val dict = buildChunkDict(0, totalChunks, image, sender)
                Log.d(TAG, "Polling app with chunk 0 (attempt $attempt)...")
                
                if (sendDictionaryWithAck(dict)) {
                    Log.i(TAG, "App ready! Chunk 0 ACKed on attempt $attempt")
                    appReady = true
                    break
                } else {
                    Log.d(TAG, "Waiting for app... attempt $attempt failed or NACKed")
                }
            }

            if (!appReady) {
                Log.e(TAG, "App never became ready, aborting")
                return
            }

            // ── 3. Send remaining chunks (PebbleKit 1) ───────────
            for (i in 1 until totalChunks) {
                val dict = buildChunkDict(i, totalChunks, image, sender)
                var success = false

                for (retry in 1..MAX_RETRIES) {
                    Log.d(TAG, "Sending chunk $i/$totalChunks (retry $retry)")
                    if (sendDictionaryWithAck(dict)) {
                        success = true
                        break
                    }
                    Log.w(TAG, "Chunk $i failed, retrying in ${500 * retry}ms...")
                    delay(500L * retry)
                }

                if (!success) {
                    Log.e(TAG, "Failed to send chunk $i after $MAX_RETRIES retries, aborting")
                    return
                }

                if (i < totalChunks - 1) {
                    delay(INTER_CHUNK_DELAY_MS)
                }
            }

            Log.i(TAG, "All $totalChunks chunks sent successfully!")

        } finally {
            try { pebble2.close() } catch (_: Exception) {}
        }
    }

    /**
     * Sends a PebbleDictionary and waits for ACK/NACK or timeout.
     */
    private suspend fun sendDictionaryWithAck(dict: PebbleDictionary): Boolean = withTimeoutOrNull(5000L) {
        suspendCancellableCoroutine { cont ->
            var resolved = false

            val ackReceiver = object : PebbleKit.PebbleAckReceiver(WATCH_APP_UUID) {
                override fun receiveAck(context: Context, transactionId: Int) {
                    if (!resolved) {
                        resolved = true
                        try { appContext.unregisterReceiver(this) } catch (_: Exception) {}
                        cont.resume(true)
                    }
                }
            }

            val nackReceiver = object : PebbleKit.PebbleNackReceiver(WATCH_APP_UUID) {
                override fun receiveNack(context: Context, transactionId: Int) {
                    if (!resolved) {
                        resolved = true
                        try { appContext.unregisterReceiver(this) } catch (_: Exception) {}
                        cont.resume(false)
                    }
                }
            }

            // Register receivers for this specific transaction
            PebbleKit.registerReceivedAckHandler(appContext, ackReceiver)
            PebbleKit.registerReceivedNackHandler(appContext, nackReceiver)

            // Send via PK1
            PebbleKit.sendDataToPebble(appContext, WATCH_APP_UUID, dict)

            // Cleanup if coroutine is cancelled (e.g. timeout)
            cont.invokeOnCancellation {
                if (!resolved) {
                    try { appContext.unregisterReceiver(ackReceiver) } catch (_: Exception) {}
                    try { appContext.unregisterReceiver(nackReceiver) } catch (_: Exception) {}
                }
            }
        }
    } ?: false

    private fun buildChunkDict(
        index: Int,
        total: Int,
        image: ImageProcessor.Result,
        sender: String
    ): PebbleDictionary {
        val dict = PebbleDictionary()
        val offset = index * CHUNK_SIZE
        val length = minOf(CHUNK_SIZE, image.gcolorData.size - offset)
        val chunkBytes = image.gcolorData.copyOfRange(offset, offset + length)

        dict.addUint16(KEY_CHUNK_INDEX, index.toShort())
        dict.addBytes(KEY_CHUNK_DATA, chunkBytes)

        // First chunk carries metadata
        if (index == 0) {
            dict.addUint16(KEY_CHUNK_COUNT, total.toShort())
            dict.addUint16(KEY_IMAGE_WIDTH, image.width.toShort())
            dict.addUint16(KEY_IMAGE_HEIGHT, image.height.toShort())
            if (sender.isNotEmpty()) {
                dict.addString(KEY_SENDER, sender)
            }
        }

        return dict
    }

    fun destroy() {
        sendJob?.cancel()
        scope.cancel()
    }
}
