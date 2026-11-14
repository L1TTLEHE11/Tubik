package dev.tubik.app

import android.app.Application
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.content.pm.PackageManager
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.tubik.app.data.InnerTube
import dev.tubik.app.data.NpDownloader
import dev.tubik.app.data.Prefs
import dev.tubik.app.data.Repository
import dev.tubik.app.data.SponsorBlock
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.Locale
import java.util.concurrent.TimeUnit

class App : Application(), SingletonImageLoader.Factory {
    lateinit var http: OkHttpClient
    lateinit var prefs: Prefs
    lateinit var repo: Repository
    lateinit var sponsorBlock: SponsorBlock

    override fun onCreate() {
        super.onCreate()
        instance = this
        http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        prefs = Prefs(this)

        val lang = Locale.getDefault().language.ifBlank { "ru" }
        val country = Locale.getDefault().country.ifBlank { "RU" }
        NewPipe.init(NpDownloader(http), Localization(lang, country), ContentCountry(country))

        val innerTube = InnerTube(
            http,
            cookieProvider = { prefs.cookies },
            onCookiesUpdated = { if (!prefs.cookies.isNullOrBlank()) prefs.cookies = it },
            hl = lang,
            gl = country,
        )
        repo = Repository(innerTube, prefs)
        sponsorBlock = SponsorBlock(http)
    }

    override fun newImageLoader(context: Context): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { http })) }
            .build()

    companion object {
        lateinit var instance: App
            private set

        fun isTv(context: Context): Boolean {
            val ui = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
            return ui.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
                !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        }
    }
}
