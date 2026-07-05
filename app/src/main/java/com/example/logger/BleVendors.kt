package com.example.logger

/**
 * Decodare producator/tip pentru dispozitive BLE, ca la scanare sa vedem MAI MULT decat MAC-ul.
 *
 * Doua surse (scan pasiv, fara conectare):
 *  - OUI (primii 3 octeti din MAC) -> producator hardware. Senzorii nostri Song Meter = Wildlife
 *    Acoustics, OUI 9C:25:BE (verificat 2026-07-05 pe MAC real 9C:25:BE:51:28:43).
 *  - Company ID din "manufacturer specific data" (BT SIG) -> brandul care difuzeaza (Apple/Samsung/...).
 *
 * Numele "adevarat" al multor device-uri e disponibil doar dupa conectare (GAP Device Name) — aici
 * ramanem la ce se poate citi din advertisement, fara sa deranjam device-ul.
 */
object BleVendors {

    /** OUI (uppercase "XX:XX:XX") -> eticheta senzor cunoscut. Toti senzorii aceluiasi model = acelasi OUI. */
    val KNOWN_SENSOR_OUI: Map<String, String> = mapOf(
        "9C:25:BE" to "Song Meter · Wildlife Acoustics",
        // adauga aici alte prefixe de senzori pe masura ce le vezi (ex. AudioMoth = alt OUI)
    )

    /** Company Identifier BT SIG (cheia din manufacturerSpecificData) -> brand. Subset de branduri uzuale. */
    val COMPANY: Map<Int, String> = mapOf(
        0x004C to "Apple",
        0x0006 to "Microsoft",
        0x0075 to "Samsung",
        0x00E0 to "Google",
        0x027D to "Huawei",
        0x038F to "Xiaomi",
        0x0157 to "Huami / Amazfit",
        0x0087 to "Garmin",
        0x0117 to "Fitbit",
        0x0059 to "Nordic Semi",
        0x0499 to "Ruuvi",
        0x004F to "Nike",
        0x00D2 to "Polar",
        0x01D1 to "Sony",
        0x0171 to "Amazon",
        0x0201 to "JBL / Harman",
        0x00C4 to "LG",
    )

    fun ouiOf(addr: String): String =
        if (addr.length >= 8) addr.substring(0, 8).uppercase() else addr.uppercase()

    /** Eticheta de senzor cunoscut din OUI, sau null. */
    fun knownSensorLabel(addr: String): String? = KNOWN_SENSOR_OUI[ouiOf(addr)]

    /** Brandul care difuzeaza, din company ID; null daca necunoscut. */
    fun brandOf(companyId: Int): String? = COMPANY[companyId]
}
