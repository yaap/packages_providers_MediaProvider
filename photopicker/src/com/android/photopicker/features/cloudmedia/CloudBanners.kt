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

package com.android.photopicker.features.cloudmedia

import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.android.photopicker.R
import com.android.photopicker.core.banners.Banner
import com.android.photopicker.core.banners.BannerDefinition
import com.android.photopicker.core.banners.BannerDefinitions
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerRuntimeEnv
import com.android.photopicker.data.model.CollectionInfo
import com.android.photopicker.data.model.Icon
import com.android.photopicker.data.model.Provider
import com.android.photopicker.data.model.VectorIcon

/**
 * A UI banner that shows the user a message asking them to set their CloudMediaProvider app and
 * provides a secondary action that links to the [ACTION_PICK_IMAGES_SETTINGS] page.
 */
val cloudChooseProviderBanner =
    object : Banner {

        override val declaration = BannerDefinitions.CLOUD_CHOOSE_PROVIDER
        override val bannerDefinition = BannerDefinition.CLOUD_CHOOSE_PROVIDER

        @Composable
        override fun buildTitle(): String {
            val config = LocalPhotopickerConfiguration.current
            return stringResource(
                if (config.flags.PICKER_BANNER_REDESIGN_ENABLED) {
                    R.string.photopicker_banner_cloud_choose_media_app_title
                } else {
                    R.string.photopicker_banner_cloud_choose_provider_title
                }
            )
        }

        @Composable
        override fun buildMessage(): String {
            val config = LocalPhotopickerConfiguration.current
            return stringResource(
                if (config.flags.PICKER_BANNER_REDESIGN_ENABLED) {
                    R.string.photopicker_banner_cloud_choose_media_app_message
                } else {
                    R.string.photopicker_banner_cloud_choose_provider_message
                }
            )
        }

        @Composable override fun getIcon() = VectorIcon(Icons.Outlined.Cloud)

        @Composable
        override fun actionLabel(): String? {
            return stringResource(R.string.photopicker_banner_cloud_choose_app_button)
        }

        override fun onAction(context: Context) {
            context.startActivity(Intent(MediaStore.ACTION_PICK_IMAGES_SETTINGS))
        }
    }

/**
 * Builder for the [BannerDefinitions.CLOUD_CHOOSE_ACCOUNT] banner that shows a secondary action
 * that links to the active CloudMediaProvider's account configuration page.
 *
 * @param cloudProvider the [Provider] details of the active CloudMediaProvider.
 * @param collectionInfo the associated [CollectionInfo] of the active collection with the active
 *   provider.
 * @return The [Banner] to be displayed in the UI.
 */
fun buildCloudChooseAccountBanner(
    cloudProvider: Provider,
    collectionInfo: CollectionInfo,
    providerIcon: Icon?,
): Banner {
    return object : Banner {

        override val declaration = BannerDefinitions.CLOUD_CHOOSE_ACCOUNT
        override val bannerDefinition = BannerDefinition.CLOUD_CHOOSE_ACCOUNT

        @Composable
        override fun buildTitle(): String {
            val config = LocalPhotopickerConfiguration.current
            return if (config.flags.PICKER_BANNER_REDESIGN_ENABLED) {
                stringResource(R.string.photopicker_banner_cloud_select_account_title)
            } else {
                stringResource(
                    R.string.photopicker_banner_cloud_choose_account_title,
                    "${cloudProvider.displayName}",
                )
            }
        }

        @Composable
        override fun buildMessage(): String {
            val config = LocalPhotopickerConfiguration.current
            return stringResource(
                if (config.flags.PICKER_BANNER_REDESIGN_ENABLED) {
                    R.string.photopicker_banner_cloud_select_account_message
                } else {
                    R.string.photopicker_banner_cloud_choose_account_message
                },
                "${cloudProvider.displayName}",
            )
        }

        @Composable override fun getIcon() = providerIcon ?: VectorIcon(Icons.Outlined.Cloud)

        @Composable
        override fun actionLabel(): String? {
            val config = LocalPhotopickerConfiguration.current
            return collectionInfo.accountConfigurationIntent?.let {
                stringResource(
                    if (config.flags.PICKER_BANNER_REDESIGN_ENABLED) {
                        R.string.photopicker_banner_cloud_manage_account_button
                    } else {
                        R.string.photopicker_banner_cloud_choose_account_button
                    }
                )
            }
        }

        override fun onAction(context: Context) {
            collectionInfo.accountConfigurationIntent?.let { context.startActivity(it) }
        }
    }
}

/**
 * Builder for a CloudMediaAvailable banner object that indicates to the user their backed up cloud
 * media is available to be selected in the Photopicker.
 *
 * @param cloudProvider the [Provider] details of the active CloudMediaProvider.
 * @param collectionInfo the associated [CollectionInfo] of the active collection with the active
 *   provider.
 * @return The [Banner] to be displayed in the UI.
 */
fun buildCloudMediaAvailableBanner(
    cloudProvider: Provider,
    collectionInfo: CollectionInfo,
    providerIcon: Icon?,
): Banner {
    return object : Banner {

        override val declaration = BannerDefinitions.CLOUD_MEDIA_AVAILABLE
        override val bannerDefinition = BannerDefinition.CLOUD_MEDIA_AVAILABLE

        @Composable
        override fun buildTitle(): String {
            val config = LocalPhotopickerConfiguration.current
            return stringResource(
                if (config.flags.PICKER_BANNER_REDESIGN_ENABLED) {
                    R.string.photopicker_banner_backed_up_cloud_media_available_title
                } else {
                    R.string.photopicker_banner_cloud_media_available_title
                }
            )
        }

        @Composable
        override fun buildMessage(): String {
            val config = LocalPhotopickerConfiguration.current
            return if (config.flags.PICKER_BANNER_REDESIGN_ENABLED) {
                stringResource(
                    R.string.photopicker_banner_cloud_backed_up_media_available_message,
                    collectionInfo.accountName ?: "",
                    "${cloudProvider.displayName}",
                )
            } else {
                stringResource(
                    R.string.photopicker_banner_cloud_media_available_message,
                    "${cloudProvider.displayName}",
                    collectionInfo.accountName ?: "",
                )
            }
        }

        @Composable
        override fun actionLabel(): String? {
            val config = LocalPhotopickerConfiguration.current
            return if (
                config.flags.PICKER_BANNER_REDESIGN_ENABLED &&
                    config.runtimeEnv != PhotopickerRuntimeEnv.EMBEDDED
            ) {
                stringResource(R.string.photopicker_offline_banner_go_to_settings_button_label)
            } else {
                null
            }
        }

        override fun onAction(context: Context) {
            collectionInfo.accountConfigurationIntent?.let { context.startActivity(it) }
        }

        @Composable override fun getIcon() = providerIcon ?: VectorIcon(Icons.Outlined.Cloud)
    }
}

/**
 * Builder for the [BannerDefinitions.CLOUD_SEARCH_RESULTS_OFFLINE] banner in from search results
 * page that shows a action that takes to network connection page .
 *
 * @param cloudProvider the [Provider] details of the active CloudMediaProvider.
 * @return The [Banner] to be displayed in the UI.
 */
fun buildSearchResultsOfflineBanner(cloudProvider: Provider): Banner {
    return object : Banner {

        override val declaration = BannerDefinitions.CLOUD_SEARCH_RESULTS_OFFLINE
        override val bannerDefinition: BannerDefinition
            get() = TODO("Not Supported")

        @Composable
        override fun buildTitle(): String {
            return stringResource(R.string.photopicker_banner_search_result_no_network_title)
        }

        @Composable
        override fun buildMessage(): String {
            return stringResource(
                R.string.photopicker_banner_search_result_no_network_connection,
                "${cloudProvider.displayName}",
            )
        }

        @Composable override fun getIcon() = VectorIcon(Icons.Outlined.CloudOff)

        @Composable
        override fun actionLabel(): String? {
            return null
        }

        override fun onAction(context: Context) {}
    }
}

/**
 * Builder for [BannerDefinitions.DEVICE_NETWORK_UNAVAILABLE] banner object that indicates to the
 * user that there is no network connection available on the device.
 *
 * @param cloudProvider the [Provider] details of the active CloudMediaProvider.
 * @param isEmbedded Boolean indicates if runtime environment is embedded or not.
 * @return The [Banner] to be displayed in the UI.
 */
fun buildNoNetworkAvailableBanner(cloudProvider: Provider, isEmbedded: Boolean = false): Banner {
    return object : Banner {

        override val declaration = BannerDefinitions.DEVICE_NETWORK_UNAVAILABLE
        override val bannerDefinition: BannerDefinition
            get() = TODO("Not supported")

        @Composable
        override fun buildTitle(): String {
            return stringResource(R.string.photopicker_banner_no_network_connection_title)
        }

        @Composable
        override fun buildMessage(): String {
            return stringResource(
                R.string.photopicker_banner_no_network_connection_message,
                cloudProvider.displayName,
            )
        }

        @Composable override fun getIcon() = VectorIcon(Icons.Outlined.CloudOff)

        @Composable
        override fun actionLabel(): String? {
            if (!isEmbedded)
                return stringResource(
                    R.string.photopicker_offline_banner_go_to_settings_button_label
                )
            else return null
        }

        override fun onAction(context: Context) {
            // TODO(b/465364190): Enable settings link action in embedded picker.
            if (!isEmbedded) context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
    }
}
