/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.providers.media.photopicker.util.exceptions;

/**
 * Exception that gets thrown when cloud sync params received from the current cloud provider
 * for a media sync are invalid. This exception will only be thrown when both of the following are
 * true:
 * <ul>
 * <li> Empty/null collectionId </li>
 *
 * <li> Empty/null account name </li>
 * </ul>
 *
 * This exception indicates a persistent inability to fetch valid sync
 * parameters from the current cloud media provider. In order to restore picker
 * functionality and respect user choices, consistent occurrences of this exception over a
 * defined retry period will result in the system resetting the current CMP to null.
 * This exception should be used exclusively for parameter failures and not less impactful
 * invalid scenarios.
 */
public class InvalidProviderSyncParamsException extends Exception {
    public InvalidProviderSyncParamsException(String message) {
        super(message);
    }
}
