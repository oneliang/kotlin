/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.storage

/**
 * The exception means that computation started under StorageManager failed unsuccessfully and cannot be recovered,
 * thus all caches need to be flushed even in case of ProcessCanceledException.
 */
class CorruptingComputationException(val wrapped: Throwable) : Exception()
