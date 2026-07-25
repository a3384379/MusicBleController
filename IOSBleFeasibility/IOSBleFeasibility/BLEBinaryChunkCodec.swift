import Foundation

struct BLEBinaryChunk: Equatable {
    let magic: UInt8
    let kindCode: UInt8
    let index: Int
    let total: Int
    let payload: Data
}

enum BLEBinaryChunkCodec {
    static let headerSize = 6

    static func decode(_ data: Data, expectedMagic: UInt8) -> BLEBinaryChunk? {
        guard data.count > headerSize,
              data[data.startIndex] == expectedMagic else {
            return nil
        }
        let kindCode = data[data.startIndex + 1]
        let index = Int(data[data.startIndex + 2]) << 8 |
            Int(data[data.startIndex + 3])
        let total = Int(data[data.startIndex + 4]) << 8 |
            Int(data[data.startIndex + 5])
        guard total > 0, index >= 0, index < total else {
            return nil
        }
        return BLEBinaryChunk(
            magic: expectedMagic,
            kindCode: kindCode,
            index: index,
            total: total,
            payload: Data(data.dropFirst(headerSize))
        )
    }

    static func missingIndexes(
        chunks: [Int: Data],
        expectedCount: Int
    ) -> [Int] {
        guard expectedCount > 0 else { return [] }
        return (0..<expectedCount).filter { chunks[$0] == nil }
    }

    static func reassemble(
        chunks: [Int: Data],
        expectedCount: Int
    ) -> Data? {
        guard expectedCount > 0,
              missingIndexes(chunks: chunks, expectedCount: expectedCount).isEmpty else {
            return nil
        }
        return (0..<expectedCount).reduce(into: Data()) { result, index in
            result.append(chunks[index] ?? Data())
        }
    }

    static func crc32(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xffff_ffff
        for byte in data {
            crc ^= UInt32(byte)
            for _ in 0..<8 {
                crc = (crc >> 1) ^ ((crc & 1) == 1 ? 0xedb8_8320 : 0)
            }
        }
        return crc ^ 0xffff_ffff
    }
}
