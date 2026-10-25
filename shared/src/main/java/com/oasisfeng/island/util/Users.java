package com.oasisfeng.island.util;

import android.annotation.SuppressLint;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import androidx.annotation.Nullable;

import com.oasisfeng.android.content.IntentFilters;
import com.oasisfeng.android.content.pm.LauncherAppsCompat;
import com.oasisfeng.pattern.PseudoContentProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static android.content.Context.USER_SERVICE;
import static android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES;
import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.N_MR1;
import static java.util.Objects.requireNonNull;

/**
 * Utility class for user-related helpers. Only works within the process where this provider is declared to be running.
 *
 * Created by Oasis on 2016/9/25.
 */
public class Users extends PseudoContentProvider {

	public static @Nullable UserHandle profile;		// The first profile managed by Island (semi-immutable, until profile is created or destroyed)
	public static UserHandle owner;     // TODO: Rename to "parent"

	public static boolean hasProfile() { return profile != null; }

	private static final UserHandle CURRENT = Process.myUserHandle();
	private static final int CURRENT_ID = toId(CURRENT);

	public static UserHandle current() { return CURRENT; }
	public static int currentId() { return CURRENT_ID; }

	@Override public boolean onCreate() {
		Log.v(TAG, "onCreate()");
		final int priority = IntentFilter.SYSTEM_HIGH_PRIORITY - 1;
		@SuppressLint("InlinedApi") final String ACTION_PROFILE_OWNER_CHANGED = DevicePolicyManager.ACTION_PROFILE_OWNER_CHANGED;
		context().registerReceiver(mProfileChangeObserver, IntentFilters.forActions(Intent.ACTION_MANAGED_PROFILE_ADDED,// ACTION_MANAGED_PROFILE_ADDED is sent by DevicePolicyManagerService.setProfileEnabled()
				Intent.ACTION_MANAGED_PROFILE_REMOVED, ACTION_PROFILE_OWNER_CHANGED).inPriority(priority));             // ACTION_PROFILE_OWNER_CHANGED is sent after "dpm set-profile-owner ..."
		refreshUsers(context());
		return true;
	}

	/** This method should not be called under normal circumstance. */
	public static void refreshUsers(final Context context) {
		final List<UserHandle> owner_and_profiles = requireNonNull((UserManager) context.getSystemService(USER_SERVICE)).getUserProfiles();
		final List<UserHandle> profiles_managed_by_island = new ArrayList<>(owner_and_profiles.size() - 1);
		final List<UserHandle> foreign_profiles = new ArrayList<>(0);
		if (isOwner()) {
			final String ui_module = Modules.getMainLaunchActivity(context).getPackageName();
			final LauncherApps la = context.getSystemService(LauncherApps.class);
			final String activity_in_owner = la.getActivityList(ui_module, CURRENT).get(0).getName();
			for (final UserHandle user : owner_and_profiles) {
				if (isOwner(user)) { owner = user; continue; }
				final List<LauncherActivityInfo> activities;
				try { activities = la.getActivityList(ui_module, user); }
				catch (final SecurityException e) { Log.w(TAG, "Error inspecting profile " + toId(user), e); continue; }	// "Cannot retrieve activities for unrelated profile" on some ROMs
				if (activities.stream().anyMatch(activity -> ! activity.getName().equals(activity_in_owner))) {
					profiles_managed_by_island.add(user);
					Log.i(TAG, "Profile managed by Island: " + toId(user));
				} else if (isProfileManagedByOthers(context, la, user)) {
					foreign_profiles.add(user);
					Log.i(TAG, "Profile managed by other app: " + toId(user));
				} else Log.i(TAG, "Profile not managed by Island: " + toId(user));	// Probably pending provisioning
			}
		} else for (final UserHandle user : owner_and_profiles) {
			if (isOwner(user)) owner = user;
			else if (user.equals(CURRENT)) {
				profiles_managed_by_island.add(user);
				Log.i(TAG, "Profile managed by Island: " + toId(user));
			} else Log.w(TAG, "Skip sibling profile (may not managed by Island): " + toId(user));
		}
		profile = profiles_managed_by_island.isEmpty() ? null : profiles_managed_by_island.get(0);
		sProfilesManagedByIsland = Collections.unmodifiableList(profiles_managed_by_island);
		sForeignProfiles = Collections.unmodifiableList(foreign_profiles);
		try { sCurrentProfileManagedByIsland = new DevicePolicies(context).isProfileOwner(); }
		catch (final RuntimeException e) { Log.e(TAG, "Error checking current profile", e); }
	}

	/** A profile without Island installed is managed by others, otherwise it is probably pending provisioning of Island (unless known otherwise). */
	private static boolean isProfileManagedByOthers(final Context context, final LauncherApps la, final UserHandle profile) {
		final ApplicationInfo engine = LauncherAppsCompat.getApplicationInfoNoThrows(la, Modules.MODULE_ENGINE, MATCH_UNINSTALLED_PACKAGES, profile);
		if (engine == null || (engine.flags & ApplicationInfo.FLAG_INSTALLED) == 0) return true;	// MATCH_UNINSTALLED_PACKAGES also returns package installed only in other users
		final Optional<ComponentName> owner = DevicePolicies.getProfileOwnerAsUser(context, profile);	// Always null on Android Q+
		return owner != null && owner.isPresent() && ! Modules.MODULE_ENGINE.equals(owner.get().getPackageName());
	}

	public static boolean isProfileRunning(final Context context, final UserHandle user) {
		if (CURRENT.equals(user)) return true;
		final UserManager um = requireNonNull(context.getSystemService(UserManager.class));
		if (SDK_INT >= N_MR1) try {
			return um.isUserRunning(user);
		} catch (final RuntimeException e) {
			Log.w(TAG, "Error checking running state for user " + toId(user));
		}
		return um.isQuietModeEnabled(user);
	}

	public static boolean isOwner() { return CURRENT_ID == 0; }	// TODO: Support non-system primary user
	public static boolean isOwner(final UserHandle user) { return toId(user) == 0; }
	public static boolean isOwner(final int user_id) { return user_id == 0; }

	public static boolean isProfileManagedByIsland() { return sCurrentProfileManagedByIsland; }
	@OwnerUser public static boolean isProfileManagedByIsland(final UserHandle user) {
		if (isOwner(user)) {
			if (isOwner()) return sCurrentProfileManagedByIsland;
			throw new IllegalArgumentException("Not working for profile parent user");
		}
		return sProfilesManagedByIsland.contains(user);
	}
	public static List<UserHandle> getProfilesManagedByIsland() { return sProfilesManagedByIsland/* already unmodifiable */; }
	/** Managed profiles NOT managed by Island (e.g. work profile of MDM), never to be operated with privileges of Island (including shuttle) */
	@OwnerUser public static List<UserHandle> getForeignProfiles() { return sForeignProfiles/* already unmodifiable */; }
	@OwnerUser public static boolean isForeignProfile(final UserHandle user) { return sForeignProfiles.contains(user); }

	public static int toId(final UserHandle user) { return user.hashCode(); }

	public static boolean isSameApp(final int uid1, final int uid2) {
		return getAppId(uid1) == getAppId(uid2);
	}

	private static int getAppId(final int uid) {
		return uid % PER_USER_RANGE;
	}

	private final BroadcastReceiver mProfileChangeObserver = new BroadcastReceiver() { @Override public void onReceive(final Context context, final Intent intent) {
		final boolean added = Intent.ACTION_MANAGED_PROFILE_ADDED.equals(intent.getAction());
		final UserHandle user = intent.getParcelableExtra(Intent.EXTRA_USER);
		Log.i(TAG, (added ? "Profile added: " : "Profile removed: ") + (user != null ? String.valueOf(toId(user)) : "null"));

		refreshUsers(context);
	}};

	private static final int PER_USER_RANGE = 100000;
	private static List<UserHandle> sProfilesManagedByIsland = null;	// Intentionally left null to fail early if this class is accidentally used in non-default process.
	private static List<UserHandle> sForeignProfiles = Collections.emptyList();	// Always empty in profile, since sibling profiles are not inspected there.
	private static boolean sCurrentProfileManagedByIsland = false;
	private static final String TAG = "Island.Users";
}
