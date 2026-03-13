import XCTest
@testable import CapMatrixPlugin

class CapMatrixTests: XCTestCase {
    func testMapSyncState() {
        let bridge = CapMatrix()
        // Verify the bridge can be instantiated (basic smoke test)
        XCTAssertNotNil(bridge)
    }
}
