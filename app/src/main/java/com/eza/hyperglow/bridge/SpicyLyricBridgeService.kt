package com.eza.hyperglow.bridge

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.eza.hyperglow.AppLog
import java.util.concurrent.Executors

class SpicyLyricBridgeService : Service() {
    private val documentExecutor = Executors.newSingleThreadExecutor()
    private var documentArrivalRevision = 0L
    private var lastStateLogKey = ""

    private val binder = object : ISpicyLyricBridge.Stub() {
        override fun publishState(state: Bundle?) {
            if (!CallerValidator.isSpotify(this@SpicyLyricBridgeService) || state == null) return
            val accepted = try {
                SpicyBridgeStore.accept(state)
            } catch (error: Exception) {
                AppLog.w(TAG, "Rejected malformed state", error)
                false
            }
            if (!accepted) AppLog.w(TAG, "Rejected stale or invalid state") else logTransition()
        }

        override fun publishDocument(metadata: Bundle?, document: ParcelFileDescriptor?) {
            if (!CallerValidator.isSpotify(this@SpicyLyricBridgeService) ||
                metadata == null || document == null) {
                runCatching { document?.close() }
                return
            }
            val ownedMetadata = try {
                SpicyBridgeDocumentMetadata.from(metadata)
            } catch (error: Exception) {
                runCatching { document.close() }
                AppLog.w(TAG, "Rejected malformed document metadata", error)
                return
            } ?: run {
                runCatching { document.close() }
                AppLog.w(TAG, "Rejected malformed document metadata")
                return
            }
            val arrivalRevision = nextDocumentArrivalRevision()
            try {
                documentExecutor.execute {
                    try {
                        val accepted = try {
                            SpicyBridgeDocumentStore.accept(
                                metadata = ownedMetadata,
                                descriptor = document,
                                arrivalRevision = arrivalRevision
                            )
                        } catch (error: Exception) {
                            AppLog.w(TAG, "Rejected malformed document", error)
                            false
                        }
                        if (accepted) {
                            AppLog.i(TAG, "Accepted document generation=${ownedMetadata.generation}")
                        } else {
                            AppLog.w(TAG, "Rejected stale or invalid document")
                        }
                    } finally {
                        runCatching { document.close() }
                    }
                }
            } catch (error: Exception) {
                runCatching { document.close() }
                AppLog.w(TAG, "Document queue rejected", error)
            }
        }

        override fun clearState(producerId: String?, generation: Long) {
            if (!CallerValidator.isSpotify(this@SpicyLyricBridgeService)) return
            SpicyBridgeStore.clear(producerId.orEmpty(), generation)
            SpicyBridgeDocumentStore.clear(producerId.orEmpty(), generation)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        documentExecutor.shutdownNow()
        super.onDestroy()
    }

    @Synchronized
    private fun nextDocumentArrivalRevision(): Long = ++documentArrivalRevision

    @Synchronized
    private fun logTransition() {
        val state = SpicyBridgeStore.state.value ?: return
        val key = "${state.producerId}:${state.generation}:${state.status}:${state.playing}"
        if (key == lastStateLogKey) return
        lastStateLogKey = key
        AppLog.i(TAG, "Accepted generation=${state.generation} status=${state.status} playing=${state.playing}")
    }

    companion object {
        private const val TAG = "SpicyBridgeService"
    }
}
