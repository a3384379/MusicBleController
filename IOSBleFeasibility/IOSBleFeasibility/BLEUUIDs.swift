import CoreBluetooth

enum BLEUUIDs {
    static let service = CBUUID(string: "0000A001-0000-1000-8000-00805F9B34FB")
    static let command = CBUUID(string: "0000A002-0000-1000-8000-00805F9B34FB")
    static let status = CBUUID(string: "0000A003-0000-1000-8000-00805F9B34FB")

    static let iosControllerName = "MusicControllerIOS"
}
