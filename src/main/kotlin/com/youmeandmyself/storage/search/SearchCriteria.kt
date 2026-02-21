package com.youmeandmyself.storage.search

import java.time.Instant

/**
 * Criteria for searching exchanges in the Library.
 *
 * All fields are optional — omitted fields are not filtered on.
 * Multiple fields combine with AND logic.
 *
 * ## Collection filtering
 *
 * When [collectionId] is set, only exchanges that have a bookmark
 * in that collection are returned. This powers "search within a collection"
 * in the Library sidebar.
 */
data class SearchCriteria(
    val query: String = "",
    val projectId: String? = null,
    val profileId: String? = null,
    val providerId: String? = null,
    val languages: List<String>? = null,
    val hasCode: Boolean? = null,
    val isBookmarked: Boolean? = null,
    val collectionId: String? = null,
    val dateFrom: Instant? = null,
    val dateTo: Instant? = null,
    val limit: Int = 50,
    val offset: Int = 0
)