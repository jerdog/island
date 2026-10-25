package com.oasisfeng.island.data

import android.Manifest.permission.BIND_DEVICE_ADMIN
import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo.FLAG_SYSTEM
import android.content.pm.LauncherApps
import android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS
import android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES
import android.os.Process
import android.os.UserHandle
import android.util.ArrayMap
import android.util.Log
import com.oasisfeng.android.content.pm.LauncherAppsCompat
import com.oasisfeng.island.data.helper.installed
import com.oasisfeng.island.mobile.R
import com.oasisfeng.island.util.DevicePolicies
import com.oasisfeng.island.util.OwnerUser
import com.oasisfeng.island.util.Users
import com.oasisfeng.island.util.toId

/**
 * Helpers for managed profiles NOT managed by Island (e.g. work profile of MDM), see [Users.getForeignProfiles].
 * Apps in such profile can only be listed and launched (via [LauncherApps]), no privileged operation is possible.
 */
@OwnerUser object ForeignProfiles {

	@JvmStatic fun getDisplayName(context: Context, profile: UserHandle): String = context.getString(R.string.tab_work_profile)

	@JvmStatic fun getManagedByText(context: Context, profile: UserHandle): String = getOwnerLabel(context, profile)
			?.let { context.getString(R.string.status_appendix_managed_by, it) } ?: context.getString(R.string.status_appendix_managed_by_other)

	/** @return the label of the app managing this profile, or null if unknown */
	@JvmStatic fun getOwnerLabel(context: Context, profile: UserHandle): CharSequence? = synchronized(sOwnerLabels) {
		if (sOwnerLabels.containsKey(profile)) sOwnerLabels[profile]
		else findOwnerLabel(context, profile).also { sOwnerLabels[profile] = it; Log.i(TAG, "Owner of profile ${profile.toId()}: $it") }}

	@JvmStatic fun invalidate() = synchronized(sOwnerLabels) { sOwnerLabels.clear() }

	private fun findOwnerLabel(context: Context, profile: UserHandle): CharSequence? {
		DevicePolicies.getProfileOwnerAsUser(context, profile)?.orElse(null)?.also { return DevicePolicies.getOwnerLabel(context, it) }

		// On Android Q+, there's no API to query the profile owner of another user. Best effort: the only non-system device admin installed in that profile.
		val pm = context.packageManager; val la = context.getSystemService(LauncherApps::class.java)!!; val myUid = Process.myUid()
		val candidates = pm.queryBroadcastReceivers(Intent(DeviceAdminReceiver.ACTION_DEVICE_ADMIN_ENABLED), MATCH_UNINSTALLED_PACKAGES or MATCH_DISABLED_COMPONENTS)
				.map { it.activityInfo }.filter { it.permission == BIND_DEVICE_ADMIN && it.applicationInfo.flags and FLAG_SYSTEM == 0
						&& ! Users.isSameApp(it.applicationInfo.uid, myUid) }     // Exclude system apps (e.g. "Find My Device" of GMS) and Island itself
				.filter { LauncherAppsCompat.getApplicationInfoNoThrows(la, it.packageName, MATCH_UNINSTALLED_PACKAGES, profile)?.installed == true }
				.distinctBy { it.packageName }
		return candidates.singleOrNull()?.let { DevicePolicies.getOwnerLabel(context, ComponentName(it.packageName, it.name)) }
	}

	private val sOwnerLabels = ArrayMap<UserHandle, CharSequence?>()
}

private const val TAG = "Island.FP"
