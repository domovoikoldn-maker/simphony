package com.domovoi4ik.symphony.ads;

/**
 * Реклама глазами приложения: App Open (при холодном старте) и Rewarded (по кнопке).
 * Реализация с Яндексом (YandexAds) есть только в Gradle-сборке; без неё работает {@link None}.
 * Все методы и колбэки — на главном потоке.
 */
public interface Ads {
    interface Callback {
        /** Сейчас откроется полноэкранная реклама: звук на паузу. */
        void onAdOpening();
        /** Награда подтверждена (onRewarded). Единственное место выдачи награды. */
        void onAdRewarded();
        /** Реклама закрыта. rewardedFlow — это была реклама за награду; failed — не смогла показаться. */
        void onAdClosed(boolean rewardedFlow, boolean failed);
        /** Можно ли сейчас показать рекламу при открытии (не идёт запись/воспроизведение, экран на месте). */
        boolean canShowAppOpen();
    }

    boolean available();
    boolean rewardedReady();
    void showRewarded();
    void preload();
    void setPersonalized(boolean on);
    void destroy();

    final class None implements Ads {
        @Override public boolean available() { return false; }
        @Override public boolean rewardedReady() { return false; }
        @Override public void showRewarded() { }
        @Override public void preload() { }
        @Override public void setPersonalized(boolean on) { }
        @Override public void destroy() { }
    }
}
