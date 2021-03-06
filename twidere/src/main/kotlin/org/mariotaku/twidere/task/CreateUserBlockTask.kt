package org.mariotaku.twidere.task

import android.content.ContentValues
import android.content.Context
import org.mariotaku.microblog.library.MicroBlog
import org.mariotaku.microblog.library.MicroBlogException
import org.mariotaku.microblog.library.twitter.model.User
import org.mariotaku.sqliteqb.library.Expression
import org.mariotaku.twidere.Constants
import org.mariotaku.twidere.R
import org.mariotaku.twidere.annotation.AccountType
import org.mariotaku.twidere.constant.nameFirstKey
import org.mariotaku.twidere.model.AccountDetails
import org.mariotaku.twidere.model.ParcelableUser
import org.mariotaku.twidere.model.event.FriendshipTaskEvent
import org.mariotaku.twidere.provider.TwidereDataStore.*
import org.mariotaku.twidere.util.DataStoreUtils
import org.mariotaku.twidere.util.Utils

/**
 * Created by mariotaku on 16/3/11.
 */
open class CreateUserBlockTask(
        context: Context,
        val filterEverywhere: Boolean = false
) : AbsFriendshipOperationTask(context, FriendshipTaskEvent.Action.BLOCK), Constants {

    @Throws(MicroBlogException::class)
    override fun perform(twitter: MicroBlog, details: AccountDetails,
                         args: Arguments): User {
        when (details.type) {
            AccountType.FANFOU -> {
                return twitter.createFanfouBlock(args.userKey.id)
            }
        }
        return twitter.createBlock(args.userKey.id)
    }

    override fun succeededWorker(twitter: MicroBlog, details: AccountDetails, args: Arguments,
                                 user: ParcelableUser) {
        val resolver = context.contentResolver
        Utils.setLastSeen(context, args.userKey, -1)
        for (uri in DataStoreUtils.STATUSES_URIS) {
            val where = Expression.and(
                    Expression.equalsArgs(Statuses.ACCOUNT_KEY),
                    Expression.equalsArgs(Statuses.USER_KEY)
            )
            val whereArgs = arrayOf(args.accountKey.toString(), args.userKey.toString())
            resolver.delete(uri, where.sql, whereArgs)
        }
        for (uri in DataStoreUtils.ACTIVITIES_URIS) {
            val where = Expression.and(
                    Expression.equalsArgs(Activities.ACCOUNT_KEY),
                    Expression.equalsArgs(Activities.STATUS_USER_KEY)
            )
            val whereArgs = arrayOf(args.accountKey.toString(), args.userKey.toString())
            resolver.delete(uri, where.sql, whereArgs)
        }
        // I bet you don't want to see this user in your auto complete list.
        val values = ContentValues()
        values.put(CachedRelationships.ACCOUNT_KEY, args.accountKey.toString())
        values.put(CachedRelationships.USER_KEY, args.userKey.toString())
        values.put(CachedRelationships.BLOCKING, true)
        values.put(CachedRelationships.FOLLOWING, false)
        values.put(CachedRelationships.FOLLOWED_BY, false)
        resolver.insert(CachedRelationships.CONTENT_URI, values)

        if (filterEverywhere) {
            DataStoreUtils.addToFilter(context, listOf(user), true)
        }
    }

    override fun showSucceededMessage(params: Arguments, user: ParcelableUser) {
        val nameFirst = kPreferences[nameFirstKey]
        val message = context.getString(R.string.message_blocked_user, manager.getDisplayName(user,
                nameFirst))
        Utils.showInfoMessage(context, message, false)

    }

    override fun showErrorMessage(params: Arguments, exception: Exception?) {
        Utils.showErrorMessage(context, R.string.action_blocking, exception, true)
    }
}
