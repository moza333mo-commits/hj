package com.audiotest.routetester.service

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * زر سريع في شريط الإشعارات (Quick Settings Tile) لتفعيل أو إيقاف إجبار مايك الجكة أثناء المكالمات بضغطة واحدة.
 */
class CallMicOverrideTileService : TileService() {

    private var isActiveGuard = false

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        isActiveGuard = !isActiveGuard
        val intent = Intent(this, AudioBridgeForegroundService::class.java).apply {
            action = if (isActiveGuard) {
                AudioBridgeForegroundService.ACTION_START_WATCHDOG
            } else {
                AudioBridgeForegroundService.ACTION_STOP_WATCHDOG
            }
            putExtra(AudioBridgeForegroundService.EXTRA_PREFER_WIRED_JACK, true)
        }

        if (isActiveGuard) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } else {
            startService(intent)
        }
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        tile.state = if (isActiveGuard) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (isActiveGuard) "مايك الجكة: مفعل" else "تثبيت مايك الجكة"
        tile.updateTile()
    }
}
