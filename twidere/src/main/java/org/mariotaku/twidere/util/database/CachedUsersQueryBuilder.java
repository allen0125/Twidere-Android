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

package org.mariotaku.twidere.util.database;

import org.mariotaku.sqliteqb.library.Columns;
import org.mariotaku.sqliteqb.library.Expression;
import org.mariotaku.sqliteqb.library.Join;
import org.mariotaku.sqliteqb.library.OrderBy;
import org.mariotaku.sqliteqb.library.Selectable;
import org.mariotaku.sqliteqb.library.Table;
import org.mariotaku.sqliteqb.library.Tables;
import org.mariotaku.sqliteqb.library.query.SQLSelectQuery;
import org.mariotaku.twidere.model.UserKey;
import org.mariotaku.twidere.provider.TwidereDataStore;
import org.mariotaku.twidere.provider.TwidereDataStore.CachedRelationships;
import org.mariotaku.twidere.util.TwidereArrayUtils;
import org.mariotaku.twidere.util.Utils;

import java.util.Locale;

import kotlin.Pair;

/**
 * Created by mariotaku on 2017/2/17.
 */
public final class CachedUsersQueryBuilder {

    private CachedUsersQueryBuilder() {
    }

    public static Pair<SQLSelectQuery, String[]> withRelationship(final String[] projection,
            final String selection,
            final String[] selectionArgs,
            final String sortOrder,
            final UserKey accountKey) {
        return withRelationship(Utils.getColumnsFromProjection(projection), selection,
                selectionArgs, sortOrder, accountKey);
    }

    public static Pair<SQLSelectQuery, String[]> withRelationship(final Selectable select,
            final String selection,
            final String[] selectionArgs,
            final String sortOrder,
            final UserKey accountKey) {
        final SQLSelectQuery.Builder qb = new SQLSelectQuery.Builder();
        qb.select(select).from(new Tables(TwidereDataStore.CachedUsers.TABLE_NAME));
        final Columns.Column relationshipsUserId = new Columns.Column(new Table(CachedRelationships.TABLE_NAME),
                CachedRelationships.USER_KEY);
        final Columns.Column usersUserId = new Columns.Column(new Table(TwidereDataStore.CachedUsers.TABLE_NAME),
                CachedRelationships.USER_KEY);
        final Columns.Column relationshipsAccountId = new Columns.Column(new Table(CachedRelationships.TABLE_NAME),
                CachedRelationships.ACCOUNT_KEY);
        final Expression on = Expression.and(
                Expression.equals(relationshipsUserId, usersUserId),
                Expression.equalsArgs(relationshipsAccountId.getSQL())
        );
        qb.join(new Join(false, Join.Operation.LEFT, new Table(CachedRelationships.TABLE_NAME), on));
        final Expression userTypeExpression;
        final String host = accountKey.getHost();
        final String[] accountKeyArgs;
        if (host == null) {
            userTypeExpression = Expression.notLikeRaw(new Columns.Column(new Table(TwidereDataStore.CachedUsers.TABLE_NAME),
                    TwidereDataStore.CachedUsers.USER_KEY), "'%@%'");
            accountKeyArgs = new String[]{accountKey.toString()};
        } else {
            userTypeExpression = Expression.likeRaw(new Columns.Column(new Table(TwidereDataStore.CachedUsers.TABLE_NAME),
                    TwidereDataStore.CachedUsers.USER_KEY), "'%@'||?");
            accountKeyArgs = new String[]{accountKey.toString(), host};
        }
        if (selection != null) {
            qb.where(Expression.and(userTypeExpression, new Expression(selection)));
        } else {
            qb.where(userTypeExpression);
        }
        if (sortOrder != null) {
            qb.orderBy(new OrderBy(sortOrder));
        }
        final String[] mergedArgs = new String[TwidereArrayUtils.arraysLength(accountKeyArgs, selectionArgs)];
        TwidereArrayUtils.mergeArray(mergedArgs, accountKeyArgs, selectionArgs);
        return new Pair<>(qb.build(), mergedArgs);
    }

    public static Pair<SQLSelectQuery, String[]> withScore(final String[] projection,
            final String selection,
            final String[] selectionArgs,
            final String sortOrder,
            final UserKey accountKey,
            final int limit) {
        final SQLSelectQuery.Builder qb = new SQLSelectQuery.Builder();
        final Selectable select = Utils.getColumnsFromProjection(projection);
        final Columns.Column[] columns = new Columns.Column[TwidereDataStore.CachedUsers.COLUMNS.length + 1];
        for (int i = 0, j = columns.length - 1; i < j; i++) {
            final String column = TwidereDataStore.CachedUsers.COLUMNS[i];
            if (TwidereDataStore.CachedUsers._ID.equals(column) || TwidereDataStore.CachedUsers.USER_KEY.equals(column)) {
                columns[i] = new Columns.Column(new Table(TwidereDataStore.CachedUsers.TABLE_NAME), column, column);
            } else {
                columns[i] = new Columns.Column(column);
            }
        }
        final String expr = String.format(Locale.ROOT, "%s * 100 + %s * 50 + %s * 50 - %s * 100 - %s * 100 - %s * 100",
                valueOrZero(CachedRelationships.FOLLOWING, CachedRelationships.NOTIFICATIONS_ENABLED,
                        CachedRelationships.FOLLOWED_BY, CachedRelationships.BLOCKING,
                        CachedRelationships.BLOCKED_BY, CachedRelationships.MUTING));
        columns[columns.length - 1] = new Columns.Column(expr, "score");
        qb.select(select);
        final Pair<SQLSelectQuery, String[]> pair = withRelationship(new Columns(columns), null,
                null, null, accountKey);
        qb.from(pair.getFirst());
        final String[] mergedArgs = new String[TwidereArrayUtils.arraysLength(pair.getSecond(), selectionArgs)];
        TwidereArrayUtils.mergeArray(mergedArgs, pair.getSecond(), selectionArgs);
        if (selection != null) {
            qb.where(new Expression(selection));
        }
        if (sortOrder != null) {
            qb.orderBy(new OrderBy(sortOrder));
        }
        if (limit > 0) {
            qb.limit(limit);
        }
        return new Pair<>(qb.build(), mergedArgs);
    }

    private static Object[] valueOrZero(String... columns) {
        final String[] result = new String[columns.length];
        for (int i = 0, j = columns.length; i < j; i++) {
            result[i] = String.format(Locale.ROOT, "CASE WHEN %s IS NULL THEN 0 ELSE %s END",
                    columns[i], columns[i]);
        }
        return result;
    }

}
