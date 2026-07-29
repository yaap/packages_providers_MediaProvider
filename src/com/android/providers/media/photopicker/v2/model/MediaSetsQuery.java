/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.providers.media.photopicker.v2.model;

import static com.android.providers.media.photopicker.v2.model.SearchRequest.getMimeTypesAsString;

import static java.util.Objects.requireNonNull;

import android.os.Bundle;

import androidx.annotation.NonNull;

import com.android.providers.media.photopicker.v2.sqlite.PickerSQLConstants;
import com.android.providers.media.photopicker.v2.sqlite.SelectSQLiteQueryBuilder;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A class to extract out all the required parameters for querying media sets of a particular
 * category in a paginated form.
 */
public class MediaSetsQuery {

    public static final String KEY_PARENT_CATEGORY_AUTHORITY = "parent_category_authority";
    public static final String KEY_MIME_TYPES = "mime_types";
    public static final String KEY_PARENT_CATEGORY_ID = "parent_category_id";
    public static final String KEY_PICKER_ID = "picker_id";
    public static final String KEY_PAGE_SIZE = "current_page_size";

    private final long mPickerId;
    private final int mPageSize;
    private final String mParentCategoryId;
    private final String mParentCategoryAuthority;
    private final List<String> mMimeTypes;

    public MediaSetsQuery(@NonNull Bundle extras) {
        Objects.requireNonNull(extras);
        mPickerId = extras.getLong(KEY_PICKER_ID, Long.MIN_VALUE);
        mPageSize = extras.getInt(KEY_PAGE_SIZE, Integer.MAX_VALUE);
        mParentCategoryId = Objects.requireNonNull(
                extras.getString(KEY_PARENT_CATEGORY_ID
                ));
        mParentCategoryAuthority = Objects.requireNonNull(
                extras.getString(KEY_PARENT_CATEGORY_AUTHORITY
                ));
        mMimeTypes = extras.getStringArrayList(KEY_MIME_TYPES);
    }

    public long getPickerId() {
        return mPickerId;
    }

    public int getPageSize() {
        return mPageSize;
    }

    public String getParentCategoryAuthority() {
        return mParentCategoryAuthority;
    }

    public String getParentCategoryId() {
        return mParentCategoryId;
    }

    public List<String> getMimeTypes() {
        return mMimeTypes;
    }

    /**
     * Add the common where clauses to sql queries used to fetch media sets.
     * @param queryBuilder Adds the where clauses to this sql query builder object.
     * @param query Object of type [MediaSetsQuery] which holds all the necessary params for
     *              fetching media sets for the given category.
     */
    public void addCommonMediaSetsWhereClauses(
            @NonNull SelectSQLiteQueryBuilder queryBuilder, @NonNull MediaSetsQuery query) {
        requireNonNull(queryBuilder);
        requireNonNull(query);

        final String categoryId = query.getParentCategoryId();
        final String authority = query.getParentCategoryAuthority();
        final List<String> mimeTypes = query.getMimeTypes();

        queryBuilder
                .appendWhereStandalone(
                        String.format(Locale.ROOT, " %s = '%s' ",
                                PickerSQLConstants.MediaSetsTableColumns.CATEGORY_ID
                                        .getColumnName(),
                                categoryId))
                .appendWhereStandalone(
                        String.format(Locale.ROOT, " %s = '%s' ",
                                PickerSQLConstants.MediaSetsTableColumns.MEDIA_SET_AUTHORITY
                                        .getColumnName(),
                                authority))
                .appendWhereStandalone(
                        String.format(Locale.ROOT, " %s = '%s' ",
                                PickerSQLConstants.MediaSetsTableColumns.MIME_TYPE_FILTER
                                        .getColumnName(),
                                getMimeTypesAsString(mimeTypes)));
    }

    /**
     * Filters the given query for pickerId i.e. add a where clause to the query involving
     * the pickerId.
     *
     * @param queryBuilder Adds the pickerId where clauses to this sql query builder object.
     * @param query Object of type [MediaSetsQuery] which holds all the necessary params for
     *              fetching media sets for the given category
     * @param isSortOrderAsc Default pickerId filtering is >= unless the opposite
     *                                  is required indicated by this boolean value
     */
    public void addPickerIdWhereClause(
            @NonNull SelectSQLiteQueryBuilder queryBuilder,
            @NonNull MediaSetsQuery query,
            boolean isSortOrderAsc
    ) {
        requireNonNull(query);
        requireNonNull(queryBuilder);

        if (isSortOrderAsc) {
            queryBuilder.appendWhereStandalone(String.format(Locale.ROOT, "%s < %d ",
                    PickerSQLConstants.MediaSetsTableColumns.PICKER_ID.getColumnName(),
                    query.getPickerId()));
        } else {
            queryBuilder.appendWhereStandalone(String.format(Locale.ROOT, "%s >= %d ",
                    PickerSQLConstants.MediaSetsTableColumns.PICKER_ID.getColumnName(),
                    query.getPickerId()));
        }

    }
}
