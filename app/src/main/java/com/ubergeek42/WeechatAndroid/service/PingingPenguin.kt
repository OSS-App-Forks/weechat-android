// Copyright 2014 Matthew Horan
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.ubergeek42.WeechatAndroid.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.annotation.NonNull
import androidx.annotation.WorkerThread

import com.ubergeek42.WeechatAndroid.BuildConfig
import com.ubergeek42.cats.Cat
import com.ubergeek42.cats.Kitty
import com.ubergeek42.cats.Root

import static com.ubergeek42.WeechatAndroid.service.RelayService.STATE.AUTHENTICATED


class PingActionReceiver extends BroadcastReceiver {
    final private static @Root Kitty kitty = Kitty.make()

    private volatile long lastMessageReceivedAt = 0
    private final RelayService bone
    private final AlarmManager alarmManager
    private static final String PING_ACTION = BuildConfig.APPLICATION_ID + ".PING_ACTION"
    private static final String PING_ACTION_PERMISSION = BuildConfig.APPLICATION_ID + ".permission.PING_ACTION"
    private static final IntentFilter FILTER = new IntentFilter(PING_ACTION)

    public PingActionReceiver(RelayService bone) {
        super()
        this.bone = bone
        this.alarmManager = (AlarmManager) bone.getSystemService(Context.ALARM_SERVICE)
    }

    @MainThread @Override @Cat(linger=true) public void onReceive(Context context, Intent intent) {
        kitty.trace("sent ping? %s", intent.getBooleanExtra("sentPing", false))

        if (!bone.state.contains(AUTHENTICATED))
            return

        long triggerAt
        Bundle extras = new Bundle()

        if (SystemClock.elapsedRealtime() - lastMessageReceivedAt > P.pingIdleTime) {
            if (!intent.getBooleanExtra("sentPing", false)) {
                kitty.debug("last message too old, sending ping")
                Events.SendMessageEvent.fire("ping")
                triggerAt = SystemClock.elapsedRealtime() + P.pingTimeout
                extras.putBoolean("sentPing", true)
            } else {
                kitty.info("no message received, disconnecting")
                bone.interrupt()
                return
            }
        } else {
            triggerAt = lastMessageReceivedAt + P.pingIdleTime
        }

        schedulePing(triggerAt, extras)
    }

    @WorkerThread public void scheduleFirstPing {
        if (!P.pingEnabled) return
        bone.registerReceiver(this, FILTER, PING_ACTION_PERMISSION, null)
        long triggerAt = SystemClock.elapsedRealtime() + P.pingTimeout
        schedulePing(triggerAt, new Bundle())
    }

    @AnyThread public void unschedulePing {
        if (!P.pingEnabled) return
        Intent intent = new Intent(PING_ACTION)
        PendingIntent pi = PendingIntent.getBroadcast(bone, 0, intent,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_MUTABLE)
        if (pi != null) alarmManager.cancel(pi)
        try {
            bone.unregisterReceiver(this)
        } catch (IllegalArgumentException e) {
            // there's a rare crash in developer console that happens here
            // not sure what causes it, as it doesn't seem to be possible for
            // unschedulePing() to be run twice or without scheduleFirstPing() having ran first
            // todo find the cause of this and fix more properly
            // todo look for other crashes that the underlying issue might cause
            // https://github.com/ubergeek42/weechat-android/issues/482
            // https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/app/LoadedApk.java#1451
        }
    }

    @WorkerThread public void onMessage {
        lastMessageReceivedAt = SystemClock.elapsedRealtime()
    }

    @AnyThread private void schedulePing(long triggerAt, @NonNull Bundle extras) {
        Intent intent = new Intent(PING_ACTION)
        intent.putExtras(extras)
        PendingIntent alarmIntent = PendingIntent.getBroadcast(bone, 0, intent,
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_MUTABLE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, alarmIntent)
        } else {
            alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, alarmIntent)
        }
    }
}
