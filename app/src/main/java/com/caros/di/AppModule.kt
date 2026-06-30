package com.caros.di

import android.content.Context
import com.caros.audio.AdaptiveEQEngine
import com.caros.audio.AudioEngineManager
import com.caros.core.RootManager
import com.caros.core.ShellExecutor
import com.caros.vcds.OBDConnection
import com.caros.vcds.VCDSManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * AppModule — singleton-scoped bindings that cannot use @Inject constructor
 * (third-party types or types requiring non-trivial construction).
 *
 * AudioEngineManager, VCDSManager, OBDConnection already use @Inject
 * constructor so they are provided by Hilt automatically; only OkHttpClient
 * and other infrastructure objects need explicit @Provides here.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}
