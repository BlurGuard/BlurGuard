package com.nash.core.common.di

import javax.inject.Qualifier

/** Qualifies the face [com.nash.core.model.Detector] binding. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FaceDetection

/** Qualifies the license-plate [com.nash.core.model.Detector] binding. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PlateDetection