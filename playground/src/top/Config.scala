package top

object Config {
    var XLEN = 32
    var RESETVEC = 0x30000000L

    var enablePerf = true
    var isTiming = false

    var ICacheRange = (0xa0000000L, 0xbfffffffL)
    var DCacheRange = (0xa0000000L, 0xbfffffffL)

    var useGHR = true
}