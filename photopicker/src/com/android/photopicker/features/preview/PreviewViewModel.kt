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

import android.content.ContentProviderClient
import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.os.RemoteException
import android.provider.CloudMediaProviderContract.EXTRA_AUTHORITY
import android.provider.CloudMediaProviderContract.EXTRA_LOOPING_PLAYBACK_ENABLED
import android.provider.CloudMediaProviderContract.EXTRA_SURFACE_CONTROLLER
import android.provider.CloudMediaProviderContract.EXTRA_SURFACE_CONTROLLER_AUDIO_MUTE_ENABLED
import android.provider.CloudMediaProviderContract.EXTRA_SURFACE_STATE_CALLBACK
import android.provider.CloudMediaProviderContract.METHOD_CREATE_SURFACE_CONTROLLER
import android.provider.ICloudMediaSurfaceController
import android.provider.ICloudMediaSurfaceStateChangedCallback
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.os.bundleOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.events.Event
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.events.Telemetry
import com.android.photopicker.core.features.FeatureToken
import com.android.photopicker.core.selection.GrantsAwareSelectionImpl
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.core.selection.SelectionModifiedResult.FAILURE_SELECTION_LIMIT_EXCEEDED
import com.android.photopicker.core.selection.SelectionStrategy
import com.android.photopicker.core.user.UserMonitor
import com.android.photopicker.data.DataService
import com.android.photopicker.data.model.Media
import com.android.providers.media.flags.Flags
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The view model for the Preview routes.
 *
 * This view model manages snapshots of the session's selection so that items can observe a slice of
 * state rather than the mutable selection state.
 *
 * Additionally, [RemoteSurfaceController] are created and held for re-use in the scope of this view
 * model. The view model handles the [ICloudMediaSurfaceStateChangedCallback] for each controller,
 * and stores the information for the UI to obtain via exported flows.
 */
@HiltViewModel
class PreviewViewModel
@Inject
constructor(
    private val scopeOverride: CoroutineScope?,
    private val selection: Selection<Media>,
    private val userMonitor: UserMonitor,
    private val dataService: DataService,
    private val events: Events,
    private val configManager: ConfigurationManager,
) : ViewModel() {

    companion object {
        val TAG: String = PreviewFeature.TAG

        // These are the authority strings for [CloudMediaProvider]-s for local on device files.
        private val PHOTOPICKER_PROVIDER_AUTHORITY = "com.android.providers.media.photopicker"
        private val REMOTE_PREVIEW_PROVIDER_AUTHORITY =
            "com.android.providers.media.remote_video_preview"
    }

    // Request Media in batches of 10 items
    private val PREVIEW_PAGER_PAGE_SIZE = 10

    // Keep up to 5 pages loaded in memory before unloading pages.
    private val PREVIEW_PAGER_MAX_ITEMS_IN_MEMORY = PREVIEW_PAGER_PAGE_SIZE * 5

    // Check if a scope override was injected before using the default [viewModelScope]
    private val scope: CoroutineScope =
        if (scopeOverride == null) {
            this.viewModelScope
        } else {
            scopeOverride
        }

    /**
     * A flow which exposes a snapshot of the selection. Initially this is an empty set and will not
     * automatically update with the current selection, snapshots must be explicitly requested.
     */
    val selectionSnapshot = MutableStateFlow<Set<Media>>(emptySet())

    val deselectionSnapshot = MutableStateFlow<Set<Media>>(emptySet())

    init {
        // The snapshot is taken once when the ViewModel is first created
        takeNewSelectionSnapshot()
    }

    /** Trigger a new snapshot of the selection. */
    @VisibleForTesting
    fun takeNewSelectionSnapshot() {
        scope.launch {
            selectionSnapshot.update { selection.snapshot() }
            deselectionSnapshot.update { selection.getDeselection().toHashSet() }
        }
    }

    /**
     * Toggle the media item into the current session's selection.
     *
     * @param media
     */
    fun toggleInSelection(media: Media, onSelectionLimitExceeded: () -> Unit) {
        scope.launch {
            val result = selection.toggle(item = media)
            if (result == FAILURE_SELECTION_LIMIT_EXCEEDED) {
                onSelectionLimitExceeded()
            }
        }
    }

    fun toggleInSelection(media: Collection<Media>, onSelectionLimitExceeded: () -> Unit) {
        scope.launch {
            val result = selection.toggleAll(media)
            if (result == FAILURE_SELECTION_LIMIT_EXCEEDED) {
                onSelectionLimitExceeded()
            }
        }
    }

    /**
     * Provides a flow containing paging data for items that needs to be displayed on the preview
     * view.
     *
     * It takes into account pre-grants, selections and de-selections.
     */
    fun getPreviewMediaIncludingPreGrantedItems(
        selectionSet: Set<Media>,
        selectionStrategy: SelectionStrategy,
        isSingleItemPreview: Boolean = false,
    ): Flow<PagingData<Media>> {
        val flow =
            if (isSingleItemPreview) flowOf(PagingData.from(selectionSet.toList()))
            else {
                when (selectionStrategy) {
                    SelectionStrategy.DEFAULT -> flowOf(PagingData.from(selectionSet.toList()))
                    SelectionStrategy.GRANTS_AWARE_SELECTION -> {
                        val deselectAllEnabled =
                            if (selection is GrantsAwareSelectionImpl) {
                                selection.isDeSelectAllEnabled
                            } else {
                                false
                            }
                        if (deselectAllEnabled) {
                            flowOf(PagingData.from(selectionSet.toList()))
                        } else {
                            val pager =
                                Pager(
                                    PagingConfig(
                                        pageSize = PREVIEW_PAGER_PAGE_SIZE,
                                        maxSize = PREVIEW_PAGER_MAX_ITEMS_IN_MEMORY,
                                    )
                                ) {
                                    dataService.previewMediaPagingSource(
                                        PREVIEW_PAGER_PAGE_SIZE,
                                        selectionSnapshot.value,
                                        deselectionSnapshot.value,
                                    )
                                }
                            pager.flow
                        }
                    }
                }
            }

        /** Export the data from the pager and prepare it for use in the [Preview] */
        val data = flow.cachedIn(scope)
        return data
    }

    /**
     * Holds any cached [RemotePreviewControllerInfo] to avoid re-creating
     * [RemoteSurfaceController]-s that already exist during a preview session.
     */
    val controllers: HashMap<String, RemotePreviewControllerInfo> = HashMap()

    /**
     * A flow that all [ICloudMediaSurfaceStateChangedCallback] push their [setPlaybackState]
     * updates to. This flow is later filtered to a specific (authority + surfaceId) pairing for
     * providing the playback state updates to the UI composables to collect.
     *
     * A shared flow is used here to ensure that all emissions are delivered since a StateFlow will
     * conflate deliveries to slow receivers (sometimes the UI is slow to pull emissions) to this
     * flow since they happen in quick succession, and this will avoid dropping any.
     *
     * See [getPlaybackInfoForPlayer] where this flow is filtered.
     */
    private val _playbackInfo = MutableSharedFlow<PlaybackInfo>()

    /**
     * Creates a [Flow<PlaybackInfo>] for the provided player configuration. This just siphons the
     * larger [playbackInfo] flow that all of the [ICloudMediaSurfaceStateChangedCallback]-s push
     * their updates to.
     *
     * The larger flow is filtered for updates related to the requested video session. (surfaceId +
     * authority)
     */
    fun getPlaybackInfoForPlayer(surfaceId: Int, video: Media.Video): Flow<PlaybackInfo> {
        return _playbackInfo.filter { it.surfaceId == surfaceId && it.authority == video.authority }
    }

    /** @return the active user's [ContentResolver]. */
    fun getContentResolverForCurrentUser(): ContentResolver {
        return userMonitor.userStatus.value.activeContentResolver
    }

    /**
     * Obtains an instance of [RemoteSurfaceController] for the requested authority. Attempts to
     * re-use any controllers that have previously been fetched, and additionally, generates a
     * [RemotePreviewControllerInfo] for the requested authority and holds it in [controllers] for
     * future re-use. If a controller cannot be fetched from the cloud provider, we fallback to the
     * local implementation of CloudMediaSurfaceController.
     *
     * @param authority authority of the video to be previewed
     * @return A [RemoteSurfaceController] for [authority]
     */
    fun getControllerForAuthority(authority: String): RemoteSurfaceController {
        var controller = getControllerForAuthority(authority, fallbackToLocal = false)

        // Fallback to local if necessary and enabled
        if (controller == null && Flags.enableCmpImprovements()) {
            Log.d(
                TAG,
                "Couldn't fetch a cloud controller, falling back to " +
                    "local controller for $authority",
            )
            controller = getControllerForAuthority(authority, fallbackToLocal = true)
        }

        // 3. Final safety check with a clear error message
        return checkNotNull(controller) { "Unable to obtain a surface controller for $authority" }
    }

    /**
     * Fetches and returns a [RemoteSurfaceController] for [authority] for video playback. Based on
     * [fallbackToLocal] value, it determines if the requested controller is to be fetched from the
     * cloud provider or if the local controller implementation should be used in case cloud fails
     * to return one.
     *
     * @param mediaSourceAuthority authority of the video to be previewed.
     * @param fallbackToLocal determines if the RemoteSurfaceController is to be fetched from the
     *   cloud provider or the remote provider.
     */
    private fun getControllerForAuthority(
        mediaSourceAuthority: String,
        fallbackToLocal: Boolean = false,
    ): RemoteSurfaceController? {

        if (controllers.containsKey(mediaSourceAuthority)) {
            Log.d(TAG, "Existing controller found, re-using for $mediaSourceAuthority")
            return controllers.getValue(mediaSourceAuthority).controller
        }

        Log.d(TAG, "Creating controller for authority: $mediaSourceAuthority")

        // We're determining the authority of the provider which will eventually return us the
        // controller for video playback. In case the cloud provider fails to return us one, we
        // fallback to the local RemoteVideoPreviewProvider which creates and provides us with a
        // local implementation of RemoteSurfaceController that we can eventually use.
        val controllerCreatorAuthority =
            if (fallbackToLocal) REMOTE_PREVIEW_PROVIDER_AUTHORITY else mediaSourceAuthority

        // For local photos or cloud videos rendered by a local surface controller which use the
        // PhotopickerProvider, the remote video preview  functionality is actually delegated to
        // the mediaprovider:Photopicker process and is run out of the RemoteVideoPreviewProvider,
        // so for the purposes of acquiring a [ContentProviderClient], use a different authority.
        // In case the local controller needs to be fetched, the [ContentProviderClient] should be
        // fetched using the local provider authority.
        val remotePreviewClientAuthority =
            if (fallbackToLocal || mediaSourceAuthority == PHOTOPICKER_PROVIDER_AUTHORITY) {
                REMOTE_PREVIEW_PROVIDER_AUTHORITY
            } else {
                mediaSourceAuthority
            }

        Log.d(
            TAG,
            "Fetching controller for authority: $controllerCreatorAuthority" +
                " while binding to the client with authority: $remotePreviewClientAuthority ",
        )

        // Acquire a [ContentProviderClient] that can be retained as long as the [PreviewViewModel]
        // is active. This creates a binding between the current process that is running Photopicker
        // and the remote process that is rendering video and prevents the remote process from being
        // killed by the OS. This client is held onto until the [PreviewViewModel] is cleared when
        // the Preview route is navigated away from. (The PreviewViewModel is bound to the
        // navigation backStackEntry).
        val remoteClient =
            getContentResolverForCurrentUser()
                .acquireContentProviderClient(remotePreviewClientAuthority)
        // TODO: b/323833427 Navigate back to the main grid when a controller cannot be obtained.
        checkNotNull(remoteClient) { "Unable to get a client for $remotePreviewClientAuthority" }

        // Don't reuse the remote client from above since it may not be the right provider for
        // local files. Instead, assemble a new URI, and call the correct provider via
        // [ContentResolver#call]
        val uri = getUriToFetchController(controllerCreatorAuthority)

        val extras =
            getControllerExtras(
                fallbackToLocal = fallbackToLocal,
                mediaSourceAuthority = mediaSourceAuthority,
            )

        val controllerBundle: Bundle? =
            getContentResolverForCurrentUser()
                .call(
                    /*uri=*/ uri,
                    /*method=*/ METHOD_CREATE_SURFACE_CONTROLLER,
                    /*arg=*/ null,
                    /*extras=*/ extras,
                )
        if (controllerBundle == null) {
            Log.w(TAG, "Null bundle returned for $mediaSourceAuthority")
            remoteClient.close()
            return null
        }

        val binder = controllerBundle.getBinder(EXTRA_SURFACE_CONTROLLER)
        if (binder == null) {
            Log.w(TAG, "Null binder retrieved for $mediaSourceAuthority")
            remoteClient.close()
            return null
        }

        val configuration = configManager.configuration.value
        // UI event to mark the start of surface controller creation
        scope.launch {
            events.dispatch(
                Event.LogPhotopickerUIEvent(
                    FeatureToken.PREVIEW.token,
                    configuration.sessionId,
                    configuration.callingPackageUid ?: -1,
                    Telemetry.UiEvent.CREATE_SURFACE_CONTROLLER_START,
                )
            )
        }

        // Produce the [RemotePreviewControllerInfo] and save it for future re-use.
        val controllerInfo =
            RemotePreviewControllerInfo(
                authority = mediaSourceAuthority,
                client = remoteClient,
                controller =
                    RemoteSurfaceController(ICloudMediaSurfaceController.Stub.asInterface(binder)),
            )
        controllers.put(mediaSourceAuthority, controllerInfo)

        return controllerInfo.controller
    }

    /**
     * Prepares the bundle of extras to be sent in the request to obtain a [RemoteSurfaceController]
     * for video preview.
     *
     * @param fallbackToLocal boolean value determining if we need to fallback to the local
     *   controller in case we were not able to fetch the remote controller.
     * @param mediaSourceAuthority authority of the video to be previewed.
     * @return [Bundle] of extras to be sent in the request to fetch the controller for video
     *   playback.
     */
    private fun getControllerExtras(
        fallbackToLocal: Boolean,
        mediaSourceAuthority: String,
    ): Bundle {
        val callback = buildSurfaceStateChangedCallback(mediaSourceAuthority)
        @Suppress("DEPRECATION")
        val controllerBundle =
            bundleOf(
                EXTRA_LOOPING_PLAYBACK_ENABLED to true,
                EXTRA_SURFACE_CONTROLLER_AUDIO_MUTE_ENABLED to true,
                EXTRA_SURFACE_STATE_CALLBACK to callback,
            )
        if (fallbackToLocal) {
            controllerBundle.putString(EXTRA_AUTHORITY, mediaSourceAuthority)
        }
        return controllerBundle
    }

    /**
     * Builds the request [Uri] to obtain a [RemoteSurfaceController] for video playback.
     *
     * @param authority authority of the provider to fetch the controller from
     * @return [Uri] to request the controller from the provider
     */
    private fun getUriToFetchController(authority: String): Uri {
        return Uri.Builder()
            .apply {
                scheme(ContentResolver.SCHEME_CONTENT)
                authority(authority)
            }
            .build()
    }

    /**
     * When this ViewModel is cleared, close any held [ContentProviderClient]s that are retained for
     * video rendering.
     */
    override fun onCleared() {
        // When the view model is cleared then it is safe to assume the preview route is no longer
        // active, and any [ContentProviderClient] that are being held to support remote video
        // preview can now be closed.
        for ((_, controllerInfo) in controllers) {

            try {
                controllerInfo.controller.onDestroy()
                val configuration = configManager.configuration.value
                // UI event to mark the end of surface controller creation
                scope.launch {
                    events.dispatch(
                        Event.LogPhotopickerUIEvent(
                            FeatureToken.PREVIEW.token,
                            configuration.sessionId,
                            configuration.callingPackageUid ?: -1,
                            Telemetry.UiEvent.CREATE_SURFACE_CONTROLLER_END,
                        )
                    )
                }
            } catch (e: RemoteException) {
                Log.d(TAG, "Failed to destroy surface controller.", e)
            }

            controllerInfo.client.close()
        }
    }

    /**
     * Constructs a [ICloudMediaSurfaceStateChangedCallback] for the provided authority.
     *
     * @param authority The authority this callback will assign to its PlaybackInfo emissions.
     * @return A [ICloudMediaSurfaceStateChangedCallback] bound to the provided authority.
     */
    private fun buildSurfaceStateChangedCallback(
        authority: String
    ): ICloudMediaSurfaceStateChangedCallback.Stub {
        return object : ICloudMediaSurfaceStateChangedCallback.Stub() {
            override fun setPlaybackState(
                surfaceId: Int,
                playbackState: Int,
                playbackStateInfo: Bundle?,
            ) {
                scope.launch {
                    _playbackInfo.emit(
                        PlaybackInfo(
                            state = PlaybackState.fromStateInt(playbackState),
                            surfaceId = surfaceId,
                            authority = authority,
                            playbackStateInfo = playbackStateInfo,
                        )
                    )
                }
            }
        }
    }
}
