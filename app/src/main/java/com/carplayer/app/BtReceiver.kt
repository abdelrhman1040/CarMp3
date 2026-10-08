package com.carplayer.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

// Declared in the manifest, so the system wakes the app only when a Bluetooth
// device connects or disconnects. Nothing runs in the background otherwise.
class BtReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
        val mac = try {
            dev.address
        } catch (e: SecurityException) {
            return
        }
        val name = try {
            dev.name ?: mac
        } catch (e: SecurityException) {
            mac
        }

        if (action == BluetoothDevice.ACTION_ACL_CONNECTED) {
            // "Add by connecting": the next device that connects is added to the list.
            if (System.currentTimeMillis() < Store.learnUntil(context)) {
                Store.addDevice(context, Device(mac, name))
                Store.setLearnUntil(context, 0L)
                Logger.add(context, "learned device: $name [$mac]")
                Toast.makeText(context, "Car Player: added $name", Toast.LENGTH_LONG).show()
            }
            val trusted = Store.isTrusted(context, mac)
            Logger.add(context, "connected: $name [$mac] trusted=$trusted")
            if (!trusted) return

            val svc = PlayerService.instance
            if (svc != null) {
                svc.onCarConnected()
            } else {
                try {
                    context.startForegroundService(
                        Intent(context, PlayerService::class.java).setAction(PlayerService.A_BT_CONNECTED)
                    )
                    Logger.add(context, "auto-start: OK")
                } catch (e: Exception) {
                    Logger.add(context, "auto-start FAILED: ${e.javaClass.simpleName}")
                    fallback(context)
                }
            }
        } else if (action == BluetoothDevice.ACTION_ACL_DISCONNECTED) {
            if (Store.isTrusted(context, mac)) {
                Logger.add(context, "disconnected: $name [$mac]")
                PlayerService.instance?.onCarDisconnected()
            }
        }
    }

    // If Android refuses to start the service from the background,
    // show a notification; tapping it starts playback.
    private fun fallback(ctx: Context) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel("alert", "Car connected", NotificationManager.IMPORTANCE_HIGH)
            )
            val i = Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_AUTOPLAY)
            val pi = PendingIntent.getActivity(
                ctx, 9, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = Notification.Builder(ctx, "alert")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Car connected")
                .setContentText("Tap to start playing")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(2, n)
        } catch (e: Exception) {
        }
    }
}
