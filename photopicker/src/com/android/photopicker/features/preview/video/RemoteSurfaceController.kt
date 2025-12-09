/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.photopicker.features.preview

import android.os.Bundle
import android.os.RemoteException
import android.provider.ICloudMediaSurfaceController
import android.util.Log
import android.view.Surface

/**
 * A wrapper class around [ICloudMediaSurfaceController].
 *
 * This class just proxies all calls through to the wrapped controller. Additionally, this
 * Controller provides methods for the UI for obtaining surfaceId's and manages calling the
 * [onPlayerCreate] and [onPlayerRelease] based on the number of active surfaces.
 *
 * @property wrapped the [ICloudMediaSurfaceController] to wrap.
 */
class RemoteSurfaceController(private val wrapped: ICloudMediaSurfaceController) :
    ICloudMediaSurfaceController.Stub() {

    companion object {
        private const val TAG = "PhotopickerRemoteSurfaceController"
    }

    /** The next unique surface id for this controller */
    private var nextSurfaceId: Int = 0

    /**
     * The total number of active surfaces for this controller. This count is increased during
     * [onSurfaceCreated] and decreased during [onSurfaceDestroyed].
     */
    private var activeSurfaceCount: Int = 0

    /** Get a new surfaceId for a new player surface */
    fun getNextSurfaceId(): Int {
        return ++nextSurfaceId
    }

    /**
     * Pass through of the Surface's [onSurfaceChanged] lifecycle.
     *
     * Additionally, this method manages creating the player if it is required.
     */
    override fun onSurfaceCreated(surfaceId: Int, surface: Surface, mediaId: String) {

        if (activeSurfaceCount == 0) {
            // If this is the first surface being created for this controller,
            // the player needs to be initialized.
            onPlayerCreate()
        }

        activeSurfaceCount++
        try {
            wrapped.onSurfaceCreated(surfaceId, surface, mediaId)
        } catch (e: RemoteException) {
            Log.e(TAG, "onSurfaceCreated: Remote surface controller might be dead", e)
        }
    }

    /** Pass through of the Surface's [onSurfaceChanged] lifecycle. */
    override fun onSurfaceChanged(surfaceId: Int, format: Int, width: Int, height: Int) {
        try {
            wrapped.onSurfaceChanged(surfaceId, format, width, height)
        } catch (e: RemoteException) {
            Log.e(TAG, "onSurfaceChanged: Remote surface controller might be dead", e)
        }
    }

    /** Pass through of the Surface's [onSurfaceDestroyed] lifecycle. */
    override fun onSurfaceDestroyed(surfaceId: Int) {
        try {
            wrapped.onSurfaceDestroyed(surfaceId)
        } catch (e: RemoteException) {
            Log.e(TAG, "onSurfaceDestroyed: Remote surface controller might be dead", e)
        }

        if (--activeSurfaceCount == 0) {
            // If there are no active surfaces left, release the player.
            onPlayerRelease()
        }
    }

    override fun onMediaPlay(surfaceId: Int) {
        try {
            wrapped.onMediaPlay(surfaceId)
        } catch (e: RemoteException) {
            Log.e(TAG, "onMediaPlay: Remote surface controller might be dead", e)
        }
    }

    override fun onMediaPause(surfaceId: Int) {
        try {
            wrapped.onMediaPause(surfaceId)
        } catch (e: RemoteException) {
            Log.e(TAG, "onMediaPause: Remote surface controller might be dead", e)
        }
    }

    override fun onMediaSeekTo(surfaceId: Int, timestampMillis: Long) {
        try {
            wrapped.onMediaSeekTo(surfaceId, timestampMillis)
        } catch (e: RemoteException) {
            Log.e(TAG, "onMediaSeekTo: Remote surface controller might be dead", e)
        }
    }

    override fun onConfigChange(bundle: Bundle) {
        try {
            wrapped.onConfigChange(bundle)
        } catch (e: RemoteException) {
            Log.e(TAG, "onConfigChange: Remote surface controller might be dead", e)
        }
    }

    override fun onDestroy() {
        try {
            wrapped.onDestroy()
        } catch (e: RemoteException) {
            Log.e(TAG, "onDestroy: Remote surface controller might be dead", e)
        }
    }

    override fun onPlayerCreate() {
        try {
            wrapped.onPlayerCreate()
        } catch (e: RemoteException) {
            Log.e(TAG, "onPlayerCreate: Remote surface controller might be dead", e)
        }
    }

    override fun onPlayerRelease() {
        try {
            wrapped.onPlayerRelease()
        } catch (e: RemoteException) {
            Log.e(TAG, "onPlayerRelease: Remote surface controller might be dead", e)
        }
    }
}
