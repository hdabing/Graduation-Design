package com.bingoogol.frogcare.service;

import java.util.ArrayList;
import java.util.List;

import org.androidannotations.annotations.AfterInject;
import org.androidannotations.annotations.App;
import org.androidannotations.annotations.Background;
import org.androidannotations.annotations.Bean;
import org.androidannotations.annotations.EService;
import org.androidannotations.annotations.SystemService;
import org.androidannotations.annotations.sharedpreferences.Pref;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;

import com.bingoogol.frogcare.FrogCareApplication;
import com.bingoogol.frogcare.callback.WatchDogCallback;
import com.bingoogol.frogcare.db.dao.AppLockDao;
import com.bingoogol.frogcare.ui.AppLockAuthActivity_;
import com.bingoogol.frogcare.util.Constants;
import com.bingoogol.frogcare.util.ISharedPreferences_;
import com.bingoogol.frogcare.util.Logger;

@EService
public class WatchDogService extends Service {
	private static final String TAG = "WatchDogService";
	@App
	protected FrogCareApplication mApp;
	@Pref
	protected ISharedPreferences_ mSp;
	@SystemService
	protected ActivityManager mActivityManager;
	@Bean
	protected AppLockDao mAppLockDao;
	private boolean mIsWatching;
	private List<String> mTempStopProtectPackageNames;
	private List<String> mProtectPackageNames;

	private ApplockObserver mApplockObserver;
	private ScreenLockReceiver mScreenLockReceiver;
	private ScreenUnLockReceiver mScreenUnLockReceiver;
	private Intent mAuthIntent;

	@Override
	public IBinder onBind(Intent intent) {
		return new MyBinder();
	}

	@AfterInject
	public void afterInject() {
		mTempStopProtectPackageNames = new ArrayList<String>();
		mProtectPackageNames = mAppLockDao.findAll();

		mAuthIntent = new Intent(mApp, AppLockAuthActivity_.class);
		// 如果不加这个标记，并且之前该应用的activity任务栈里还有activity，那么在用户完成验证后还会回到该应用当中
		mAuthIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

		mScreenLockReceiver = new ScreenLockReceiver();
		registerReceiver(mScreenLockReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF));

		mScreenUnLockReceiver = new ScreenUnLockReceiver();
		registerReceiver(mScreenUnLockReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON));

		mApplockObserver = new ApplockObserver(new Handler());
		getContentResolver().registerContentObserver(AppLockDao.uri, true, mApplockObserver);

		startWatchDog();
	}

	@Override
	public void onDestroy() {
		mIsWatching = false;
		unregisterReceiver(mScreenLockReceiver);
		mScreenLockReceiver = null;
		unregisterReceiver(mScreenUnLockReceiver);
		mScreenUnLockReceiver = null;
		getContentResolver().unregisterContentObserver(mApplockObserver);
		mApplockObserver = null;
		super.onDestroy();
	}

	@Background
	protected void startWatchDog() {
		mIsWatching = true;
		while (mIsWatching) {
			// 获取最近创建的任务栈
			RunningTaskInfo runningTaskInfo = mActivityManager.getRunningTasks(2).get(0);
			String packageName = runningTaskInfo.topActivity.getPackageName();
			if (mProtectPackageNames.contains(packageName)) {
				// 如果当前应用程序没有处于临时停止保护状态
				if (!mTempStopProtectPackageNames.contains(packageName)) {
					mAuthIntent.putExtra(Constants.extra.PACKAGENAME, packageName);
					startActivity(mAuthIntent);
				}
			}
			try {
				Thread.sleep(300);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}

	private class ScreenLockReceiver extends BroadcastReceiver {

		@Override
		public void onReceive(Context context, Intent intent) {
			Logger.i(TAG, "屏幕锁屏");
			mTempStopProtectPackageNames.clear();
			mIsWatching = false;
		}

	}

	private class ScreenUnLockReceiver extends BroadcastReceiver {

		@Override
		public void onReceive(Context context, Intent intent) {
			Logger.i(TAG, "屏幕解锁");
			if (!mIsWatching) {
				startWatchDog();
			}
		}

	}

	private class MyBinder extends Binder implements WatchDogCallback {

		@Override
		public void addTempStopProtectPackageName(String packageName) {
			Logger.i(TAG, "停止保护" + packageName);
			mTempStopProtectPackageNames.add(packageName);
		}

	}

	private class ApplockObserver extends ContentObserver {

		public ApplockObserver(Handler handler) {
			super(handler);
		}

		@Override
		public void onChange(boolean selfChange) {
			super.onChange(selfChange);
			Logger.i(TAG, "加锁应用发生变化");
			mProtectPackageNames = mAppLockDao.findAll();
		}

	}
}