package com.domovoi4ik.symphony.dsp;

/**
 * Каталог встроенных эффектов. Все они — «плагины» Симфонии: ставятся в цепочку любой дорожки
 * или мастера, включаются/выключаются, сохраняются в проекте.
 */
public final class Effects {
    public static final int EQ = 0, COMP = 1, REVERB = 2, DELAY = 3, CHORUS = 4, DRIVE = 5, FILTER = 6, TREMOLO = 7, BITCRUSH = 8, WIDENER = 9;
    public static final String[] NAMES = {
        "Эквалайзер", "Компрессор", "Реверберация", "Задержка (дилей)", "Хорус", "Перегруз", "Фильтр", "Тремоло", "Лоу-фай (биткраш)", "Стерео-расширение"
    };
    public static final String[] HINTS = {
        "3 полосы: низ, середина, верх", "Выравнивает громкость, делает звук плотнее", "Эхо комнаты или зала",
        "Повторы с затуханием, можно в темп", "Объём и «плавание» звука", "Тёплый перегруз до дисторшна",
        "Срез низов или верхов, «вау» резонансом", "Пульсация громкости в темп", "Звук старых приставок и сэмплеров",
        "Шире стереобаза, «Хаас»-эффект"
    };

    private Effects() { }

    public static Effect create(int type) {
        switch (type) {
            case EQ: return new Eq3();
            case COMP: return new Compressor();
            case REVERB: return new Reverb();
            case DELAY: return new Delay();
            case CHORUS: return new Chorus();
            case DRIVE: return new Drive();
            case FILTER: return new Filter();
            case TREMOLO: return new Tremolo();
            case BITCRUSH: return new BitCrush();
            case WIDENER: return new Widener();
            default: return null;
        }
    }
}
