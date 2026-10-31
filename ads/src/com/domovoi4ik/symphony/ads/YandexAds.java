/*
 * «Симфония» — реклама Yandex Mobile Ads (SDK 7.x, только Java). Разработчик и владелец: domovoi4ik.
 *
 *  • App Open (R-M-20314265-3) — один раз при ХОЛОДНОМ старте, не при возврате из фона.
 *    Показываем, только если загрузилась за 5 с, экран на месте и ничего не играет/не пишется.
 *    Главный экран вертикальный — Яндекс отдаёт App Open только вертикальным приложениям.
 *  • Rewarded (R-M-20314265-2) — по кнопке; награда (экспорт без метки) ТОЛЬКО из onRewarded().
 *  • После ошибки загрузки не перезагружаем сами: следующая попытка — по кнопке.
 *  • Любая ошибка SDK — приложение работает дальше без рекламы.
 * Все вызовы SDK и колбэки — на главном потоке. Без лямбд и анонимных классов.
 */
package com.domovoi4ik.symphony.ads;

import android.app.Activity;
import android.os.SystemClock;
import android.util.Log;

import com.domovoi4ik.symphony.BuildConfig;
import com.yandex.mobile.ads.appopenad.AppOpenAd;
import com.yandex.mobile.ads.appopenad.AppOpenAdEventListener;
import com.yandex.mobile.ads.appopenad.AppOpenAdLoadListener;
import com.yandex.mobile.ads.appopenad.AppOpenAdLoader;
import com.yandex.mobile.ads.common.AdError;
import com.yandex.mobile.ads.common.AdRequestConfiguration;
import com.yandex.mobile.ads.common.AdRequestError;
import com.yandex.mobile.ads.common.ImpressionData;
import com.yandex.mobile.ads.common.InitializationListener;
import com.yandex.mobile.ads.common.MobileAds;
import com.yandex.mobile.ads.rewarded.Reward;
import com.yandex.mobile.ads.rewarded.RewardedAd;
import com.yandex.mobile.ads.rewarded.RewardedAdEventListener;
import com.yandex.mobile.ads.rewarded.RewardedAdLoadListener;
import com.yandex.mobile.ads.rewarded.RewardedAdLoader;

public final class YandexAds implements Ads {
    static final String TAG = "SymphonyAds";
    static final long APP_OPEN_TIMEOUT_MS = 5000;

    final Activity act;
    final Callback cb;
    final boolean coldStart;
    final long createdAt = SystemClock.elapsedRealtime();
    boolean destroyed, sdkReady;
    // rewarded
    RewardedAdLoader rLoader;
    RewardedAd rAd;
    boolean rLoading;
    // app open
    AppOpenAdLoader oLoader;
    boolean openDone;

    public YandexAds(Activity act, Callback cb, boolean personalized, boolean coldStart) {
        this.act = act;
        this.cb = cb;
        this.coldStart = coldStart;
        MobileAds.enableLogging(BuildConfig.DEBUG);
        MobileAds.setUserConsent(personalized);        // до initialize; пока не спросили — false
        MobileAds.setLocationConsent(false);           // геолокацию не запрашиваем, в политике её нет
        MobileAds.initialize(act.getApplicationContext(), new Init(this));
    }

    void onSdkReady() {
        sdkReady = true;
        if (coldStart) loadAppOpen(); else openDone = true;
        preload();
    }

    // ================================================================== App Open
    void loadAppOpen() {
        try {
            oLoader = new AppOpenAdLoader(act.getApplication());
            oLoader.setAdLoadListener(new OpenLoad(this));
            oLoader.loadAd(new AdRequestConfiguration.Builder(BuildConfig.AD_APP_OPEN_ID).build());
        } catch (Throwable t) {
            openDone = true;
            Log.w(TAG, "app open load: " + t);
        }
    }
    void appOpenLoaded(AppOpenAd ad) {
        if (destroyed || openDone) return;
        openDone = true;
        boolean inTime = SystemClock.elapsedRealtime() - createdAt <= APP_OPEN_TIMEOUT_MS;
        if (!inTime || act.isFinishing() || !cb.canShowAppOpen()) return;   // опоздала или пользователь уже занят
        cb.onAdOpening();
        try {
            ad.setAdEventListener(new OpenEvents(this, ad));
            ad.show(act);
        } catch (Throwable t) {
            Log.w(TAG, "app open show: " + t);
            ad.setAdEventListener(null);
            cb.onAdClosed(false, true);
        }
    }

    // ================================================================== Rewarded
    @Override public boolean available() { return true; }
    @Override public boolean rewardedReady() { return rAd != null && !destroyed; }

    @Override public void preload() {
        if (destroyed || !sdkReady || rAd != null || rLoading) return;
        try {
            if (rLoader == null) {
                rLoader = new RewardedAdLoader(act);
                rLoader.setAdLoadListener(new RewardedLoad(this));
            }
            rLoading = true;
            rLoader.loadAd(new AdRequestConfiguration.Builder(BuildConfig.AD_REWARDED_ID).build());
        } catch (Throwable t) {
            rLoading = false;
            Log.w(TAG, "rewarded load: " + t);
        }
    }

    @Override public void showRewarded() {
        RewardedAd ad = rAd;
        if (destroyed) return;
        if (ad == null) { cb.onAdClosed(true, true); preload(); return; }
        rAd = null;                                    // объявление одноразовое
        cb.onAdOpening();                              // пауза звука ДО показа
        try {
            ad.setAdEventListener(new RewardedEvents(this, ad));
            ad.show(act);
        } catch (Throwable t) {
            Log.w(TAG, "rewarded show: " + t);
            ad.setAdEventListener(null);
            cb.onAdClosed(true, true);
            preload();
        }
    }

    @Override public void setPersonalized(boolean on) {
        try { MobileAds.setUserConsent(on); } catch (Throwable t) { Log.w(TAG, "consent: " + t); }
    }

    @Override public void destroy() {
        destroyed = true;
        if (rLoader != null) { rLoader.setAdLoadListener(null); rLoader = null; }
        if (oLoader != null) { oLoader.setAdLoadListener(null); oLoader = null; }
        if (rAd != null) { rAd.setAdEventListener(null); rAd = null; }
    }

    // ================================================================== слушатели
    static final class Init implements InitializationListener {
        final YandexAds y;
        Init(YandexAds y) { this.y = y; }
        @Override public void onInitializationCompleted() { if (!y.destroyed) y.onSdkReady(); }
    }

    static final class OpenLoad implements AppOpenAdLoadListener {
        final YandexAds y;
        OpenLoad(YandexAds y) { this.y = y; }
        @Override public void onAdLoaded(AppOpenAd ad) { y.appOpenLoaded(ad); }
        @Override public void onAdFailedToLoad(AdRequestError e) {
            y.openDone = true;                         // без повтора: приложение просто открывается
            Log.w(TAG, "app open не загрузилась: " + e.getCode() + " " + e.getDescription());
        }
    }
    static final class OpenEvents implements AppOpenAdEventListener {
        final YandexAds y; final AppOpenAd ad;
        OpenEvents(YandexAds y, AppOpenAd ad) { this.y = y; this.ad = ad; }
        @Override public void onAdShown() { }
        @Override public void onAdFailedToShow(AdError e) { ad.setAdEventListener(null); if (!y.destroyed) y.cb.onAdClosed(false, true); }
        @Override public void onAdDismissed() { ad.setAdEventListener(null); if (!y.destroyed) y.cb.onAdClosed(false, false); }
        @Override public void onAdClicked() { }
        @Override public void onAdImpression(ImpressionData d) { }
    }

    static final class RewardedLoad implements RewardedAdLoadListener {
        final YandexAds y;
        RewardedLoad(YandexAds y) { this.y = y; }
        @Override public void onAdLoaded(RewardedAd a) { y.rLoading = false; if (!y.destroyed) y.rAd = a; }
        @Override public void onAdFailedToLoad(AdRequestError e) {
            y.rLoading = false;                        // без автоматического повтора
            Log.w(TAG, "rewarded не загрузилась: " + e.getCode() + " " + e.getDescription());
        }
    }
    static final class RewardedEvents implements RewardedAdEventListener {
        final YandexAds y; final RewardedAd a;
        RewardedEvents(YandexAds y, RewardedAd a) { this.y = y; this.a = a; }
        @Override public void onAdShown() { }
        @Override public void onAdFailedToShow(AdError e) { a.setAdEventListener(null); if (!y.destroyed) { y.cb.onAdClosed(true, true); y.preload(); } }
        @Override public void onAdDismissed() { a.setAdEventListener(null); if (!y.destroyed) { y.cb.onAdClosed(true, false); y.preload(); } }
        @Override public void onAdClicked() { }
        @Override public void onAdImpression(ImpressionData d) { }
        /** Единственный источник награды. */
        @Override public void onRewarded(Reward r) { if (!y.destroyed) y.cb.onAdRewarded(); }
    }
}
