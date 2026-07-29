/*
 * Copyright 2026 The Android Open Source Project
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

package com.android.photopicker.features.camera

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.UserProperties
import android.os.Parcel
import android.os.UserHandle
import android.os.UserManager
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.modules.utils.build.SdkLevel
import com.android.photopicker.R
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.configuration.PhotopickerRuntimeEnv
import com.android.photopicker.core.configuration.TestDeviceConfigProxyImpl
import com.android.photopicker.core.configuration.TestPhotopickerConfiguration
import com.android.photopicker.core.configuration.provideTestConfigurationFlow
import com.android.photopicker.core.events.generatePickerSessionId
import com.android.photopicker.core.user.SwitchUserProfileResult
import com.android.photopicker.core.user.UserMonitor
import com.android.photopicker.core.user.UserProfile
import com.android.photopicker.core.user.UserStatus
import com.android.photopicker.util.test.mockSystemService
import com.android.photopicker.util.test.whenever
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.MockitoAnnotations

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CameraViewModelTest {

    /**
     * Class that exposes the @hide api [targetUserId] in order to supply proper values for
     * reflection based code that is inspecting this field.
     *
     * @property targetUserId
     */
    private class ReflectedResolveInfo(@JvmField val targetUserId: Int) : ResolveInfo() {

        override fun isCrossProfileIntentForwarderActivity(): Boolean = true
    }

    private val PLATFORM_PROVIDED_PROFILE_LABEL = "Platform Label"
    private val USER_HANDLE_PRIMARY: UserHandle
    private val USER_ID_PRIMARY: Int = 0
    private val PRIMARY_PROFILE_BASE: UserProfile
    private val USER_HANDLE_MANAGED: UserHandle
    private val USER_ID_MANAGED: Int = 10
    private val MANAGED_PROFILE_BASE: UserProfile
    private val mockContentResolver: ContentResolver = mock(ContentResolver::class.java)

    @Mock lateinit var mockContext: Context
    @Mock lateinit var mockUserManager: UserManager
    @Mock lateinit var mockPackageManager: PackageManager

    init {

        val parcel1 = Parcel.obtain()
        parcel1.writeInt(USER_ID_PRIMARY)
        parcel1.setDataPosition(0)
        USER_HANDLE_PRIMARY = UserHandle(parcel1)
        parcel1.recycle()

        PRIMARY_PROFILE_BASE =
            UserProfile(
                handle = USER_HANDLE_PRIMARY,
                profileType = UserProfile.ProfileType.PRIMARY,
                label = PLATFORM_PROVIDED_PROFILE_LABEL,
            )

        val parcel2 = Parcel.obtain()
        parcel2.writeInt(USER_ID_MANAGED)
        parcel2.setDataPosition(0)
        USER_HANDLE_MANAGED = UserHandle(parcel2)
        parcel2.recycle()

        MANAGED_PROFILE_BASE =
            UserProfile(
                handle = USER_HANDLE_MANAGED,
                profileType = UserProfile.ProfileType.MANAGED,
                label = PLATFORM_PROVIDED_PROFILE_LABEL,
            )
    }

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        val resources = InstrumentationRegistry.getInstrumentation().getContext().getResources()

        mockSystemService(mockContext, UserManager::class.java) { mockUserManager }
        whenever(mockContext.packageManager) { mockPackageManager }
        whenever(mockContext.packageName) { "" }
        whenever(mockContext.contentResolver) { mockContentResolver }
        whenever(mockContext.createPackageContextAsUser(any(), anyInt(), any())) { mockContext }
        whenever(mockContext.createContextAsUser(any(UserHandle::class.java), anyInt())) {
            mockContext
        }

        // Initial setup state: Two profiles (Personal/Work), both enabled
        whenever(mockUserManager.userProfiles) { listOf(USER_HANDLE_PRIMARY, USER_HANDLE_MANAGED) }

        // Default responses for relevant UserManager apis
        whenever(mockUserManager.isQuietModeEnabled(USER_HANDLE_PRIMARY)) { false }
        whenever(mockUserManager.isManagedProfile(USER_ID_PRIMARY)) { false }
        whenever(mockUserManager.isQuietModeEnabled(USER_HANDLE_MANAGED)) { false }
        whenever(mockUserManager.isManagedProfile(USER_ID_MANAGED)) { true }
        whenever(mockUserManager.getProfileParent(USER_HANDLE_MANAGED)) { USER_HANDLE_PRIMARY }

        if (SdkLevel.isAtLeastV()) {
            whenever(mockUserManager.getUserBadge()) {
                resources.getDrawable(R.drawable.android, /* theme= */ null)
            }
            whenever(mockUserManager.getProfileLabel()) { PLATFORM_PROVIDED_PROFILE_LABEL }
            whenever(
                mockUserManager.getUserProperties(USER_HANDLE_PRIMARY)
            ) @JvmSerializableLambda { UserProperties.Builder().build() }
            // By default, allow managed profile to be available
            whenever(
                mockUserManager.getUserProperties(USER_HANDLE_MANAGED)
            ) @JvmSerializableLambda {
                UserProperties.Builder()
                    .setCrossProfileContentSharingStrategy(
                        UserProperties.CROSS_PROFILE_CONTENT_SHARING_DELEGATE_FROM_PARENT
                    )
                    .build()
            }
        } else {
            // Fake for a CrossProfileIntentForwarderActivity for the managed profile
            val resolveInfoForManagedUser =
                ReflectedResolveInfo(USER_HANDLE_MANAGED.getIdentifier())
            whenever(
                mockPackageManager.queryIntentActivitiesAsUser(
                    any(Intent::class.java),
                    anyInt(),
                    eq(USER_HANDLE_PRIMARY),
                )
            ) {
                listOf(resolveInfoForManagedUser)
            }
        }

        /* Default the device to have a camera, so we can focus on eligibility and owner logic */
        whenever(mockPackageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA))
            .thenReturn(true)
        whenever(mockPackageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT))
            .thenReturn(false)

        /* Default the device to NOT have any excluded features (making it an eligible device) */
        whenever(mockPackageManager.hasSystemFeature(PackageManager.FEATURE_PC)).thenReturn(false)
        whenever(mockPackageManager.hasSystemFeature(PackageManager.FEATURE_EMBEDDED))
            .thenReturn(false)
        whenever(mockPackageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK))
            .thenReturn(false)
        whenever(mockPackageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE))
            .thenReturn(false)
        whenever(mockPackageManager.hasSystemFeature(PackageManager.FEATURE_WATCH))
            .thenReturn(false)
    }

    /**
     * Verify that camera should be available when the current profile is not the launching profile
     * and the device is a phone or tablet (no excluded features).
     */
    @Test
    fun testIsCameraAvailable_isTrue_whenActiveProfileIsLaunchingProfileAndDeviceEligible() =
        runTest {
            val testDispatcher = StandardTestDispatcher(this.testScheduler)

            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = testDispatcher,
                    deviceConfigProxy = TestDeviceConfigProxyImpl(),
                    sessionId = generatePickerSessionId(),
                )

            val userMonitor =
                UserMonitor(
                    context = mockContext,
                    configuration = configurationManager.configuration,
                    scope = this.backgroundScope,
                    dispatcher = testDispatcher,
                    processOwnerUserHandle = USER_HANDLE_PRIMARY,
                )
            advanceTimeBy(100)

            assertWithMessage("UserMonitor active user should be primary (0)")
                .that(userMonitor.userStatus.value.activeUserProfile.identifier)
                .isEqualTo(0)

            assertWithMessage("UserMonitor process owner should be primary (0)")
                .that(userMonitor.launchingProfile.identifier)
                .isEqualTo(0)

            val viewModel =
                CameraViewModel(
                    scopeOverride = this.backgroundScope,
                    appContext = mockContext,
                    userMonitor = userMonitor,
                )
            advanceTimeBy(100)

            val emissions = mutableListOf<Boolean>()
            val collectJob =
                launch(testDispatcher) { viewModel.isCameraAvailable.collect { emissions.add(it) } }
            advanceTimeBy(100)

            assertWithMessage("isCameraAvailable should emit true").that(emissions.last()).isTrue()

            collectJob.cancel()
        }

    /**
     * Verify that camera should NOT be available if the current active profile is different from
     * the profile that launched the Photopicker process.
     */
    @Test
    fun testIsCameraAvailable_isFalse_whenActiveProfileIsNotLaunchingProfile() = runTest {
        val testDispatcher = StandardTestDispatcher(this.testScheduler)

        val managedUserHandle = UserHandle.of(10)
        /* Mock that the system now has a managed profile */
        whenever(mockUserManager.userProfiles)
            .thenReturn(listOf(USER_HANDLE_PRIMARY, managedUserHandle))
        whenever(mockUserManager.isManagedProfile(10)).thenReturn(true)
        whenever(mockUserManager.isQuietModeEnabled(managedUserHandle)).thenReturn(false)
        whenever(mockUserManager.getProfileParent(managedUserHandle))
            .thenReturn(USER_HANDLE_PRIMARY)

        if (SdkLevel.isAtLeastV()) {
            val managedUserProperties =
                UserProperties.Builder()
                    .setShowInSharingSurfaces(UserProperties.SHOW_IN_SHARING_SURFACES_SEPARATE)
                    .setCrossProfileContentSharingStrategy(
                        UserProperties.CROSS_PROFILE_CONTENT_SHARING_DELEGATE_FROM_PARENT
                    )
                    .build()
            whenever(mockUserManager.getUserProperties(managedUserHandle))
                .thenReturn(managedUserProperties)
        }

        val configurationFlow =
            provideTestConfigurationFlow(
                scope = this.backgroundScope,
                defaultConfiguration =
                    TestPhotopickerConfiguration.build {
                        action(MediaStore.ACTION_PICK_IMAGES)
                        intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                    },
            )

        val userMonitor =
            UserMonitor(
                context = mockContext,
                configuration = configurationFlow,
                scope = this.backgroundScope,
                dispatcher = testDispatcher,
                processOwnerUserHandle = USER_HANDLE_PRIMARY,
            )

        val emissions = mutableListOf<UserStatus>()
        val userStatusCollectJob =
            launch(testDispatcher) { userMonitor.userStatus.collect { emissions.add(it) } }

        /* Wait for UserMonitor to initialize and pick up the managed profile from mockUserManager */
        advanceUntilIdle()
        advanceTimeBy(100)

        val managedProfile = emissions.last().allProfiles.find { it.identifier == 10 }
        assertWithMessage("Managed profile should be known to UserMonitor before switching")
            .that(managedProfile)
            .isNotNull()

        assertWithMessage("Managed profile should be enabled ${managedProfile?.disabledReasons}")
            .that(managedProfile?.enabled)
            .isTrue()

        /* Request a profile switch to the managed user */
        val switchResult =
            userMonitor.requestSwitchActiveUserProfile(
                requested = managedProfile!!,
                context = mockContext,
            )
        assertWithMessage("Profile switch should return SUCCESS")
            .that(switchResult)
            .isEqualTo(SwitchUserProfileResult.SUCCESS)
        advanceTimeBy(100)

        /* Assertion 1: Check if the user is NOT considered the process owner anymore */
        val activeUserIdentifier = emissions.last().activeUserProfile.identifier
        assertWithMessage("UserMonitor should have the managed user (10) as active")
            .that(activeUserIdentifier)
            .isEqualTo(10)

        val viewModel =
            CameraViewModel(
                scopeOverride = this.backgroundScope,
                appContext = mockContext,
                userMonitor = userMonitor,
            )
        advanceTimeBy(100)

        /* Even if the device is eligible, it's not the process owner, so should be false */
        assertWithMessage("isCameraAvailable should be false when not the process owner")
            .that(viewModel.isCameraAvailable.value)
            .isFalse()

        userStatusCollectJob.cancel()
    }

    /**
     * Verify that camera should NOT be available if the device has features that exclude it from
     * the camera feature (e.g., if it's a Watch), even if the user is the process owner.
     */
    @Test
    fun testIsCameraAvailable_isFalse_whenDeviceIsIneligible() = runTest {
        /* Mock that the device is a Watch (an excluded feature) */
        whenever(mockPackageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)).thenReturn(true)

        val testDispatcher = StandardTestDispatcher(this.testScheduler)

        val configurationManager =
            ConfigurationManager(
                runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                scope = this.backgroundScope,
                dispatcher = testDispatcher,
                deviceConfigProxy = TestDeviceConfigProxyImpl(),
                sessionId = generatePickerSessionId(),
            )

        val userMonitor =
            UserMonitor(
                context = mockContext,
                configuration = configurationManager.configuration,
                scope = this.backgroundScope,
                dispatcher = testDispatcher,
                processOwnerUserHandle = USER_HANDLE_PRIMARY,
            )
        advanceTimeBy(100)

        val viewModel =
            CameraViewModel(
                scopeOverride = this.backgroundScope,
                appContext = mockContext,
                userMonitor = userMonitor,
            )
        advanceTimeBy(100)

        /* User is the process owner, but the device type is ineligible */
        assertWithMessage("isCameraAvailable should be false for ineligible device types")
            .that(viewModel.isCameraAvailable.value)
            .isFalse()
    }
}
