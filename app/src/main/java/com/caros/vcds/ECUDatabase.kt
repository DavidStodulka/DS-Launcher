package com.caros.vcds

// ─────────────────────────────────────────────────────────────────────────────
//  ECUDatabase.kt — Static catalogue of ECU addresses and coding presets for
//  the Seat León 5F / VW Group MQB platform.
//
//  ECU addresses are UDS/KWP addresses as used by VCDS (Ross-Tech notation).
//  Coding presets describe single-bit or single-channel changes that can be
//  toggled via CarOS without a full VCDS installation.
// ─────────────────────────────────────────────────────────────────────────────

object ECUDatabase {

    // ── ECU address map ───────────────────────────────────────────────────────

    /**
     * A known ECU present on the Seat León 5F CAN bus.
     *
     * @param address   UDS/KWP hex address (e.g. 0x01 = engine ECU)
     * @param name      Full descriptive name as shown in VCDS
     * @param shortName Abbreviation used in the CarOS UI
     */
    data class ECU(
        val address: Int,
        val name: String,
        val shortName: String
    )

    /** All ECUs scanned during a full vehicle scan on the Seat León 5F. */
    val LEON_5F_ECUS: List<ECU> = listOf(
        ECU(0x01, "Engine Control Module",   "ECM"),
        ECU(0x02, "Gearbox / DSG",           "TCM"),
        ECU(0x03, "ABS / ESP",               "ABS"),
        ECU(0x08, "HVAC / Climatronic",      "HVAC"),
        ECU(0x09, "Central Electronics",     "BCM"),
        ECU(0x15, "Airbag",                  "SRS"),
        ECU(0x16, "Steering Wheel Module",   "SWM"),
        ECU(0x17, "Instrument Cluster",      "INST"),
        ECU(0x19, "CAN Gateway",             "GW"),
        ECU(0x44, "Steering Assist",         "EPS"),
        ECU(0x52, "Door FL",                 "DFL"),
        ECU(0x53, "Door FR",                 "DFR"),
        ECU(0x55, "Door RL",                 "DRL"),
        ECU(0x56, "Door RR",                 "DRR")
    )

    /** Look up an [ECU] record by its hex address. Returns null if not found. */
    fun findByAddress(address: Int): ECU? =
        LEON_5F_ECUS.firstOrNull { it.address == address }

    // ── Coding preset definitions ─────────────────────────────────────────────

    /**
     * Definition of a single toggleable coding preset.
     *
     * @param id            Unique stable identifier (no spaces); used as the DB key
     * @param nameCZ        Czech display name shown in the CarOS VCDS screen
     * @param description   Short English description of what this coding does
     * @param ecuAddress    UDS/KWP address of the ECU that owns this channel
     * @param channel       UDS data identifier / VCDS channel number, or null if bit-only
     * @param bitPosition   Bit position within the byte, or null if the whole byte is written
     * @param enableValue   Value string to write when enabling this preset
     * @param disableValue  Value string to write when disabling this preset
     * @param isRisky       True if enabling this preset may affect safety systems
     * @param warningText   Warning message shown to the user before applying a risky preset
     */
    data class CodingPresetDef(
        val id: String,
        val nameCZ: String,
        val description: String,
        val ecuAddress: Int,
        val channel: Int?,
        val bitPosition: Int?,
        val enableValue: String,
        val disableValue: String,
        val isRisky: Boolean = false,
        val warningText: String? = null
    )

    /** All supported coding presets for the Seat León 5F. */
    val CODING_PRESETS: List<CodingPresetDef> = listOf(
        CodingPresetDef(
            id = "xds",
            nameCZ = "XDS Elektronický diferenciál",
            description = "Improves traction during cornering via EPS torque vectoring",
            ecuAddress = 0x44,
            channel = null,
            bitPosition = 0,
            enableValue = "1",
            disableValue = "0"
        ),
        CodingPresetDef(
            id = "esc_sport",
            nameCZ = "ESC Sportovní mód",
            description = "Widens ESC intervention threshold for track driving",
            ecuAddress = 0x03,
            channel = null,
            bitPosition = 2,
            enableValue = "1",
            disableValue = "0",
            isRisky = true,
            warningText = "Snižuje stabilizaci při extrémní jízdě. Pouze pro závodní dráhu!"
        ),
        CodingPresetDef(
            id = "throttle_map",
            nameCZ = "Agresivní mapa škrticí klapky",
            description = "More direct throttle response — maps pedal position to wider throttle angle",
            ecuAddress = 0x01,
            channel = 98,
            bitPosition = null,
            enableValue = "1",
            disableValue = "0"
        ),
        CodingPresetDef(
            id = "drl_off",
            nameCZ = "Vypnutí denních světel",
            description = "Disables automatic Daytime Running Lights",
            ecuAddress = 0x09,
            channel = null,
            bitPosition = 5,
            enableValue = "0",
            disableValue = "1"
        ),
        CodingPresetDef(
            id = "seatbelt_chime",
            nameCZ = "Vypnutí zvuku pásu řidiče",
            description = "Silences the driver seatbelt reminder chime",
            ecuAddress = 0x17,
            channel = null,
            bitPosition = 3,
            enableValue = "0",
            disableValue = "1",
            isRisky = true,
            warningText = "Nezapomínejte zapínat bezpečnostní pás!"
        ),
        CodingPresetDef(
            id = "passenger_belt_warn",
            nameCZ = "Varování pásu spolujezdce off",
            description = "Disables the front-passenger seatbelt warning indicator",
            ecuAddress = 0x17,
            channel = null,
            bitPosition = 4,
            enableValue = "0",
            disableValue = "1"
        ),
        CodingPresetDef(
            id = "coming_home",
            nameCZ = "Coming / Leaving home světla",
            description = "Headlights illuminate briefly after locking/unlocking",
            ecuAddress = 0x09,
            channel = null,
            bitPosition = 7,
            enableValue = "1",
            disableValue = "0"
        ),
        CodingPresetDef(
            id = "auto_lock",
            nameCZ = "Automatický zámek za jízdy",
            description = "Door locks engage automatically above 10 km/h",
            ecuAddress = 0x09,
            channel = null,
            bitPosition = 8,
            enableValue = "1",
            disableValue = "0"
        ),
        CodingPresetDef(
            id = "speed_volume",
            nameCZ = "Hlasitost závislá na rychlosti",
            description = "Infotainment volume increases automatically with vehicle speed",
            ecuAddress = 0x56,
            channel = 7,
            bitPosition = null,
            enableValue = "1",
            disableValue = "0"
        ),
        CodingPresetDef(
            id = "needle_sweep",
            nameCZ = "Sweep ručiček při startu",
            description = "Gauge needles sweep to maximum and back on ignition on",
            ecuAddress = 0x17,
            channel = null,
            bitPosition = 1,
            enableValue = "1",
            disableValue = "0"
        ),
        CodingPresetDef(
            id = "mirror_dip",
            nameCZ = "Sklopení zrcátka při couvání",
            description = "Right mirror dips to kerb angle when reverse is selected",
            ecuAddress = 0x09,
            channel = null,
            bitPosition = 9,
            enableValue = "1",
            disableValue = "0"
        ),
        CodingPresetDef(
            id = "cornering_lights",
            nameCZ = "Rohová světla",
            description = "Front fog lights activate as cornering lights at low speed",
            ecuAddress = 0x09,
            channel = null,
            bitPosition = 10,
            enableValue = "1",
            disableValue = "0"
        )
    )

    /** Look up a [CodingPresetDef] by its stable [id]. Returns null if not found. */
    fun findPresetById(presetId: String): CodingPresetDef? =
        CODING_PRESETS.firstOrNull { it.id == presetId }
}
