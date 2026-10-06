package com.mcpintelligence.fr3k.integrations.blackwave

data class BlackwaveDeviceHorizonEntry(
    val id: String,
    val displayName: String,
    val tier: String,
    val mode: String,
    val priority: String,
    val role: String,
)

object BlackwaveDeviceHorizon {
    val entries = listOf(
        BlackwaveDeviceHorizonEntry("tembed-si4732", "LILYGO T-Embed SI4732", "B", "receiver firmware", "P0", "broadcast / shortwave receive intelligence"),
        BlackwaveDeviceHorizonEntry("tbeam-1w-915", "LILYGO T-Beam 1W 915 MHz", "B", "LoRa firmware", "P0", "long-range survey / relay / field anchor"),
        BlackwaveDeviceHorizonEntry("esp32-p4-poe", "ESP32-P4 PoE Unit", "B", "wired edge firmware", "P0", "PoE sensor / protocol gateway"),
        BlackwaveDeviceHorizonEntry("tdisplay-p4", "LILYGO T-Display P4", "B", "visual-console firmware", "P1", "local BLACKWAVE visual console"),
        BlackwaveDeviceHorizonEntry("cardputer-mesh", "M5Stack Cardputer / Mesh", "B", "field-terminal firmware", "P1", "pocket keyboard / mesh annotation terminal"),
        BlackwaveDeviceHorizonEntry("m5stamp-uwb", "M5Stamp UWB pair", "B", "ranging integration", "P1", "spatial ranging evidence"),
        BlackwaveDeviceHorizonEntry("wismesh-repeater-mini", "RAK WisMesh Repeater Mini", "B", "supported-stack integration", "P1", "mesh coverage relay"),
        BlackwaveDeviceHorizonEntry("t1000e", "LILYGO T-1000E", "B", "mesh-node evaluation", "P2", "compact low-power field node"),
        BlackwaveDeviceHorizonEntry("pico-epaper", "Waveshare Pico e-Paper", "B", "status-beacon firmware", "P2", "low-power signed status display"),
        BlackwaveDeviceHorizonEntry("oneplus-7-pro", "OnePlus 7 Pro", "C", "Android integration", "P0", "primary mobile operator / gateway"),
        BlackwaveDeviceHorizonEntry("oneplus-7", "OnePlus 7", "C", "Android integration", "P1", "secondary mobile operator / compatibility lane"),
        BlackwaveDeviceHorizonEntry("gpd-win-mini", "GPD Win Mini 2024", "C", "Linux host integration", "P0", "primary orchestrator / RF instrument host"),
        BlackwaveDeviceHorizonEntry("raspberry-pi-500", "Raspberry Pi 500", "C", "Linux gateway integration", "P1", "portable field gateway"),
        BlackwaveDeviceHorizonEntry("beryl-ax", "GL.iNet Beryl AX", "C", "OpenWrt integration", "P0", "portable network hub"),
        BlackwaveDeviceHorizonEntry("tinysa-ultra-plus", "tinySA ULTRA+ ZS-407", "C", "instrument adapter", "P0", "spectrum observation instrument"),
        BlackwaveDeviceHorizonEntry("sdr", "SDR equipment", "C", "capture adapter", "P1", "wideband capture / decoder input"),
        BlackwaveDeviceHorizonEntry("mlx90640", "MLX90640 Thermal Camera", "C", "sensor adapter", "P1", "thermal evidence sensor"),
        BlackwaveDeviceHorizonEntry("reachy-mini", "Reachy Mini", "C", "robotics adapter", "P1", "embodied observer"),
        BlackwaveDeviceHorizonEntry("viture-glasses", "VITURE glasses", "C", "HUD output", "P1", "wearable operator display"),
        BlackwaveDeviceHorizonEntry("nanovna-v2-plus4", "NanoVNA V2 Plus4", "C", "measurement adapter", "P2", "RF path / antenna characterization"),
        BlackwaveDeviceHorizonEntry("rplidar", "RPLIDAR", "C", "sensor adapter", "P2", "geometry / movement context"),
        BlackwaveDeviceHorizonEntry("canbus-unit", "CANBus Units", "C", "bus adapter", "P2", "vehicle / industrial telemetry"),
        BlackwaveDeviceHorizonEntry("rs485-unit", "Isolated RS485 Units", "C", "bus adapter", "P2", "industrial telemetry bridge"),
        BlackwaveDeviceHorizonEntry("roller485", "Roller485 BLDC unit", "D", "gated actuator", "P3", "controlled actuation experiment"),
        BlackwaveDeviceHorizonEntry("kingsong-16s", "KingSong 16S", "D", "read-only mobility", "P3", "mobility telemetry"),
        BlackwaveDeviceHorizonEntry("kingsong-14s", "KingSong 14S", "D", "read-only mobility", "P3", "repair / telemetry evidence"),
        BlackwaveDeviceHorizonEntry("begode-mten", "Begode MTen", "D", "diagnostics only", "P3", "fault-diagnostics donor"),
    )

    private val canonicalCatalogIds = setOf(
        "tdeck-plus", "twatch-ultra", "twatch-2020s3", "tembed-cc1101",
        "tdeck-pro-4g", "unihiker-k10", "m5stack-tab5", "tdisplay-s3",
        "tdisplay-k230", "techo-pathfinder",
    )

    fun validate() {
        require(entries.map { it.id }.distinct().size == entries.size) {
            "BLACKWAVE horizon ids must be unique"
        }
        require(entries.none { it.id in canonicalCatalogIds }) {
            "BLACKWAVE horizon must remain separate from canonical catalog ids"
        }
    }
}
