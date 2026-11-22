package org.exponio.app

import android.os.Bundle
import android.view.KeyEvent
import com.getcapacitor.BridgeActivity
import org.exponio.app.camera.CameraPlugin
import org.exponio.app.transfer.TransferPlugin

class MainActivity : BridgeActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Lokale Plugins müssen vor super.onCreate() registriert werden.
        registerPlugin(CameraPlugin::class.java)
        registerPlugin(TransferPlugin::class.java)
        super.onCreate(savedInstanceState)
    }

    /** Lautstärketasten als Auslöser, solange die Kamera-Vorschau aktiv ist. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val plugin = bridge?.getPlugin("PtCamera")?.instance as? CameraPlugin
            if (plugin != null && plugin.handleVolumeKey(event)) return true
        }
        return super.dispatchKeyEvent(event)
    }
}
