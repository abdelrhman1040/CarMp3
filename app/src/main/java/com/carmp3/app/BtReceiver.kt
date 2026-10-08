package com.carmp3.app

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * هذا هو "الاستيقاظ الذكي": النظام يشغّل هذا المستقبِل لحظياً عند اتصال/انقطاع أي جهاز بلوتوث.
 * إن لم يكن الجهاز معتمداً نخرج فوراً ولا يُشغَّل أي شيء آخر.
 */
class BtReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != BluetoothDevice.ACTION_ACL_CONNECTED &&
            action != BluetoothDevice.ACTION_ACL_DISCONNECTED
        ) return

        val device = getDevice(intent) ?: return
        val address = device.address
        val name = try {
            device.name
        } catch (e: SecurityException) {
            null // إذن "الأجهزة القريبة" غير ممنوح بعد
        }

        if (action == BluetoothDevice.ACTION_ACL_CONNECTED) {
            val prefs = Prefs(context)
            val explicit = prefs.matchesList(address, name)
            if (!explicit && !prefs.anyDevice) return // ليس جهازنا → العودة للخمول

            val svc = Intent(context, PlaybackService::class.java)
                .setAction(PlaybackService.ACTION_BT_CONNECTED)
                .putExtra(PlaybackService.EXTRA_ADDRESS, address)
                .putExtra(PlaybackService.EXTRA_EXPLICIT, explicit)
            try {
                context.startForegroundService(svc)
            } catch (e: Exception) {
                Log.w("BtReceiver", "cannot start service", e)
            }
        } else {
            // الخدمة تعمل في نفس العملية؛ إن لم تكن تعمل فلا شيء لإيقافه
            PlaybackService.instance?.onBtDisconnected(address)
        }
    }

    @Suppress("DEPRECATION")
    private fun getDevice(i: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33)
            i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else
            i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
}
