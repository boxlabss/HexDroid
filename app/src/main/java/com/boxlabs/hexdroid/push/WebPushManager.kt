/*
* HexDroidIRC - An IRC Client for Android
* Copyright (C) 2026 boxlabs
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package com.boxlabs.hexdroid.push

import android.content.Context
import org.unifiedpush.android.connector.UnifiedPush

/**
 * Owns the device's Web Push subscription: the endpoint a distributor gave us, the keys
 * servers encrypt with, and the registrations we have told each network about.
 *
 * The point of the feature is that a bouncer can wake the app for a message while no TCP
 * connection is open, so the keep-alive foreground service stops being the only way to
 * hear about a highlight.
 *
 * The transport is UnifiedPush
 */
object WebPushManager {

    private const val PREFS = "hexdroid_webpush"
    private const val KEY_ENDPOINT = "endpoint"
    private const val KEY_AUTH = "auth"
    private const val KEY_P256DH = "p256dh"
    private const val KEY_VAPID = "vapid"

    /** A subscription with its encryption keys, as handed to WEBPUSH REGISTER. */
    data class Subscription(
        val endpoint: String,
        val auth: String,
        val p256dh: String,
    )

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The current subscription, or null when no distributor has given us an endpoint yet. */
    fun subscription(ctx: Context): Subscription? {
        val p = prefs(ctx)
        val endpoint = p.getString(KEY_ENDPOINT, null) ?: return null
        val auth = p.getString(KEY_AUTH, null) ?: return null
        val p256dh = p.getString(KEY_P256DH, null) ?: return null
        if (endpoint.isBlank() || auth.isBlank() || p256dh.isBlank()) return null
        return Subscription(endpoint, auth, p256dh)
    }

    /**
     * Record the subscription a distributor just issued.
     */
    fun storeSubscription(ctx: Context, endpoint: String, auth: String, p256dh: String) {
        prefs(ctx).edit()
            .putString(KEY_ENDPOINT, endpoint)
            .putString(KEY_AUTH, auth)
            .putString(KEY_P256DH, p256dh)
            .apply()
    }

    /**
     * The VAPID key the current subscription was requested with, or null when it was
     * requested without one.
     *
     * A keyless subscription still works with distributors that don't enforce signing,
     * but a server that does sign will have its pushes dropped, so callers use this to
     * notice a subscription that predates knowing any server's key.
     */
    fun subscribedVapid(ctx: Context): String? = prefs(ctx).getString(KEY_VAPID, null)

    /** Forget the subscription. */
    fun clearSubscription(ctx: Context) {
        prefs(ctx).edit()
            .remove(KEY_ENDPOINT)
            .remove(KEY_AUTH)
            .remove(KEY_P256DH)
            .remove(KEY_VAPID)
            .apply()
    }

    /** Distributor apps installed on this device that can carry our push messages. */
    fun availableDistributors(ctx: Context): List<String> = UnifiedPush.getDistributors(ctx)

    /**
     * Ask the saved distributor for an endpoint.
     *
     * [vapid] is the server's application-server key, which the distributor uses to reject
     * pushes that are not signed by that server. Because it is per-server and a
     * subscription is per-device, the first connected server's key wins; a user pointing
     * one install at two webpush servers with different keys would need a subscription per
     * server, which the spec supports but this does not yet.
     *
     * The endpoint arrives asynchronously at [HexPushService.onNewEndpoint].
     */
    fun register(ctx: Context, vapid: String?) {
        if (UnifiedPush.getAckDistributor(ctx) == null) return
        prefs(ctx).edit().putString(KEY_VAPID, vapid).apply()
        runCatching { UnifiedPush.register(ctx, vapid = vapid) }
    }

    /** Choose [distributor] and immediately ask it for an endpoint. */
    fun selectDistributor(ctx: Context, distributor: String, vapid: String?) {
        prefs(ctx).edit().putString(KEY_VAPID, vapid).apply()
        runCatching {
            UnifiedPush.saveDistributor(ctx, distributor)
            UnifiedPush.register(ctx, vapid = vapid)
        }
    }

    /** The distributor currently in use, or null when none has been chosen or acked. */
    fun currentDistributor(ctx: Context): String? = UnifiedPush.getAckDistributor(ctx)

    /**
     * Tear the subscription down.
     *
     * Only removes it at the distributor. Telling each server to forget the endpoint is
     * the caller's job, because that needs a live connection per network and this object
     * has none.
     */
    fun unregister(ctx: Context) {
        runCatching { UnifiedPush.unregister(ctx) }
        clearSubscription(ctx)
    }
}
