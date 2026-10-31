# MainActivity создаёт рекламу через Class.forName — не вырезать и не переименовывать.
-keep class com.domovoi4ik.symphony.ads.YandexAds { public <init>(android.app.Activity, com.domovoi4ik.symphony.ads.Ads$Callback, boolean, boolean); }
