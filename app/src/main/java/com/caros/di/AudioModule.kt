package com.caros.di

import com.caros.audio.AdaptiveEQEngine
import com.caros.audio.AudioEngineManager
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * AudioModule — documents that AudioEngineManager and AdaptiveEQEngine
 * are provided via @Inject constructor in their respective classes.
 *
 * If a test or variant needs a fake AudioEngineManager, replace this module
 * with one that @Binds a test double to an IAudioEngineManager interface.
 */
@Module
@InstallIn(SingletonComponent::class)
object AudioModule
// AudioEngineManager  → @Singleton @Inject constructor(@ApplicationContext context)
// AdaptiveEQEngine    → @Singleton @Inject constructor(context, AudioEngineManager)
// All constructor-injected; Hilt provides them without @Provides methods.
