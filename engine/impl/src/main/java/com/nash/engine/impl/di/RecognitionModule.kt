package com.nash.engine.impl.di

import androidx.camera.core.ImageProxy
import com.nash.core.model.FaceRecognizer
import com.nash.core.model.RecognitionConfig
import com.nash.core.model.TrustedPersonStore
import com.nash.engine.ml.recognition.MobileFaceNetRecognizer
import com.nash.engine.recognition.SessionTrustedPersonStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Face-recognition bindings: the embedding recognizer implementation and
 * the session-scoped trusted-person gallery it enrols into (split out of
 * the former EngineImplModule — review fix: focused, cohesive DI modules).
 */
@Module
@InstallIn(SingletonComponent::class)
object RecognitionModule {

    @Provides
    @Singleton
    fun provideFaceRecognizer(
        recognizer: MobileFaceNetRecognizer,
    ): FaceRecognizer<ImageProxy> = recognizer

    @Provides
    @Singleton
    fun provideTrustedPersonStore(config: RecognitionConfig): TrustedPersonStore =
        SessionTrustedPersonStore(
            maxGallerySize = config.maxGallerySize,
            duplicateSimilarity = config.duplicateSimilarity,
        )
}