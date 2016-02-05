/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package pinch.wearable;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import java.lang.ref.WeakReference;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine {
        final Handler mUpdateTimeHandler = new EngineHandler(this);

        private Bitmap mWeatherIcon;
        private String mWeatherMaxTemp = "20";
        private String mWeatherMinTemp = "10";

        static final String KEY_PATH = "/weather";
        static final String KEY_WEATHER_ID = "key_weather_id";
        static final String KEY_MAX_TEMP = "key_max_temp";
        static final String KEY_MIN_TEMP = "key_min_temp";

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        boolean mRegisteredTimeZoneReceiver = false;

        Paint mBackgroundPaint;
        Paint mTextPaint;
        //
        Paint mHourPaint, mMinutePaint, mColonPaint, mDatePaint, mMinTempPaint, mMaxTempPaint;

        boolean mAmbient;
        Time mTime;
        int mTapCount;

        float mXOffset;
        float mYOffset;
        float mLineHeight;
        //
        float mTextXOffsett;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = SunshineWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mLineHeight = resources.getDimension(R.dimen.digital_line_height);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTextPaint = new Paint();
            mTextPaint = createTextPaint(resources.getColor(R.color.digital_text));
            //
            mHourPaint = createTextPaint(resources.getColor(R.color.digital_text));
            mColonPaint = createTextPaint(resources.getColor(R.color.digital_text));
            mMinutePaint = createTextPaint(resources.getColor(R.color.digital_text_light));
            mDatePaint = createTextPaint(resources.getColor(R.color.digital_text_light));
            mMinTempPaint = createTextPaint(resources.getColor(R.color.digital_text_light));
            mMaxTempPaint = createTextPaint(resources.getColor(R.color.digital_text_light));

            mTime = new Time();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            //float timeTextSize = resources.getDimension(isRound ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);
            float timeTextSize, dateTextSize, tempTextSize;
            if(isRound) {
                timeTextSize = resources.getDimension(R.dimen.digital_text_size_round);
                dateTextSize = resources.getDimension(R.dimen.digital_text_size_date_round);
                tempTextSize = resources.getDimension(R.dimen.digital_text_size_temp_round);
            } else {
                timeTextSize = resources.getDimension(R.dimen.digital_text_size);
                dateTextSize = resources.getDimension(R.dimen.digital_text_size_date);
                tempTextSize = resources.getDimension(R.dimen.digital_text_size_temp);
            }

            mTextPaint.setTextSize(timeTextSize);
            //
            mHourPaint.setTextSize(timeTextSize);
            mColonPaint.setTextSize(timeTextSize);
            mMinutePaint.setTextSize(timeTextSize);
            mDatePaint.setTextSize(dateTextSize);
            mMinTempPaint.setTextSize(tempTextSize);
            mMaxTempPaint.setTextSize(tempTextSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaint.setAntiAlias(!inAmbientMode);
                    //
                    mHourPaint.setAntiAlias(!inAmbientMode);
                    mColonPaint.setAntiAlias(!inAmbientMode);
                    mMinutePaint.setAntiAlias(!inAmbientMode);
                    mDatePaint.setAntiAlias(!inAmbientMode);
                    mMinTempPaint.setAntiAlias(!inAmbientMode);
                    mMaxTempPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            Resources resources = SunshineWatchFace.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    mTapCount++;
                    mBackgroundPaint.setColor(resources.getColor(mTapCount % 2 == 0 ?
                            R.color.background : R.color.background2));
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {

            int width = bounds.width();
            int height = bounds.height();
            float centerX = width / 2f;
            float centerY = height / 2f;

            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            mTime.setToNow();
            String text = mAmbient
                    ? String.format("%d:%02d", mTime.hour, mTime.minute)
                    : String.format("%d:%02d", mTime.hour, mTime.minute);
                    //: String.format("%d:%02d:%02d", mTime.hour, mTime.minute, mTime.second);
            canvas.drawText(text, centerX - (mTextPaint.measureText(text) / 2), mYOffset, mTextPaint);

            //
            // Date
            String dateString = "Fri, Jul 14 2015";
            float datePosition = centerX - (mDatePaint.measureText(dateString) / 2);
            canvas.drawText(dateString, datePosition, mYOffset + mLineHeight, mDatePaint);

            //
            //for weather icon
            //
            int weatherId = 800;
            mWeatherIcon = BitmapFactory.decodeResource(getResources(), Utils.getIconsWeatherCondition(weatherId));
            //
            Bitmap resizedBitmap = Bitmap.createScaledBitmap(mWeatherIcon, 40, 40, true);
            String maxTempString = mWeatherMaxTemp + "\u00b0";
            String minTempString = mWeatherMinTemp + "\u00b0";
            float imgPosition = centerX - resizedBitmap.getWidth() - mMinTempPaint.measureText(minTempString) / 2 - 15;
            float maxTempPosition = centerX - mMaxTempPaint.measureText(maxTempString) / 2;
            float minTempPosition = centerX + mMaxTempPaint.measureText(maxTempString) / 2 + 15;
            canvas.drawBitmap(resizedBitmap, imgPosition, mYOffset + 70, new Paint());

            canvas.drawText(maxTempString, maxTempPosition,
                    60 + mYOffset + 40, mMaxTempPaint);

            canvas.drawText(minTempString, minTempPosition,
                    60 + mYOffset + 40, mMinTempPaint);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }
}
