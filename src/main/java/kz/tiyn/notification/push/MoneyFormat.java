package kz.tiyn.notification.push;

final class MoneyFormat {

    private static final char GROUP = ' ';

    private MoneyFormat() {}

    static String tenge(long minorUnits) {
        long units = Math.abs(minorUnits) / 100;
        long cents = Math.abs(minorUnits) % 100;
        StringBuilder grouped = new StringBuilder(Long.toString(units));
        for (int i = grouped.length() - 3; i > 0; i -= 3) {
            grouped.insert(i, GROUP);
        }
        if (cents != 0) {
            grouped.append(',').append(cents < 10 ? "0" : "").append(cents);
        }
        return (minorUnits < 0 ? "-" : "") + grouped + GROUP + "₸";
    }
}
