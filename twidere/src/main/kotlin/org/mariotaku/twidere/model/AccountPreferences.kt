/*
 *             Twidere - Twitter client for Android
 *
 *  Copyright (C) 2012-2017 Mariotaku Lee <mariotaku.lee@gmail.com>
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.mariotaku.twidere.model

import android.accounts.AccountManager
import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.support.v4.content.ContextCompat
import android.text.TextUtils
import org.mariotaku.ktextension.contains
import org.mariotaku.twidere.R
import org.mariotaku.twidere.TwidereConstants.ACCOUNT_PREFERENCES_NAME_PREFIX
import org.mariotaku.twidere.constant.SharedPreferenceConstants.*
import org.mariotaku.twidere.model.util.AccountUtils

class AccountPreferences(private val context: Context, val accountKey: UserKey) {
    private val preferences = context.getSharedPreferences("$ACCOUNT_PREFERENCES_NAME_PREFIX$accountKey", Context.MODE_PRIVATE)

    val defaultNotificationLightColor: Int
        get() {
            val a = AccountUtils.getAccountDetails(AccountManager.get(context), accountKey, true)
            if (a != null) {
                return a.color
            } else {
                return ContextCompat.getColor(context, R.color.branding_color)
            }
        }

    val directMessagesNotificationType: Int
        get() = preferences.getInt(KEY_NOTIFICATION_TYPE_DIRECT_MESSAGES, DEFAULT_NOTIFICATION_TYPE_DIRECT_MESSAGES)

    val homeTimelineNotificationType: Int
        get() = preferences.getInt(KEY_NOTIFICATION_TYPE_HOME, DEFAULT_NOTIFICATION_TYPE_HOME)

    val mentionsNotificationType: Int
        get() = preferences.getInt(KEY_NOTIFICATION_TYPE_MENTIONS, DEFAULT_NOTIFICATION_TYPE_MENTIONS)

    val notificationLightColor: Int
        get() = preferences.getInt(KEY_NOTIFICATION_LIGHT_COLOR, defaultNotificationLightColor)

    val notificationRingtone: Uri
        get() {
            val ringtone = preferences.getString(KEY_NOTIFICATION_RINGTONE, null)
            if (TextUtils.isEmpty(ringtone)) {
                return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            } else {
                return Uri.parse(ringtone)
            }
        }

    val isAutoRefreshEnabled: Boolean
        get() = preferences.getBoolean(KEY_AUTO_REFRESH, preferences.getBoolean(KEY_DEFAULT_AUTO_REFRESH, false))

    val isAutoRefreshHomeTimelineEnabled: Boolean
        get() = preferences.getBoolean(KEY_AUTO_REFRESH_HOME_TIMELINE, DEFAULT_AUTO_REFRESH_HOME_TIMELINE)

    val isAutoRefreshMentionsEnabled: Boolean
        get() = preferences.getBoolean(KEY_AUTO_REFRESH_MENTIONS, DEFAULT_AUTO_REFRESH_MENTIONS)

    val isAutoRefreshDirectMessagesEnabled: Boolean
        get() = preferences.getBoolean(KEY_AUTO_REFRESH_DIRECT_MESSAGES, DEFAULT_AUTO_REFRESH_DIRECT_MESSAGES)

    val isAutoRefreshTrendsEnabled: Boolean
        get() = preferences.getBoolean(KEY_AUTO_REFRESH_TRENDS, DEFAULT_AUTO_REFRESH_TRENDS)

    val isStreamingEnabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLE_STREAMING, false)

    val isStreamHomeTimelineEnabled: Boolean
        get() = preferences.getBoolean("stream_home_timeline", true)

    val isStreamInteractionsEnabled: Boolean
        get() = preferences.getBoolean("stream_interactions", true)

    val isStreamDirectMessagesEnabled: Boolean
        get() = preferences.getBoolean("stream_direct_messages", true)

    val isStreamNotificationUsersEnabled: Boolean
        get() = preferences.getBoolean("stream_notification_users", true)

    val isDirectMessagesNotificationEnabled: Boolean
        get() = preferences.getBoolean(KEY_DIRECT_MESSAGES_NOTIFICATION, DEFAULT_DIRECT_MESSAGES_NOTIFICATION)

    val isHomeTimelineNotificationEnabled: Boolean
        get() = preferences.getBoolean(KEY_HOME_TIMELINE_NOTIFICATION, DEFAULT_HOME_TIMELINE_NOTIFICATION)

    val isInteractionsNotificationEnabled: Boolean
        get() = preferences.getBoolean(KEY_MENTIONS_NOTIFICATION, DEFAULT_MENTIONS_NOTIFICATION)

    val isNotificationFollowingOnly: Boolean
        get() = preferences.getBoolean(KEY_NOTIFICATION_FOLLOWING_ONLY, false)

    val isNotificationMentionsOnly: Boolean
        get() = preferences.getBoolean(KEY_NOTIFICATION_MENTIONS_ONLY, false)

    val isNotificationEnabled: Boolean
        get() = preferences.getBoolean(KEY_NOTIFICATION, DEFAULT_NOTIFICATION)

    companion object {

        fun getAccountPreferences(context: Context, accountKeys: Array<UserKey>): Array<AccountPreferences> {
            return Array(accountKeys.size) { AccountPreferences(context, accountKeys[it]) }
        }

        fun isNotificationHasLight(flags: Int): Boolean {
            return VALUE_NOTIFICATION_FLAG_LIGHT in flags
        }

        fun isNotificationHasRingtone(flags: Int): Boolean {
            return VALUE_NOTIFICATION_FLAG_RINGTONE in flags
        }

        fun isNotificationHasVibration(flags: Int): Boolean {
            return VALUE_NOTIFICATION_FLAG_VIBRATION in flags
        }
    }
}
