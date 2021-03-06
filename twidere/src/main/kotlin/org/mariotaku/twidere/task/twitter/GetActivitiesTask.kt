package org.mariotaku.twidere.task.twitter

import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.support.annotation.UiThread
import android.util.Log
import com.squareup.otto.Bus
import org.mariotaku.abstask.library.AbstractTask
import org.mariotaku.kpreferences.KPreferences
import org.mariotaku.microblog.library.MicroBlog
import org.mariotaku.microblog.library.MicroBlogException
import org.mariotaku.microblog.library.twitter.model.Activity
import org.mariotaku.microblog.library.twitter.model.Paging
import org.mariotaku.microblog.library.twitter.model.ResponseList
import org.mariotaku.sqliteqb.library.Expression
import org.mariotaku.twidere.BuildConfig
import org.mariotaku.twidere.TwidereConstants.LOGTAG
import org.mariotaku.twidere.TwidereConstants.QUERY_PARAM_NOTIFY
import org.mariotaku.twidere.constant.loadItemLimitKey
import org.mariotaku.twidere.extension.model.newMicroBlogInstance
import org.mariotaku.twidere.model.AccountDetails
import org.mariotaku.twidere.model.RefreshTaskParam
import org.mariotaku.twidere.model.UserKey
import org.mariotaku.twidere.model.message.GetActivitiesTaskEvent
import org.mariotaku.twidere.model.util.AccountUtils
import org.mariotaku.twidere.model.util.ParcelableActivityUtils
import org.mariotaku.twidere.provider.TwidereDataStore.Activities
import org.mariotaku.twidere.util.*
import org.mariotaku.twidere.util.content.ContentResolverUtils
import org.mariotaku.twidere.util.dagger.GeneralComponentHelper
import java.util.*
import javax.inject.Inject

/**
 * Created by mariotaku on 16/1/4.
 */
abstract class GetActivitiesTask(
        protected val context: Context
) : AbstractTask<RefreshTaskParam, Unit, () -> Unit>() {
    @Inject
    lateinit var preferences: KPreferences
    @Inject
    lateinit var bus: Bus
    @Inject
    lateinit var errorInfoStore: ErrorInfoStore
    @Inject
    lateinit var readStateManager: ReadStateManager
    @Inject
    lateinit var userColorNameManager: UserColorNameManager

    init {
        GeneralComponentHelper.build(context).inject(this)
    }

    public override fun doLongOperation(param: RefreshTaskParam) {
        if (param.shouldAbort) return
        val accountIds = param.accountKeys
        val maxIds = param.maxIds
        val maxSortIds = param.maxSortIds
        val sinceIds = param.sinceIds
        val cr = context.contentResolver
        val loadItemLimit = preferences[loadItemLimitKey]
        var saveReadPosition = false
        for (i in accountIds.indices) {
            val accountKey = accountIds[i]
            val noItemsBefore = DataStoreUtils.getActivitiesCount(context, contentUri, accountKey) <= 0
            val credentials = AccountUtils.getAccountDetails(AccountManager.get(context), accountKey, true) ?: continue
            val microBlog = credentials.newMicroBlogInstance(context = context, cls = MicroBlog::class.java)
            val paging = Paging()
            paging.count(loadItemLimit)
            var maxId: String? = null
            var maxSortId: Long = -1
            if (maxIds != null) {
                maxId = maxIds[i]
                if (maxSortIds != null) {
                    maxSortId = maxSortIds[i]
                }
                if (maxId != null) {
                    paging.maxId(maxId)
                }
            }
            var sinceId: String? = null
            if (sinceIds != null) {
                sinceId = sinceIds[i]
                if (sinceId != null) {
                    paging.sinceId(sinceId)
                    if (maxIds == null || maxId == null) {
                        paging.setLatestResults(true)
                        saveReadPosition = true
                    }
                }
            }
            // We should delete old activities has intersection with new items
            try {
                val activities = getActivities(microBlog, credentials, paging)
                storeActivities(cr, loadItemLimit, credentials, noItemsBefore, activities, sinceId,
                        maxId, false)
                if (saveReadPosition) {
                    saveReadPosition(accountKey, credentials, microBlog)
                }
                errorInfoStore.remove(errorInfoKey, accountKey)
            } catch (e: MicroBlogException) {
                if (BuildConfig.DEBUG) {
                    Log.w(LOGTAG, e)
                }
                if (e.errorCode == 220) {
                    errorInfoStore.put(errorInfoKey, accountKey,
                            ErrorInfoStore.CODE_NO_ACCESS_FOR_CREDENTIALS)
                } else if (e.isCausedByNetworkIssue) {
                    errorInfoStore.put(errorInfoKey, accountKey,
                            ErrorInfoStore.CODE_NETWORK_ERROR)
                }
            }

        }
    }

    protected abstract val errorInfoKey: String

    private fun storeActivities(cr: ContentResolver, loadItemLimit: Int, details: AccountDetails,
                                noItemsBefore: Boolean, activities: ResponseList<Activity>,
                                sinceId: String?, maxId: String?, notify: Boolean) {
        val deleteBound = LongArray(2) { -1 }
        val valuesList = ArrayList<ContentValues>()
        var minIdx = -1
        var minPositionKey: Long = -1
        if (!activities.isEmpty()) {
            val firstSortId = activities.first().createdAt.time
            val lastSortId = activities.last().createdAt.time
            // Get id diff of first and last item
            val sortDiff = firstSortId - lastSortId
            for (i in activities.indices) {
                val item = activities[i]
                val activity = ParcelableActivityUtils.fromActivity(item, details.key, false)
                activity.position_key = GetStatusesTask.getPositionKey(activity.timestamp,
                        activity.timestamp, lastSortId, sortDiff, i, activities.size)
                if (deleteBound[0] < 0) {
                    deleteBound[0] = activity.min_sort_position
                } else {
                    deleteBound[0] = Math.min(deleteBound[0], activity.min_sort_position)
                }
                if (deleteBound[1] < 0) {
                    deleteBound[1] = activity.max_sort_position
                } else {
                    deleteBound[1] = Math.max(deleteBound[1], activity.max_sort_position)
                }
                if (minIdx == -1 || item < activities[minIdx]) {
                    minIdx = i
                    minPositionKey = activity.position_key
                }

                activity.inserted_date = System.currentTimeMillis()
                val values = ContentValuesCreator.createActivity(activity,
                        details, userColorNameManager)
                valuesList.add(values)
            }
        }
        var olderCount = -1
        if (minPositionKey > 0) {
            olderCount = DataStoreUtils.getActivitiesCount(context, contentUri, minPositionKey,
                    Activities.POSITION_KEY, false, details.key)
        }
        val writeUri = UriUtils.appendQueryParameters(contentUri, QUERY_PARAM_NOTIFY, notify)
        if (deleteBound[0] > 0 && deleteBound[1] > 0) {
            val where = Expression.and(
                    Expression.equalsArgs(Activities.ACCOUNT_KEY),
                    Expression.greaterEqualsArgs(Activities.MIN_SORT_POSITION),
                    Expression.lesserEqualsArgs(Activities.MAX_SORT_POSITION))
            val whereArgs = arrayOf(details.key.toString(), deleteBound[0].toString(), deleteBound[1].toString())
            val rowsDeleted = cr.delete(writeUri, where.sql, whereArgs)
            // Why loadItemLimit / 2? because it will not acting strange in most cases
            val insertGap = !noItemsBefore && olderCount > 0 && rowsDeleted <= 0 && activities.size > loadItemLimit / 2
            if (insertGap && !valuesList.isEmpty()) {
                valuesList[valuesList.size - 1].put(Activities.IS_GAP, true)
            }
        }
        ContentResolverUtils.bulkInsert(cr, writeUri, valuesList)

        if (maxId != null && sinceId == null) {
            val noGapValues = ContentValues()
            noGapValues.put(Activities.IS_GAP, false)
            val noGapWhere = Expression.and(Expression.equalsArgs(Activities.ACCOUNT_KEY),
                    Expression.equalsArgs(Activities.MIN_REQUEST_POSITION),
                    Expression.equalsArgs(Activities.MAX_REQUEST_POSITION)).sql
            val noGapWhereArgs = arrayOf(details.key.toString(), maxId, maxId)
            cr.update(writeUri, noGapValues, noGapWhere, noGapWhereArgs)
        }
    }

    protected abstract fun saveReadPosition(accountKey: UserKey, details: AccountDetails, twitter: MicroBlog)

    @Throws(MicroBlogException::class)
    protected abstract fun getActivities(twitter: MicroBlog, details: AccountDetails, paging: Paging): ResponseList<Activity>

    public override fun afterExecute(handler: (() -> Unit)?, result: Unit) {
        context.contentResolver.notifyChange(contentUri, null)
        bus.post(GetActivitiesTaskEvent(contentUri, false, null))
    }

    protected abstract val contentUri: Uri

    @UiThread
    public override fun beforeExecute() {
        bus.post(GetActivitiesTaskEvent(contentUri, true, null))
    }
}
