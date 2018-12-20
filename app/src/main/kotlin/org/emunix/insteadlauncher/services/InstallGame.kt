package org.emunix.insteadlauncher.services

import android.app.IntentService
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import org.emunix.insteadlauncher.InsteadLauncher
import org.emunix.insteadlauncher.R
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipException
import android.app.PendingIntent
import org.emunix.insteadlauncher.InsteadLauncher.Companion.CHANNEL_INSTALL
import org.emunix.insteadlauncher.InsteadLauncher.Companion.INSTALL_NOTIFICATION_ID
import org.emunix.insteadlauncher.data.Game.State.*
import org.emunix.insteadlauncher.event.DownloadProgressEvent
import org.emunix.insteadlauncher.helpers.*
import org.emunix.insteadlauncher.helpers.network.ProgressListener
import org.emunix.insteadlauncher.ui.game.GameActivity
import org.emunix.insteadlauncher.helpers.network.ProgressResponseBody


class InstallGame : IntentService("InstallGame") {

    companion object {
        const val CONTENT_LENGTH_UNAVAILABLE = -1L
    }

    private lateinit var gameName: String

    override fun onHandleIntent(intent: Intent?) {
        val url = intent?.getStringExtra("game_url")
        gameName = intent?.getStringExtra("game_name") ?: return
        val game = InsteadLauncher.db.games().getByName(gameName)

        val notificationIntent = Intent(this, GameActivity::class.java)
        notificationIntent.putExtra("game_name", gameName)
        notificationIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT)

        val notification = NotificationCompat.Builder(this, CHANNEL_INSTALL)
                .setContentTitle(gameName)
                .setContentText(getText(R.string.notification_download_and_install_game))
                .setSmallIcon(R.drawable.ic_download_white_24dp)
                .setContentIntent(pendingIntent)
                .build()

        startForeground(INSTALL_NOTIFICATION_ID, notification)

        if (url != null) {
            try {
                game.saveStateToDB(IS_INSTALL)
                val zipfile = File(externalCacheDir, extractFilename(url))
                download(url, zipfile)
                val gameDir = File(StorageHelper(this).getGamesDirectory(), gameName)
                gameDir.deleteRecursively()
                zipfile.unzip(StorageHelper(this).getGamesDirectory())
                zipfile.deleteRecursively()
                game.saveStateToDB(INSTALLED)
                game.saveInstalledVersionToDB(game.version)
            } catch (e: IndexOutOfBoundsException) {
                // invalid url (exception from String.substring)
                sendNotification(getString(R.string.error), "Bad url: $url", pendingIntent)
                game.saveStateToDB(NO_INSTALLED)
            } catch (e: IOException) {
                sendNotification(getString(R.string.error), e.localizedMessage, pendingIntent)
                game.saveStateToDB(NO_INSTALLED)
            } catch (e: ZipException) {
                sendNotification(getString(R.string.error), e.localizedMessage, pendingIntent)
                game.saveStateToDB(NO_INSTALLED)
            }
        }

        stopForeground(true)
    }

    @Throws(IOException::class)
    private fun download(url: String, file: File) {

        val progressListener = object : ProgressListener {
            override fun update(bytesRead: Long, contentLength: Long, done: Boolean) {
                val msg: String = application.getString(R.string.game_activity_message_downloading,
                        FileUtils.byteCountToDisplaySize(bytesRead),
                        if (contentLength == CONTENT_LENGTH_UNAVAILABLE) "??" else FileUtils.byteCountToDisplaySize(contentLength))

                RxBus.publish(DownloadProgressEvent(gameName, bytesRead, contentLength, msg, done))
            }
        }

        val request = Request.Builder().url(url).build()
        val client = OkHttpClient.Builder()
                .addNetworkInterceptor { chain ->
                    val originalResponse = chain.proceed(chain.request())
                    originalResponse.newBuilder()
                            .body(ProgressResponseBody(originalResponse.body()!!, progressListener))
                            .build()
                }
                .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val msg = application.getString(R.string.error_failed_to_download_file, url)
            RxBus.publish(DownloadProgressEvent(gameName,0, 0, "", true, true, msg))
            throw IOException(msg)
        }
        FileOutputStream(file).use { toFile ->
            IOUtils.copy(response.body()?.byteStream(), toFile)
        }
    }

    private fun extractFilename(url: String): String {
        return url.substring(url.lastIndexOf('/') + 1)
    }

    private fun sendNotification(title: String, body: String, intent: PendingIntent) {
        val notification = NotificationCompat.Builder(this, InsteadLauncher.CHANNEL_INSTALL)
                .setSmallIcon(R.drawable.ic_alert_white_24dp)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle()
                        .bigText(body))
                .setContentIntent(intent)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(2, notification.build())
    }
}
