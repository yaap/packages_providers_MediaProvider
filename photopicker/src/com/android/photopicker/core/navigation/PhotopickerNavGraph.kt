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

package com.android.photopicker.core.navigation

import android.content.Context
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDeepLink
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import com.android.photopicker.MainActivity
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerRuntimeEnv
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.LocalFeatureManager
import com.android.photopicker.core.features.PhotopickerUiFeature
import com.android.photopicker.core.features.Priority

/**
 * The main composable for Photopicker's feature navigation.
 *
 * This composable will generate a [NavHost] element with a populated [NavGraph] with all registered
 * [Route]s that are provided by the currently enabled feature set in the [FeatureManager].
 */
@Composable
fun PhotopickerNavGraph() {

    val featureManager = LocalFeatureManager.current
    val navController = LocalNavController.current
    val configuration = LocalPhotopickerConfiguration.current
    // This would either be the Activity Context or Application context in case of Embedded Picker.
    // This context will be provided to the dialogs in the NavGraph.
    val sessionContext = LocalContext.current

    NavHost(
        navController = navController,
        startDestination =
            getStartDestination(featureManager.enabledUiFeatures, configuration).route,
        builder = {
            this.setupFeatureRoutesForNavigation(featureManager, configuration, sessionContext)
        },
        // Disable all transitions by default so that routes fully control the transition logic.
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    )
}

/**
 * Extend the NavGraphBuilder to provide a hook for the NavGraph to be dynamically constructed based
 * on the state present in the [FeatureManager].
 *
 * This will construct a navigation graph that contains exposed [Route]s from all enabled
 * [PhotopickerUiFeature]s at runtime.
 *
 * @param featureManager the Photopicker session's feature manager object
 * @param configuration the Photopicker session's configuration
 * @param context the activity context that will be provided to the dialogs in the NavGraph. This
 *   doesn't impact Embedded Picker, since we cannot display dialogs in an Embedded Picker session.
 */
private fun NavGraphBuilder.setupFeatureRoutesForNavigation(
    featureManager: FeatureManager,
    configuration: PhotopickerConfiguration,
    context: Context,
) {

    // Create a flat set of all registered routes, across all features.
    var allRoutes = featureManager.enabledUiFeatures.flatMap { it.registerNavigationRoutes() }

    // This is to ensure there is always at least one route registered in the graph.
    // In the event no enabled features expose routes, this  will provide an empty default.
    if (allRoutes.size == 0) {
        Log.w(
            MainActivity.TAG,
            "There were no registered feature routes. Defaulting to an empty NavigationGraph",
        )
        allRoutes = listOf(getStartDestination(featureManager.enabledUiFeatures))
    }

    // For all of the collected routes, register them into the graph based on their [Route]
    // declaration.
    for (route in allRoutes) {
        if (route.isDialog) {
            // Validate the runtime environment is not embedded picker before registering a dialog.
            if (configuration.runtimeEnv == PhotopickerRuntimeEnv.EMBEDDED) {
                throw IllegalStateException("Cannot register dialog route in embedded picker")
            }

            // Validate the available context is an activity context.
            val activity = context as? ComponentActivity
            checkNotNull(activity) {
                "Context provided to dialog needs to be ComponentActivity for obtainViewModel to " +
                    "work correctly in case of activity scoped view models."
            }

            dialog(
                route = route.route,
                arguments = route.arguments,
                deepLinks = route.deepLinks,
                dialogProperties = route.dialogProperties ?: DialogProperties(),
            ) { backStackEntry ->
                // Provide activity context to the dialog
                CompositionLocalProvider(LocalContext provides context) {
                    route.composable(backStackEntry)
                }
            }
        } else {
            composable(
                route = route.route,
                arguments = route.arguments,
                deepLinks = route.deepLinks,
                enterTransition = route.enterTransition,
                exitTransition = route.exitTransition,
                popEnterTransition = route.popEnterTransition,
                popExitTransition = route.popExitTransition,
            ) { backStackEntry ->
                route.composable(backStackEntry)
            }
        }
    }
}

/**
 * Inspects the enabled UI features and locates the [Route] with the highest declared priority. In
 * the event of a tie, the Registration order will resolve the tie.
 *
 * In the event there are no declared routes, this returns an empty route object to prevent the UI
 * from crashing.
 *
 * @return The starting route for initializing the Navigation Graph.
 */
private fun getStartDestination(
    enabledUiFeatures: Set<PhotopickerUiFeature>,
    configuration: PhotopickerConfiguration? = null,
): Route {

    val allRoutes = enabledUiFeatures.flatMap { it.registerNavigationRoutes() }

    configuration?.let {
        val startRouteFromConfiguration =
            allRoutes.find { it.route == configuration.startDestination.route }
        startRouteFromConfiguration?.let {
            return (it)
        }
    }

    return allRoutes.maxByOrNull { it.initialRoutePriority }
        // A default blank route in case no route exists.
        ?: object : Route {
            override val route = PhotopickerDestinations.DEFAULT.route
            override val initialRoutePriority = Priority.LOW.priority
            override val arguments = emptyList<NamedNavArgument>()
            override val deepLinks = emptyList<NavDeepLink>()
            override val isDialog = false
            override val dialogProperties = null
            override val enterTransition = null
            override val exitTransition = null
            override val popEnterTransition = null
            override val popExitTransition = null

            @Composable override fun composable(navBackStackEntry: NavBackStackEntry?) {}
        }
}
