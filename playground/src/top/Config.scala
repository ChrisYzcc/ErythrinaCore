package top

object DefaultConfig {
    def apply() = Map(
        "XLEN"  -> 32,
        "RESETVEC"  -> 0x30000000L,

        // Frontend
        "FetchWidth"    -> 2,
        "FTQSize"       -> 16
    )
}

object Config {
    var config: Map[String, AnyVal] = DefaultConfig()

    def get(field: String) = {
        config(field).asInstanceOf[Boolean]
    }

    def getLong(field: String) = {
        config(field).asInstanceOf[Long]
    }

    def getInt(field: String) = {
        config(field).asInstanceOf[Int]
    }
}