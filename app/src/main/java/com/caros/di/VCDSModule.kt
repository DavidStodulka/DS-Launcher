package com.caros.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * VCDSModule — declares the VCDS subsystem's DI graph.
 *
 * All VCDS classes (OBDConnection, DTCReader, CodingPresets, CodingHistory,
 * VCDSManager) use @Singleton @Inject constructor so Hilt provides them
 * without explicit @Provides methods.
 *
 * Dependency chain:
 *   ShellExecutor
 *     └─ OBDConnection(@ApplicationContext, ShellExecutor)
 *         ├─ DTCReader(OBDConnection)
 *         ├─ CodingPresets(OBDConnection)
 *         └─ VCDSManager(OBDConnection, DTCReader, CodingPresets, CodingHistory)
 */
@Module
@InstallIn(SingletonComponent::class)
object VCDSModule
