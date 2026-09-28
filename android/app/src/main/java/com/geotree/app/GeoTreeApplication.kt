package com.geotree.app

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory

class GeoTreeApplication : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    /** Coil shares the API's OkHttp client so server images carry the session token. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(container.apiProvider.okHttpClient)
        .crossfade(true)
        .build()
}

val Context.appContainer: AppContainer
    get() = (applicationContext as GeoTreeApplication).container
